# [Testing] 테스트 코드와 견고한 설계

#Settlement #Testing #QA #TDD
[[settlement_system_master_guide|◀️ 돌아가기]]

## "테스트 코드가 없으면 배포할 수 없습니다."

불안에 떨며 "배포 버튼"을 누를 것인가요, 아니면 커피 한 잔 마시며 여유롭게 누를 것인가요?
테스트 코드는 개발자의 **수명 연장**을 위한 필수 도구입니다.

---

## Chapter 1. 무엇을 테스트해야 하는가?

모든 코드를 다 테스트할 순 없습니다. 선택과 집중이 필요합니다.

### 1-1. 우선순위 (테스트 피라미드)

1. **도메인 단위 테스트 (최우선)**: `Contract.calculate()` 처럼 핵심 비즈니스 로직. DB 없이 순수 자바 코드로 검증합니다. 가장 빠르고 중요합니다.
2. **Repository 테스트**: "쿼리가 진짜 내가 생각한 대로 나가는가?" JPA/Filter 설정 검증.
3. **통합 테스트**: 전체 흐름(Controller -> Service -> DB)이 잘 연결되는지 확인.

---

## Chapter 2. 도메인 단위 테스트 (Unit Test)

DB도, 스프링도 필요 없습니다. 오직 **JUnit**만 있으면 됩니다.

```java
class ContractTest {

    @Test
    @DisplayName("계약금이 음수면 생성할 수 없다")
    void cannotPriceMinus() {
        // Given
        BigDecimal minusPrice = new BigDecimal("-100");

        // When & Then
        assertThatThrownBy(() -> new Contract(..., minusPrice))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

> [!TIP]
> 이런 테스트가 수백 개 쌓이면, 리팩토링할 때 두렵지 않습니다. 빨간 불이 들어오면 바로 고치면 되니까요.

---

## Chapter 3. Mockito를 활용한 고립(Isolation)

Service를 테스트할 때, Repository(DB)까지 진짜를 쓰면 느리고 설정이 복잡합니다.
**가짜(Mock) 객체**를 써서 외부 요인을 차단합시다.

```java
@ExtendWith(MockitoExtension.class) // Mockito 사용 선언
class BillServiceTest {

    @InjectMocks
    BillService billService; // 테스트 대상

    @Mock
    ContractRepository contractRepository; // 가짜 객체

    @Test
    void createBill() {
        // GIVEN: "레포지토리에 뭘 물어보면 이걸 리턴해라"고 조작
        given(contractRepository.findById(1L))
            .willReturn(Optional.of(new Contract(...)));

        // WHEN
        billService.createBill(1L);

        // THEN: 비즈니스 로직이 제대로 돌았는지 검증
        // (DB에 저장됐는지는 관심 없고, 로직만 본다)
        // ...
    }
}
```

---

## Chapter 4. `@DataJpaTest`: 쿼리 검증

쿼리가 복잡하거나 `EntityGraph` 등이 잘 먹히는지 볼 때 사용합니다.

```java
@DataJpaTest // JPA 관련 설정만 로딩 (가볍다)
@AutoConfigureTestDatabase(replace = Replace.NONE) // 실제 DB 사용 (TestContainer 추천)
class ContractRepositoryTest {

    @Autowired ContractRepository repository;

    @Test
    void findWithTenant() {
        // SQL 로그를 눈으로 보거나, 쿼리 카운트를 세서 N+1 여부 체크
        List<Contract> result = repository.findAll();
        // ...
    }
}
```

---

## Chapter 5. 엣지 케이스(Edge Case) 정복

정산 시스템은 **"경계값"** 에서 항상 사고가 터집니다.
테스트 코드는 성공 케이스(Happy Path)보다 실패 케이스가 더 중요합니다.

- **윤년**: 2월 29일에 배치가 도는가?
- **월말**: 31일이 없는 달(4, 6, 9, 11월) 처리는?
- **오버플로우**: 금액이 경(10^16) 단위를 넘어갈 때 터지지 않는가?
- **0원 결제**: 0원일 때 PG사에 요청을 보내면 에러가 나는가?

> [!WARNING]
> 이런 상황을 상상하고 테스트 코드로 괴롭히는 것이 개발자의 역할입니다.

---

## 마무리

테스트 코드는 작성 시간이 아니라 **"디버깅 시간을 줄여주는 투자"** 입니다.

1. 도메인 로직은 **순수 단위 테스트**로 꼼꼼하게.
2. 외부 의존성(DB, PG사)은 **Mock**으로 격리하거나 **TestContainer**를 활용.
3. 항상 **"최악의 상황(Edge Case)"** 을 먼저 테스트하라.
