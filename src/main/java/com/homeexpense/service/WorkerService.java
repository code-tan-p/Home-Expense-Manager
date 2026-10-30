package com.homeexpense.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.homeexpense.dto.CreateWorkerResult;
import com.homeexpense.dto.NewWorker;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.EmploymentPeriod;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.model.WorkerType;
import com.homeexpense.repository.EmploymentPeriodRepository;
import com.homeexpense.repository.WorkRecordRepository;
import com.homeexpense.repository.WorkerRepository;
import com.homeexpense.repository.WorkerTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workers, worker types and employment periods.
 *
 * <p>Deactivating a worker closes their open employment period; reactivating adds a new
 * period to the same worker. Schedules, rates and other history are never deleted or
 * copied, so they carry over unless explicitly changed.
 */
@Service
@Transactional
public class WorkerService {

    static final String ENTITY_WORKER = "WORKER";
    static final String ENTITY_WORKER_TYPE = "WORKER_TYPE";

    private final WorkerRepository workerRepository;
    private final WorkerTypeRepository workerTypeRepository;
    private final EmploymentPeriodRepository employmentPeriodRepository;
    private final WorkRecordRepository workRecordRepository;
    private final EarningsGuard earningsGuard;
    private final AuditService auditService;
    private final Clock clock;

    public WorkerService(WorkerRepository workerRepository, WorkerTypeRepository workerTypeRepository,
                         EmploymentPeriodRepository employmentPeriodRepository, WorkRecordRepository workRecordRepository,
                         EarningsGuard earningsGuard, AuditService auditService, Clock clock) {
        this.workerRepository = workerRepository;
        this.workerTypeRepository = workerTypeRepository;
        this.employmentPeriodRepository = employmentPeriodRepository;
        this.workRecordRepository = workRecordRepository;
        this.earningsGuard = earningsGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    public WorkerType addWorkerType(String name) {
        String trimmed = requireText(name, "Enter a name for the worker type.", 50);
        if (workerTypeRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new BusinessRuleException("A worker type named '" + trimmed + "' already exists.");
        }
        WorkerType type = workerTypeRepository.save(new WorkerType(trimmed));
        auditService.record(null, ENTITY_WORKER_TYPE, type.getId(), "CREATE", "name=" + trimmed);
        return type;
    }

    @Transactional(readOnly = true)
    public List<WorkerType> listWorkerTypes() {
        return workerTypeRepository.findAllByOrderByNameAsc();
    }

    public CreateWorkerResult createWorker(NewWorker request) {
        String name = requireText(request.name(), "Enter the worker's name.", 100);
        if (request.workerTypeId() == null) {
            throw new BusinessRuleException("Choose a worker type.");
        }
        WorkerType type = workerTypeRepository.findById(request.workerTypeId())
                .orElseThrow(() -> new BusinessRuleException("The chosen worker type does not exist."));
        if (request.employmentStart() == null) {
            throw new BusinessRuleException("Enter the date " + name + " started working.");
        }

        List<String> warnings = new ArrayList<>();
        for (Worker existing : workerRepository.findByNameIgnoreCase(name)) {
            warnings.add("Another worker named '" + existing.getName() + "' already exists (worker #"
                    + existing.getId() + "). Check that this is not a duplicate entry.");
        }

        Worker worker = workerRepository.save(new Worker(name, type, blankToNull(request.phone()),
                blankToNull(request.notes()), clock.instant()));
        EmploymentPeriod period = employmentPeriodRepository.save(new EmploymentPeriod(worker, request.employmentStart()));
        auditService.record(worker.getId(), ENTITY_WORKER, worker.getId(), "CREATE",
                "name=" + name + "; type=" + type.getName() + "; employmentStart=" + request.employmentStart());
        return new CreateWorkerResult(worker, period, List.copyOf(warnings));
    }

    /** Changes name, phone and notes. The change is kept in the audit history. */
    public Worker updateDetails(Long workerId, String name, String phone, String notes) {
        Worker worker = getWorker(workerId);
        String cleanName = requireText(name, "Enter the worker's name.", 100);
        String before = "name=" + worker.getName() + "; phone=" + worker.getPhone() + "; notes=" + worker.getNotes();
        worker.updateDetails(cleanName, blankToNull(phone), blankToNull(notes));
        Worker saved = workerRepository.save(worker);
        auditService.record(workerId, ENTITY_WORKER, workerId, "UPDATE", "was: " + before + "; now: name=" + cleanName
                + "; phone=" + saved.getPhone() + "; notes=" + saved.getNotes());
        return saved;
    }

    /** Ends the worker's current employment period on {@code lastWorkingDay} (inclusive). */
    public EmploymentPeriod deactivate(Long workerId, LocalDate lastWorkingDay, String reason) {
        Worker worker = getWorker(workerId);
        if (lastWorkingDay == null) {
            throw new BusinessRuleException("Enter " + worker.getName() + "'s last working day.");
        }
        EmploymentPeriod open = openPeriod(workerId);
        if (open == null) {
            throw new BusinessRuleException(worker.getName() + " is not currently active.");
        }
        if (lastWorkingDay.isBefore(open.getStartDate())) {
            throw new BusinessRuleException("The last working day cannot be before " + worker.getName()
                    + " started (" + open.getStartDate() + ").");
        }
        if (earningsGuard.hasEarningsOnOrAfter(workerId, lastWorkingDay.plusDays(1))) {
            throw new BusinessRuleException(worker.getName() + " has earnings recorded after " + lastWorkingDay
                    + ". Choose a later last working day, or correct those earnings first.");
        }
        List<WorkRecord> laterWork = workRecordRepository.findCurrentBetween(workerId, lastWorkingDay.plusDays(1),
                EffectivePeriods.FAR_FUTURE);
        if (!laterWork.isEmpty()) {
            throw new BusinessRuleException("Work is recorded for " + worker.getName() + " on "
                    + laterWork.getLast().getWorkDate() + ", after " + lastWorkingDay
                    + ". Choose a later last working day, or void those records first.");
        }
        open.close(lastWorkingDay, blankToNull(reason));
        employmentPeriodRepository.saveAndFlush(open);
        auditService.record(workerId, ENTITY_WORKER, workerId, "DEACTIVATE",
                "lastWorkingDay=" + lastWorkingDay + (reason == null || reason.isBlank() ? "" : "; reason=" + reason.strip()));
        return open;
    }

    /** Starts a new employment period for the same worker. Existing history is reused, not copied. */
    public EmploymentPeriod reactivate(Long workerId, LocalDate startDate) {
        Worker worker = getWorker(workerId);
        if (startDate == null) {
            throw new BusinessRuleException("Enter the date " + worker.getName() + " starts working again.");
        }
        if (openPeriod(workerId) != null) {
            throw new BusinessRuleException(worker.getName() + " is already active.");
        }
        EmploymentPeriod latest = employmentPeriodRepository.findByWorkerIdOrderByStartDateAsc(workerId).stream()
                .max(Comparator.comparing(EmploymentPeriod::getStartDate))
                .orElse(null);
        if (latest != null && !startDate.isAfter(latest.getEndDate())) {
            throw new BusinessRuleException(worker.getName() + "'s previous employment ended on " + latest.getEndDate()
                    + ". The new start date must be after that.");
        }
        EmploymentPeriod period = employmentPeriodRepository.saveAndFlush(new EmploymentPeriod(worker, startDate));
        auditService.record(workerId, ENTITY_WORKER, workerId, "REACTIVATE", "startDate=" + startDate);
        return period;
    }

    @Transactional(readOnly = true)
    public Worker getWorker(Long workerId) {
        if (workerId == null) {
            throw new BusinessRuleException("Choose a worker.");
        }
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new BusinessRuleException("Worker #" + workerId + " was not found."));
    }

    @Transactional(readOnly = true)
    public boolean isEmployedOn(Long workerId, LocalDate date) {
        return !employmentPeriodRepository.findCovering(workerId, date).isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean isActiveToday(Long workerId) {
        return isEmployedOn(workerId, LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public List<EmploymentPeriod> employmentHistory(Long workerId) {
        return employmentPeriodRepository.findByWorkerIdOrderByStartDateAsc(workerId);
    }

    @Transactional(readOnly = true)
    public List<Worker> listAllWorkers() {
        return workerRepository.findAllByOrderByNameAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<Worker> workersEmployedOn(LocalDate date) {
        List<Long> ids = employmentPeriodRepository.findWorkerIdsEmployedOn(date);
        return workerRepository.findAllByOrderByNameAscIdAsc().stream()
                .filter(worker -> ids.contains(worker.getId()))
                .toList();
    }

    private EmploymentPeriod openPeriod(Long workerId) {
        return employmentPeriodRepository.findByWorkerIdOrderByStartDateAsc(workerId).stream()
                .filter(EmploymentPeriod::isOpen)
                .findFirst()
                .orElse(null);
    }

    static String requireText(String value, String missingMessage, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(missingMessage);
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new BusinessRuleException("'" + trimmed + "' is too long. Use at most " + maxLength + " characters.");
        }
        return trimmed;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
