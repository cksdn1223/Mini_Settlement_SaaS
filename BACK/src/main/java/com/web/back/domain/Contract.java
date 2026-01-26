package com.web.back.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
//@Table(name = "contract", indexes = {
//        @Index(name = "idx_company_id", columnList = "companyId") // 성능: 회사별 조회 속도 향상
//})
public class Contract {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 멀티테넌시: 이 데이터가 '어떤 임대관리 회사의 것인가?'
    @Column(nullable = false)
    private Long companyId;

    // 임차인 정보 (원래는 Tenant 객체가 따로 있어야 하지만, 지금은 ID로 대체)
    @Column(nullable = false)
    private Long tenantId;

    // 정산 및 회계: 돈은 절대 double로 쓰지 않는다. (소수점 4자리까지 허용)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyRent; // 월세

    @Column(nullable = false)
    private LocalDate startDate; // 계약 시작일

    @Column(nullable = false)
    private LocalDate endDate;   // 계약 종료일

    // 계약 라이프사이클 관리: 상태를 Enum으로 관리
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    // 생성자 (빌더 패턴 대신 정적 팩토리 메서드 권장 - DDD 스타일)
    public Contract(Long companyId, Long tenantId, BigDecimal monthlyRent, LocalDate startDate, LocalDate endDate) {
        this.companyId = companyId;
        this.tenantId = tenantId;
        this.monthlyRent = monthlyRent;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = ContractStatus.ACTIVE; // 생성 시 기본은 '활성' 상태
    }
}