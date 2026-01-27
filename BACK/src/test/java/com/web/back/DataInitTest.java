package com.web.back;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Random;

@SpringBootTest
public class DataInitTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("테넌트 데이터 10만 개 넣기")
    void initTenant() {
        // Contract에 tenant_id 1~100,000이 들어있으니, Tenant도 똑같이 10만 개 만들어야 매칭됨
        final int TOTAL_COUNT = 100_000;
        String sql = "INSERT INTO tenant (name, email) VALUES (?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, "Tenant_" + (i + 1)); // 예: Tenant_1
                ps.setString(2, "tenant" + (i + 1) + "@test.com");
            }
            @Override
            public int getBatchSize() { return TOTAL_COUNT; }
        });
    }

    @Test
    @DisplayName("계약 데이터 10만 개  bulk insert")
    void initData() {
        // 1. 데이터 개수 설정 (10만 건)
        final int TOTAL_COUNT = 100_000;

        String sql = "INSERT INTO contract " +
                "(company_id, tenant_id, monthly_rent, start_date, end_date, status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        // 랜덤 데이터 생성을 위한 도구
        Random random = new Random();

        long startTime = System.currentTimeMillis();
        System.out.println("🚀 데이터 생성을 시작합니다...");

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                // i는 0부터 99,999까지 돕니다.

                // 1. Company ID: 1~10번 회사 (멀티테넌시 시뮬레이션)
                long companyId = random.nextInt(10) + 1;

                // 2. Tenant ID: 1~100,000번 (각자 고유함)
                long tenantId = i + 1;

                // 3. 월세: 10만원 ~ 200만원 사이 (만원 단위)
                int rentAmount = (random.nextInt(190) + 10) * 10000;

                // 4. 날짜: 시작일은 최근 1년 내 랜덤, 종료일은 1년 뒤
                LocalDate startDate = LocalDate.now().minusDays(random.nextInt(365));
                LocalDate endDate = startDate.plusYears(1);

                // 5. 상태: 90%는 ACTIVE(계약중), 10%는 TERMINATED(해지)로 설정
                String status = (random.nextInt(10) < 9) ? "ACTIVE" : "TERMINATED";

                // PreparedStatement에 값 세팅
                ps.setLong(1, companyId);
                ps.setLong(2, tenantId);
                ps.setBigDecimal(3, new BigDecimal(rentAmount));
                ps.setDate(4, Date.valueOf(startDate));
                ps.setDate(5, Date.valueOf(endDate));
                ps.setString(6, status);
            }

            @Override
            public int getBatchSize() {
                return TOTAL_COUNT;
            }
        });

        long endTime = System.currentTimeMillis();
        System.out.println("✅ " + TOTAL_COUNT + "건 저장 완료!");
        System.out.println("⏱️ 소요 시간: " + (endTime - startTime) + "ms");
    }
}