package com.homeexpense.service;

import static com.homeexpense.service.EarningsFixture.JANUARY;
import static com.homeexpense.service.EarningsFixture.d;
import static com.homeexpense.service.EarningsFixture.timed;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Not @Transactional on purpose: each service call commits, as in the running application.
 * A failure injected after the new lines are written must leave the previous draft intact.
 */
class EarningsAtomicityIntegrationTest extends SqliteIntegrationTest {

    @MockitoBean
    private AuditService auditService;

    @Autowired
    private WorkerService workerService;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private PayService payService;
    @Autowired
    private DailyWorkService dailyWorkService;
    @Autowired
    private EarningsService earningsService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void failedRecalculationLeavesThePreviousDraftUnchanged() {
        EarningsFixture fx = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService, earningsService);
        Task task = fx.task("Atomicity test mopping");
        Worker worker = fx.worker("Atomicity test worker", d("2026-01-01"), PaymentModel.PER_TASK);
        WorkSchedule schedule = scheduleService.addScheduleVersion(worker.getId(), d("2026-01-01"),
                List.of(timed(MONDAY, task, "10:00", "11:00")), null);
        fx.rate(worker, RateType.PER_TASK, "50", d("2026-01-01"));
        fx.scheduled(worker, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, task), WorkDetails.completed());
        long runId = earningsService.calculate(worker.getId(), JANUARY).run().getId();

        fx.scheduled(worker, "2026-01-12", EarningsFixture.entryOn(schedule, MONDAY, task), WorkDetails.completed());
        doThrow(new IllegalStateException("Simulated failure after writing lines"))
                .when(auditService).record(anyLong(), eq(EarningsService.ENTITY_RUN), any(), eq("CALCULATE"), anyString());

        assertThatThrownBy(() -> earningsService.calculate(worker.getId(), JANUARY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Simulated failure");

        assertThat(jdbc.queryForObject("SELECT total_paise FROM earning_run WHERE id = ?", Long.class, runId)).isEqualTo(5000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_line WHERE run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_record_treatment WHERE run_id = ?", Integer.class, runId)).isEqualTo(1);
    }
}
