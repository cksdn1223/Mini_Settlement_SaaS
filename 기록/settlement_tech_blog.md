# [Retrospect] Spring Batch를 활용한 대용량 SaaS 임대료 정산 시스템 개발기

> **Project Overview**
>
> - **Topic**: 매월 1일, 10만 건 이상의 임대 계약 데이터를 처리하여 정산서(Bill)를 자동 발행하는 시스템 구축
> - **Tech Stack**: Java 17, Spring Boot 3.x, Spring Batch 5, JPA, MySQL
> - **Key Achievement**: 10만 건 데이터 처리 속도 최적화 (Insert 0.5초), N+1 문제 해결을 통한 쿼리 효율 99% 개선

---

## 1. 들어가며: 왜 정산 시스템인가?

B2B 임대 관리 SaaS(Software as a Service)를 개발하며 가장 까다롭고 중요한 핵심 기능은 바로 **'돈'을 다루는 정산(Settlement) 프로세스**였습니다. 매월 1일이 되면 수많은 계약(Contract) 데이터를 바탕으로 정확한 금액의 청구서(Bill)를 발행해야 합니다.

초기 기획 단계에서 예상되는 데이터 볼륨은 테넌트(Tenant) 당 수만 건 이상이었으며, 여러 고객사의 데이터를 동시에 처리해야 하는 **멀티테넌시(Multi-tenancy)** 환경이었습니다. 단순한 웹 요청-응답(Request-Response) 구조로는 대량의 데이터를 안정적으로 처리하기 어렵다고 판단하여 **Spring Batch**를 도입하게 되었습니다.

이 글에서는 해당 프로젝트를 진행하며 고민했던 **도메인 설계, 대량 데이터 처리 전략, 그리고 성능 문제 해결(Troubleshooting)** 과정을 상세히 공유하고자 합니다.

---

## 2. 도메인 설계: 돈과 객체의 정합성 (DDD & Money)

정산 시스템에서 가장 중요한 것은 **'금액의 정확성'**과 **'객체의 불변성'**입니다.

### 2.1. `BigDecimal` vs `double`

금융 데이터를 다룰 때 부동소수점(`float`, `double`) 타입을 사용하는 것은 매우 위험합니다.

> **[Concept Note] 부동소수점 오차 (Floating Point Error)**
> 컴퓨터는 실수를 0과 1로 표현하기 위해 **IEEE 754**라는 부동소수점 표준을 사용합니다. 하지만 이 방식은 `0.1 + 0.2`를 정확히 `0.3`으로 표현하지 못하고 `0.30000000000000004`와 같은 미세한 오차를 발생시킵니다.
> 돈 계산에서 이런 1원 미만의 오차가 수만 건 누적되면 나중에는 수천 원, 수만 원의 차액이 발생하게 되므로, 금융권이나 정산 시스템에서는 절대 `double`을 사용해서는 안 됩니다.

따라서 저는 **`BigDecimal`**을 도입하여 금액을 처리했습니다. 이 클래스는 내부적으로 숫자를 정수 배열로 다루기 때문에 오차 없는 정확한 사칙연산이 가능합니다. DB 스키마 설계 시에도 `precision`과 `scale`을 명시하여 소수점 처리를 엄격하게 관리했습니다.

```java
// domain/Contract.java

// 정산 및 회계: 돈은 절대 double로 쓰지 않는다. (소수점 4자리까지 허용)
@Column(nullable = false, precision = 19, scale = 4)
private BigDecimal monthlyRent; // 월세
```

### 2.2. 불완전한 객체 생성을 막는 DDD 스타일

Entity 객체가 생성되는 시점에 필수 데이터가 누락된다면, 이후 로직에서 `NullPointerException` 등 런타임 예외가 발생할 위험이 큽니다. 이를 방지하기 위해 **기본 생성자의 접근을 `protected`로 막고**, 필수 값을 모두 받는 생성자를 통해서만 객체를 생성할 수 있도록 강제했습니다.

```java
// domain/Contract.java

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 무분별한 생성 방지
public class Contract {
    // ... 필드 생략 ...

    // DDD 스타일: 정적 팩토리 메서드 혹은 생성자를 통해 정합성 보장
    // 생성자가 호출되는 시점에 이 객체는 '완전한 상태'임이 보장된다.
    public Contract(Long companyId, Tenant tenant, BigDecimal monthlyRent, LocalDate startDate, LocalDate endDate) {
        this.companyId = companyId;
        this.tenant = tenant;
        this.monthlyRent = monthlyRent;
        // 생성 시점부터 상태는 ACTIVE로 확정
        this.status = ContractStatus.ACTIVE;
    }
}
```

---

## 3. 대용량 데이터 처리: 10만 건을 0.5초 만에 넣기 (JDBC Bulk Insert)

성능 테스트를 위해서는 실제 운영 환경과 유사한 10만 건 이상의 더미 데이터가 필요했습니다. 처음에는 JPA의 `saveAll()`을 사용했으나, 심각한 성능 저하를 경험했습니다.

### [Problem] JPA `saveAll()`의 한계

> **[Concept Note] 영속성 컨텍스트(Persistence Context)와 Dirty Checking**
> JPA는 **'영속성 컨텍스트'**라는 1차 캐시 저장소를 둡니다. 객체를 저장(`save`)할 때 바로 DB에 쿼리를 날리는 것이 아니라, 이 캐시에 먼저 저장하고 **트랜잭션이 끝나는 시점**에 변경된 내용을 감지(**Dirty Checking**)하여 쿼리를 날립니다.
>
> 1~2건을 다룰 때는 매우 편리하지만, **10만 건**을 `saveAll()`로 넣으려고 하면 10만 개의 객체를 모두 캐시에 올리고, 각각 변경 여부를 추적해야 하므로 **메모리와 CPU 비용(Overhead)**이 엄청나게 발생합니다.

### [Solution] `JdbcTemplate` Batch Update

영속성 컨텍스트를 우회하고 DB에 직접 SQL 구문을 묶어서 보내는 **JDBC 레벨의 Batch Update**를 적용했습니다. 이는 "INSERT 쿼리 1000개를 모아뒀다가 트럭 한 대에 실어서 한 번에 보내는 것"과 같습니다.

#### 코드 상세 설명

```java
// test/.../DataInitTest.java

// batchUpdate: 여러 개의 UPDATE/INSERT 쿼리를 한 번에 전송하는 메서드
jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {

    // 1. 값 세팅 (PreparedStatement)
    // 이 메서드는 getBatchSize()만큼 반복 호출됩니다.
    // 'i'는 현재 반복 횟수(인덱스)입니다.
    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
        // ps: 실제 SQL 쿼리에 값을 채워넣는 객체
        // 쿼리의 ? 위치에 값을 넣습니다. (파라미터 바인딩)
        ps.setLong(1, companyId);
        ps.setBigDecimal(3, new BigDecimal(rentAmount));
        // ... (생략)
    }

    // 2. 배치 크기 결정
    // 총 몇 번 쿼리를 모아서 보낼지 결정합니다. 여기서는 100,000건입니다.
    @Override
    public int getBatchSize() {
        return 100_000; // 10만 건을 한 번의 배치(네트워크 요청)로 전송
    }
});
```

- `BatchPreparedStatementSetter`: 배치 처리를 위해 각 쿼리의 파라미터를 설정하는 콜백 인터페이스입니다.
- **결과**: 10만 건의 데이터를 Insert 하는 데 **약 0.5초**밖에 걸리지 않았습니다. JPA를 고집하지 않고 기술의 특징에 맞춰 적절한 도구(`JdbcTemplate`)를 선택한 결과입니다.

---

## 4. Spring Batch 아키텍처 및 구현

대용량 정산 처리는 메모리 부하를 방지하기 위해 **Chunk 지향 처리(Chunk-oriented Processing)** 방식을 채택했습니다.

### [Concept Note] Spring Batch 구조 이해하기

Spring Batch는 대용량 처리를 위해 **Reader(읽기) -> Processor(가공) -> Writer(쓰기)** 라는 3단계 파이프라인 구조를 가집니다.

1.  **ItemReader**: DB나 파일에서 데이터를 **'읽어옵니다'**. 한 번에 다 가져오지 않고, 지정한 크기(Chunk)만큼 끊어서 가져옵니다. (ex: 계약서 1000장 꺼내기)
2.  **ItemProcessor**: 읽어온 데이터를 비즈니스 로직에 따라 **'가공합니다'**. (ex: 계약서 정보를 보고 이번 달 월세 청구서 계산하기)
3.  **ItemWriter**: 가공 완료된 데이터를 **'저장합니다'**. (ex: 계산된 청구서를 DB에 저장하기)

**Chunk 지향 처리란?**
데이터를 1건씩 처리하고 저장하면 DB 커넥션을 너무 많이 맺고 끊습니다. 반대로 10만 건을 한 번에 처리하면 메모리가 터집니다.
Chunk 방식은 **"1000개 모일 때까지는 메모리에 쌓아두고 처리하다가, 1000개가 차면 딱 한 번 DB에 커밋(저장)"** 하는 방식입니다. 이를 통해 **메모리 효율**과 **트랜잭션 성능** 두 마리 토끼를 잡을 수 있습니다.

### 4.1. Job 프로세스 구현

```java
// batch/BillGenerateJobConfig.java

@Bean
public Step billStep(JpaPagingItemReader<Contract> contractReader) {
    return new StepBuilder("billStep", jobRepository)
            // <입력타입, 출력타입>chunk(사이즈, 트랜잭션매니저)
            .<Contract, Bill>chunk(1000, transactionManager)
            .reader(contractReader)      // 1. 읽기
            .processor(billProcessor())  // 2. 가공
            .writer(billWriter())        // 3. 쓰기
            .build();
}
```

- **`<Contract, Bill>chunk(1000, ...)`**: Contract 객체를 읽어서 Bill 객체로 변환하며, 1000개가 모이면 DB에 반영(Connect/Commit)하겠다는 뜻입니다.

Writer 부분에서도 `JdbcBatchItemWriter`를 사용했습니다. 이는 앞서 살펴본 Bulk Insert와 마찬가지로, 생성된 1000개의 Bill 객체를 `INSERT` 쿼리 한 방으로 묶어서 DB에 쏘는 역할을 합니다.

---

## 5. [Core] 성능 최적화: N+1 문제 해결 (Troubleshooting)

개발 과정에서 가장 큰 성능 이슈는 **N+1 문제**였습니다. 이는 ORM(JPA)을 사용할 때 발생할 수 있는 가장 흔하면서도 치명적인 성능 문제입니다.

### [Problem] 1000건 조회 시 쿼리가 1001번 나가는 현상

> **[Concept Note] N+1 문제와 지연 로딩(Lazy Loading)**
> JPA에서 연관된 객체(예: `계약` -> `임차인`)를 조회할 때, 당장 필요 없는 임차인 정보는 가짜 객체(Proxy)로 채워둡니다. 이를 **지연 로딩(Lazy Loading)**이라고 합니다.
> 하지만 이후 로직에서 `contract.getTenant().getName()` 처럼 실제 임차인 정보에 접근하는 순간, JPA는 그제서야 DB에 다시 SELECT 쿼리를 날립니다.
>
> 1. `Contracts` 1000개를 가져오는 쿼리 **1번 (1)**
> 2. 각 `Contract`마다 `Tenant`를 조회하는 쿼리 **1000번 (N)**
>
> 결과적으로 **1 + N** 번의 쿼리가 발생하여 DB에 엄청난 부하를 주게 됩니다.

**실제 로그 상황 (Hell of N+1):**

```text
Hibernate: select ... from contract ... limit 1000; (최초 조회 1번)
Hibernate: select ... from tenant where id=1 (1번째 계약의 임차인 조회)
Hibernate: select ... from tenant where id=2 (2번째 계약의 임차인 조회)
...
Hibernate: select ... from tenant where id=1000 (1000번째 계약의 임차인 조회)
```

10만 건을 처리한다면 조회 쿼리만 10만 1번이 실행되어, DB 커넥션 풀이 마르고 전체 시스템이 마비될 수 있는 심각한 문제였습니다.

### [Solution] `JOIN FETCH` 적용

이를 해결하기 위해 `JpaPagingItemReader`의 쿼리(JPQL)에 **`JOIN FETCH`**를 적용했습니다.

> **[Concept Note] JOIN FETCH란?**
> 일반적인 `JOIN`은 연관된 데이터를 필터링하는 용도라면, `JOIN FETCH`는 **"연관된 엔티티까지 진짜로 다 채워서(Fetch) 가져오라"**는 명령어입니다. DB에서 데이터를 가져올 때 아예 `Contract`와 `Tenant`를 합쳐서 가져오기 때문에, 나중에 `Tenant`를 조회해도 추가 쿼리가 나가지 않습니다.

```java
// batch/BillGenerateJobConfig.java

return new JpaPagingItemReaderBuilder<Contract>()
        .name("contractReader")
        .entityManagerFactory(entityManagerFactory)
        // [Key Point] JOIN FETCH로 한 번에 당겨온다 (Lazy Loading 무력화)
        .queryString("SELECT c FROM Contract c JOIN FETCH c.tenant WHERE c.status = 'ACTIVE' AND c.companyId = :companyId")
        .pageSize(1000)
        .build();
```

- **Before**: Contract 조회 1회 + Tenant 조회 1000회 (총 1001회)
- **After**: **Contract + Tenant 함께 조회 1회** (총 1회)
- **결과**: 쿼리 수가 1/1000 이상으로 감소하며 배치 수행 속도가 획기적으로 개선되었습니다.

---

## 6. 멀티테넌시(Multi-tenancy) 지원과 데이터 격리

SaaS 솔루션 특성상 하나의 DB에 A사, B사, C사의 데이터가 섞여 있습니다. A사의 정산 배치 작업이 B사의 데이터를 건드리면 대형 사고가 발생합니다. 즉, **데이터 격리(Data Isolation)**가 필수적입니다.

### 6.1. Job Parameter를 이용한 필터링

가장 확실한 방법은 배치를 실행할 때 "어떤 회사의 작업을 할 것인가?"를 명시적으로 주입하는 것입니다. 이를 **Job Parameter**라고 합니다.

```java
// batch/BillGenerateJobConfig.java

@Bean
@StepScope // Scope 설정 (매우 중요)
public JpaPagingItemReader<Contract> contractReader(
        @Value("#{jobParameters['companyId']}") Long companyId // 1. 파라미터 주입
) {
    if (companyId == null) {
        // ... 예외 처리 ...
    }

    return new JpaPagingItemReaderBuilder<Contract>()
            // ...
            // 2. 쿼리에 파라미터 바인딩 (WHERE c.companyId = :companyId)
            // 이를 통해 DB 레벨에서 타 회사의 데이터를 원천 배제합니다.
            .parameterValues(Collections.singletonMap("companyId", companyId))
            .build();
}
```

### 6.2. @StepScope와 Late Binding

여기서 중요한 기술 포인트는 `@StepScope`입니다.

- **문제**: Spring Bean은 보통 애플리케이션 실행 시점(서버 켤 때)에 생성됩니다. 하지만 `companyId`는 **Job을 실행하는 시점**에 결정됩니다. 서버 켤 때는 `companyId`가 뭔지 모릅니다.
- **해결**: `@StepScope`를 붙이면, Bean의 생성을 **"실제 배치 단계(Step)가 시작될 때"**까지 지연시킵니다(**Late Binding**). 덕분에 실행 시점에 들어온 파라미터를 받아서 동적인 쿼리를 만들 수 있습니다.

---

## 7. 신뢰성 있는 배치를 위한 테스트 전략 (Testing)

배치 프로세스는 시스템의 중요한 데이터를 대량으로 변경하기 때문에, 버그가 발생하면 그 파급력이 엄청납니다 (예: 10만 명에게 요금 폭탄 청구서 발송). 따라서 꼼꼼한 테스트가 필수입니다.

### 7.1. @SpringBatchTest

스프링 배치는 테스트를 위한 전용 어노테이션인 `@SpringBatchTest`를 제공합니다. 이를 사용하면 복잡한 배치 환경을 쉽게 구축하여 테스트할 수 있습니다.

```java
// test/.../BillJobTest.java

@SpringBatchTest
@SpringBootTest(classes = {BillGenerateJobConfig.class, ...})
class BillJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils; // Job 실행 도구

    @Test
    @DisplayName("청구서 발행 배치 실행 테스트")
    void runBillJob() throws Exception {
        // given: 테스트 환경 설정
        // Job 실행 시 필요한 파라미터(companyId)를 설정합니다.
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("companyId", 1L)
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();

        // when: 배치 실행
        // 실제 Job을 실행시킵니다.
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // then: 검증
        // 1. 배치가 정상적으로 끝났는지 확인 (COMPLETED)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 2. DB에 청구서가 올바르게 생성되었는지 별도 조회 검증 필요
        // (예: BillRepository.count() == 예측값)
    }
}
```

이 테스트를 통해 배치가 예외 없이 끝까지 도는지, 파라미터는 잘 먹히는지, 결과 데이터는 정합한지를 배포 전에 확실하게 검증할 수 있습니다.

---

## 8. 마치며

이번 프로젝트를 통해 단순한 기능 구현을 넘어 **"데이터의 규모가 커질 때 시스템이 어떻게 반응하는가"**를 깊이 있게 고민해볼 수 있었습니다.

1.  **JDBC Bulk Insert**로 대량 데이터의 입력을 최적화했고,
2.  **Spring Batch**의 Chunk 모델로 메모리 사용량을 예측 가능하게 설계했으며,
3.  **Fetch Join**으로 ORM 사용 시 발생할 수 있는 치명적인 성능 병목을 해결했습니다.

이러한 경험은 앞으로 더 복잡하고 거대한 트래픽을 처리하는 백엔드 시스템을 설계하는 데 있어 단단한 초석이 될 것입니다.

---
