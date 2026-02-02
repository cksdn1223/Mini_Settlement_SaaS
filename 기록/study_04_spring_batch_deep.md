# [Batch] 대용량 정산/결제 시스템 심화

#Settlement #Batch #Spring #Performance
[[settlement_system_master_guide|◀️ 돌아가기]]

## "그냥 for문 돌리면 안 되나요?" -> 안 됩니다.

앞선 자료에서 Spring Batch의 기초를 다뤘다면, 이번에는 **"실전에서 마주칠 문제들"** 을 다룹니다.
매일 밤 100만 건의 결제를 시도하는데, PG사 서버가 1초간 먹통이 된다면?
서버 한 대로는 5시간이 걸려서 아침 9시까지 정산을 못 끝낸다면?
이런 아찔한 상황을 기술로 해결해 봅시다.

---

## Chapter 1. 실패를 다루는 기술: 재시도(Retry)와 건너뛰기(Skip)

외부 시스템(PG사, 은행)과 통신할 때는 반드시 **네트워크 장애**를 대비해야 합니다. 내 코드가 완벽해도 상대방 서버가 죽을 수 있으니까요.

### 1-1. `Retry`: "잠깐 렉 걸린 걸 거야. 다시 해보자."

```java
@Bean
public Step paymentStep() {
    return new StepBuilder("paymentStep", jobRepository)
            .<Bill, PayResult>chunk(100, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            // 1. "나는 장애에도 굴하지 않겠다" 선언
            .faultTolerant()
            // 2. 이 예외(Timeout)가 발생하면?
            .retry(TimeoutException.class)
            // 3. 끈질기게 3번에 걸쳐 다시 시도해라. 그래도 안 되면 그제서야 실패 처리.
            .retryLimit(3)
            .build();
}
```

> [!TIP] **Retry의 효과**
> 일시적인 네트워크 깜빡임(Glitch)으로 인해 배치가 통째로 실패하는 것을 막아줍니다. 99%의 간헐적 오류는 이걸로 해결됩니다.

### 1-2. `Skip`: "이 건은 답이 없다. 버리고 가자."

데이터 자체가 꼬여서(예: 잔액이 `null`, 계좌번호 형식 오류) 에러가 나는 경우, 재시도해봤자 시간 낭비입니다.
이럴 땐 **해당 데이터만 건너뛰고 나머지 99만 9999건을 살려야 합니다.**

```java
.skip(IllegalArgumentException.class) // "이런 논리적 오류가 나면"
.skipLimit(100) // "100건까지는 눈감아주겠다." (그 이상이면 데이터 오염이 심각하니 배치 중단)
```

> [!WARNING] **주의점**
> Skip된 데이터는 그냥 버리면 안 됩니다. `SkipListener`를 등록해서 별도의 에러 파일(`error_log.csv`)이나 테이블에 기록해둬야, 다음 날 아침에 담당자가 보고 "아, 이 3건은 계좌번호가 틀렸구나" 하고 조치를 취할 수 있습니다.

---

## Chapter 2. 확장성(Scalability): 파티셔닝(Partitioning)

데이터가 10만 건일 땐 한 대로 충분했지만, 1000만 건이 되면 시간 내에 처리가 불가능합니다.
이때 **"일감을 쪼개서 친구들(스레드/서버)이랑 나눠서 처리"** 하는 파티셔닝을 씁니다.

### 2-1. 원리: "반장(Master)과 조원들(Slaves)"

Master Step은 직접 일을 하지 않습니다. 일감을 케이크 자르듯 쪼개서 나눠주기만 합니다.

- **전체 데이터**: 1번 ~ 10,000번
- **Slave 1**: "너는 1 ~ 2,500번 처리해"
- **Slave 2**: "너는 2,501 ~ 5,000번 처리해"
- ...
- **Slave 4**: "너는 7,501 ~ 10,000번 처리해"

```java
@Bean
public Step masterStep() {
    return new StepBuilder("masterStep", jobRepository)
            .partitioner("slaveStep", partitioner()) // "자, 쪼개자!" (쪼개는 로직 담당)
            .step(slaveStep()) // 실제 일할 일꾼 Step
            .gridSize(4) // "4명한테 나눠줄 거야" (스레드 4개 생성)
            .taskExecutor(taskExecutor) // "병렬로 동시에 달려!"
            .build();
}
```

이렇게 하면 이론상 **4배** 빨라집니다. (DB가 버텨준다면요)

---

## Chapter 3. 멱등성(Idempotency): 두 번 돌려도 안전하게

정산 시스템에서 가장 무서운 말. **"어? 배치 돌다가 서버 꺼졌는데? 다시 돌려도 되나?"**
만약 멱등성이 없다면, 이미 돈을 준 사람한테 또 돈을 주게 됩니다(중복 지급).

### 3-1. 처리 상태 플래그 활용

단순하지만 강력한 방식은 DB에 "처리했음" 도장을 찍는 것입니다.

```sql
-- 배치 Reader가 읽을 때 조건
SELECT * FROM bill WHERE status = 'WAITING';
```

배치가 돌면서 처리가 끝난 건은 `UPDATE bill SET status = 'DONE'`으로 바꿉니다.
전원이 꺼져도 `DONE`인 건들은 다시 읽히지 않으니 안전합니다.

### 3-2. Job Parameter를 활용한 날짜 통제

Job을 실행할 때 항상 **"기준 날짜"** 를 파라미터로 넘겨야 합니다.
코드 안에 `LocalDate.now()`를 박아버리면 멱등성을 지키기 어렵습니다.

**[상황 예시]**

- 1월 31일 밤 11시 59분에 배치가 돌다가 12시 01분에 죽었습니다.
- 2월 1일 아침에 다시 돌렸습니다.
- 코드에 `now()`가 있다면? -> **2월 1일자로 정산이 진행되어 버립니다.** (우리는 1월 31일 정산이 필요한데!)

**[해결책]**

```java
@Bean
@StepScope
public ItemReader<Bill> reader(@Value("#{jobParameters[targetDate]}") String date) {
    // 무조건 외부에서 주입받은 'targetDate'만 쳐다본다.
    // 오늘이 2월 1일이어도, 파라미터로 '1월 31일'이 들어오면 1월 31일 데이터를 처리한다.
    return new JpaPagingItemReader(..., date);
}
```

이렇게 해야 과거 데이터 재정산(Re-processing)이 가능하고, 실수로 여러 번 돌려도 결과가 변하지 않습니다.

---

## 마무리

대용량 처리를 위한 3가지 무기:

1. **Fault Tolerance**: `Retry`와 `Skip`으로 죽지 않는 좀비 같은 배치 만들기
2. **Partitioning**: 혼자서 안 되면 쪼개서 병렬로 처리하기
3. **Idempotency**: "기준 날짜" 파라미터와 "상태 플래그"로 언제 다시 돌려도 안전하게 만들기

이것들이 갖춰져야 **"밤잠을 잘 수 있는 정산 시스템"** 이 됩니다. 인프라 장애는 언제든 일어날 수 있으니까요.
