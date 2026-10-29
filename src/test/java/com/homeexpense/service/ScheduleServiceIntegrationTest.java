package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ScheduleServiceIntegrationTest extends SqliteIntegrationTest {

    // 2026-01-05, 2026-02-02 and 2026-03-02 are Mondays.
    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private PayService payService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EntityManager entityManager;

    private Worker worker;
    private Task sweeping;
    private Task mopping;
    private Shift morning;

    @BeforeEach
    void setUp() {
        Fixtures fixtures = new Fixtures(workerService, catalogService);
        worker = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        mopping = fixtures.task("Mopping");
        morning = fixtures.shift("Morning", "07:00", "09:00");
    }

    private WorkSchedule addVersion(String from, NewScheduleEntry... entries) {
        return scheduleService.addScheduleVersion(worker.getId(), d(from), List.of(entries), null);
    }

    private List<String> taskNamesOn(String date) {
        return scheduleService.entriesOn(worker.getId(), d(date)).stream()
                .map(entry -> entry.getTask().getName()).toList();
    }

    @Test
    void firstVersionIsOpenEndedAndListsEntriesForMatchingWeekdays() {
        WorkSchedule schedule = addVersion("2026-01-01",
                NewScheduleEntry.task(MONDAY, sweeping.getId()),
                NewScheduleEntry.task(TUESDAY, mopping.getId()));

        assertThat(schedule.getEffectiveTo()).isNull();
        assertThat(taskNamesOn("2026-01-05")).containsExactly("Sweeping");
        assertThat(taskNamesOn("2026-01-06")).containsExactly("Mopping");
        assertThat(taskNamesOn("2026-01-07")).isEmpty();
    }

    @Test
    void scheduleChangeAddsAVersionAndPreservesTheOldOne() {
        WorkSchedule original = addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));
        Long originalEntryId = original.getEntries().getFirst().getId();

        WorkSchedule changed = addVersion("2026-03-01", NewScheduleEntry.task(MONDAY, mopping.getId()));
        entityManager.flush();
        entityManager.clear();

        List<WorkSchedule> history = scheduleService.history(worker.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getEffectiveTo()).isEqualTo(d("2026-02-28"));
        assertThat(history.get(0).getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getId()).isEqualTo(originalEntryId);
            assertThat(entry.getTask().getName()).isEqualTo("Sweeping");
        });
        assertThat(history.get(1).getId()).isEqualTo(changed.getId());
        assertThat(history.get(1).getEffectiveTo()).isNull();

        assertThat(scheduleService.scheduleOn(worker.getId(), d("2026-02-28")).orElseThrow().getId()).isEqualTo(original.getId());
        assertThat(scheduleService.scheduleOn(worker.getId(), d("2026-03-01")).orElseThrow().getId()).isEqualTo(changed.getId());
        assertThat(taskNamesOn("2026-02-02")).containsExactly("Sweeping");
        assertThat(taskNamesOn("2026-03-02")).containsExactly("Mopping");
    }

    @Test
    void pastEffectiveChangeBetweenVersionsIsAllowedWithoutOverlap() {
        addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));
        WorkSchedule march = addVersion("2026-03-01", NewScheduleEntry.task(MONDAY, mopping.getId()));

        WorkSchedule february = addVersion("2026-02-01",
                NewScheduleEntry.task(MONDAY, sweeping.getId()), NewScheduleEntry.task(MONDAY, mopping.getId()));

        assertThat(february.getEffectiveTo()).isEqualTo(d("2026-02-28"));
        List<WorkSchedule> history = scheduleService.history(worker.getId());
        assertThat(history).extracting(WorkSchedule::getEffectiveFrom)
                .containsExactly(d("2026-01-01"), d("2026-02-01"), d("2026-03-01"));
        assertThat(history.get(0).getEffectiveTo()).isEqualTo(d("2026-01-31"));
        assertThat(history.get(2).getId()).isEqualTo(march.getId());
        assertThat(history.get(2).getEffectiveTo()).isNull();
        assertThat(taskNamesOn("2026-02-02")).containsExactlyInAnyOrder("Sweeping", "Mopping");
    }

    @Test
    void changeOnTheSameStartDateIsRejectedWithAnExplanation() {
        addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));

        assertThatThrownBy(() -> addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, mopping.getId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already starts on 2026-01-01");
        assertThat(taskNamesOn("2026-01-05")).containsExactly("Sweeping");
    }

    @Test
    void severalTasksAndShiftsOnOneDayAreAllowed() {
        addVersion("2026-01-01",
                NewScheduleEntry.task(MONDAY, sweeping.getId()),
                NewScheduleEntry.task(MONDAY, mopping.getId()),
                new NewScheduleEntry(MONDAY, sweeping.getId(), null, LocalTime.of(17, 0), LocalTime.of(18, 0), null, null),
                NewScheduleEntry.shift(MONDAY, morning.getId()));

        assertThat(scheduleService.entriesOn(worker.getId(), d("2026-01-05"))).hasSize(4);
    }

    @Test
    void identicalEntriesTwiceAreRejected() {
        assertThatThrownBy(() -> addVersion("2026-01-01",
                NewScheduleEntry.task(MONDAY, sweeping.getId()),
                NewScheduleEntry.task(MONDAY, sweeping.getId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("listed twice");
        assertThat(scheduleService.history(worker.getId())).isEmpty();
    }

    @Test
    void entriesUseShiftTimesUnlessOverridden() {
        addVersion("2026-01-01",
                NewScheduleEntry.shift(MONDAY, morning.getId()),
                new NewScheduleEntry(TUESDAY, null, morning.getId(), LocalTime.of(6, 30), LocalTime.of(9, 30), null, null),
                new NewScheduleEntry(TUESDAY, mopping.getId(), null, null, null, 45, null));

        ScheduleEntry monday = scheduleService.entriesOn(worker.getId(), d("2026-01-05")).getFirst();
        assertThat(monday.effectiveStartTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(monday.effectiveEndTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.scheduledMinutes()).isEqualTo(120);

        List<ScheduleEntry> tuesday = scheduleService.entriesOn(worker.getId(), d("2026-01-06"));
        ScheduleEntry overridden = tuesday.stream().filter(e -> e.getShift() != null).findFirst().orElseThrow();
        assertThat(overridden.effectiveStartTime()).isEqualTo(LocalTime.of(6, 30));
        assertThat(overridden.scheduledMinutes()).isEqualTo(180);
        ScheduleEntry untimed = tuesday.stream().filter(e -> e.getTask() != null).findFirst().orElseThrow();
        assertThat(untimed.effectiveStartTime()).isNull();
        assertThat(untimed.scheduledMinutes()).isEqualTo(45);
    }

    @Test
    void invalidEntriesAreRejected() {
        Long task = sweeping.getId();
        assertThatThrownBy(() -> addVersion("2026-01-01", new NewScheduleEntry(MONDAY, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("task, a shift, or both");
        assertThatThrownBy(() -> addVersion("2026-01-01", new NewScheduleEntry(null, task, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("day of the week");
        assertThatThrownBy(() -> addVersion("2026-01-01",
                new NewScheduleEntry(MONDAY, task, null, LocalTime.of(7, 0), null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("both a start and an end");
        assertThatThrownBy(() -> addVersion("2026-01-01",
                new NewScheduleEntry(MONDAY, task, null, LocalTime.of(22, 0), LocalTime.of(6, 0), null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Overnight shifts are not supported");
        assertThatThrownBy(() -> addVersion("2026-01-01",
                new NewScheduleEntry(MONDAY, task, null, LocalTime.of(7, 0), LocalTime.of(8, 0), 60, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("duration");
        assertThatThrownBy(() -> addVersion("2026-01-01",
                new NewScheduleEntry(MONDAY, null, morning.getId(), null, null, null, 1500L)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("quantity needs a task");
        assertThatThrownBy(() -> addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, 987654L)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("was not found");
        assertThat(scheduleService.history(worker.getId())).isEmpty();
    }

    @Test
    void quantityBasedEntriesKeepTheirExpectedQuantity() {
        Task milk = catalogService.addTask("Milk delivery");
        addVersion("2026-01-01", new NewScheduleEntry(MONDAY, milk.getId(), null, null, null, null, 1500L));

        assertThat(scheduleService.entriesOn(worker.getId(), d("2026-01-05"))).singleElement()
                .satisfies(entry -> assertThat(entry.getExpectedQuantityMilli()).isEqualTo(1500L));
    }

    @Test
    void noEntriesOutsideEmployment() {
        addVersion("2025-12-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));

        assertThat(scheduleService.entriesOn(worker.getId(), d("2025-12-29"))).isEmpty();
        assertThat(taskNamesOn("2026-01-05")).containsExactly("Sweeping");
    }

    @Test
    void scheduleChangeDoesNotChangeTheMonthlySalary() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        PayRate salary = payService.addRate(NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "6000", JAN_1));
        addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));

        addVersion("2026-03-01", NewScheduleEntry.task(MONDAY, sweeping.getId()),
                NewScheduleEntry.task(TUESDAY, mopping.getId()));

        assertThat(payService.rateHistory(worker.getId())).singleElement().satisfies(rate -> {
            assertThat(rate.getId()).isEqualTo(salary.getId());
            assertThat(rate.getAmountPaise()).isEqualTo(600000);
            assertThat(rate.getEffectiveTo()).isNull();
        });
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-03-02"), null, null)).get()
                .satisfies(resolved -> assertThat(resolved.rate().getId()).isEqualTo(salary.getId()));
    }

    @Test
    void scheduleChangesAreAudited() {
        WorkSchedule first = addVersion("2026-01-01", NewScheduleEntry.task(MONDAY, sweeping.getId()));
        WorkSchedule second = addVersion("2026-03-01", NewScheduleEntry.task(MONDAY, mopping.getId()));

        List<AuditEvent> events = auditService.historyForWorker(worker.getId()).stream()
                .filter(event -> event.getEntityType().equals(ScheduleService.ENTITY_SCHEDULE)).toList();
        assertThat(events).extracting(AuditEvent::getEntityId, AuditEvent::getAction).containsExactly(
                org.assertj.core.groups.Tuple.tuple(first.getId(), "CREATE"),
                org.assertj.core.groups.Tuple.tuple(first.getId(), "END"),
                org.assertj.core.groups.Tuple.tuple(second.getId(), "CREATE"));
    }
}
