package com.homeexpense.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.repository.WorkRecordRepository;
import com.homeexpense.repository.WorkScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective-dated weekly schedules.
 *
 * <p>A schedule change adds a new version; existing versions keep their entries. Schedules
 * never change pay rates: an agreed monthly salary stays as it is when the schedule changes.
 */
@Service
@Transactional
public class ScheduleService {

    static final String ENTITY_SCHEDULE = "WORK_SCHEDULE";

    private final WorkScheduleRepository workScheduleRepository;
    private final WorkRecordRepository workRecordRepository;
    private final WorkerService workerService;
    private final CatalogService catalogService;
    private final EarningsGuard earningsGuard;
    private final AuditService auditService;

    public ScheduleService(WorkScheduleRepository workScheduleRepository, WorkRecordRepository workRecordRepository,
                           WorkerService workerService, CatalogService catalogService, EarningsGuard earningsGuard,
                           AuditService auditService) {
        this.workScheduleRepository = workScheduleRepository;
        this.workRecordRepository = workRecordRepository;
        this.workerService = workerService;
        this.catalogService = catalogService;
        this.earningsGuard = earningsGuard;
        this.auditService = auditService;
    }

    /**
     * Adds a schedule version starting on {@code effectiveFrom}. Past or future dates are
     * allowed when no version starts on the same date and no earnings would be affected.
     * An empty entry list means "no regular schedule" from that date.
     */
    public WorkSchedule addScheduleVersion(Long workerId, LocalDate effectiveFrom, List<NewScheduleEntry> entries, String note) {
        Worker worker = workerService.getWorker(workerId);
        if (effectiveFrom == null) {
            throw new BusinessRuleException("Enter the date the schedule starts.");
        }
        List<NewScheduleEntry> requested = entries == null ? List.of() : entries;
        validateNoDuplicateEntries(requested);

        List<WorkSchedule> versions = workScheduleRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId);
        EffectivePeriods.Placement<WorkSchedule> placement =
                EffectivePeriods.place(versions, effectiveFrom, "A schedule version for " + worker.getName());
        if (earningsGuard.hasEarningsOnOrAfter(workerId, effectiveFrom)) {
            throw new BusinessRuleException(worker.getName() + " has earnings recorded on or after " + effectiveFrom
                    + ". Changing the schedule from that date is not supported yet; choose a later date.");
        }
        // Recorded work answers the schedule that applied on its date; do not change that schedule under it.
        LocalDate lastAffected = placement.newVersionEnd() == null ? EffectivePeriods.FAR_FUTURE : placement.newVersionEnd();
        List<WorkRecord> affected = workRecordRepository.findCurrentBetween(workerId, effectiveFrom, lastAffected);
        if (!affected.isEmpty()) {
            LocalDate last = affected.getLast().getWorkDate();
            throw new BusinessRuleException("Work is already recorded for " + worker.getName() + " between "
                    + affected.getFirst().getWorkDate() + " and " + last + ". A schedule change from " + effectiveFrom
                    + " would change what was expected on those days. Start the new schedule after " + last
                    + ", or void those records first.");
        }

        WorkSchedule schedule = new WorkSchedule(worker, effectiveFrom, placement.newVersionEnd(),
                WorkerService.blankToNull(note));
        for (NewScheduleEntry entry : requested) {
            buildEntry(schedule, entry);
        }

        // Shorten first so the overlap trigger never sees two versions covering the same day.
        WorkSchedule shortened = placement.versionToShorten();
        if (shortened != null) {
            shortened.endOn(effectiveFrom.minusDays(1));
            workScheduleRepository.saveAndFlush(shortened);
            auditService.record(workerId, ENTITY_SCHEDULE, shortened.getId(), "END",
                    "effectiveTo=" + shortened.getEffectiveTo());
        }
        WorkSchedule saved = workScheduleRepository.saveAndFlush(schedule);
        auditService.record(workerId, ENTITY_SCHEDULE, saved.getId(), "CREATE",
                "effectiveFrom=" + effectiveFrom + "; effectiveTo=" + saved.getEffectiveTo()
                        + "; entries=" + saved.getEntries().size());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<WorkSchedule> scheduleOn(Long workerId, LocalDate date) {
        return workScheduleRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId).stream()
                .filter(version -> version.covers(date))
                .findFirst();
    }

    /** The scheduled entries for a date; empty if the worker is not employed on that date. */
    @Transactional(readOnly = true)
    public List<ScheduleEntry> entriesOn(Long workerId, LocalDate date) {
        if (!workerService.isEmployedOn(workerId, date)) {
            return List.of();
        }
        return scheduleOn(workerId, date)
                .map(schedule -> schedule.entriesOn(date.getDayOfWeek()))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<WorkSchedule> history(Long workerId) {
        return workScheduleRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId);
    }

    private void buildEntry(WorkSchedule schedule, NewScheduleEntry entry) {
        if (entry.dayOfWeek() == null) {
            throw new BusinessRuleException("Each schedule entry needs a day of the week.");
        }
        if (entry.taskId() == null && entry.shiftId() == null) {
            throw new BusinessRuleException("Each schedule entry needs a task, a shift, or both.");
        }
        Task task = entry.taskId() == null ? null : catalogService.getActiveTask(entry.taskId());
        Shift shift = entry.shiftId() == null ? null : catalogService.getActiveShift(entry.shiftId());

        boolean hasStart = entry.startTime() != null;
        boolean hasEnd = entry.endTime() != null;
        if (hasStart != hasEnd) {
            throw new BusinessRuleException("Enter both a start and an end time, or neither.");
        }
        if (hasStart) {
            CatalogService.validateTimes(entry.startTime(), entry.endTime(), "The schedule entry");
        }
        if (entry.durationMinutes() != null) {
            if (hasStart || shift != null) {
                throw new BusinessRuleException("A duration can only be given for entries without times or a shift; "
                        + "otherwise the time is worked out from the start and end.");
            }
            if (entry.durationMinutes() < 1 || entry.durationMinutes() > 1440) {
                throw new BusinessRuleException("The duration must be between 1 and 1440 minutes.");
            }
        }
        if (entry.expectedQuantityMilli() != null) {
            if (task == null) {
                throw new BusinessRuleException("An expected quantity needs a task, such as milk delivery.");
            }
            if (entry.expectedQuantityMilli() <= 0) {
                throw new BusinessRuleException("The expected quantity must be more than zero.");
            }
        }
        new ScheduleEntry(schedule, entry.dayOfWeek(), task, shift, entry.startTime(), entry.endTime(),
                entry.durationMinutes(), entry.expectedQuantityMilli());
    }

    private static void validateNoDuplicateEntries(List<NewScheduleEntry> entries) {
        record Identity(Object day, Long taskId, Long shiftId, Object start) {
        }
        Set<Identity> seen = new HashSet<>();
        for (NewScheduleEntry entry : entries) {
            Objects.requireNonNull(entry, "schedule entry");
            Identity identity = new Identity(entry.dayOfWeek(), entry.taskId(), entry.shiftId(), entry.startTime());
            if (!seen.add(identity)) {
                throw new BusinessRuleException("The same activity is listed twice on " + entry.dayOfWeek()
                        + ". Different tasks, shifts or start times on one day are fine.");
            }
        }
    }
}
