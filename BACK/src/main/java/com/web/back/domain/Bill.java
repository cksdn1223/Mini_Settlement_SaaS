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
// 정산 데이터는 조회가 많으므로 인덱스 필수
@Table(name = "bill", indexes = {
        @Index(name = "idx_bill_company_date", columnList = "companyId, billingDate")
})
public class Bill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long companyId; // 멀티테넌시 구분용

    @ManyToOne(fetch = FetchType.LAZY) // 중요: 필요할 때만 조회 (성능 최적화)
    @JoinColumn(name = "tenant_id")    // DB 테이블의 컬럼 이름 지정
    private Tenant tenant;

    @Column(nullable = false)
    private Long contractId; // 어떤 계약에 대한 청구인지 추적

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount; // 청구 금액

    @Column(nullable = false)
    private LocalDate billingDate; // 청구일 (예: 2026-02-01)

    @Column(nullable = false)
    private String status; // UNPAID(미납), PAID(완납)

    // 생성자 (필수 값 강제)
    public Bill(Long companyId, Tenant tenant, Long contractId, BigDecimal amount, LocalDate billingDate) {
        this.companyId = companyId;
        this.tenant = tenant;
        this.contractId = contractId;
        this.amount = amount;
        this.billingDate = billingDate;
        this.status = "UNPAID"; // 생성 시 기본은 미납
    }

    public Long getTenantId() {
        return this.tenant.getId();
    }
}