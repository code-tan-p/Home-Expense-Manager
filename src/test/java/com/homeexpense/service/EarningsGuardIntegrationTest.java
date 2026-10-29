package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulates earnings recorded up to 2026-03-31 (earnings arrive in Phase 4) to prove that
 * history-changing operations refuse to alter dates that earnings depend on.
 */
@Transactional
class EarningsGuardIntegrationTest extends SqliteIntegrationTest {

    private static final LocalDate LAST_EARNING = d("2026-03-31");

    @MockitoBean
    private EarningsGuard earningsGuard;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private PayService payService;

    private Worker worker;
    private Task sweeping;
    private PayRate salary;

    @BeforeEach
    void setUp() {
        Fixtures fixtures = new Fixtures(workerService, catalogService);
        worker = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        scheduleService.addScheduleVersion(worker.getId(), JAN_1, List.of(NewScheduleEntry.task(MONDAY, sweeping.getId())), null);
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        salary = payService.addRate(NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "6000", JAN_1));

        when(earningsGuard.hasEarningsOnOrAfter(anyLong(), any()))
                .thenAnswer(call -> !call.<LocalDate>getArgument(1).isAfter(LAST_EARNING));
        when(earningsGuard.isRateUsedByEarnings(eq(salary.getId()))).thenReturn(true);
    }

    @Test
    void scheduleCannotChangeFromADateThatHasEarnings() {
        assertThatThrownBy(() -> scheduleService.addScheduleVersion(worker.getId(), d("2026-03-01"), List.of(), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("earnings recorded on or after 2026-03-01");
        assertThat(scheduleService.history(worker.getId())).singleElement()
                .satisfies(version -> assertThat(version.getEffectiveTo()).isNull());

        scheduleService.addScheduleVersion(worker.getId(), d("2026-04-01"), List.of(), null);
        assertThat(scheduleService.history(worker.getId())).hasSize(2);
    }

    @Test
    void rateAndArrangementCannotChangeFromADateThatHasEarnings() {
        assertThatThrownBy(() -> payService.addRate(
                NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "6500", d("2026-02-01"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2026-02-01");
        assertThatThrownBy(() -> payService.setPrimaryModel(worker.getId(), PaymentModel.PER_DAY, d("2026-03-15")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2026-03-15");

        payService.addRate(NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "6500", d("2026-04-01")));
        assertThat(payService.rateHistory(worker.getId())).extracting(PayRate::getAmountPaise)
                .containsExactly(600000L, 650000L);
    }

    @Test
    void rateUsedByEarningsCannotBeCorrectedInPlace() {
        assertThatThrownBy(() -> payService.correctRateAmount(salary.getId(), "6500", "Agreed amount was higher"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be corrected in place");
        assertThat(payService.rateHistory(worker.getId())).singleElement()
                .satisfies(rate -> assertThat(rate.getAmountPaise()).isEqualTo(600000));
    }

    @Test
    void workerCannotBeDeactivatedBeforeRecordedEarnings() {
        assertThatThrownBy(() -> workerService.deactivate(worker.getId(), d("2026-02-28"), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("earnings recorded after 2026-02-28");

        workerService.deactivate(worker.getId(), d("2026-03-31"), null);
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-04-01"))).isFalse();
    }
}
