package com.web.back.batch;

import com.web.back.domain.Bill;
import com.web.back.domain.Contract;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Collections;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class BillGenerateJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;

    // Job 생성 (배치 작업의 단위)
    @Bean
    public Job billJob(Step billStep) {
        return new JobBuilder("billJob", jobRepository)
                .start(billStep) // 첫 번째 단계 실행
                .build();
    }

    // Step 생성 (실제 작업의 단계: 읽기 -> 가공 -> 쓰기)
    @Bean
    public Step billStep(JpaPagingItemReader<Contract> contractReader) {
        return new StepBuilder("billStep", jobRepository)
                // 1000개씩 끊어서 처리 (메모리 보호)
                .<Contract, Bill>chunk(1000, transactionManager)
                .reader(contractReader)
                .processor(billProcessor())
                .writer(billWriter())
                .build();
    }

    // [Reader] 외부에서 companyId를 받아와서 그 회사 데이터만 조회
    @Bean
    @StepScope // 이게 있어야 JobParameters를 받아올 수 있음
    // 배치가 실행되는 그 순간에 Bean을 생성하게 만듭니다. 실행되는 순간에만 파라미터를 알 수 있기 때문에 필수적입니다.
    public JpaPagingItemReader<Contract> contractReader(
            @Value("#{jobParameters['companyId']}") Long companyId
    ) {
        // 파라미터 검증
        if (companyId == null) {
            log.warn("companyId가 없습니다. 테스트를 위해 기본값 1L로 설정합니다.");
            companyId = 1L;
        }
        return new JpaPagingItemReaderBuilder<Contract>()
                .name("contractReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT c FROM Contract c JOIN FETCH c.tenant WHERE c.status = 'ACTIVE' AND c.companyId = :companyId")
                .parameterValues(Collections.singletonMap("companyId", companyId)) // 파라미터 맵핑
                .pageSize(1000) // 한 번에 조회할 페이지 크기
                .build();
    }

    // [Processor] Contract -> Bill 변환 (비즈니스 로직)
    @Bean
    public ItemProcessor<Contract, Bill> billProcessor() {
        return contract -> {
            // "이번 달" 청구서 발행
            LocalDate billingDate = LocalDate.now();

            // 여기서 복잡한 계산 로직이 들어갈 수 있음 (연체료, 할인 등)
            return new Bill(
                    contract.getCompanyId(),
                    contract.getTenant(),
                    contract.getId(),
                    contract.getMonthlyRent(),
                    billingDate
            );
        };
    }

    // [Writer] Bill 데이터를 DB에 저장 (JDBC Batch 사용으로 성능 최적화)
    @Bean
    public JdbcBatchItemWriter<Bill> billWriter() {
        return new JdbcBatchItemWriterBuilder<Bill>()
                .dataSource(dataSource)
                .sql("INSERT INTO bill (company_id, tenant_id, contract_id, amount, billing_date, status) " +
                        "VALUES (:companyId, :tenantId, :contractId, :amount, :billingDate, :status)")
                .beanMapped() // Bill 객체의 필드명과 SQL 파라미터를 자동 매핑
                .build();
    }
}