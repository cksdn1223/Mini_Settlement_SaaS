# [Architecture] 정산 시스템의 도메인 설계와 OOP

#Settlement #Architecture #Domain #OOP
[[settlement_system_master_guide|◀️ 돌아가기]]

## "숫자를 다루는 코드"가 아닙니다. "돈"을 다루는 코드입니다.

정산 시스템에서 가장 중요한 것은 **신뢰성**입니다.
`100원`이 `100.0000001원`이 되는 순간, 회사의 신뢰는 무너집니다.
단순히 "기능이 돌아가는 것"을 넘어, 비즈니스 규칙이 코드에 **안전하게** 녹아들도록 설계해야 합니다.

---

## Chapter 1. Rich Domain Model: 객체 스스로 일하게 하라

많은 개발자들이 흔히 저지르는 실수가 있습니다. **Entity는 데이터만 담고, 로직은 Service에 몰아넣는 것(Anemic Domain Model)**입니다.

### 1-1. 나쁜 예: 절차지향적인 Service 계층

```java
// ❌ Service가 모든 계산을 담당함 (수동적 Entity)
@Transactional
public void calculateRent(Long contractId) {
    Contract contract = repository.findById(contractId);

    // 비즈니스 로직이 서비스에 노출됨
    if (contract.getStatus() == "WATING") {
        throw new IllegalStateException();
    }

    BigDecimal amount = contract.getBasePrice().add(contract.getVat());
    contract.setTotalAmount(amount); // Setter로 주입
}
```

이 방식의 문제는 로직이 여기저기 흩어진다는 점입니다. `Contract`의 상태 규칙이 `RentService`, `TerminationService` 등에 중복되어 작성될 위험이 큽니다.

### 1-2. 좋은 예: 객체지향적인 설계 (Rich Domain Model)

데이터(필드)가 있는 곳에 책임(로직)도 있어야 합니다.

```java
// ✅ Contract 객체가 스스로 규칙을 검증하고 계산함
@Entity
public class Contract {

    public void calculateTotalAmount() {
        validateStatus(); // 자신의 상태는 자신이 검증
        this.totalAmount = this.basePrice.add(this.vat);
    }

    private void validateStatus() {
        if (this.status == ContractStatus.WAITING) {
            throw new BusinessException("대기 상태에서는 계산할 수 없습니다.");
        }
    }
}
```

> [!TIP] **Why: 왜 이렇게 해야 하나요?**
>
> - **응집도**: 계약 관련 규칙이 변경되면 `Contract` 클래스만 수정하면 됩니다.
> - **안전성**: 외부에서 함부로 데이터를 조작(`setTotalAmount`)할 수 없게 막을 수 있습니다.

---

## Chapter 2. Value Object (VO): 불변의 가치

돈, 주소, 날짜 구간 같은 개념은 **값 객체(Value Object)**로 포장해야 합니다.

### 2-1. Primitive Type의 한계

```java
// 의미를 알 수 없는 BigDecimal들
private BigDecimal supplyPrice;
private BigDecimal tax;
private BigDecimal total;
```

이 코드만 봐서는 `supplyPrice`가 원화(KRW)인지 달러(USD)인지, 마이너스가 되어도 되는지 알 수 없습니다.

### 2-2. Money 객체 만들기

```java
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Money {

    private BigDecimal amount;

    public Money(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("돈은 음수가 될 수 없습니다.");
        }
        this.amount = amount;
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    // ... equals & hashCode 구현 ...
}
```

이제 `Contract` 코드가 아래와 같이 변합니다.

```java
@Embedded
@AttributeOverride(name = "amount", column = @Column(name = "supply_price"))
private Money supplyPrice;
```

> [!NOTE] **응용: 어떻게 활용하나요?**
> VO는 **불변(Immutable)**이어야 합니다. 값이 바뀌어야 한다면 `setAmount()`가 아니라 `new Money()`로 새로운 객체를 반환하세요. 이는 부작용(Side-effect) 없는 안전한 코드를 만듭니다.

---

## Chapter 3. 상태 패턴 (State Pattern): 복잡한 라이프사이클 관리

계약은 `신청` -> `승인` -> `진행중` -> `만료` -> `해지` 등 복잡한 상태 변화를 겪습니다. 이를 `if-else`문으로 처리하면 코드가 엉망이 됩니다.

### 3-1. Enum을 활용한 상태 관리

Java의 `Enum`은 강력합니다. 상태별 행위를 Enum 안에 정의할 수 있습니다.

```java
public enum ContractStatus {

    DRAFT {
        @Override
        public void checkCancellable() {
            // OK. 초안은 언제든 취소 가능
        }
    },
    ACTIVE {
        @Override
        public void checkCancellable() {
            throw new BusinessException("진행 중인 계약은 중도 해지 절차를 밟아야 합니다.");
        }
    },
    TERMINATED {
        @Override
        public void checkCancellable() {
            throw new BusinessException("이미 종료된 계약입니다.");
        }
    };

    // 추상 메서드로 정의하여 각 상태가 구현하도록 강제
    public abstract void checkCancellable();
}
```

### 3-2. 도메인 적용

```java
public class Contract {
    @Enumerated(EnumType.STRING)
    private ContractStatus status;

    public void cancel() {
        // if문 없이 다형성으로 해결
        this.status.checkCancellable();
        this.status = ContractStatus.TERMINATED;
    }
}
```

이렇게 하면 새로운 상태가 추가되어도 `Contract`로직을 건드리지 않고 `Enum`만 수정하면 됩니다(OCP 원칙 준수).

---

## 마무리

정산 시스템의 도메인 설계는 **"코드로 법전을 만드는 일"**과 같습니다.

1. **Rich Domain**: 로직을 Entity 안으로 모으세요.
2. **VO**: 돈과 같은 중요 개념은 전용 객체로 감싸세요.
3. **상태 관리**: 복잡한 상태 변화는 Enum이나 State 패턴으로 추상화하세요.

이 기초가 튼튼해야 정산 데이터가 꼬이지 않습니다.
