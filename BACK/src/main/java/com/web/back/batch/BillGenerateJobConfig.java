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

    private final JobRepository jobRepository; // 배치의 실행 기록을 DB에 저장하고 조회함
    private final PlatformTransactionManager transactionManager; // 트랜잭션을 관리함
    private final EntityManagerFactory entityManagerFactory; // JPA 를 사용하기위해 필요함
    private final DataSource dataSource; // JPA가 아닌 순수한 DB 연결 통로

    // Job 생성 (배치 작업의 단위)
    @Bean
    public Job billJob(Step billStep) {
        return new JobBuilder("billJob", jobRepository) // billJob 고유 이름, jobRepository로 기록 저장
                .start(billStep) // 아래에서 만든 첫 번째 단계 실행
                .build(); // Job 객체 생성완료
    }

    // Step 생성 (실제 작업의 단계: 읽기 -> 가공 -> 쓰기)
    @Bean
    public Step billStep(JpaPagingItemReader<Contract> contractReader) {
        return new StepBuilder("billStep", jobRepository) // 스탭의 고유 이름은 billStep 이고 jobRepository로 기록저장
                // 1000개씩 끊어서 처리 (메모리 보호), transactionManager로 트랜잭션 관리
                // Reader로 Contract입력 Writer에게 Bill출력
                .<Contract, Bill>chunk(1000, transactionManager)
                .reader(contractReader) // 밑에서 만든 Reader로 데이터 가져옴
                .processor(billProcessor()) // Processor로 비즈니스 로직 처리
                .writer(billWriter()) // 완료된 데이터를 Writer로 저장
                .build(); // Step 객체 생성 완료
    }

    // [Reader] 외부에서 companyId를 받아와서 그 회사 데이터만 조회
    @Bean
    @StepScope // 지연 생성: 서버 켤 때 미리 만들지 말고, 이 단계가 실행될 때 만들어줌 (그래야 파라미터를 받을 수 있음)
    public JpaPagingItemReader<Contract> contractReader(
            @Value("#{jobParameters['companyId']}") Long companyId // 외부 주입: Job 실행 시 넘겨준 'companyId' 값을 쏙 뽑아서 가져옴
    ) {
        // 파라미터 검증
        if (companyId == null) {
            log.warn("companyId가 없습니다. 테스트를 위해 기본값 1L로 설정합니다.");
            companyId = 1L;
        }
        return new JpaPagingItemReaderBuilder<Contract>()
                .name("contractReader") // Spring Batch가 내부적으로 관리할 Reader의 이름표
                .entityManagerFactory(entityManagerFactory) // JPA를 써야 하니까 JPA 사용하기위한 의존성을 연결해줌
                // 성능 최적화 쿼리 (JPQL)
                // 1. JOIN FETCH: Contract 가져올 때 Tenant도 같이 가져옴 (N+1 문제 해결)
                // 2. WHERE: 'ACTIVE' 상태이면서, 입력받은 'companyId'인 것만 골라냄 (멀티테넌시 격리)
                .queryString("SELECT c FROM Contract c JOIN FETCH c.tenant WHERE c.status = 'ACTIVE' AND c.companyId = :companyId")
                // 파라미터 바인딩: 위 쿼리의 구멍(:companyId)에 실제 변수값(companyId)을 채워 넣음
                .parameterValues(Collections.singletonMap("companyId", companyId))
                // 페이지 크기: 한 번 DB에 갈 때마다 1000개씩 들고 옴
                // 중요: Step의 ChunkSize(1000)와 숫자를 똑같이 맞춰야 성능이 가장 좋음!
                .pageSize(1000)
                .build(); // Reader 객체 생성 완료
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
                // 왜 JPA(saveAll)가 아니라 JDBC인가?
                // JPA는 '영속성 컨텍스트'를 관리하느라 대량 입력 시 속도가 느림.
                // JDBC는 그냥 묻지도 따지지도 않고 DB에 꽂아버리기 때문에 대용량 처리에 압도적으로 빠름.
                .dataSource(dataSource)
                // Named Parameter SQL
                // '?' 대신 ':이름' 형식을 사용하여 가독성을 높임.
                // VALUES 뒤에 있는 :companyId, :amount 등이 나중에 실제 값으로 치환됨.
                .sql("INSERT INTO bill (company_id, tenant_id, contract_id, amount, billing_date, status) " +
                        "VALUES (:companyId, :tenantId, :contractId, :amount, :billingDate, :status)")
                // 자동 매핑 (마법사)
                // Bill 객체의 Getter 이름과 SQL의 :파라미터 이름이 같으면 알아서 값을 넣어줌.
                // 예: SQL의 :amount 자리에 bill.getAmount() 값을 자동으로 쏙 집어넣음.
                .beanMapped()
                .build(); // Writer 객체 생성 완료
    }
}