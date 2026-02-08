# [Spring Batch] 대용량 정산 시스템

## 돈과 10만 건의 데이터

우리가 만들 시스템은 **"임대료 정산 시스템"** 입니다.
상황은 이렇습니다:

- **매월 1일**이 되면,
- 등록된 **10만 건의 임대 계약(Contract)** 데이터를 읽어서,
- 이번 달 청구해야 할 **월세 고지서(Bill)**를 자동으로 발행해야 합니다.

웹 서버(Controller)에서 `for`문을 돌려 처리하면 안 될까요?

1. **타임아웃**: 웹 요청은 보통 30초~60초면 끊깁니다. 10만 건 처리는 그보다 오래 걸립니다.
2. **메모리 부족(OOM)**: 10만 건을 한 번에 리스트에 담으면 서버가 뻗습니다.
3. **재시도 불가**: 5만 건 처리하다 에러가 나면? 처음부터 다시 해야 할까요? 아니면 50001번째부터 해야 할까요? 배치는 이런 "실패 지점 관리"를 자동으로 해줍니다.

그래서 우리는 **Spring Batch**를 사용합니다.

---

## Chapter 1. 도메인 설계: 돈은 소중하니까

정산 시스템의 핵심은 **정확성**입니다. 1원의 오차도 허용하지 않는 코드를 작성해야 합니다.

### 1-1. `BigDecimal` vs `double`

자바를 배울 때 실수는 `double`이라고 배웠을 겁니다. 하지만 금융 시스템에서 `double`은 금기어입니다.

```java
// ❌ 절대 금지
double value = 0.1 + 0.2; // 결과: 0.30000000000000004
```

컴퓨터는 부동소수점(`IEEE 754`) 방식을 쓰기 때문에 미세한 오차가 발생합니다. 이게 쌓이면 몇천 원, 몇만 원이 됩니다.
그래서 우리는 **`BigDecimal`**을 사용합니다. 이것은 숫자를 내부적으로 문자에 가까운 형태(정확히는 정수 배열)로 저장해서 100% 정확한 연산을 보장합니다.

**[Contract.java]**

```java
@Column(nullable = false, precision = 19, scale = 4)
private BigDecimal monthlyRent;
```

- `precision = 19`: 전체 자릿수 (약 1000조 단위까지 커버)
- `scale = 4`: 소수점 이하 자릿수. 소수점 4째 자리까지 정확하게 저장하겠다는 뜻입니다.

### 1-2. 생성자 막아두기 (DDD & 불변성)

객체가 "불완전한 상태"로 돌아다니는 것을 막아야 합니다.
월세가 없는 계약서, 임차인이 없는 계약서가 존재할 수 있나요? 없어야 합니다.

```java
// [Contract.java]

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 1. 기본 생성자 막기
public class Contract {
    // ... 필드들 ...

    // 2. 필수 값을 모두 받는 생성자 (또는 팩토리 메서드) 제공
    public Contract(Long companyId, Tenant tenant, BigDecimal monthlyRent, ...) {
        this.companyId = companyId;
        this.tenant = tenant;
        this.monthlyRent = monthlyRent;
        // 생성되는 순간 필수 데이터가 다 채워져 있음이 보장됨
    }
}
```

1. `@NoArgsConstructor(access = AccessLevel.PROTECTED)`: JPA는 기본 생성자가 필요하지만, 외부에서 개발자가 `new Contract()`로 텅 빈 객체를 만드는 실수는 막아놓습니다.
2. 모든 필드를 받는 생성자를 통해, 객체가 생성되자마자 "완전한 상태"가 되도록 강제합니다.

---

## Chapter 2. 데이터 준비: 10만 건을 0.5초 만에 넣는 비결

배치를 테스트하려면 데이터가 있어야 합니다. 10만 건을 DB에 넣어봅시다.

### 2-1. JPA `saveAll()`의 배신

```java
// ❌ 이렇게 하면 10만 건 넣는데 1분 넘게 걸릴 수도 있습니다.
repository.saveAll(list);
```

JPA는 데이터를 넣기 전에 **영속성 컨텍스트(1차 캐시)**에 객체를 저장합니다. 그리고 "이 객체가 변경되었나?"를 계속 감시(Dirty Checking)합니다. 10만 개를 다 캐시에 넣고 감시 비용까지 지불하니 속도가 느릴 수밖에 없습니다.

### 2-2. `JdbcTemplate` Batch Update (해결사)

캐시고 뭐고 다 건너뛰고, "INSERT 쿼리 문자열"만 만들어서 DB에 바로 쏘는 **JDBC 레벨의 기술**을 사용합니다.

**[DataInitTest.java]**

```java
String sql = "INSERT INTO contract (company_id, ..., monthly_rent) VALUES (?, ..., ?)";

jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {

    // 이 메서드는 10만 번 호출됩니다 (i = 0 ~ 99999)
    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
        // SQL의 '?' 구멍에 값을 채워넣는 과정
        ps.setLong(1, randomCompanyId);
        ps.setLong(2, tenantId);
        ps.setBigDecimal(3, rentAmount);
    }

    // "총 몇 개 보낼 거야?"
    @Override
    public int getBatchSize() {
        return 100_000; // 10만 개!
    }
});
```

이 방식은 INSERT 쿼리를 하나씩 DB에 날리는 게 아니라, 메모리에 쿼리를 쭉 쌓아뒀다가 **트럭 한 대에 실어서 한 번에** 보냅니다 (Batch Size 만큼).
결과적으로 제 로컬 PC 기준 **0.5초 ~ 1초** 만에 10만 건이 들어갑니다.

---

## Chapter 3. 배치 아키텍처: 공장 조립 라인 만들기

Spring Batch가 어렵게 느껴진다면 **"거대한 공장"**을 상상해보세요.

### 3-1. Job와 Step: 공장과 조립 라인

- **Job (일감)**: "이번 달 정산하기"라는 하나의 거대한 작업 목표입니다.
- **Step (단계)**: Job을 구성하는 세부 단계입니다. (예: 1단계-데이터 읽기, 2단계-계산하기, 3단계-저장하기)

우리의 `billJob`은 단 하나의 `billStep`으로 이루어져 있습니다.

```java
@Bean
public Job billJob(Step billStep) {
    return new JobBuilder("billJob", jobRepository)
            .start(billStep) // "정산 작업 시작!"
            .build();
}
```

### 3-2. 핵심 3요소 (Reader, Processor, Writer)

Step 내부에서는 **3명의 전문 일꾼**이 분업을 합니다.

1. **ItemReader (공급 담당)**: 창고(DB)에서 원자재(Contract)를 꺼내옵니다.
2. **ItemProcessor (가공 담당)**: 원자재를 다듬어서 완제품(Bill)으로 만듭니다.
3. **ItemWriter (납품 담당)**: 완제품을 포장해서 창고(DB)에 다시 넣습니다.

### 3-3. Chunk 지향 처리: 트럭에 실어 나르기

데이터가 10만 개인데 하나 만들고 DB에 넣고, 하나 만들고 DB에 넣으면 시간이 너무 오래 걸립니다. (택배 기사님이 상자 하나 들고 배송하고 다시 물류센터 오는 꼴)

그래서 **Chunk(덩어리)** 개념을 씁니다.
"1000개 모일 때까지 기다렸다가, 1000개가 차면 **한 번에** 처리하자!"

```java
// [BillGenerateJobConfig.java]

return new StepBuilder("billStep", jobRepository)
        // <입력, 출력>chunk(사이즈, 트랜잭션매니저)
        .<Contract, Bill>chunk(1000, transactionManager)
        .reader(contractReader)
        .processor(processor)
        .writer(writer)
        .build();
```

- **메모리 보호**: 10만 개를 다 메모리에 올리지 않고 1000개만 올립니다.
- **트랜잭션 효율**: 10만 번 커밋하지 않고 100번만 커밋합니다. (속도 향상 핵심)

---

## Chapter 4. 멀티테넌시: 내 데이터는 내가 지킨다

우리 서비스는 SaaS입니다. 삼성전자 데이터 정산하는데 LG전자 데이터가 딸려오면 큰일 납니다.
Job을 실행할 때 외부에서 `companyId`를 받아서 필터링해야 합니다.

### 4-1. Job Parameter와 `@StepScope`: 타이밍의 마법

"배치 돌릴 때 어떤 회사 건지 알려줄게!" 라고 외부에서 값을 주입받는 것을 **Job Parameter**라고 합니다.
그런데 이걸 받으려면 **`@StepScope`**라는 특별한 설정이 필요합니다.

```java
@Bean
@StepScope // ✨ "지연 생성"의 마법
public JpaPagingItemReader<Contract> contractReader(
        @Value("#{jobParameters['companyId']}") Long companyId // 실행 시점에 주입됨
) { ... }
```

**[Why?] 왜 `@StepScope`가 필수인가요?**

- 일반적인 Spring Bean은 **앱 서버가 켜질 때(로딩 시점)** 다 만들어집니다.
- 하지만 `companyId`는 **Job을 실행하는 순간(런타임)**에 비로소 알 수 있습니다.
- `@StepScope`를 붙이면, Bean 생성을 **"Step이 실제로 실행될 때까지" 미룹니다(Late Binding).** 덕분에 실행 시점에 파라미터를 받아서 Reader를 만들 수 있게 됩니다.

---

## Chapter 5. 성능 최적화: N+1 지옥 탈출

ORM(JPA)을 쓸 때 가장 조심해야 할 문제입니다.

### 5-1. 문제 상황 (Lazy Loading)

`Contract` 안에는 `Tenant`(임차인) 정보가 있습니다.

```java
@ManyToOne(fetch = FetchType.LAZY)
private Tenant tenant;
```

`FetchType.LAZY`는 "진짜 필요할 때 DB에서 가져오겠다"는 뜻입니다.

1. `Contract` 1000개를 읽음 (쿼리 1방)
2. 첫 번째 `Contract`의 `tenant.getName()`을 호출함 -> DB 조회 (쿼리 1방)
3. 두 번째 ... (쿼리 1방)
   ...
   결국 **1 + 1000 = 1001번**의 쿼리가 나갑니다. 이를 **N+1 문제**라고 합니다.

### 5-2. 해결책: `JOIN FETCH`

"야, Contract 가져올 때 Tenant도 그냥 같이 옆구리에 끼워서 가져와." 라고 명령하는 것입니다.

**[BillGenerateJobConfig.java]**

```java
// BEFORE: SELECT c FROM Contract c WHERE ...
// AFTER
.queryString("SELECT c FROM Contract c JOIN FETCH c.tenant WHERE ...")
```

`JOIN FETCH` 한 단어 추가로, 쿼리는 단 **1방**으로 줄어듭니다.
데이터가 10만 건이면 100,001번 쿼리가 100번(Chunk size 1000 기준)으로 줄어드는 기적을 볼 수 있습니다.

---

## Chapter 6. 쓰기 최적화: 마지막 병목 뚫기

읽기(Reader)를 튜닝했다면, 이제 쓰기(Writer)입니다. 여기서도 **JPA를 버리고 JDBC를 선택**했습니다.

### 6-1. 심플한 게 최고다 (`JdbcBatchItemWriter`)

```java
@Bean
public JdbcBatchItemWriter<Bill> billWriter() {
    return new JdbcBatchItemWriterBuilder<Bill>()
            .dataSource(dataSource)
            // SQL을 직접 작성 (묻지도 따지지도 않고 바로 꽂는다)
            .sql("INSERT INTO bill (company_id, ..., amount) VALUES (:companyId, ..., :amount)")
            .beanMapped() // Bill 객체의 필드명과 SQL 파라미터(:names) 자동 매핑
            .build();
}
```

**[Why?] 왜 JPA(`JpaItemWriter`)가 아닌가?**

- **JPA**: 너무 똑똑합니다. "이거 저장하면 기존 데이터랑 충돌 안 나나?", "영속성 컨텍스트에 넣어야지" 등등 따지는 게 많아서 **대량 입력 시 느립니다.** (특히 `Identity` 전략 사용 시 Bulk Insert 불가)
- **JDBC**: 단순 무식합니다. "그냥 DB에 꽂아!" 하고 바로 밀어 넣습니다. **대용량 처리에는 이렇게 "생각 없는 친구"가 훨씬 빠릅니다.**

---

## 마무리

이 시스템은 단순히 "돌아가는 코드"가 아니라, **"커지는 데이터에도 버티는 코드"**를 고민한 결과입니다.

1. **정합성**: `BigDecimal`과 생성자 제약
2. **속도**: `Bulk Insert`와 `Chunk` 처리
3. **효율**: `N+1` 문제 해결

이 세 가지 원칙만 기억한다면, 100만 건, 1000만 건의 데이터도 두렵지 않을 것입니다.
