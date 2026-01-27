package com.web.back;

import com.web.back.batch.BillGenerateJobConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest // 배치 테스트 전용 어노테이션
@SpringBootTest(classes = {com.web.back.BackApplication.class, BillGenerateJobConfig.class})
class BillJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Test
    @DisplayName("청구서 발행 배치 실행 테스트")
    void runBillJob() throws Exception {
        // given: Job 파라미터 생성 (companyId = 1번 회사)
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("companyId", 1L)
                .addLong("time", System.currentTimeMillis()) // 중복 실행 방지용 (같은 파라미터로 두 번 실행 안 되는 제약 회피)
                .toJobParameters();

        // when: 파라미터를 넣어서 배치 실행
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }


}