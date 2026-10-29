package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulates earnings already worked out for 2026-01-12 (earnings arrive in Phase 4) to prove
 * that recording, correcting, voiding and deduction decisions all respect the lock.
 */
@Transactional
class WorkRecordEarningsLockIntegrationTest extends SqliteIntegrationTest {

    private static final LocalDate OPEN_MONDAY = d("2026-01-05");
    private static final LocalDate LOCKED_MONDAY = d("2026-01-12");

    @MockitoBean
    private EarningsGuard earningsGuard;

    @Autowired
    private DailyWorkService dailyWorkService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    private Worker sunita;
    private ScheduleEntry monSweeping;

    @BeforeEach
    void setUp() {
        Fixtures fixtures = new Fixtures(workerService, catalogService);
        sunita = fixtures.maid("Sunita");
        Task sweeping = fixtures.task("Sweeping");
        monSweeping = scheduleService.addScheduleVersion(sunita.getId(), JAN_1,
                List.of(NewScheduleEntry.task(MONDAY, sweeping.getId())), null).getEntries().getFirst();
    }

    private void lockMonday() {
        when(earningsGuard.hasEarningsOn(anyLong(), any())).thenAnswer(call -> LOCKED_MONDAY.equals(call.getArgument(1)));
    }

    @Test
    void newWorkCannotBeRecordedOnALockedDay() {
        lockMonday();

        assertThatThrownBy(() -> dailyWorkService.recordScheduled(sunita.getId(), LOCKED_MONDAY, monSweeping.getId(),
                WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Earnings have already been worked out");
        assertThatThrownBy(() -> dailyWorkService.markWholeDay(sunita.getId(), LOCKED_MONDAY, AbsenceReason.HOUSEHOLD_HOLIDAY, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Earnings have already been worked out");

        assertThat(dailyWorkService.recordScheduled(sunita.getId(), OPEN_MONDAY, monSweeping.getId(), WorkDetails.completed())
                .saved()).isTrue();
    }

    @Test
    void correctionVoidAndDecisionCannotBypassTheLock() {
        WorkRecord done = dailyWorkService.recordScheduled(sunita.getId(), LOCKED_MONDAY, monSweeping.getId(),
                WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE)).record();
        lockMonday();

        assertThatThrownBy(() -> dailyWorkService.correct(done.getId(), WorkDetails.completed(), "Actually came", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be changed here");
        assertThatThrownBy(() -> dailyWorkService.voidRecord(done.getId(), "Mistake"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be changed here");
        assertThatThrownBy(() -> dailyWorkService.decideDeduction(done.getId(), DeductionDecision.APPROVED, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be changed here");

        assertThat(done.isCurrent()).isTrue();
        assertThat(done.getDeductionDecision()).isEqualTo(DeductionDecision.PENDING);
        assertThat(dailyWorkService.history(sunita.getId(), LOCKED_MONDAY, LOCKED_MONDAY)).hasSize(1);
    }
}
