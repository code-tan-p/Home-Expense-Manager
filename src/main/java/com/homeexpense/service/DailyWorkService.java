package com.homeexpense.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.homeexpense.dto.DayView;
import com.homeexpense.dto.RecordResult;
import com.homeexpense.dto.WholeDayResult;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.repository.ScheduleEntryRepository;
import com.homeexpense.repository.WorkRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily work and attendance: what was expected on a date (from the schedules) and what was
 * actually recorded. Nothing is assumed done; no earnings or deductions are calculated here.
 *
 * <p>Records are never edited or deleted. A correction voids the current record and inserts a
 * linked replacement after running every check a new record must pass.
 */
@Service
@Transactional
public class DailyWorkService {

    static final String ENTITY_WORK_RECORD = "WORK_RECORD";

    private final WorkRecordRepository workRecordRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;
    private final WorkerService workerService;
    private final ScheduleService scheduleService;
    private final CatalogService catalogService;
    private final PayService payService;
    private final EarningsGuard earningsGuard;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DailyWorkService(WorkRecordRepository workRecordRepository, ScheduleEntryRepository scheduleEntryRepository,
                            WorkerService workerService, ScheduleService scheduleService, CatalogService catalogService,
                            PayService payService, EarningsGuard earningsGuard, AuditService auditService, Clock clock) {
        this.workRecordRepository = workRecordRepository;
        this.scheduleEntryRepository = scheduleEntryRepository;
        this.workerService = workerService;
        this.scheduleService = scheduleService;
        this.catalogService = catalogService;
        this.payService = payService;
        this.earningsGuard = earningsGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** Expected activities and current records for every worker employed on {@code date}. */
    @Transactional(readOnly = true)
    public DayView dayView(LocalDate date) {
        requireDate(date);
        List<DayView.WorkerDay> workers = new ArrayList<>();
        for (Worker worker : workerService.workersEmployedOn(date)) {
            workers.add(workerDay(worker, date));
        }
        return new DayView(date, List.copyOf(workers));
    }

    @Transactional(readOnly = true)
    public DayView.WorkerDay workerDay(Worker worker, LocalDate date) {
        List<ScheduleEntry> entries = scheduleService.entriesOn(worker.getId(), date);
        List<WorkRecord> current = workRecordRepository.findCurrent(worker.getId(), date);
        Map<Long, WorkRecord> byEntry = current.stream()
                .filter(WorkRecord::isScheduled)
                .collect(Collectors.toMap(r -> r.getScheduleEntry().getId(), Function.identity()));

        List<DayView.ExpectedActivity> expected = entries.stream()
                .map(entry -> new DayView.ExpectedActivity(entry, byEntry.get(entry.getId())))
                .toList();
        List<WorkRecord> unscheduled = current.stream().filter(r -> !r.isScheduled()).toList();

        List<String> flags = new ArrayList<>();
        long notRecorded = expected.stream().filter(a -> !a.isRecorded()).count();
        if (notRecorded > 0) {
            flags.add(notRecorded + " expected " + (notRecorded == 1 ? "activity is" : "activities are") + " not recorded yet.");
        }
        current.stream()
                .filter(r -> r.getDeductionDecision() == DeductionDecision.PENDING)
                .forEach(r -> flags.add("Unauthorized absence for " + describe(r) + " needs a deduction decision."));
        for (WorkRecord record : unscheduled) {
            boolean repeated = unscheduled.stream().anyMatch(other -> other != record && sameActivity(other, record));
            if (repeated) {
                flags.add("Possible duplicate: " + describe(record) + " is recorded more than once.");
                break;
            }
        }
        DayView.DayType dayType = entries.isEmpty() ? DayView.DayType.REGULAR_DAY_OFF : DayView.DayType.SCHEDULED_DAY;
        return new DayView.WorkerDay(worker, dayType, expected, unscheduled, List.copyOf(flags));
    }

    /** All records for a worker between two dates, including voided ones, oldest first. */
    @Transactional(readOnly = true)
    public List<WorkRecord> history(Long workerId, LocalDate from, LocalDate to) {
        return workRecordRepository.findAllBetween(workerId, from, to);
    }

    /** The full correction chain containing this record, from the original to the latest version. */
    @Transactional(readOnly = true)
    public List<WorkRecord> correctionChain(Long recordId) {
        WorkRecord record = getRecord(recordId);
        WorkRecord first = record;
        while (first.getReplaces() != null) {
            first = first.getReplaces();
        }
        List<WorkRecord> chain = new ArrayList<>();
        WorkRecord next = first;
        while (next != null) {
            chain.add(next);
            next = workRecordRepository.findByReplacesId(next.getId()).orElse(null);
        }
        return List.copyOf(chain);
    }

    // ---------------------------------------------------------------- recording

    /** Records what happened for one expected (scheduled) activity. */
    public RecordResult recordScheduled(Long workerId, LocalDate date, Long scheduleEntryId, WorkDetails details) {
        Worker worker = workerService.getWorker(workerId);
        requireDate(date);
        ScheduleEntry entry = requireExpectedEntry(worker, date, scheduleEntryId);
        requireNotLocked(worker, date);
        workRecordRepository.findCurrentForExpected(entry.getId(), date).ifPresent(existing -> {
            throw new BusinessRuleException(describe(entry) + " on " + date + " is already recorded for "
                    + worker.getName() + ". Correct that record instead of adding another.");
        });
        Validated valid = validate(worker, date, details, true, entry.scheduledMinutes(), entry.getExpectedQuantityMilli());
        WorkRecord saved = workRecordRepository.saveAndFlush(newRecord(worker, date, entry, entry.getTask(), entry.getShift(),
                details, valid, entry.scheduledMinutes(), entry.getExpectedQuantityMilli(), null));
        auditService.record(workerId, ENTITY_WORK_RECORD, saved.getId(), "RECORD", summary(saved));
        return new RecordResult(saved, List.of());
    }

    /**
     * Records work that was not on the schedule: an additional activity on a working day, or
     * work on a regular day off. Likely duplicates are only saved when {@code confirmDuplicate}.
     */
    public RecordResult recordUnscheduled(Long workerId, LocalDate date, Long taskId, Long shiftId,
                                          WorkDetails details, boolean confirmDuplicate) {
        Worker worker = workerService.getWorker(workerId);
        requireDate(date);
        requireEmployed(worker, date);
        if (taskId == null && shiftId == null) {
            throw new BusinessRuleException("Choose the task or shift that was worked.");
        }
        Task task = taskId == null ? null : catalogService.getActiveTask(taskId);
        Shift shift = shiftId == null ? null : catalogService.getActiveShift(shiftId);
        requireNotLocked(worker, date);
        Validated valid = validate(worker, date, details, false, null, null);

        List<String> warnings = duplicateWarnings(worker, date, task, shift, details.actualStart(), null);
        if (!warnings.isEmpty() && !confirmDuplicate) {
            return new RecordResult(null, warnings);
        }
        WorkRecord saved = workRecordRepository.saveAndFlush(
                newRecord(worker, date, null, task, shift, details, valid, null, null, null));
        auditService.record(workerId, ENTITY_WORK_RECORD, saved.getId(), "RECORD",
                summary(saved) + (warnings.isEmpty() ? "" : "; confirmedDuplicate=true"));
        return new RecordResult(saved, warnings);
    }

    /**
     * Records the same reason for every expected activity of the day that is not recorded yet.
     * Already recorded activities are left unchanged. On a regular day off nothing is recorded.
     */
    public WholeDayResult markWholeDay(Long workerId, LocalDate date, AbsenceReason reason, String note) {
        Worker worker = workerService.getWorker(workerId);
        requireDate(date);
        if (reason == null) {
            throw new BusinessRuleException("Choose why " + worker.getName() + " did not work.");
        }
        requireEmployed(worker, date);
        List<ScheduleEntry> entries = scheduleService.entriesOn(workerId, date);
        List<String> messages = new ArrayList<>();
        if (entries.isEmpty()) {
            messages.add(date + " is a regular day off for " + worker.getName() + ", so nothing was recorded."
                    + (reason == AbsenceReason.HOUSEHOLD_HOLIDAY
                    ? " A household holiday on a regular day off does not create extra payment." : ""));
            return new WholeDayResult(List.of(), messages);
        }
        List<WorkRecord> created = new ArrayList<>();
        for (ScheduleEntry entry : entries) {
            if (workRecordRepository.findCurrentForExpected(entry.getId(), date).isPresent()) {
                messages.add(describe(entry) + " was already recorded and was left unchanged.");
                continue;
            }
            created.add(recordScheduled(workerId, date, entry.getId(), WorkDetails.notDone(reason).withNote(note)).record());
        }
        return new WholeDayResult(List.copyOf(created), messages);
    }

    // ---------------------------------------------------------------- corrections

    /**
     * Corrects a current record: the record is voided (kept unchanged otherwise) and a
     * replacement with the new details is inserted, linked to it. The replacement passes every
     * check a new record must pass. The worker, date and activity stay the same; to change
     * those, void the record and record the work again.
     */
    public RecordResult correct(Long recordId, WorkDetails details, String reason, boolean confirmDuplicate) {
        WorkRecord original = getRecord(recordId);
        if (!original.isCurrent()) {
            throw new BusinessRuleException("This record has already been corrected or voided. Correct the current version instead.");
        }
        String why = requireReason(reason, "Enter a reason for the correction.");
        Worker worker = original.getWorker();
        LocalDate date = original.getWorkDate();
        requireEmployed(worker, date);
        requireNotLocked(worker, date);

        List<String> warnings = new ArrayList<>();
        Validated valid;
        if (original.isScheduled()) {
            requireExpectedEntry(worker, date, original.getScheduleEntry().getId());
            valid = validate(worker, date, details, true, original.getExpectedMinutes(), original.getExpectedQuantityMilli());
        } else {
            valid = validate(worker, date, details, false, null, null);
            warnings.addAll(duplicateWarnings(worker, date, original.getTask(), original.getShift(), details.actualStart(), original.getId()));
            if (!warnings.isEmpty() && !confirmDuplicate) {
                return new RecordResult(null, List.copyOf(warnings));
            }
        }
        if (original.getDeductionDecision() != null && original.getDeductionDecision() != DeductionDecision.PENDING
                && details.absenceReason() == AbsenceReason.UNAUTHORIZED_ABSENCE) {
            warnings.add("The earlier deduction decision (" + original.getDeductionDecision()
                    + ") was not carried over. Decide again for the corrected record.");
        }

        // Void first: only one current record may answer an expected activity.
        original.voidRecord(clock.instant(), "Corrected: " + why);
        workRecordRepository.saveAndFlush(original);
        WorkRecord replacement = workRecordRepository.saveAndFlush(newRecord(worker, date, original.getScheduleEntry(),
                original.getTask(), original.getShift(), details, valid, original.getExpectedMinutes(),
                original.getExpectedQuantityMilli(), original));
        auditService.record(worker.getId(), ENTITY_WORK_RECORD, original.getId(), "VOID", "reason=Corrected: " + why
                + "; replacedBy=" + replacement.getId());
        auditService.record(worker.getId(), ENTITY_WORK_RECORD, replacement.getId(), "CORRECT", "replaces=" + original.getId()
                + "; reason=" + why + "; was: " + summary(original) + "; now: " + summary(replacement));
        return new RecordResult(replacement, List.copyOf(warnings));
    }

    /** Voids a record that should not exist. It stays in history; the activity becomes unrecorded. */
    public WorkRecord voidRecord(Long recordId, String reason) {
        WorkRecord record = getRecord(recordId);
        if (!record.isCurrent()) {
            throw new BusinessRuleException("This record is already void.");
        }
        String why = requireReason(reason, "Enter a reason for removing this record.");
        requireNotLocked(record.getWorker(), record.getWorkDate());
        record.voidRecord(clock.instant(), why);
        workRecordRepository.saveAndFlush(record);
        auditService.record(record.getWorker().getId(), ENTITY_WORK_RECORD, recordId, "VOID", "reason=" + why);
        return record;
    }

    /** Records the household's decision about a pending unauthorized absence. No amount is calculated. */
    public WorkRecord decideDeduction(Long recordId, DeductionDecision decision, String note) {
        WorkRecord record = getRecord(recordId);
        if (!record.isCurrent()) {
            throw new BusinessRuleException("This record has been corrected or voided. Decide on the current version.");
        }
        if (record.getAbsenceReason() != AbsenceReason.UNAUTHORIZED_ABSENCE) {
            throw new BusinessRuleException("Deduction decisions are only for unauthorized absences.");
        }
        if (decision != DeductionDecision.APPROVED && decision != DeductionDecision.WAIVED) {
            throw new BusinessRuleException("Choose whether to approve or waive a deduction.");
        }
        if (record.getDeductionDecision() != DeductionDecision.PENDING) {
            throw new BusinessRuleException("A decision has already been made for this absence ("
                    + record.getDeductionDecision() + "). Correct the record to decide again.");
        }
        requireNotLocked(record.getWorker(), record.getWorkDate());
        record.decideDeduction(decision);
        workRecordRepository.saveAndFlush(record);
        auditService.record(record.getWorker().getId(), ENTITY_WORK_RECORD, recordId, "DEDUCTION_DECISION",
                "decision=" + decision + (note == null || note.isBlank() ? "" : "; note=" + note.strip()));
        return record;
    }

    // ---------------------------------------------------------------- validation

    private record Validated(Integer actualMinutes, String note) {
    }

    private Validated validate(Worker worker, LocalDate date, WorkDetails details, boolean scheduled,
                               Integer expectedMinutes, Long expectedQuantity) {
        if (details == null || details.outcome() == null) {
            throw new BusinessRuleException("Choose whether the work was done, partly done or not done.");
        }
        WorkOutcome outcome = details.outcome();
        String note = WorkerService.blankToNull(details.note());

        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            boolean plannedAbsence = outcome == WorkOutcome.NOT_DONE
                    && (details.absenceReason() == AbsenceReason.HOUSEHOLD_HOLIDAY || details.absenceReason() == AbsenceReason.APPROVED_LEAVE);
            if (!plannedAbsence) {
                throw new BusinessRuleException(date + " is in the future. Only a household holiday or approved leave "
                        + "can be recorded in advance.");
            }
        }

        if (outcome == WorkOutcome.NOT_DONE) {
            if (!scheduled) {
                throw new BusinessRuleException("Unscheduled work can only be recorded as done or partly done.");
            }
            if (details.absenceReason() == null) {
                throw new BusinessRuleException("Choose why the activity was not done.");
            }
            if (details.actualStart() != null || details.actualEnd() != null || details.actualMinutes() != null
                    || details.actualQuantityMilli() != null) {
                throw new BusinessRuleException("Work that was not done cannot have hours or a quantity.");
            }
            return new Validated(null, note);
        }
        if (details.absenceReason() != null) {
            throw new BusinessRuleException("An absence reason is only for work that was not done.");
        }

        Integer minutes = details.actualMinutes();
        if ((details.actualStart() == null) != (details.actualEnd() == null)) {
            throw new BusinessRuleException("Enter both the start and end time, or neither.");
        }
        if (details.actualStart() != null) {
            CatalogService.validateTimes(details.actualStart(), details.actualEnd(), "The work");
            int fromTimes = (int) Duration.between(details.actualStart(), details.actualEnd()).toMinutes();
            if (minutes != null && minutes != fromTimes) {
                throw new BusinessRuleException("The minutes (" + minutes + ") do not match the start and end times ("
                        + fromTimes + " minutes).");
            }
            minutes = fromTimes;
        }
        if (minutes != null && (minutes < 1 || minutes > 1440)) {
            throw new BusinessRuleException("The time worked must be between 1 and 1440 minutes.");
        }
        Long quantity = details.actualQuantityMilli();
        if (quantity != null && quantity <= 0) {
            throw new BusinessRuleException("The quantity must be more than zero.");
        }

        if (minutes == null && payService.primaryModelOn(worker.getId(), date).orElse(null) == PaymentModel.HOURLY) {
            throw new BusinessRuleException(worker.getName() + " is paid hourly. Enter the time actually worked.");
        }

        if (outcome == WorkOutcome.PARTIAL) {
            if (expectedMinutes == null && expectedQuantity == null) {
                if (note == null) {
                    throw new BusinessRuleException("There is no usual time or quantity to compare with, so add a note "
                            + "describing what was done.");
                }
            } else {
                boolean measured = (expectedMinutes != null && minutes != null) || (expectedQuantity != null && quantity != null);
                if (!measured) {
                    throw new BusinessRuleException(expectedMinutes != null
                            ? "Enter the time actually worked, to compare with the usual " + expectedMinutes + " minutes."
                            : "Enter the quantity actually delivered.");
                }
            }
            if (expectedMinutes != null && minutes != null && minutes >= expectedMinutes) {
                throw new BusinessRuleException(minutes + " minutes is not less than the usual " + expectedMinutes
                        + " minutes. Record it as done instead of partly done.");
            }
            if (expectedQuantity != null && quantity != null && quantity >= expectedQuantity) {
                throw new BusinessRuleException("That is not less than the expected quantity. Record it as done instead.");
            }
        }
        if (outcome == WorkOutcome.COMPLETED && expectedQuantity != null) {
            if (quantity == null) {
                throw new BusinessRuleException("Enter the quantity actually delivered.");
            }
            if (quantity < expectedQuantity) {
                throw new BusinessRuleException("That is less than the expected quantity. Record it as partly done.");
            }
        }
        return new Validated(minutes, note);
    }

    private ScheduleEntry requireExpectedEntry(Worker worker, LocalDate date, Long scheduleEntryId) {
        if (scheduleEntryId == null) {
            throw new BusinessRuleException("Choose the scheduled activity.");
        }
        requireEmployed(worker, date);
        ScheduleEntry entry = scheduleEntryRepository.findById(scheduleEntryId)
                .orElseThrow(() -> new BusinessRuleException("Scheduled activity #" + scheduleEntryId + " was not found."));
        boolean expected = scheduleService.entriesOn(worker.getId(), date).stream()
                .anyMatch(candidate -> candidate.getId().equals(entry.getId()));
        if (!expected) {
            throw new BusinessRuleException(describe(entry) + " is not on " + worker.getName() + "'s schedule for "
                    + date + " (" + date.getDayOfWeek() + ").");
        }
        return entry;
    }

    private void requireEmployed(Worker worker, LocalDate date) {
        if (!workerService.isEmployedOn(worker.getId(), date)) {
            throw new BusinessRuleException(worker.getName() + " was not employed on " + date + ".");
        }
    }

    private void requireNotLocked(Worker worker, LocalDate date) {
        if (earningsGuard.hasEarningsOn(worker.getId(), date)) {
            throw new BusinessRuleException("Earnings have already been worked out for " + worker.getName() + " on " + date
                    + ", so work for that day cannot be changed here.");
        }
    }

    private List<String> duplicateWarnings(Worker worker, LocalDate date, Task task, Shift shift, LocalTime start,
                                           Long excludeRecordId) {
        List<String> warnings = new ArrayList<>();
        Long taskId = task == null ? null : task.getId();
        Long shiftId = shift == null ? null : shift.getId();
        boolean similarRecorded = workRecordRepository.findCurrent(worker.getId(), date).stream()
                .filter(r -> !r.isScheduled() && !r.getId().equals(excludeRecordId))
                .anyMatch(r -> Objects.equals(id(r.getTask()), taskId) && Objects.equals(id(r.getShift()), shiftId)
                        && Objects.equals(r.getActualStart(), start));
        if (similarRecorded) {
            warnings.add("The same unscheduled work is already recorded for " + worker.getName() + " on " + date
                    + ". Save again only if it really happened twice.");
        }
        boolean matchesSchedule = scheduleService.entriesOn(worker.getId(), date).stream()
                .anyMatch(e -> Objects.equals(id(e.getTask()), taskId) && Objects.equals(id(e.getShift()), shiftId));
        if (matchesSchedule) {
            warnings.add("This matches " + worker.getName() + "'s scheduled activity on " + date
                    + ". Record it against the schedule instead, unless it was done an extra time.");
        }
        return warnings;
    }

    // ---------------------------------------------------------------- helpers

    private WorkRecord newRecord(Worker worker, LocalDate date, ScheduleEntry entry, Task task, Shift shift,
                                 WorkDetails details, Validated valid, Integer expectedMinutes, Long expectedQuantity,
                                 WorkRecord replaces) {
        return new WorkRecord(worker, date, entry, task, shift, details.outcome(), details.absenceReason(),
                expectedMinutes, expectedQuantity, details.actualStart(), details.actualEnd(), valid.actualMinutes(),
                details.actualQuantityMilli(), valid.note(), clock.instant(), replaces);
    }

    private WorkRecord getRecord(Long recordId) {
        if (recordId == null) {
            throw new BusinessRuleException("Choose a work record.");
        }
        return workRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessRuleException("Work record #" + recordId + " was not found."));
    }

    private static void requireDate(LocalDate date) {
        if (date == null) {
            throw new BusinessRuleException("Choose a date.");
        }
    }

    private static String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(message);
        }
        return reason.strip();
    }

    private static boolean sameActivity(WorkRecord a, WorkRecord b) {
        return Objects.equals(id(a.getTask()), id(b.getTask())) && Objects.equals(id(a.getShift()), id(b.getShift()))
                && Objects.equals(a.getActualStart(), b.getActualStart());
    }

    private static Long id(Task task) {
        return task == null ? null : task.getId();
    }

    private static Long id(Shift shift) {
        return shift == null ? null : shift.getId();
    }

    static String describe(ScheduleEntry entry) {
        return describe(entry.getTask(), entry.getShift(), entry.effectiveStartTime());
    }

    static String describe(WorkRecord record) {
        return describe(record.getTask(), record.getShift(),
                record.isScheduled() ? record.getScheduleEntry().effectiveStartTime() : record.getActualStart());
    }

    private static String describe(Task task, Shift shift, LocalTime start) {
        List<String> parts = new ArrayList<>();
        if (task != null) {
            parts.add(task.getName());
        }
        if (shift != null) {
            parts.add(shift.getName() + " shift");
        }
        String text = String.join(", ", parts);
        return start == null ? text : text + " at " + start;
    }

    private static String summary(WorkRecord r) {
        return "date=" + r.getWorkDate() + "; activity=" + describe(r) + "; outcome=" + r.getOutcome()
                + (r.getAbsenceReason() == null ? "" : "; reason=" + r.getAbsenceReason())
                + (r.getActualMinutes() == null ? "" : "; minutes=" + r.getActualMinutes())
                + (r.getActualQuantityMilli() == null ? "" : "; quantityMilli=" + r.getActualQuantityMilli());
    }
}
