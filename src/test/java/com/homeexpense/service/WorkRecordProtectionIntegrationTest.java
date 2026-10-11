package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Schedule changes and deactivation may not contradict work that has already been recorded. */
@Transactional
class WorkRecordProtectionIntegrationTest extends SqliteIntegrationTest {

    private static final LocalDate MON = d("2026-01-05");
    private static final LocalDate WED = d("2026-01-07");

    @Autowired
    private DailyWorkService dailyWorkService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    private Worker sunita;
    private Task sweeping;
    private ScheduleEntry monSweeping;

    @BeforeEach
    void setUp() {
        Fixtures fixtures = new Fixtures(workerService, catalogService);
        sunita = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        monSweeping = scheduleService.addScheduleVersion(sunita.getId(), JAN_1,
                List.of(NewScheduleEntry.task(MONDAY, sweeping.getId())), null).getEntries().getFirst();
    }

    @Test
    void scheduleCannotChangeUnderRecordedWork() {
        dailyWorkService.recordScheduled(sunita.getId(), MON, monSweeping.getId(), WorkDetails.completed());

        assertThatThrownBy(() -> scheduleService.addScheduleVersion(sunita.getId(), d("2026-01-02"), List.of(), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Work is already recorded for Sunita between 2026-01-05 and 2026-01-05")
                .hasMessageContaining("Start the new schedule after 2026-01-05");
        assertThat(scheduleService.history(sunita.getId())).singleElement()
                .satisfies(v -> assertThat(v.getEffectiveTo()).isNull());

        scheduleService.addScheduleVersion(sunita.getId(), d("2026-01-06"), List.of(), null);
        assertThat(scheduleService.history(sunita.getId())).hasSize(2);
    }

    @Test
    void unscheduledWorkAlsoProtectsTheScheduleThatClassifiedIt() {
        dailyWorkService.recordUnscheduled(sunita.getId(), WED, sweeping.getId(), null, WorkDetails.completed(), false);

        assertThatThrownBy(() -> scheduleService.addScheduleVersion(sunita.getId(), d("2026-01-07"), List.of(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Work is already recorded");
    }

    @Test
    void voidedRecordsDoNotBlockScheduleChanges() {
        WorkRecord mistaken = dailyWorkService.recordScheduled(sunita.getId(), MON, monSweeping.getId(), WorkDetails.completed()).record();
        dailyWorkService.voidRecord(mistaken.getId(), "Wrong worker");

        scheduleService.addScheduleVersion(sunita.getId(), d("2026-01-02"), List.of(), null);
        assertThat(scheduleService.history(sunita.getId())).hasSize(2);
    }

    @Test
    void workerCannotBeDeactivatedBeforeRecordedWork() {
        dailyWorkService.recordScheduled(sunita.getId(), MON, monSweeping.getId(), WorkDetails.completed());

        assertThatThrownBy(() -> workerService.deactivate(sunita.getId(), d("2026-01-04"), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Work is recorded for Sunita on 2026-01-05, after 2026-01-04");
        assertThat(workerService.isEmployedOn(sunita.getId(), d("2026-01-05"))).isTrue();

        workerService.deactivate(sunita.getId(), MON, null);
        assertThat(workerService.isEmployedOn(sunita.getId(), d("2026-01-06"))).isFalse();
    }

    @Test
    void phaseTwoBehaviourIsUnchangedWithoutRecordedWork() {
        scheduleService.addScheduleVersion(sunita.getId(), d("2026-01-02"), List.of(), null);
        workerService.deactivate(sunita.getId(), d("2026-01-03"), null);

        assertThat(scheduleService.history(sunita.getId())).hasSize(2);
        assertThat(workerService.isEmployedOn(sunita.getId(), d("2026-01-04"))).isFalse();
    }
}
