package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.DayView;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.RecordResult;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Void-and-replace corrections: originals are preserved, chains are kept, and no check is bypassed. */
@Transactional
class WorkRecordCorrectionIntegrationTest extends SqliteIntegrationTest {

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

    @Autowired
    private AuditService auditService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Worker sunita;
    private Task sweeping;
    private ScheduleEntry monMopping;
    private ScheduleEntry monSweeping;

    @BeforeEach
    void setUp() {
        Fixtures fixtures = new Fixtures(workerService, catalogService);
        sunita = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        Task mopping = fixtures.task("Mopping");
        var schedule = scheduleService.addScheduleVersion(sunita.getId(), JAN_1, List.of(
                NewScheduleEntry.task(MONDAY, sweeping.getId()),
                new NewScheduleEntry(MONDAY, mopping.getId(), null, LocalTime.of(10, 0), LocalTime.of(11, 0), null, null)), null);
        monSweeping = schedule.getEntries().stream().filter(e -> e.getTask().getId().equals(sweeping.getId())).findFirst().orElseThrow();
        monMopping = schedule.getEntries().stream().filter(e -> e.getTask().getId().equals(mopping.getId())).findFirst().orElseThrow();
    }

    private WorkRecord record(ScheduleEntry entry, WorkDetails details) {
        return dailyWorkService.recordScheduled(sunita.getId(), MON, entry.getId(), details).record();
    }

    private Map<String, Object> row(Long id) {
        entityManager.flush();
        return jdbc.queryForMap("SELECT * FROM work_record WHERE id = ?", id);
    }

    @Test
    void correctionVoidsTheOriginalWithoutChangingItAndLinksTheReplacement() {
        WorkRecord original = record(monMopping, WorkDetails.completed().withNote("Done"));
        Map<String, Object> before = row(original.getId());

        RecordResult result = dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(30), "Left after half an hour", false);

        Map<String, Object> after = row(original.getId());
        assertThat(after).usingRecursiveComparison().ignoringFields("voided_at", "void_reason").isEqualTo(before);
        assertThat(before.get("voided_at")).isNull();
        assertThat(after.get("voided_at")).isNotNull();
        assertThat(after.get("void_reason")).isEqualTo("Corrected: Left after half an hour");

        WorkRecord replacement = result.record();
        assertThat(replacement.getReplaces().getId()).isEqualTo(original.getId());
        assertThat(replacement.getScheduleEntry().getId()).isEqualTo(monMopping.getId());
        assertThat(replacement.getWorkDate()).isEqualTo(MON);
        assertThat(replacement.getOutcome()).isEqualTo(WorkOutcome.PARTIAL);
        assertThat(replacement.getExpectedMinutes()).isEqualTo(original.getExpectedMinutes());
        assertThat(replacement.isCurrent()).isTrue();
    }

    @Test
    void repeatedCorrectionsFormOneChainAndHistoryKeepsEveryVersion() {
        WorkRecord first = record(monMopping, WorkDetails.completed());
        WorkRecord second = dailyWorkService.correct(first.getId(), WorkDetails.partialMinutes(30), "Left early", false).record();
        WorkRecord third = dailyWorkService.correct(second.getId(), WorkDetails.partialMinutes(45), "Actually 45 minutes", false).record();

        List<Long> expectedChain = List.of(first.getId(), second.getId(), third.getId());
        assertThat(dailyWorkService.correctionChain(first.getId())).extracting(WorkRecord::getId).isEqualTo(expectedChain);
        assertThat(dailyWorkService.correctionChain(second.getId())).extracting(WorkRecord::getId).isEqualTo(expectedChain);
        assertThat(dailyWorkService.correctionChain(third.getId())).extracting(WorkRecord::getId).isEqualTo(expectedChain);

        assertThat(dailyWorkService.history(sunita.getId(), MON, MON)).extracting(WorkRecord::getId).containsExactlyElementsOf(expectedChain);
        assertThat(dailyWorkService.history(sunita.getId(), MON, MON)).filteredOn(WorkRecord::isCurrent)
                .singleElement().satisfies(r -> assertThat(r.getId()).isEqualTo(third.getId()));

        DayView.WorkerDay day = dailyWorkService.workerDay(sunita, MON);
        assertThat(day.expected()).filteredOn(a -> a.entry().getId().equals(monMopping.getId()))
                .singleElement().satisfies(a -> assertThat(a.record().getId()).isEqualTo(third.getId()));
    }

    @Test
    void correctionsAreAudited() {
        WorkRecord original = record(monMopping, WorkDetails.completed());
        WorkRecord replacement = dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(30), "Left early", false).record();

        List<AuditEvent> events = auditService.historyForWorker(sunita.getId()).stream()
                .filter(e -> e.getEntityType().equals(DailyWorkService.ENTITY_WORK_RECORD)).toList();
        assertThat(events).extracting(AuditEvent::getEntityId, AuditEvent::getAction).containsExactly(
                org.assertj.core.groups.Tuple.tuple(original.getId(), "RECORD"),
                org.assertj.core.groups.Tuple.tuple(original.getId(), "VOID"),
                org.assertj.core.groups.Tuple.tuple(replacement.getId(), "CORRECT"));
        assertThat(events.getLast().getDetails()).contains("replaces=" + original.getId()).contains("Left early")
                .contains("outcome=COMPLETED").contains("outcome=PARTIAL");
    }

    @Test
    void onlyTheCurrentVersionCanBeCorrectedAndAReasonIsRequired() {
        WorkRecord original = record(monMopping, WorkDetails.completed());

        assertThatThrownBy(() -> dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(30), "  ", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reason");
        dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(30), "Left early", false);

        assertThatThrownBy(() -> dailyWorkService.correct(original.getId(), WorkDetails.completed(), "Again", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Correct the current version");
    }

    @Test
    void invalidCorrectionLeavesTheOriginalCurrent() {
        WorkRecord original = record(monMopping, WorkDetails.completed());

        assertThatThrownBy(() -> dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(60), "Wrong", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Record it as done");
        assertThatThrownBy(() -> dailyWorkService.correct(original.getId(),
                new WorkDetails(WorkOutcome.NOT_DONE, null, null, null, null, null, null), "Wrong", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("why the activity was not done");

        assertThat(row(original.getId()).get("voided_at")).isNull();
        assertThat(dailyWorkService.history(sunita.getId(), MON, MON)).hasSize(1);
    }

    @Test
    void correctionOfUnscheduledWorkStillWarnsAboutDuplicates() {
        WorkRecord morning = dailyWorkService.recordUnscheduled(sunita.getId(), WED, sweeping.getId(), null,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null, null), false).record();
        WorkRecord evening = dailyWorkService.recordUnscheduled(sunita.getId(), WED, sweeping.getId(), null,
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(18, 0), LocalTime.of(19, 0), null, null, null), false).record();

        RecordResult clash = dailyWorkService.correct(evening.getId(),
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null, null), "Wrong time", false);

        assertThat(clash.saved()).isFalse();
        assertThat(clash.warnings()).anyMatch(w -> w.contains("already recorded"));
        assertThat(row(evening.getId()).get("voided_at")).as("nothing changed without confirmation").isNull();
        assertThat(morning.isCurrent()).isTrue();

        RecordResult confirmed = dailyWorkService.correct(evening.getId(),
                new WorkDetails(WorkOutcome.COMPLETED, null, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null, null), "Wrong time", true);
        assertThat(confirmed.saved()).isTrue();
    }

    @Test
    void correctedAbsenceNeedsAFreshDeductionDecision() {
        WorkRecord absent = record(monSweeping, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        dailyWorkService.decideDeduction(absent.getId(), DeductionDecision.APPROVED, null);

        RecordResult corrected = dailyWorkService.correct(absent.getId(),
                WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE).withNote("Called in late"), "Added note", false);

        assertThat(corrected.record().getDeductionDecision()).isEqualTo(DeductionDecision.PENDING);
        assertThat(corrected.warnings()).singleElement().asString().contains("was not carried over");
        assertThat(row(absent.getId()).get("deduction_decision")).as("original decision preserved").isEqualTo("APPROVED");
    }

    @Test
    void absenceCorrectedToLeaveHasNoDeductionDecision() {
        WorkRecord absent = record(monSweeping, WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        WorkRecord leave = dailyWorkService.correct(absent.getId(), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE),
                "She had asked in advance", false).record();

        assertThat(leave.getDeductionDecision()).isNull();
        assertThat(dailyWorkService.workerDay(sunita, MON).flags()).noneMatch(f -> f.contains("deduction decision"));
    }

    @Test
    void voidingMakesTheActivityUnrecordedAgainButKeepsHistory() {
        WorkRecord mistaken = record(monSweeping, WorkDetails.completed());

        dailyWorkService.voidRecord(mistaken.getId(), "Entered for the wrong day");

        assertThat(dailyWorkService.workerDay(sunita, MON).expected())
                .filteredOn(a -> a.entry().getId().equals(monSweeping.getId()))
                .singleElement().satisfies(a -> assertThat(a.isRecorded()).isFalse());
        assertThat(dailyWorkService.history(sunita.getId(), MON, MON)).singleElement()
                .satisfies(r -> assertThat(r.getVoidReason()).isEqualTo("Entered for the wrong day"));
        assertThatThrownBy(() -> dailyWorkService.voidRecord(mistaken.getId(), "Again"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already void");

        WorkRecord again = record(monSweeping, WorkDetails.completed());
        assertThat(again.getReplaces()).as("a fresh record, not a correction").isNull();
    }
}
