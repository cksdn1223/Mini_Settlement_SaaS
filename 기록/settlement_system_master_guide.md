# [Total Guide] 대용량 정산 시스템 개발자 마스터 가이드

#Settlement #Architecture #SaaS #Batch #JPA #Testing

본 가이드는 정산/회계 시스템을 개발할 때 반드시 알아야 할 **핵심 원칙과 실무 기술**을 총정리한 문서입니다.
"기능 구현"을 넘어 **"돈 앞에 당당한 시스템"** 을 만드는 것이 목표입니다.

> [!INFO] **가이드 활용법**
> 이 문서는 요약본입니다. 각 파트별 상세 내용은 연결된 **상세 문서(`[[...]]`)**를 참고하세요.

---

## Part 1. 도메인 설계: 돈을 다루는 태도

> **상세 보기**: [[study_01_domain_modeling]]

### 1-1. Rich Domain Model (객체 스스로 일하게 하라)

- **Problem**: `Service`에 로직이 몰리면(Anemic Model), 데이터 정합성을 보장하기 어렵습니다.
- **Solution**: 데이터가 있는 `Entity` 안에 계산 로직을 넣으세요.
- **Example**: `Contract.calculateTotalAmount()` 메서드가 스스로 상태(`status`)를 검증하고 계산해야 합니다.

### 1-2. Value Object (VO): 돈은 특별하니까

- **Problem**: `BigDecimal price`만으로는 이게 원화인지 달러인지, 음수가 돼도 되는지 모릅니다.
- **Solution**: `Money`라는 객체를 만들어 감싸세요.
  - 생성자에서 "음수 불가" 검증
  - `add()`, `minus()` 메서드 제공
  - **불변(Immutable)** 객체로 설계하여 부작용(Side-effect) 원천 차단

### 1-3. 상태 패턴 (State Pattern)

- 계약의 복잡한 상태(`신청` -> `승인` -> `진행` -> `해지`)를 `if-else`로 처리하면 지옥이 펼쳐집니다.
- **Solution**: Java `Enum`에 추상 메서드를 선언하여, 각 상태가 스스로 "취소 가능 여부" 등을 판단하게 하세요.

---

## Part 2. 멀티테넌시(Multi-tenancy): 데이터 보안

> **상세 보기**: [[study_02_multi_tenancy]]

SaaS의 핵심은 **"남의 회사 데이터가 절대로 보이면 안 된다"** 는 것입니다.

### 2-1. 전략 선택: 컬럼 분리 (Shared Database)

- 모든 테이블에 `company_id` 컬럼을 추가하는 방식이 가장 가성비가 좋습니다.
- > [!WARNING] **주의**
  > 개발자가 실수로 `WHERE` 조건을 빼먹으면 대형 사고가 납니다.

### 2-2. 안전장치: `@TenantId` (Hibernate 6)

- **자동화**: 엔티티 필드에 `@TenantId`를 붙이면, 하이버네이트가 알아서 모든 쿼리에 `WHERE company_id = ?`를 붙여줍니다.
- **식별**: `CurrentTenantIdentifierResolver`를 구현하여, 현재 로그인한 사용자의 회사 ID를 하이버네이트에게 알려줘야 합니다(ThreadLocal 활용).

### 2-3. (대안) AOP + Filter

- AOP로 `Service` 실행 전 `ThreadLocal`에서 회사 ID를 꺼내 `session.enableFilter("tenantFilter")`를 실행하는 방식입니다. 흐름을 이해하는 데 좋습니다.

---

## Part 3. DB 설계: 불변과 정합성

> **상세 보기**: [[study_03_db_design]]

### 3-1. 절대 불변 (Immutable Ledger)

- > [!IMPORTANT] **원칙**
  > 돈과 관련된 데이터는 절대 `UPDATE`, `DELETE` 하지 않습니다.
- **수정**: 실수가 있었다면 "취소 내역"을 새로 `INSERT` 하여 상쇄시켜야 합니다.
- **테이블**: 잔액(`balance`)만 믿지 말고, 거래 내역(`transaction_history`)을 모두 기록하여 언제든 검증 가능하게 하세요.

### 3-2. 부동소수점의 배신

- `double`은 `0.1 + 0.2 != 0.3` 입니다. (부동소수점 오차)
- **Solution**: DB는 `DECIMAL`, Java는 `BigDecimal`을 필수로 사용해야 합니다.

### 3-3. 동시성 제어 (따닥 방지)

- **상황**: 환불 버튼을 동시에 2번 누르면?
- **해결**: **비관적 락(`Pessimistic Lock`)**을 사용하세요.
  - `SELECT ... FOR UPDATE` 구문으로, 트랜잭션이 끝날 때까지 해당 데이터 줄(Row)을 물리적으로 잠급니다.
  - 속도보다 정확성이 중요한 정산 시스템의 필수 무기입니다.

---

## Part 4. Spring Batch: 대용량 처리의 기술

> **상세 보기**: [[study_04_spring_batch_deep]]

100만 건, 1000만 건을 처리하려면 웹 서버의 `for`문으로는 어림도 없습니다.

### 4-1. 실패 처리 (Fault Tolerance)

- **Retry**: 네트워크가 잠깐 끊긴 건 다시 시도하세요. `.retryLimit(3)`
- **Skip**: 데이터 자체가 썩은(Null 등) 건은 건너뛰고 나머지라도 살리세요. `.skipLimit(100)`

### 4-2. 확장성 (Partitioning)

- 반장(`Master`)이 일감을 쪼개서 조원(`Slave`)들에게 나눠주는 방식입니다.
- 서버 자원(CPU)을 최대한 활용하여 처리 속도를 배로 늘릴 수 있습니다.

### 4-3. 멱등성 (Idempotency)

- **문제**: 배치가 돌다가 죽어서 다시 돌렸을 때, 중복 결제가 되면?
- **해결**:
  1. **상태 플래그**: 처리된 건은 `status = 'DONE'`으로 마킹.
  2. **날짜 파라미터**: 코드에 `now()`를 쓰지 말고, 외부에서 주입받은 `targetDate`만 바라보게 하세요. (재처리 가능)

---

## Part 5. JPA 최적화: 성능의 비밀

> **상세 보기**: [[study_05_jpa_deep]]

### 5-1. N+1 문제 해결

- 단순 조회는 `@EntityGraph`로 `JOIN FETCH`를 자동화하세요.

### 5-2. 대량 데이터 수정 (`Bulk Update`)

- JPA 변경 감지(Dirty Checking)는 1만 건 조회 -> 1만 번 업데이트라 너무 느립니다.
- `@Modifying`으로 DB에 바로 `UPDATE` SQL을 쏘세요.
- > [!CAUTION] **주의**
  > 연산 후 반드시 영속성 컨텍스트를 비워야(`clearAutomatically=true`) 데이터 불일치를 막을 수 있습니다.

### 5-3. Open Session In View (OSIV) 끄기

- 트래픽이 많다면 `open-in-view: false`로 설정하여, DB 커넥션을 빨리 반납하게 하세요. 서버가 훨씬 더 많은 요청을 버틸 수 있습니다.

### 5-4. No-Offset Paging

- `LIMIT 1000000, 10`은 앞의 100만 개를 읽고 버리느라 느립니다.
- `WHERE id > lastId LIMIT 10` (커서 방식)으로 인덱스를 타게 하세요.

---

## Part 6. 테스트: 최후의 안전장치

> **상세 보기**: [[study_06_testing]]

### 6-1. 우선순위

1. **도메인 단위 테스트**: 가장 중요. DB 없이 비즈니스 로직(계산, 상태 변경)을 검증.
2. **Mock 테스트**: 외부 의존성(PG사 등)을 가짜 객체로 대체하여 격리.

### 6-2. 엣지 케이스 (Edge Case)

- "행복한 경로(Happy Path)"만 테스트하지 마세요.
- 윤년(`2/29`), 월말, 금액 오버플로우, 0원 결제 등 **"망할 법한 상황"**을 상상하고 괴롭히세요.

---

### 결론

정산 시스템은 **[정확성 > 성능]** 입니다.
하지만 데이터가 커지면 **[성능]** 없이는 **[정확성]** 도 지킬 수 없습니다(타임아웃).
위의 기술들은 이 두 마리 토끼를 잡기 위한 필수 생존 배낭입니다.

---

**관련 문서**

- [[settlement_tech_blog]]: 원본 기술 블로그 글
