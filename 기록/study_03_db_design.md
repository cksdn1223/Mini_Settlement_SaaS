# [Database] 정산 시스템의 DB 설계와 정합성 심화

#Settlement #Database #SQL #Performance
[[settlement_system_master_guide|◀️ 돌아가기]]

## 돈은 '삭제'될 수 없습니다.

일반적인 게시판 시스템은 글을 수정하면 DB 데이터를 `UPDATE` 합니다. 글을 지우면 `DELETE` 합니다.
하지만 정산 시스템은 다릅니다. **한 번 기록된 금전 데이터는 절대 수정하거나 삭제하면 안 됩니다.**
실수를 했다면? "실수했다"는 기록을 **추가(Insert)**해야 합니다. (-1000원 기록을 +1000원 기록으로 상쇄)

---

## Chapter 1. 불변 장부(Immutable Ledger) 설계

회계의 기본 원칙은 **"모든 이력을 남기는 것"** 입니다. "잔액"은 이력의 합산 결과일 뿐입니다.

### 1-1. 잔액(Balance) 테이블의 위험성

```sql
-- ❌ 위험한 설계: 현재 잔액만 관리
CREATE TABLE wallet (
    user_id BIGINT PRIMARY KEY,
    current_balance DECIMAL(19, 4) -- 1000 -> 2000 -> 500 (과거를 모름)
);
```

이렇게 하면 나중에 "왜 내 돈이 500원이야?" 라고 물었을 때, "어... 로그 뒤져봐야 아는데요" 라고 밖에 대답할 수 없습니다.

### 1-2. 거래 내역(Transaction History) 중심 설계

```sql
-- ✅ 좋은 설계: 모든 사건을 기록
CREATE TABLE transaction_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    amount DECIMAL(19, 4), -- (+1000, -500 등 변동분)
    remain_balance DECIMAL(19, 4), -- (그 시점의 잔액 스냅샷)
    type VARCHAR(20), -- (DEPOSIT, WITHDRAW, ADJUSTMENT, CANCEL)
    reference_id BIGINT, -- (어떤 주문/계약 때문에 발생했나?)
    created_at DATETIME
);
```

> [!TIP] **설계 핵심**
>
> 1. **Insert Only**: 이 테이블에는 오직 `INSERT`만 가능합니다. `UPDATE`, `DELETE` 권한은 아예 DB 계정에서 빼버리세요.
> 2. **Snapshot**: `remain_balance`는 그 시점의 잔액을 기록해둡니다. 매번 처음부터 다 더하는 건 느리니까요. 하지만 언제든 재검증(Re-calculation)했을 때 `SUM(amount)` 값과 일치해야 합니다.

---

## Chapter 2. 왜 `Double`을 쓰면 안 되나요? (부동소수점의 배신)

"소수점 계산 좀 틀려도 되지 않나?" -> 안 됩니다. 금융에서는 **1원의 오차도 횡령**으로 간주될 수 있습니다.

### 2-1. IEEE 754 부동소수점 방식

컴퓨터는 소수를 **"가수부(숫자) x 2의 지수승"** 형태로 근사값을 저장합니다. 그래서 `0.1` 같은 숫자를 이진수로 정확히 표현할 수 없어 **무한 소수**가 되어버립니다.

```java
double a = 1.1; // 실제로는 1.1000000000000000888178...
double b = 2.0;
// a + b 결과가 3.1이 아닐 수 있습니다.
```

### 2-2. `DECIMAL`과 `BigDecimal`

- **DB**: `DECIMAL(P, S)` 타입을 씁니다. 숫자를 문자열처럼 정확하게 저장합니다.
- **Java**: `BigDecimal`을 씁니다. 속도는 느리지만 정확도는 100%입니다.

---

## Chapter 3. 동시성 제어(Concurrency Control): 따닥! 방지

사용자가 "환불 버튼"을 빛의 속도로 두 번 눌렀습니다(일명 '따닥').
서버가 동시에 2개의 요청을 받아서, 잔액 1000원인 사람에게 1000원을 두 번 환불해주면 회사는 1000원 손해를 봅니다.

### 3-1. 갱신 손실(Lost Update) 문제

트랜잭션 A와 B가 동시에 잔액 1000원을 읽었습니다.
A: 1000 - 100 = 900 저장
B: 1000 - 200 = 800 저장 (A가 한 일 덮어씀)
결과: 잔액 800원 (실제로는 700원이어야 함) -> **100원 증발!**

### 3-2. 비관적 락(Pessimistic Lock)으로 해결

돈과 관련된 트랜잭션은 보수적이어야 합니다. "내가 수정하는 동안 아무도 건드리지 마!"라고 DB 데이터 줄(Row)에 자물쇠를 거는 방식입니다.

```java
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    // SQL: SELECT * FROM wallet WHERE user_id = ? FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(Long userId);
}
```

> [!NOTE] **작동 원리 상세**
>
> 1. **Lock 획득**: 트랜잭션 A가 `findByUserIdForUpdate`를 호출합니다. DB는 해당 Row에 `X-Lock(배타적 락)`을 겁니다.
> 2. **대기(Blocking)**: 트랜잭션 B가 들어와서 똑같은 데이터를 조회하려고 하면, DB가 멈춰 세웁니다. "A가 쓰고 있으니까 기다려."
> 3. **수행 및 해제**: A가 잔액을 차감하고 `COMMIT`하면, 락이 풀립니다.
> 4. **후속 처리**: 기다리던 B가 락을 얻고 조회합니다. 이때는 이미 잔액이 줄어든 상태입니다.

---

## Chapter 4. 감사 로그(Audit Log): 누가 그랬어?

시스템이 자동으로 정산하기도 하지만, 운영자가 수동으로 조정(Adjustment)하는 경우도 많습니다.
"김대리가 실수로 0 하나 더 붙였네?" 라는 걸 찾아내려면 기록이 팔요합니다.

### 4-1. Envers를 활용한 변경 추적

단순히 `CreateAt`, `UpdateAt` 만으로는 부족합니다. **"변경 전 값"** 이 무엇이었는지 알아야 복구를 할 수 있습니다. `Hibernate Envers`는 엔티티가 변경될 때마다 이력을 테이블에 자동으로 쌓아줍니다.

**[DB 테이블 예시: `contract_AUD`]**
| REV (버전) | REVTYPE (유형) | id | monthly_rent | ... |
|---|---|---|---|---|
| 1 | ADD (생성) | 100 | 500,000 | ... |
| 2 | MOD (수정) | 100 | 600,000 | ... |

이렇게 쌓이면, "언제 월세가 50만원에서 60만원으로 올랐는지" 정확히 알 수 있습니다.

---

## 마무리

정산 DB 설계의 3원칙:

1. **불변성**: 내역(History)은 절대 수정하지 않는다. (수정 내역을 추가할 뿐)
2. **동시성**: 돈 계산에는 비관적 락(`FOR UPDATE`)을 적용하여 '따닥'을 막는다.
3. **추적성**: 모든 변경은 꼬리표(Audit)를 남겨야 한다. 정규화보다 정합성이 **압도적으로** 중요하다.

"속도가 좀 느려도 괜찮습니다. 하지만 돈이 틀리면 서비스 문 닫아야 합니다."
