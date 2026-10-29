package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.DayView;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.RecordResult;
import com.homeexpense.dto.WholeDayResult;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily work recording on a real SQLite database. Sunita's schedule from 2026-01-01:
 * Monday sweeping (untimed), Monday mopping 10:00-11:00, Monday morning shift (07:00-09:00),
 * Tuesday milk delivery of 1.5 litres. Wednesday is a regular day off.
 */
@Transactional
class DailyWorkServiceIntegrationTest extends SqliteIntegrationTest {

    private static final LocalDate MON = d("2026-01-05");
    private static final LocalDate TUE = d("2026-01-06");
    private static final LocalDate WED = d("2026-01-07");

    @Autowired
    private DailyWorkService dailyWorkService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private PayService payService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

    private Fixtures fixtures;
    private Worker sunita;
    private Task sweeping;
    private Task mopping;
    private Task milk;
    private Task cooking;
    private Shift morning;
    private ScheduleEntry monSweeping;
    private ScheduleEntry monMopping;
    private ScheduleEntry monMorning;
    private ScheduleEntry tueMilk;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(workerService, catalogService);
        sunita = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        mopping = fixtures.task("Mopping");
        milk = fixtures.task("Milk delivery");
        cooking = fixtures.task("Cooking");
        morning = fixtures.shift("Morning", "07:00", "09:00");
        var schedule = scheduleService.addScheduleVersion(sunita.getId(), JAN_1, List.of(
                NewScheduleEntry.task(MONDAY, sweeping.getId()),
                new NewScheduleEntry(MONDAY, mopping.getId(), null, LocalTime.of(10, 0), LocalTime.of(11, 0), null, null),
                NewScheduleEntry.shift(MONDAY, morning.getId()),
                new NewScheduleEntry(TUESDAY, milk.getId(), null, null, null, null, 1500L)), null);
        monSweeping = entry(schedule.getEntries(), sweeping);
        monMopping = entry(schedule.getEntries(), mopping);
        monMorning = schedule.getEntries().stream().filter(e -> e.getShift() != null).findFirst().orElseThrow();
        tueMilk = entry(schedule.getEntries(), milk);
    }

    private static ScheduleEntry entry(List<ScheduleEntry> entries, Task task) {
        return entries.stream().filter(e -> e.getTask() != null && e.getTask().getId().equals(task.getId())).findFirst().orElseThrow();
    }

    private WorkRecord record(LocalDate date, ScheduleEntry entry, WorkDetails details) {
        return dailyWorkService.recordScheduled(sunita.getId(), date, entry.getId(), details).record();
    }

    private DayView.WorkerDay sunitaOn(LocalDate date) {
        return dailyWorkService.dayView(date).workers().stream()
                .filter(day -> day.worker().getId().equals(sunita.getId())).findFirst().orElseThrow();
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_record WHERE worker_id = ?", Integer.class, sunita.getId());
    }

    private static LocalDate futureMonday() {
        return LocalDate.now().with(TemporalAdjusters.next(MONDAY)).plusWeeks(1);
    }

    // --- expected versus actual ---

    @Test
    void openingADayListsExpectedActivitiesAsNotRecordedAndWritesNothing() {
        DayView.WorkerDay day = sunitaOn(MON);

        assertThat(day.dayType()).isEqualTo(DayView.DayType.SCHEDULED_DAY);
        assertThat(day.expected()).hasSize(3).noneMatch(DayView.ExpectedActivity::isRecorded);
        assertThat(day.notRecordedCount()).isEqualTo(3);
        assertThat(day.flags()).contains("3 expected activities are not recorded yet.");
        assertThat(recordCount()).isZero();
    }

    @Test
    void regularDayOffHasNoExpectedActivities() {
        DayView.WorkerDay day = sunitaOn(WED);

        assertThat(day.dayType()).isEqualTo(DayView.DayType.REGULAR_DAY_OFF);
        assertThat(day.expected()).isEmpty();
        assertThat(day.flags()).isEmpty();
    }

    @Test
    void expectedActivitiesFollowTheScheduleVersionForTheDate() {
        scheduleService.addScheduleVersion(sunita.getId(), d("2026-02-01"),
                List.of(NewScheduleEntry.task(MONDAY, mopping.getId())), null);

        assertThat(sunitaOn(d("2026-01-26")).expected()).hasSize(3);
        assertThat(sunitaOn(d("2026-02-02")).expected()).singleElement()
                .satisfies(a -> assertThat(a.entry().getTask().getName()).isEqualTo("Mopping"));
    }

    @Test
    void workersNotEmployedOnTheDateAreNotListed() {
        Worker former = fixtures.maid("Former worker");
        workerService.deactivate(former.getId(), d("2026-01-02"), null);

        assertThat(dailyWorkService.dayView(MON).workers()).extracting(day -> day.worker().getId())
                .contains(sunita.getId()).doesNotContain(former.getId());
    }

    // --- recording and reopening a day ---

    @Test
    void recordingAnActivityShowsItAsRecordedWhenTheDayIsReopened() {
        WorkRecord done = record(MON, monSweeping, WorkDetails.completed());

        DayView.WorkerDay day = sunitaOn(MON);
        assertThat(day.expected()).filteredOn(DayView.ExpectedActivity::isRecorded).singleElement()
                .satisfies(a -> assertThat(a.record().getId()).isEqualTo(done.getId()));
        assertThat(day.notRecordedCount()).isEqualTo(2);
        assertThat(done.getTask().getId()).isEqualTo(sweeping.getId());
        assertThat(auditService.historyForWorker(sunita.getId())).extracting(AuditEvent::getAction).contains("RECORD");
    }

    @Test
    void anExpectedActivityCannotBeRecordedTwice() {
        record(MON, monSweeping, WorkDetails.completed());

        assertThatThrownBy(() -> record(MON, monSweeping, WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already recorded")
                .hasMessageContaining("Correct that record");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void severalActivitiesOnTheSameDayAreAllRecorded() {
        record(MON, monSweeping, WorkDetails.completed());
        record(MON, monMopping, WorkDetails.completed());
        record(MON, monMorning, WorkDetails.completed());
        RecordResult extra = dailyWorkService.recordUnscheduled(sunita.getId(), MON, cooking.getId(), null,
                WorkDetails.completed(), false);

        assertThat(extra.saved()).isTrue();
        DayView.WorkerDay day = sunitaOn(MON);
        assertThat(day.expected()).allMatch(DayView.ExpectedActivity::isRecorded);
        assertThat(day.unscheduled()).hasSize(1);
        assertThat(day.unscheduledWorkLabel()).isEqualTo("Additional activity");
    }

    @Test
    void activityMustBeOnTheWorkersScheduleForThatDate() {
        assertThatThrownBy(() -> record(TUE, monSweeping, WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("is not on Sunita's schedule for 2026-01-06");

        Worker asha = fixtures.maid("Asha");
        assertThatThrownBy(() -> dailyWorkService.recordScheduled(asha.getId(), MON, monSweeping.getId(), WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("is not on Asha's schedule");
    }

    @Test
    void workCanOnlyBeRecordedWhileEmployed() {
        assertThatThrownBy(() -> dailyWorkService.recordUnscheduled(sunita.getId(), d("2025-12-31"), sweeping.getId(), null,
                WorkDetails.completed(), false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("was not employed on 2025-12-31");
    }

    // --- unscheduled work and duplicate warnings ---

    @Test
    void workOnARegularDayOffIsRecordedAndLabelled() {
        RecordResult result = dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                WorkDetails.completedMinutes(60), false);

        assertThat(result.saved()).isTrue();
        assertThat(result.warnings()).isEmpty();
        DayView.WorkerDay day = sunitaOn(WED);
        assertThat(day.unscheduled()).hasSize(1);
        assertThat(day.unscheduledWorkLabel()).isEqualTo("Work on a regular day off");
    }

    @Test
    void likelyDuplicateIsOnlySavedWhenConfirmed() {
        dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null, WorkDetails.completed(), false);

        RecordResult unconfirmed = dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                WorkDetails.completed(), false);
        assertThat(unconfirmed.saved()).isFalse();
        assertThat(unconfirmed.warnings()).singleElement().asString().contains("already recorded");
        assertThat(recordCount()).isEqualTo(1);

        RecordResult confirmed = dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                WorkDetails.completed(), true);
        assertThat(confirmed.saved()).isTrue();
        assertThat(recordCount()).isEqualTo(2);
        assertThat(sunitaOn(WED).flags()).anyMatch(flag -> flag.startsWith("Possible duplicate"));
    }

    @Test
    void sameTaskAtADifferentTimeIsNotADuplicate() {
        dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null, null), false);

        RecordResult evening = dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(18, 0), LocalTime.of(19, 0), null, null, null), false);

        assertThat(evening.saved()).isTrue();
        assertThat(evening.warnings()).isEmpty();
    }

    @Test
    void unscheduledEntryMatchingTheScheduleNeedsConfirmation() {
        RecordResult result = dailyWorkService.recordUnscheduled(sunita.getId(), MON, sweeping.getId(), null,
                WorkDetails.completed(), false);

        assertThat(result.saved()).isFalse();
        assertThat(result.warnings()).singleElement().asString().contains("Record it against the schedule instead");
    }

    @Test
    void unscheduledWorkCannotBeAnAbsence() {
        assertThatThrownBy(() -> dailyWorkService.recordUnscheduled(sunita.getId(), WED, mopping.getId(), null,
                WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE), false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("only be recorded as done or partly done");
        assertThatThrownBy(() -> dailyWorkService.recordUnscheduled(sunita.getId(), WED, null, null, WorkDetails.completed(), false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Choose the task or shift");
    }

    // --- partial work ---

    @Test
    void partialWorkIsComparedWithTheUsualTime() {
        WorkRecord partial = record(MON, monMopping, WorkDetails.partialMinutes(40));

        assertThat(partial.getOutcome()).isEqualTo(WorkOutcome.PARTIAL);
        assertThat(partial.getExpectedMinutes()).isEqualTo(60);
        assertThat(partial.getActualMinutes()).isEqualTo(40);
    }

    @Test
    void partialWorkRules() {
        assertThatThrownBy(() -> record(MON, monMopping, WorkDetails.partialMinutes(60)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Record it as done");
        assertThatThrownBy(() -> record(MON, monMopping, new WorkDetails(WorkOutcome.PARTIAL, null, null, null, null, null, "Left early")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("usual 60 minutes");
        assertThatThrownBy(() -> record(MON, monSweeping, new WorkDetails(WorkOutcome.PARTIAL, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("add a note");

        WorkRecord explained = record(MON, monSweeping,
                new WorkDetails(WorkOutcome.PARTIAL, null, null, null, null, null, "Only the front rooms"));
        assertThat(explained.getExpectedMinutes()).isNull();
        assertThat(explained.getNote()).isEqualTo("Only the front rooms");
    }

    @Test
    void timesWorkOutTheMinutesAndMustAgreeWithThem() {
        WorkRecord shift = record(MON, monMorning,
                new WorkDetails(WorkOutcome.PARTIAL, null, LocalTime.of(7, 0), LocalTime.of(8, 0), null, null, null));
        assertThat(shift.getExpectedMinutes()).isEqualTo(120);
        assertThat(shift.getActualMinutes()).isEqualTo(60);

        assertThatThrownBy(() -> record(MON, monMopping,
                new WorkDetails(WorkOutcome.PARTIAL, null, LocalTime.of(10, 0), LocalTime.of(10, 30), 45, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("do not match");
        assertThatThrownBy(() -> record(MON, monMopping,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(23, 0), LocalTime.of(1, 0), null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Overnight");
        assertThatThrownBy(() -> record(MON, monMopping,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(10, 0), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("both the start and end");
    }

    // --- quantity-based work ---

    @Test
    void deliveriesAreRecordedWithTheActualQuantity() {
        WorkRecord full = record(TUE, tueMilk, WorkDetails.completedQuantity(1500));
        assertThat(full.getExpectedQuantityMilli()).isEqualTo(1500L);
        assertThat(full.getActualQuantityMilli()).isEqualTo(1500L);

        WorkRecord extra = record(d("2026-01-13"), tueMilk, WorkDetails.completedQuantity(2000));
        assertThat(extra.getOutcome()).isEqualTo(WorkOutcome.COMPLETED);

        WorkRecord less = record(d("2026-01-20"), tueMilk, WorkDetails.partialQuantity(1000));
        assertThat(less.getOutcome()).isEqualTo(WorkOutcome.PARTIAL);
    }

    @Test
    void deliveryQuantityRules() {
        assertThatThrownBy(() -> record(TUE, tueMilk, WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("quantity actually delivered");
        assertThatThrownBy(() -> record(TUE, tueMilk, WorkDetails.completedQuantity(1000)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Record it as partly done");
        assertThatThrownBy(() -> record(TUE, tueMilk, WorkDetails.partialQuantity(1500)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Record it as done");
        assertThatThrownBy(() -> record(TUE, tueMilk, WorkDetails.completedQuantity(0)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("more than zero");
    }

    @Test
    void missedDeliveryIsRecordedAsNotDone() {
        WorkRecord missed = record(TUE, tueMilk, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        assertThat(missed.getOutcome()).isEqualTo(WorkOutcome.NOT_DONE);
        assertThat(missed.getActualQuantityMilli()).isNull();
        assertThat(missed.getDeductionDecision()).isEqualTo(DeductionDecision.PENDING);
    }

    // --- holidays, leave and absence ---

    @Test
    void eachAbsenceReasonIsRecorded() {
        WorkRecord holiday = record(MON, monSweeping, WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        WorkRecord leave = record(MON, monMopping, WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));
        WorkRecord absent = record(MON, monMorning, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        assertThat(holiday.getDeductionDecision()).isNull();
        assertThat(leave.getDeductionDecision()).isNull();
        assertThat(absent.getDeductionDecision()).isEqualTo(DeductionDecision.PENDING);
        assertThat(sunitaOn(MON).flags()).anyMatch(flag -> flag.contains("needs a deduction decision"));
    }

    @Test
    void notDoneRules() {
        assertThatThrownBy(() -> record(MON, monSweeping, new WorkDetails(WorkOutcome.NOT_DONE, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("why the activity was not done");
        assertThatThrownBy(() -> record(MON, monMopping,
                new WorkDetails(WorkOutcome.NOT_DONE, AbsenceReason.APPROVED_LEAVE, null, null, 30, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot have hours");
        assertThatThrownBy(() -> record(MON, monMopping,
                new WorkDetails(WorkOutcome.COMPLETED, AbsenceReason.APPROVED_LEAVE, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("only for work that was not done");
    }

    @Test
    void wholeDayFillsOnlyActivitiesNotYetRecorded() {
        WorkRecord done = record(MON, monSweeping, WorkDetails.completed());

        WholeDayResult result = dailyWorkService.markWholeDay(sunita.getId(), MON, AbsenceReason.HOUSEHOLD_HOLIDAY, "Festival");

        assertThat(result.records()).hasSize(2).allSatisfy(r -> {
            assertThat(r.getAbsenceReason()).isEqualTo(AbsenceReason.HOUSEHOLD_HOLIDAY);
            assertThat(r.getNote()).isEqualTo("Festival");
        });
        assertThat(result.messages()).singleElement().asString().contains("already recorded and was left unchanged");
        assertThat(sunitaOn(MON).expected()).filteredOn(a -> a.entry().getId().equals(monSweeping.getId()))
                .singleElement().satisfies(a -> assertThat(a.record().getId()).isEqualTo(done.getId()));
    }

    @Test
    void householdHolidayOnARegularDayOffRecordsNothing() {
        WholeDayResult result = dailyWorkService.markWholeDay(sunita.getId(), WED, AbsenceReason.HOUSEHOLD_HOLIDAY, null);

        assertThat(result.records()).isEmpty();
        assertThat(result.messages()).singleElement().asString()
                .contains("regular day off")
                .contains("does not create extra payment");
        assertThat(recordCount()).isZero();
    }

    // --- future dates ---

    @Test
    void holidayAndApprovedLeaveCanBeRecordedInAdvance() {
        LocalDate future = futureMonday();

        assertThat(dailyWorkService.markWholeDay(sunita.getId(), future, AbsenceReason.APPROVED_LEAVE, null).records()).hasSize(3);
        assertThat(dailyWorkService.markWholeDay(sunita.getId(), future.plusWeeks(1), AbsenceReason.HOUSEHOLD_HOLIDAY, null)
                .records()).hasSize(3);
    }

    @Test
    void workAndUnauthorizedAbsenceCannotBeRecordedInAdvance() {
        LocalDate future = futureMonday();

        assertThatThrownBy(() -> record(future, monSweeping, WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("is in the future");
        assertThatThrownBy(() -> record(future, monSweeping, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("is in the future");
    }

    // --- hourly workers ---

    @Test
    void hourlyWorkersNeedTheActualTime() {
        payService.setPrimaryModel(sunita.getId(), PaymentModel.HOURLY, JAN_1);

        assertThatThrownBy(() -> record(MON, monSweeping, WorkDetails.completed()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("paid hourly");
        assertThat(record(MON, monSweeping, WorkDetails.completedMinutes(75)).getActualMinutes()).isEqualTo(75);
    }

    // --- deduction decisions ---

    @Test
    void deductionDecisionIsRecordedOnceWithoutAnyAmount() {
        WorkRecord absent = record(MON, monMorning, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        dailyWorkService.decideDeduction(absent.getId(), DeductionDecision.APPROVED, "Agreed with Sunita");

        assertThat(absent.getDeductionDecision()).isEqualTo(DeductionDecision.APPROVED);
        assertThatThrownBy(() -> dailyWorkService.decideDeduction(absent.getId(), DeductionDecision.WAIVED, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already been made");
        assertThat(auditService.historyForWorker(sunita.getId()))
                .filteredOn(e -> e.getAction().equals("DEDUCTION_DECISION")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).contains("APPROVED").contains("Agreed with Sunita"));
    }

    @Test
    void deductionDecisionRules() {
        WorkRecord leave = record(MON, monMopping, WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));
        WorkRecord absent = record(MON, monMorning, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        assertThatThrownBy(() -> dailyWorkService.decideDeduction(leave.getId(), DeductionDecision.APPROVED, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("only for unauthorized absences");
        assertThatThrownBy(() -> dailyWorkService.decideDeduction(absent.getId(), DeductionDecision.PENDING, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("approve or waive");

        dailyWorkService.decideDeduction(absent.getId(), DeductionDecision.WAIVED, null);
        assertThat(absent.getDeductionDecision()).isEqualTo(DeductionDecision.WAIVED);
    }
}
