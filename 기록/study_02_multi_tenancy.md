# [Architecture] 멀티테넌시(Multi-tenancy) 설계 심화

#Settlement #Architecture #Security #MultiTenancy
[[settlement_system_master_guide|◀️ 돌아가기]]

## "내 데이터는 내가 지킨다": SaaS의 숙명

SaaS(Software as a Service)의 핵심은 **"하나의 애플리케이션으로 여러 회사(Tenant)를 지원하는 것"** 입니다.
이때 가장 치명적인 사고는 **"A사 데이터가 B사 관리자에게 보이는 것"** 입니다.
개발자가 `WHERE company_id = ?`를 깜빡하는 순간, 회사는 망합니다. 시스템 차원에서 이를 원천 봉쇄해야 합니다.

---

## Chapter 1. DB 분리 전략: 어떻게 나눌 것인가?

### 1-1. DB별 분리 (Database per Tenant)

- **방식**: A사 DB, B사 DB를 물리적으로 따로 만듭니다.
- **장점**: 보안성 최고. A사 DB가 털려도 B사는 안전합니다.
- **단점**: 회사가 1000개면 DB도 1000개입니다. 마이그레이션이나 배포가 지옥이 됩니다. 비용도 매우 비쌉니다.

### 1-2. 스키마 분리 (Schema per Tenant)

- **방식**: 하나의 DB 안에서 `schema_a`, `schema_b`로 나눕니다.
- **장점**: DB 분리보다 관리 비용이 적습니다.
- **단점**: 여전히 백업/복구 및 커넥션 풀 관리가 복잡합니다.

### 1-3. 컬럼 분리 (Shared Database, Shared Schema)

- **방식**: 모든 테이블에 `company_id` (Tenant ID) 컬럼을 추가합니다.
- **장점**: 가장 경제적이고 관리가 쉽습니다. 스타트업/중소 규모 SaaS의 99%가 이 방식을 씁니다.
- **단점**: 개발자가 쿼리 짤 때 `company_id` 조건을 빼먹으면 데이터 유출 사고가 납니다. **-> 그래서 우리는 이 부분을 기술로 자동화합니다.**

---

## Chapter 2. 하이버네이트 필터(Hibernate Filter)와 AOP

개발자의 실수로 `WHERE` 절이 빠지는 것을 막기 위해, JPA(Hibernate) 레벨에서 강제로 조건을 붙입시다.

### 2-1. `@FilterDef`와 `@Filter` 정의

DB 테이블마다 "이 테이블은 `companyId`로 걸러내야 해"라고 딱지를 붙이는 작업입니다.

```java
@Entity
// 1. 필터 정의: "tenantFilter"라는 이름의 거름망을 만들자. 이 망은 'companyId'라는 알갱이를 쓴다.
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "companyId", type = Long.class))
// 2. 필터 적용: 이 엔티티를 조회할 땐 자동으로 "AND company_id = :companyId" 조건을 붙여라.
@Filter(name = "tenantFilter", condition = "company_id = :companyId")
public class Contract {
    @Id private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;
}
```

하지만 이렇게 정의만 해두면 작동하지 않습니다. **"이 요청은 A사의 요청이다!"** 라고 하이버네이트에게 알려주는 누군가가 필요합니다. 그게 바로 **AOP**입니다.

### 2-2. AOP(Aspect Oriented Programming)로 필터 자동 켜기

> [!INFO] **AOP란 무엇인가요?**
> AOP는 **"핵심 로직 사이사이에 공통적으로 해야 할 일(보안, 로그, 트랜잭션 등)을 끼워넣는 기술"** 입니다.
> 마치 "은행 금고(DB)"에 들어가기 전에 항상 "신분증 검사(Filter 설정)"를 하게 만드는 경비원과 같습니다.

**[작동 메커니즘 상세 설명]**

1. 사용자가 API를 호출합니다 (로그인한 상태).
2. `Service` 메서드가 실행되기 **직전(Before)** 에 AOP가 가로챕니다.
3. AOP는 현재 로그인한 사람의 `Company ID`를 꺼냅니다.
4. 그리고 하이버네이트 세션(데이터베이스 연결 통로)을 열어서 **"지금부터 하는 모든 조회는 이 ID로 필터링해!"** 라고 명령(`enableFilter`)합니다.
5. 그 후 원래 하려던 `Service` 로직을 실행합니다.

```java
@Aspect // "나는 감시자(Aspect)입니다"
@Component
@RequiredArgsConstructor
public class TenantAspect {

    private final EntityManager em;

    // Pointcut: 감시할 지점 설정
    // "com.settlement.service 패키지 아래의 모든 메서드(*.*)가 실행될 때" 끼어들겠다.
    @Before("execution(* com.settlement.service..*(..))")
    public void enableTenantFilter() {
        // 1. 현재 요청을 보낸 사람이 누구인지(어느 회사인지) 확인
        // SecurityContextHolder는 ThreadLocal을 사용하여 "현재 스레드"만의 정보를 저장합니다.
        Long currentCompanyId = SecurityContextHolder.getCompanyId();

        // 2. 하이버네이트 세션을 구함 (JPA EntityManager의 본체)
        Session session = em.unwrap(Session.class);

        // 3. 필터 스위치 ON
        // "tenantFilter를 켜고, 파라미터 companyId에 현재 회사 ID를 넣어라"
        session.enableFilter("tenantFilter")
               .setParameter("companyId", currentCompanyId);

        // 4. 이제 메서드 본문이 실행되면, 자동으로 "WHERE company_id = ..."가 붙음
    }
}
```

> [!NOTE] **심화: 왜 SecurityContextHolder 인가요?**
> 웹 요청은 하나의 스레드(Thread)가 처리합니다. AOP 안에서 `companyId`를 알 수 있는 방법은, 요청이 들어올 때 필터(Servlet Filter)에서 스레드 전용 저장소(`ThreadLocal`)에 ID를 넣어두었기 때문입니다.

---

## Chapter 3. Hibernate 6.x의 `@TenantId` (최신 트렌드)

Spring Boot 3.x (Hibernate 6)부터는 저렇게 복잡하게 AOP를 짤 필요가 없어졌습니다. 하이버네이트가 멀티테넌시를 **공식 기능**으로 지원하기 시작했거든요.

### 3-1. 어노테이션 하나로 끝 (`@TenantId`)

```java
@Entity
public class Contract {

    @Id @GeneratedValue
    private Long id;

    @TenantId // "이 필드가 바로 테넌트 구분자입니다!" 라고 선언
    @Column(name = "company_id")
    private Long companyId;
}
```

이제 개발자가 `repository.save(contract)`를 할 때 `companyId`를 세팅하지 않아도 됩니다. 하이버네이트가 알아서 채워줍니다.
조회할 때도 자동으로 `WHERE company_id = ?`를 붙여줍니다.

### 3-2. `CurrentTenantIdentifierResolver` 구현

하지만 하이버네이트도 마법사는 아닙니다. "지금 접속한 놈이 ID 몇 번이야?" 는 우리가 알려줘야 합니다.
이를 위해 **"현재 테넌트 ID를 알려주는 도우미 클래스"** 를 하나 만들어야 합니다.

```java
@Component
// "하이버네이트야, ID 필요할 때 여기 물어봐"
public class TenantIdResolver implements CurrentTenantIdentifierResolver {

    // 1. 현재 테넌트 ID를 반환하는 메서드
    @Override
    public String resolveCurrentTenantIdentifier() {
        // 아까처럼 SecurityContext(ThreadLocal)에서 꺼내서 줍니다.
        String tenantId = SecurityContextHolder.getCompanyId().toString();

        // 만약 ID가 없으면(비로그인 등), 기본값이나 예외를 던짐
        return (tenantId != null) ? tenantId : "DEFAULT_TENANT";
    }

    // 2. 세션이 열려있는 동안 ID가 바뀔 일이 있나? (보통 없으므로 false)
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
```

이걸 Bean으로 등록만 해두면, 하이버네이트가 SQL을 만들 때마다 `resolveCurrentTenantIdentifier()`를 호출해서 ID를 가져다가 `WHERE` 절에 박습니다. AOP보다 훨씬 깔끔하고 표준화된 방식입니다.

---

## 마무리

멀티테넌시 아키텍처는 **"보안"과 "효율"의 줄다리기** 입니다.

1. **컬럼 분리**: 가장 현실적인 가성비 전략입니다.
2. **AOP + Filter**: (구식) 직접 경비원을 세워서 검사하는 방식입니다. 흐름을 이해하는 데 좋습니다.
3. **@TenantId**: (신식) 하이버네이트 표준 기능을 사용하여, 실수 자체를 원천 차단하는 가장 우아한 방법입니다.

어느 방식을 쓰든 목표는 하나입니다. **"개발자가 실수로 `WHERE`를 빼먹어도, 다른 회사의 데이터는 절대 보이지 않게 하라."**
