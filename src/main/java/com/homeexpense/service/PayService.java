package com.homeexpense.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.ResolvedRate;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.EffectiveDated;
import com.homeexpense.model.PayArrangement;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.Worker;
import com.homeexpense.money.Money;
import com.homeexpense.repository.PayArrangementRepository;
import com.homeexpense.repository.PayRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment arrangements (the one primary payment model) and effective-dated pay rates.
 *
 * <p>Rate changes add a new version and leave earlier versions' amounts untouched, so
 * historical earnings can keep the rate that applied when the work happened. Pay changes
 * never modify schedules.
 */
@Service
@Transactional
public class PayService {

    static final String ENTITY_ARRANGEMENT = "PAY_ARRANGEMENT";
    static final String ENTITY_RATE = "PAY_RATE";

    private final PayArrangementRepository arrangementRepository;
    private final PayRateRepository rateRepository;
    private final WorkerService workerService;
    private final CatalogService catalogService;
    private final EarningsGuard earningsGuard;
    private final AuditService auditService;

    public PayService(PayArrangementRepository arrangementRepository, PayRateRepository rateRepository,
                      WorkerService workerService, CatalogService catalogService, EarningsGuard earningsGuard,
                      AuditService auditService) {
        this.arrangementRepository = arrangementRepository;
        this.rateRepository = rateRepository;
        this.workerService = workerService;
        this.catalogService = catalogService;
        this.earningsGuard = earningsGuard;
        this.auditService = auditService;
    }

    /** Sets the worker's primary payment model from {@code effectiveFrom}. */
    public PayArrangement setPrimaryModel(Long workerId, PaymentModel model, LocalDate effectiveFrom) {
        Worker worker = workerService.getWorker(workerId);
        if (model == null) {
            throw new BusinessRuleException("Choose how " + worker.getName() + " is paid.");
        }
        if (effectiveFrom == null) {
            throw new BusinessRuleException("Enter the date this payment arrangement starts.");
        }
        List<PayArrangement> versions = arrangementRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId);
        EffectivePeriods.Placement<PayArrangement> placement =
                EffectivePeriods.place(versions, effectiveFrom, "A payment arrangement for " + worker.getName());
        LocalDate effectiveTo = placement.newVersionEnd();

        for (RateType extra : List.of(RateType.EXTRA_TASK, RateType.EXTRA_HOUR, RateType.EXTRA_DAY)) {
            if (extra.conflictingPrimaryModel() != model) {
                continue;
            }
            boolean conflict = rateRepository.findByWorkerIdAndRateTypeOrderByEffectiveFromAsc(workerId, extra).stream()
                    .anyMatch(rate -> rate.overlaps(effectiveFrom, effectiveTo));
            if (conflict) {
                throw new BusinessRuleException("Cannot switch " + worker.getName() + " to " + describe(model)
                        + " from " + effectiveFrom + ": " + describe(extra) + " rates are in effect during that time and "
                        + "the same work would be paid twice. End those rates first.");
            }
        }
        requireNoEarningsFrom(worker, effectiveFrom, "the payment arrangement");

        PayArrangement shortened = placement.versionToShorten();
        if (shortened != null) {
            shortened.endOn(effectiveFrom.minusDays(1));
            arrangementRepository.saveAndFlush(shortened);
            auditService.record(workerId, ENTITY_ARRANGEMENT, shortened.getId(), "END",
                    "effectiveTo=" + shortened.getEffectiveTo());
        }
        PayArrangement saved = arrangementRepository.saveAndFlush(
                new PayArrangement(worker, model, effectiveFrom, effectiveTo));
        auditService.record(workerId, ENTITY_ARRANGEMENT, saved.getId(), "CREATE",
                "primaryModel=" + model + "; effectiveFrom=" + effectiveFrom + "; effectiveTo=" + effectiveTo);
        return saved;
    }

    /** Adds a rate version. The previous version of the same rate keeps its amount and ends the day before. */
    public PayRate addRate(NewPayRate request) {
        Worker worker = workerService.getWorker(request.workerId());
        RateType type = request.rateType();
        if (type == null) {
            throw new BusinessRuleException("Choose what this rate pays for.");
        }
        if (request.effectiveFrom() == null) {
            throw new BusinessRuleException("Enter the date this rate starts.");
        }
        long amountPaise = Money.parsePositiveRupees(request.amountRupees());

        if (request.taskId() != null && !type.allowsTask()) {
            throw new BusinessRuleException(capitalize(describe(type)) + " rates cannot be for a specific task.");
        }
        if (request.taskId() == null && type.requiresTask()) {
            throw new BusinessRuleException("Choose the task this " + describe(type) + " rate pays for.");
        }
        if (request.shiftId() != null && !type.allowsShift()) {
            throw new BusinessRuleException(capitalize(describe(type)) + " rates cannot be for a specific shift.");
        }
        String unit = WorkerService.blankToNull(request.unitLabel());
        if (type.requiresUnit() && unit == null) {
            throw new BusinessRuleException("Enter the unit for this per-unit rate, for example litre.");
        }
        if (!type.requiresUnit() && unit != null) {
            throw new BusinessRuleException("Only per-unit rates have a unit.");
        }
        if (unit != null && unit.length() > 20) {
            throw new BusinessRuleException("The unit name is too long. Use at most 20 characters.");
        }
        Task task = request.taskId() == null ? null : catalogService.getActiveTask(request.taskId());
        Shift shift = request.shiftId() == null ? null : catalogService.getActiveShift(request.shiftId());

        LocalDate effectiveFrom = request.effectiveFrom();
        List<PayRate> sameKey = rateRepository.findByWorkerIdAndRateTypeOrderByEffectiveFromAsc(worker.getId(), type).stream()
                .filter(rate -> rate.hasSameKey(type, request.taskId(), request.shiftId()))
                .toList();
        EffectivePeriods.Placement<PayRate> placement =
                EffectivePeriods.place(sameKey, effectiveFrom, "This rate for " + worker.getName());
        LocalDate effectiveTo = placement.newVersionEnd();

        PaymentModel conflicting = type.conflictingPrimaryModel();
        if (conflicting != null) {
            boolean conflict = arrangementRepository.findByWorkerIdOrderByEffectiveFromAsc(worker.getId()).stream()
                    .anyMatch(arrangement -> arrangement.getPrimaryModel() == conflicting
                            && arrangement.overlaps(effectiveFrom, effectiveTo));
            if (conflict) {
                throw new BusinessRuleException(worker.getName() + " is paid " + describe(conflicting)
                        + " during that time, so a " + describe(type) + " rate would pay the same work twice. "
                        + "Use a " + describe(conflicting) + " rate instead.");
            }
        }
        requireNoEarningsFrom(worker, effectiveFrom, "this rate");

        PayRate shortened = placement.versionToShorten();
        if (shortened != null) {
            shortened.endOn(effectiveFrom.minusDays(1));
            rateRepository.saveAndFlush(shortened);
            auditService.record(worker.getId(), ENTITY_RATE, shortened.getId(), "END",
                    "effectiveTo=" + shortened.getEffectiveTo());
        }
        PayRate saved = rateRepository.saveAndFlush(new PayRate(worker, type, task, shift, unit, amountPaise,
                effectiveFrom, effectiveTo, WorkerService.blankToNull(request.note())));
        auditService.record(worker.getId(), ENTITY_RATE, saved.getId(), "CREATE",
                "type=" + type + "; task=" + request.taskId() + "; shift=" + request.shiftId()
                        + "; amount=" + Money.format(amountPaise) + (unit == null ? "" : " per " + unit)
                        + "; effectiveFrom=" + effectiveFrom + "; effectiveTo=" + effectiveTo);
        return saved;
    }

    /**
     * Corrects a mistakenly entered amount in place. Only allowed while no earnings use the
     * rate; afterwards the original must be kept and a new effective-dated version added.
     */
    public PayRate correctRateAmount(Long rateId, String newAmountRupees, String reason) {
        PayRate rate = getRate(rateId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Enter a reason for the correction.");
        }
        long newAmount = Money.parsePositiveRupees(newAmountRupees);
        if (newAmount == rate.getAmountPaise()) {
            throw new BusinessRuleException("The rate is already " + Money.format(newAmount) + ".");
        }
        if (earningsGuard.isRateUsedByEarnings(rateId)) {
            throw new BusinessRuleException("Earnings have already been calculated with this rate, so it cannot be "
                    + "corrected in place. Add a new rate from a later date, or use a correction for the affected earnings.");
        }
        long oldAmount = rate.getAmountPaise();
        rate.correctAmount(newAmount);
        rateRepository.saveAndFlush(rate);
        auditService.record(rate.getWorker().getId(), ENTITY_RATE, rateId, "CORRECT_AMOUNT",
                "from=" + Money.format(oldAmount) + "; to=" + Money.format(newAmount) + "; reason=" + reason.strip());
        return rate;
    }

    /** Ends a rate on {@code lastDay} (inclusive), for example when an extra-work agreement stops. */
    public PayRate endRate(Long rateId, LocalDate lastDay) {
        PayRate rate = getRate(rateId);
        Worker worker = rate.getWorker();
        if (lastDay == null) {
            throw new BusinessRuleException("Enter the last day this rate applies.");
        }
        if (lastDay.isBefore(rate.getEffectiveFrom())) {
            throw new BusinessRuleException("The rate starts on " + rate.getEffectiveFrom()
                    + "; it cannot end before that.");
        }
        if (rate.getEffectiveTo() != null && !lastDay.isBefore(rate.getEffectiveTo())) {
            throw new BusinessRuleException("The rate already ends on " + rate.getEffectiveTo() + ".");
        }
        requireNoEarningsFrom(worker, lastDay.plusDays(1), "this rate");
        rate.endOn(lastDay);
        rateRepository.saveAndFlush(rate);
        auditService.record(worker.getId(), ENTITY_RATE, rateId, "END", "effectiveTo=" + lastDay);
        return rate;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentModel> primaryModelOn(Long workerId, LocalDate date) {
        return arrangementRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId).stream()
                .filter(arrangement -> arrangement.covers(date))
                .map(PayArrangement::getPrimaryModel)
                .findFirst();
    }

    /**
     * The rate of the given type for a worker, date, task and shift. A rate specific to the
     * task (per-task, per-unit, extra-task) or shift (per-shift) wins; otherwise the standard
     * rate applies. Overlap rules guarantee at most one candidate of each kind.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedRate> rateFor(Long workerId, RateType type, LocalDate date, Long taskId, Long shiftId) {
        List<PayRate> inEffect = rateRepository.findByWorkerIdAndRateTypeOrderByEffectiveFromAsc(workerId, type).stream()
                .filter(rate -> rate.covers(date))
                .toList();
        Long specificTask = type.allowsTask() ? taskId : null;
        Long specificShift = type.allowsShift() ? shiftId : null;
        if (specificTask != null || specificShift != null) {
            Optional<PayRate> specific = single(inEffect.stream()
                    .filter(rate -> rate.hasSameKey(type, specificTask, specificShift)).toList());
            if (specific.isPresent()) {
                return Optional.of(new ResolvedRate(specific.get(), true));
            }
        }
        if (type.requiresTask()) {
            return Optional.empty();
        }
        return single(inEffect.stream().filter(PayRate::isStandard).toList())
                .map(rate -> new ResolvedRate(rate, false));
    }

    /** The primary rate under whichever payment model applies on that date. */
    @Transactional(readOnly = true)
    public Optional<ResolvedRate> primaryRateFor(Long workerId, LocalDate date, Long taskId, Long shiftId) {
        return primaryModelOn(workerId, date)
                .flatMap(model -> rateFor(workerId, model.rateType(), date, taskId, shiftId));
    }

    @Transactional(readOnly = true)
    public List<PayRate> rateHistory(Long workerId) {
        return rateRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId);
    }

    @Transactional(readOnly = true)
    public List<PayArrangement> arrangementHistory(Long workerId) {
        return arrangementRepository.findByWorkerIdOrderByEffectiveFromAsc(workerId);
    }

    private PayRate getRate(Long rateId) {
        if (rateId == null) {
            throw new BusinessRuleException("Choose a rate.");
        }
        return rateRepository.findById(rateId)
                .orElseThrow(() -> new BusinessRuleException("Rate #" + rateId + " was not found."));
    }

    private void requireNoEarningsFrom(Worker worker, LocalDate date, String what) {
        if (earningsGuard.hasEarningsOnOrAfter(worker.getId(), date)) {
            throw new BusinessRuleException(worker.getName() + " has earnings recorded on or after " + date
                    + ". Changing " + what + " from that date is not supported yet; choose a later date.");
        }
    }

    private static <T extends EffectiveDated> Optional<T> single(List<T> candidates) {
        if (candidates.size() > 1) {
            throw new IllegalStateException("Overlapping rate versions found; database constraints should prevent this");
        }
        return candidates.stream().findFirst();
    }

    static String describe(PaymentModel model) {
        return switch (model) {
            case MONTHLY_SALARY -> "monthly salary";
            case PER_TASK -> "per task";
            case HOURLY -> "hourly";
            case PER_UNIT -> "per unit";
            case PER_DAY -> "per day";
            case PER_SHIFT -> "per shift";
        };
    }

    static String describe(RateType type) {
        return switch (type) {
            case EXTRA_TASK -> "extra-task";
            case EXTRA_HOUR -> "extra-hour";
            case EXTRA_DAY -> "extra-day";
            default -> describe(PaymentModel.valueOf(type.name()));
        };
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
