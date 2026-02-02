# [JPA] 성능 최적화와 영속성 컨텍스트 심화

#Settlement #JPA #Optimization #Performance
[[settlement_system_master_guide|◀️ 돌아가기]]

## 마법을 맹신하지 마세요.

JPA는 SQL을 자동으로 짜주는 편리한 도구지만, 그만큼 **"숨겨진 비용"** 이 큽니다.
편리함 뒤에 숨어있는 성능 지뢰들을 제거하는 방법을 알아봅시다.

---

## Chapter 1. N+1 문제의 또 다른 해법: `@EntityGraph`

앞서 `JOIN FETCH`를 배웠지만, 매번 JPQL을 길게 짜는 것은 귀찮습니다.
Spring Data JPA는 어노테이션만으로 `LEFT OUTER JOIN`을 걸어주는 기능을 제공합니다.

### 1-1. `@EntityGraph` 적용

```java
public interface ContractRepository extends JpaRepository<Contract, Long> {

    // [번역] "findAll을 할 건데, tenant 필드는 즉시(EAGER) 가져와라."
    // 결과적으로 'SELECT c.*, t.* FROM contract c LEFT JOIN tenant t ...' 쿼리가 나갑니다.
    @EntityGraph(attributePaths = {"tenant"})
    List<Contract> findAll();
}
```

> [!NOTE] **언제 쓰나요?**
>
> - 복잡한 `WHERE` 조건 없이 단순히 "연관 객체도 같이 로딩"하고 싶을 때.
> - `FetchType.LAZY`로 되어있지만 이번 한 번만 급하게 같이 불러와야 할 때.

---

## Chapter 2. 대량 수정의 함정: Dirty Checking vs Bulk Update

JPA의 기본 수정 방식은 **"1. 조회 -> 2. 수정 -> 3. (트랜잭션 종료 시) 자동 반영"** 입니다. 이를 **변경 감지(Dirty Checking)** 라고 합니다.
하지만 1만 건을 수정해야 한다면? 1만 개를 다 `SELECT`해서 메모리에 올려야 할까요? 미친 짓입니다.

### 2-1. 벌크성 수정 쿼리 (`@Modifying`)

JPA를 건너뛰고 DB에 바로 `UPDATE` SQL을 날리는 것이 정답입니다.

```java
@Modifying(clearAutomatically = true) // <--- 여기가 핵심!
@Query("UPDATE Contract c SET c.status = :status WHERE c.expiredAt < :now")
int updateStatusToExpired(ContractStatus status, LocalDateTime now);
```

> [!CAUTION] **주의: `clearAutomatically = true`가 왜 필수인가요?**
> 벌크 연산은 **영속성 컨텍스트(캐시)를 무시하고 DB를 직접 타격**합니다.
>
> 1. 캐시에는 `ID=1`인 데이터가 `status=ACTIVE`로 남아있습니다.
> 2. 벌크 연산이 DB의 `ID=1`을 `status=EXPIRED`로 바꿨습니다.
> 3. 이후 로직에서 `findById(1)`을 하면? DB를 안 보고 **캐시에 있는 옛날 값(`ACTIVE`)** 을 가져옵니다. **(망함)**
>
> 따라서 `clearAutomatically = true`를 줘서 "벌크 연산 끝났으면 캐시 싹 비워!"라고 명령해야, 다음 조회가 DB에서 최신 값을 가져오게 됩니다.

---

## Chapter 3. 페이징 성능: Offset의 한계

게시판 페이징은 보통 `page=1000` 방식을 씁니다. 이를 Offset Paging이라고 합니다.
쿼리는 `LIMIT 100000, 10` (10만 번째부터 10개) 형태가 됩니다.

### 3-1. 왜 느린가요? (Offset Paging)

DB는 10만 번째 데이터를 찾기 위해 **앞의 10만 개를 다 읽고나서 버립니다.**
데이터가 1억 건이면? 뒷 페이지로 갈수록 기하급수적으로 느려집니다.

### 3-2. No-Offset (Cursor Pagination)

"몇 번째 페이지"가 아니라 **"마지막으로 본 ID보다 큰 것"** 을 조회하는 방식입니다.

```java
// WHERE id > 100000 LIMIT 10
// 인덱스(PK)를 바로 타고 점프합니다.
List<Contract> findTop10ByIdGreaterThanOrderByIdAsc(Long lastId);
```

> [!TIP] **장점**
> 1번째 페이지나 100만 번째 페이지나 속도가 똑같이 빠릅니다(인덱스 탐색 시간 O(1) ~ O(logN)).
> 무한 스크롤(Infinite Scroll) UI를 구현할 때 필수적인 최적화 기법입니다.

---

## Chapter 4. OSIV(Open Session In View): 끄는 게 정석

Spring Boot는 기본적으로 `open-in-view: true`로 설정되어 있습니다.
이것은 **"API 응답이 완전히 나갈 때까지(View 렌더링 끝날 때까지) DB 연결을 붙잡고 있겠다"** 는 뜻입니다.

### 4-1. 왜 꺼야 하나요?

- **True (기본값)**: Controller나 View에서도 `entity.getTenant().getName()` 같은 Lazy Loading이 작동합니다. 편하지만, 요청 처리 시간 내내 DB 커넥션을 점유합니다.
- **가장 큰 문제**: 외부 API 호출 등으로 응답이 5초 걸리면, DB 커넥션도 5초간 말라버립니다. 트래픽이 몰리면 순식간에 **Connection Pool 고갈**로 서버가 죽습니다.

### 4-2. 해결책

`application.yml`에서 끕니다.

```yaml
spring:
  jpa:
    open-in-view: false
```

이렇게 하면 Service 계층(트랜잭션)을 벗어나는 순간 영속성 컨텍스트가 닫히고 DB 반납이 일어납니다.

> [!NOTE] **Trade-off**
> 이제 Controller에서는 Lazy Loading을 못 씁니다(`LazyInitializationException` 발생).
> 따라서 Service 안에서 필요한 데이터를 모두 로딩해서 DTO로 변환해 내보내는 습관을 들여야 합니다. 이것이 더 깔끔한 설계이기도 합니다.

---

## 마무리

JPA 고수가 되려면 **"DB와 영속성 컨텍스트 사이의 줄타기"** 를 잘해야 합니다.

1. 단순히 조회만 할 땐 `@EntityGraph`로 `JOIN`을 챙기세요.
2. 대량 수정은 `@Modifying`으로 DB에 직접 쏘세요 (단, 영속성 컨텍스트 초기화 필수).
3. 무한 스크롤이나 대용량 리스트는 No-Offset 방식을 고려하세요.
4. 트래픽이 많다면 OSIV를 꺼서 DB 커넥션을 아끼세요.
