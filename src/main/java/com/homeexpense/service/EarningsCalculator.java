package com.homeexpense.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.homeexpense.dto.ResolvedRate;
import com.homeexpense.dto.UnrecordedActivity;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningBasis;
import com.homeexpense.model.earnings.EarningLineType;
import com.homeexpense.model.earnings.EarningReview;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.model.earnings.TreatmentType;
import com.homeexpense.model.earnings.UnrecordedConfirmation;
import com.homeexpense.money.Fraction;
import com.homeexpense.money.Money;
import com.homeexpense.repository.EarningReviewRepository;
import com.homeexpense.repository.UnrecordedConfirmationRepository;
import com.homeexpense.repository.WorkRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out a worker's earnings for one calendar month. It only reads; {@link EarningsService}
 * saves the result.
 *
 * <p>Rules (Phase 4 decisions):
 * <ul>
 *   <li>Only current work records and explicitly payable events (the salary agreement and
 *   household holidays) earn anything. Unrecorded activities never earn and block finalizing
 *   until recorded or explicitly confirmed.</li>
 *   <li>Every current record gets exactly one treatment, so nothing is paid twice.</li>
 *   <li>Amounts are exact fractions of paise, rounded HALF_UP once per line. Lines are never
 *   negative; unpaid salary time reduces paid day-units instead.</li>
 *   <li>Salary is reduced only by approved leave, unauthorized absence with an APPROVED
 *   decision, and accepted/overridden partial-work reductions. Other models earn nothing for
 *   absences, whatever the decision.</li>
 *   <li>Extra work: on a regular day off the extra-day rate first (never for per-unit), then
 *   the task's extra-task rate, then the primary rate, then the extra-hour rate. Per-unit work
 *   with a quantity always earns quantity x unit rate. Per-day workers earn at most one day
 *   rate per date.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class EarningsCalculator {

    public static final int ENGINE_VERSION = 1;

    private final WorkerService workerService;
    private final ScheduleService scheduleService;
    private final PayService payService;
    private final WorkRecordRepository workRecordRepository;
    private final EarningReviewRepository reviewRepository;
    private final UnrecordedConfirmationRepository confirmationRepository;

    public EarningsCalculator(WorkerService workerService, ScheduleService scheduleService, PayService payService,
                              WorkRecordRepository workRecordRepository, EarningReviewRepository reviewRepository,
                              UnrecordedConfirmationRepository confirmationRepository) {
        this.workerService = workerService;
        this.scheduleService = scheduleService;
        this.payService = payService;
        this.workRecordRepository = workRecordRepository;
        this.reviewRepository = reviewRepository;
        this.confirmationRepository = confirmationRepository;
    }

    // ------------------------------------------------------------------ result types

    /** A line to be saved. Amounts are in paise. */
    public static final class PlannedLine {
        final EarningLineType type;
        final EarningBasis basis;
        final LocalDate date;
        final WorkRecord record;
        final PayRate rate;
        final String unitLabel;
        final Long quantityMilli;
        final Integer minutes;
        final String paidDayUnits;
        final Integer scheduledDayUnits;
        final Fraction exactPaise;
        final Long suggestedAmountPaise;
        final Long amountPaise;
        final ReviewStatus reviewStatus;
        final String reviewKey;
        final String explanation;

        @SuppressWarnings("java:S107")
        PlannedLine(EarningLineType type, EarningBasis basis, LocalDate date, WorkRecord record, PayRate rate,
                    String unitLabel, Long quantityMilli, Integer minutes, String paidDayUnits, Integer scheduledDayUnits,
                    Fraction exactPaise, Long suggestedAmountPaise, Long amountPaise, ReviewStatus reviewStatus,
                    String reviewKey, String explanation) {
            this.type = type;
            this.basis = basis;
            this.date = date;
            this.record = record;
            this.rate = rate;
            this.unitLabel = unitLabel;
            this.quantityMilli = quantityMilli;
            this.minutes = minutes;
            this.paidDayUnits = paidDayUnits;
            this.scheduledDayUnits = scheduledDayUnits;
            this.exactPaise = exactPaise;
            this.suggestedAmountPaise = suggestedAmountPaise;
            this.amountPaise = amountPaise;
            this.reviewStatus = reviewStatus;
            this.reviewKey = reviewKey;
            this.explanation = explanation;
        }
    }

    /** A treatment to be saved; {@code line} or {@code salaryRate} identifies the paying line. */
    public static final class PlannedTreatment {
        final WorkRecord record;
        final TreatmentType type;
        PlannedLine line;
        final PayRate salaryRate;
        final String reviewKey;
        final ReviewStatus reviewStatus;
        final String suggestedValue;
        final String maxValue;
        final String appliedValue;
        final String detail;

        @SuppressWarnings("java:S107")
        PlannedTreatment(WorkRecord record, TreatmentType type, PlannedLine line, PayRate salaryRate, String reviewKey,
                         ReviewStatus reviewStatus, String suggestedValue, String maxValue, String appliedValue,
                         String detail) {
            this.record = record;
            this.type = type;
            this.line = line;
            this.salaryRate = salaryRate;
            this.reviewKey = reviewKey;
            this.reviewStatus = reviewStatus;
            this.suggestedValue = suggestedValue;
            this.maxValue = maxValue;
            this.appliedValue = appliedValue;
            this.detail = detail;
        }
    }

    public record Calculation(List<PlannedLine> lines, List<PlannedTreatment> treatments, List<String> warnings,
                              List<String> blockers, List<UnrecordedActivity> unrecorded, int needsReviewCount) {
    }

    // ------------------------------------------------------------------ calculation

    private final class Context {
        final Worker worker;
        final YearMonth month;
        final Map<String, EarningReview> reviews;
        final List<PlannedLine> lines = new ArrayList<>();
        final Map<Long, PlannedTreatment> treatments = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final Set<String> blockers = new LinkedHashSet<>();
        final List<UnrecordedActivity> unrecorded = new ArrayList<>();
        final Map<PayRate, Fraction> salaryUnits = new LinkedHashMap<>();
        boolean salaryDaysExist;
        int needsReview;

        Context(Worker worker, YearMonth month, Map<String, EarningReview> reviews) {
            this.worker = worker;
            this.month = month;
            this.reviews = reviews;
        }

        void treat(WorkRecord record, TreatmentType type, PlannedLine line, String detail) {
            treat(new PlannedTreatment(record, type, line, null, null, ReviewStatus.NONE, null, null, null, detail));
        }

        void treat(PlannedTreatment treatment) {
            PlannedTreatment previous = treatments.putIfAbsent(treatment.record.getId(), treatment);
            if (previous != null) {
                throw new IllegalStateException("Work record " + treatment.record.getId() + " would be treated twice");
            }
        }

        PlannedLine add(PlannedLine line) {
            lines.add(line);
            if (line.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                needsReview++;
            }
            return line;
        }
    }

    public Calculation calculate(Worker worker, YearMonth month) {
        Map<String, EarningReview> reviews = reviewRepository.findCurrentForWorker(worker.getId()).stream()
                .collect(Collectors.toMap(EarningReview::getReviewKey, review -> review));
        Context ctx = new Context(worker, month, reviews);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        Map<LocalDate, List<WorkRecord>> byDate = new TreeMap<>();
        for (WorkRecord record : workRecordRepository.findCurrentBetween(worker.getId(), start, end)) {
            byDate.computeIfAbsent(record.getWorkDate(), d -> new ArrayList<>()).add(record);
        }

        int scheduledDays = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!scheduledEntries(worker, d).isEmpty()) {
                scheduledDays++;
            }
        }
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            day(ctx, d, byDate.getOrDefault(d, List.of()));
        }
        salaryLines(ctx, scheduledDays);
        unrecordedCheck(ctx);

        if (ctx.needsReview > 0) {
            ctx.blockers.add(ctx.needsReview + (ctx.needsReview == 1 ? " amount needs" : " amounts need")
                    + " your review (accept the suggestion or enter an amount).");
        }
        return new Calculation(List.copyOf(ctx.lines), List.copyOf(ctx.treatments.values()),
                List.copyOf(ctx.warnings), List.copyOf(ctx.blockers), List.copyOf(ctx.unrecorded), ctx.needsReview);
    }

    /** The current set of unrecorded activities and the fingerprint a confirmation must match. */
    public List<UnrecordedActivity> unrecorded(Worker worker, YearMonth month) {
        return calculate(worker, month).unrecorded();
    }

    public static String fingerprint(List<UnrecordedActivity> activities) {
        String canonical = activities.stream().map(UnrecordedActivity::key).sorted().collect(Collectors.joining(";"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ScheduleEntry> scheduledEntries(Worker worker, LocalDate date) {
        return scheduleService.scheduleOn(worker.getId(), date)
                .map(schedule -> schedule.entriesOn(date.getDayOfWeek()))
                .orElse(List.of());
    }

    private Optional<ResolvedRate> rate(Context ctx, RateType type, LocalDate date, Long taskId, Long shiftId) {
        return payService.rateFor(ctx.worker.getId(), type, date, taskId, shiftId);
    }

    private void day(Context ctx, LocalDate d, List<WorkRecord> records) {
        boolean employed = workerService.isEmployedOn(ctx.worker.getId(), d);
        List<ScheduleEntry> entries = employed ? scheduledEntries(ctx.worker, d) : List.of();
        Optional<PaymentModel> model = payService.primaryModelOn(ctx.worker.getId(), d);

        Set<Long> recordedEntries = records.stream().filter(WorkRecord::isScheduled)
                .map(r -> r.getScheduleEntry().getId()).collect(Collectors.toSet());
        for (ScheduleEntry entry : entries) {
            if (!recordedEntries.contains(entry.getId())) {
                ctx.unrecorded.add(new UnrecordedActivity(d, entry, model.orElse(null),
                        d + " (" + d.getDayOfWeek() + "): " + DailyWorkService.describe(entry)));
            }
        }
        if (model.isEmpty()) {
            for (WorkRecord record : records) {
                noRate(ctx, record, "no payment arrangement applies on " + d);
            }
            return;
        }
        if (model.get() == PaymentModel.MONTHLY_SALARY && employed) {
            ctx.salaryDaysExist = true;
        }
        List<WorkRecord> scheduled = records.stream().filter(WorkRecord::isScheduled).toList();
        List<WorkRecord> unscheduled = records.stream().filter(r -> !r.isScheduled()).toList();
        boolean dayOff = entries.isEmpty();

        switch (model.get()) {
            case MONTHLY_SALARY -> {
                salaryDay(ctx, d, entries, scheduled);
                extras(ctx, d, unscheduled, dayOff, PaymentModel.MONTHLY_SALARY);
            }
            case PER_DAY -> perDay(ctx, d, entries, scheduled, unscheduled);
            default -> {
                for (WorkRecord record : scheduled) {
                    scheduledRecord(ctx, d, record, model.get());
                }
                extras(ctx, d, unscheduled, dayOff, model.get());
            }
        }
    }

    // ------------------------------------------------------------------ monthly salary

    private record Weights(Map<Long, Fraction> byEntry, boolean byMinutes) {
    }

    /** Each activity's share of the day: by scheduled minutes when all have them, otherwise equal shares. */
    private static Weights weights(List<ScheduleEntry> entries) {
        boolean byMinutes = entries.stream().allMatch(e -> e.scheduledMinutes() != null && e.scheduledMinutes() > 0);
        Map<Long, Fraction> weights = new HashMap<>();
        long totalMinutes = byMinutes ? entries.stream().mapToLong(ScheduleEntry::scheduledMinutes).sum() : 0;
        for (ScheduleEntry entry : entries) {
            weights.put(entry.getId(), byMinutes ? Fraction.of(entry.scheduledMinutes(), totalMinutes) : Fraction.of(1, entries.size()));
        }
        return new Weights(weights, byMinutes);
    }

    private void salaryDay(Context ctx, LocalDate d, List<ScheduleEntry> entries, List<WorkRecord> scheduled) {
        if (entries.isEmpty()) {
            return;
        }
        Optional<ResolvedRate> salary = rate(ctx, RateType.MONTHLY_SALARY, d, null, null);
        if (salary.isEmpty()) {
            ctx.blockers.add("No monthly salary rate applies on " + d + ", so the salary cannot be calculated.");
        }
        PayRate salaryRate = salary.map(ResolvedRate::rate).orElse(null);
        Weights weights = weights(entries);
        Fraction unpaid = Fraction.ZERO;

        for (WorkRecord record : scheduled) {
            Fraction weight = weights.byEntry().get(record.getScheduleEntry().getId());
            String share = "share of the day " + weight.toDecimalString(4) + (weights.byMinutes() ? " (by scheduled minutes)" : " (by number of activities)");
            String what = DailyWorkService.describe(record) + " on " + d;
            if (record.getOutcome() == WorkOutcome.COMPLETED) {
                ctx.treat(salaryTreatment(record, TreatmentType.COVERED_BY_SALARY, salaryRate, what + ": done; covered by the monthly salary."));
            } else if (record.getOutcome() == WorkOutcome.NOT_DONE) {
                switch (record.getAbsenceReason()) {
                    case HOUSEHOLD_HOLIDAY -> ctx.treat(salaryTreatment(record, TreatmentType.HOLIDAY_PAID, salaryRate,
                            what + ": household holiday on a scheduled day; salary not reduced."));
                    case APPROVED_LEAVE -> {
                        unpaid = unpaid.add(weight);
                        ctx.treat(salaryTreatment(record, TreatmentType.UNPAID_LEAVE, salaryRate,
                                what + ": approved leave is unpaid; " + share + " not paid."));
                    }
                    case UNAUTHORIZED_ABSENCE -> {
                        DeductionDecision decision = record.getDeductionDecision();
                        if (decision == DeductionDecision.APPROVED) {
                            unpaid = unpaid.add(weight);
                            ctx.treat(salaryTreatment(record, TreatmentType.ABSENCE_APPROVED_UNPAID, salaryRate,
                                    what + ": unauthorized absence, deduction approved; " + share + " not paid."));
                        } else if (decision == DeductionDecision.WAIVED) {
                            ctx.treat(salaryTreatment(record, TreatmentType.ABSENCE_WAIVED_PAID, salaryRate,
                                    what + ": unauthorized absence, deduction waived; salary not reduced."));
                        } else {
                            ctx.treat(salaryTreatment(record, TreatmentType.ABSENCE_PENDING_PAID, salaryRate,
                                    what + ": unauthorized absence without a decision; salary not reduced."));
                            ctx.warnings.add("Unauthorized absence on " + d + " (" + DailyWorkService.describe(record)
                                    + ") has no deduction decision. Salary is not reduced unless you approve a deduction.");
                        }
                    }
                }
            } else {
                unpaid = unpaid.add(salaryPartial(ctx, record, weight, weights.byMinutes(), salaryRate, what));
            }
        }
        if (salaryRate != null) {
            Fraction paid = Fraction.ONE.subtract(unpaid);
            if (paid.signum() < 0) {
                paid = Fraction.ZERO;
            }
            ctx.salaryUnits.merge(salaryRate, paid, Fraction::add);
        }
    }

    /** F3: a suggested reduction of paid day-units, applied only when accepted or overridden. */
    private Fraction salaryPartial(Context ctx, WorkRecord record, Fraction weight, boolean byMinutes, PayRate salaryRate,
                                   String what) {
        String key = "REC:" + record.getId() + ":SALARY_PARTIAL";
        Fraction suggestion = null;
        if (byMinutes && record.getExpectedMinutes() != null && record.getActualMinutes() != null && record.getExpectedMinutes() > 0) {
            Fraction worked = Fraction.of(record.getActualMinutes(), record.getExpectedMinutes());
            suggestion = weight.multiply(Fraction.ONE.subtract(worked));
        }
        String snapshot = suggestion == null ? EarningReview.NO_SUGGESTION : suggestion.toString();
        EarningReview review = ctx.reviews.get(key);
        Fraction applied = null;
        ReviewStatus status = ReviewStatus.NEEDS_REVIEW;
        if (review != null && review.appliesTo(snapshot)) {
            if (review.getDecision() == ReviewDecisionKind.ACCEPT_SUGGESTED && suggestion != null) {
                applied = suggestion;
                status = ReviewStatus.ACCEPTED;
            } else if (review.getDecision() == ReviewDecisionKind.OVERRIDE && review.getUnpaidDayUnits() != null) {
                Fraction override = Fraction.parse(review.getUnpaidDayUnits());
                if (override.signum() >= 0 && override.compareTo(weight) <= 0) {
                    applied = override;
                    status = ReviewStatus.OVERRIDDEN;
                }
            }
        }
        String detail;
        if (status == ReviewStatus.NEEDS_REVIEW) {
            ctx.needsReview++;
            detail = what + ": partly done. " + (suggestion == null
                    ? "No reliable minutes baseline, so enter the unpaid day-units (0 to " + weight.toDecimalString(4) + ")."
                    : "Suggested unpaid day-units " + suggestion.toDecimalString(4) + " (actual " + record.getActualMinutes()
                    + " of " + record.getExpectedMinutes() + " minutes); not applied until you accept or override it.");
        } else {
            detail = what + ": partly done; " + applied.toDecimalString(4) + " day-units unpaid ("
                    + (status == ReviewStatus.ACCEPTED ? "suggestion accepted" : "amount entered: " + review.getReason()) + ").";
        }
        ctx.treat(new PlannedTreatment(record, TreatmentType.SALARY_PARTIAL_REDUCTION, null, salaryRate, key, status,
                suggestion == null ? null : suggestion.toString(), weight.toString(),
                applied == null ? null : applied.toString(), detail));
        return applied == null ? Fraction.ZERO : applied;
    }

    private static PlannedTreatment salaryTreatment(WorkRecord record, TreatmentType type, PayRate salaryRate, String detail) {
        return new PlannedTreatment(record, type, null, salaryRate, null, ReviewStatus.NONE, null, null, null, detail);
    }

    private void salaryLines(Context ctx, int scheduledDays) {
        if (ctx.salaryDaysExist && scheduledDays == 0) {
            ctx.blockers.add("There are no scheduled working days in " + ctx.month
                    + ", so the monthly salary cannot be prorated. Add a schedule first.");
            return;
        }
        for (Map.Entry<PayRate, Fraction> segment : ctx.salaryUnits.entrySet()) {
            PayRate rate = segment.getKey();
            Fraction paidUnits = segment.getValue();
            Fraction exact = Fraction.of(rate.getAmountPaise()).multiply(paidUnits).divide(Fraction.of(scheduledDays));
            String explanation = "Monthly salary " + Money.format(rate.getAmountPaise()) + " (rate #" + rate.getId() + ") x "
                    + paidUnits.toDecimalString(4) + " paid of " + scheduledDays + " scheduled day-units in " + ctx.month
                    + " = " + exactText(exact) + ".";
            PlannedLine line = ctx.add(new PlannedLine(EarningLineType.MONTHLY_SALARY, EarningBasis.SALARY, null, null, rate,
                    null, null, null, paidUnits.toString(), scheduledDays, exact, null, exact.roundHalfUp(),
                    ReviewStatus.NONE, null, explanation));
            for (PlannedTreatment treatment : ctx.treatments.values()) {
                if (treatment.salaryRate == rate) {
                    treatment.line = line;
                }
            }
        }
    }

    // ------------------------------------------------------------------ per task / hourly / per unit / per shift

    private void scheduledRecord(Context ctx, LocalDate d, WorkRecord record, PaymentModel model) {
        if (record.getOutcome() == WorkOutcome.NOT_DONE) {
            switch (record.getAbsenceReason()) {
                case HOUSEHOLD_HOLIDAY -> holiday(ctx, d, record, model);
                case APPROVED_LEAVE -> ctx.treat(record, TreatmentType.UNPAID_LEAVE, null,
                        DailyWorkService.describe(record) + " on " + d + ": approved leave is unpaid.");
                case UNAUTHORIZED_ABSENCE -> ctx.treat(record, TreatmentType.ABSENCE_UNPAID, null,
                        DailyWorkService.describe(record) + " on " + d + ": unauthorized absence earns nothing (decision: "
                                + record.getDeductionDecision() + "); no penalty is applied.");
            }
            return;
        }
        String what = DailyWorkService.describe(record) + " on " + d;
        switch (model) {
            case PER_TASK -> taskOrShiftWork(ctx, d, record, RateType.PER_TASK, EarningBasis.WORK, what);
            case PER_SHIFT -> taskOrShiftWork(ctx, d, record, RateType.PER_SHIFT, EarningBasis.WORK, what);
            case HOURLY -> hourlyWork(ctx, d, record, RateType.HOURLY, EarningBasis.WORK, what);
            case PER_UNIT -> unitWork(ctx, d, record, EarningBasis.WORK, what);
            default -> throw new IllegalStateException("Unexpected model " + model);
        }
    }

    /** E4/F4: holidays on scheduled days pay the expected earnings, except per-unit. */
    private void holiday(Context ctx, LocalDate d, WorkRecord record, PaymentModel model) {
        String what = DailyWorkService.describe(record) + " on " + d + " (household holiday)";
        switch (model) {
            case PER_UNIT -> ctx.treat(record, TreatmentType.HOLIDAY_NOT_PAID, null,
                    what + ": per-unit pay is for quantities delivered, so a holiday is not paid.");
            case PER_TASK, PER_SHIFT -> {
                RateType type = model == PaymentModel.PER_TASK ? RateType.PER_TASK : RateType.PER_SHIFT;
                Long taskId = type == RateType.PER_TASK ? id(record.getTask()) : null;
                Long shiftId = type == RateType.PER_SHIFT ? id(record.getShift()) : null;
                if (taskId == null && shiftId == null) {
                    noRate(ctx, record, "the holiday entry has no " + (type == RateType.PER_TASK ? "task" : "shift"));
                    return;
                }
                Optional<ResolvedRate> rate = rate(ctx, type, d, taskId, shiftId);
                if (rate.isEmpty()) {
                    noRate(ctx, record, "no " + PayService.describe(type) + " rate applies on " + d);
                    return;
                }
                Fraction exact = Fraction.of(rate.get().rate().getAmountPaise());
                PlannedLine line = ctx.add(fixedLine(lineType(type), EarningBasis.HOLIDAY, d, record, rate.get(), null, null, exact,
                        what + ": paid as expected, " + rateText(rate.get()) + " x 1 = " + exactText(exact) + "."));
                ctx.treat(record, TreatmentType.HOLIDAY_PAID, line, what + ": paid as expected.");
            }
            case HOURLY -> {
                Optional<ResolvedRate> rate = rate(ctx, RateType.HOURLY, d, null, null);
                if (rate.isEmpty()) {
                    noRate(ctx, record, "no hourly rate applies on " + d);
                    return;
                }
                if (record.getExpectedMinutes() == null) {
                    PlannedLine line = ctx.add(reviewLine(ctx, EarningLineType.HOURLY, EarningBasis.HOLIDAY, d, record, rate.get(),
                            null, "REC:" + record.getId() + ":HOLIDAY", null,
                            what + ": the activity has no expected minutes, so enter the holiday amount."));
                    ctx.treat(reviewTreatment(record, line, what + ": holiday amount needs review."));
                    return;
                }
                Fraction exact = Fraction.of(rate.get().rate().getAmountPaise()).multiply(Fraction.of(record.getExpectedMinutes(), 60));
                PlannedLine line = ctx.add(fixedLine(EarningLineType.HOURLY, EarningBasis.HOLIDAY, d, record, rate.get(), null,
                        record.getExpectedMinutes(), exact, what + ": paid as expected, " + rateText(rate.get()) + " x "
                                + record.getExpectedMinutes() + " expected min / 60 = " + exactText(exact) + "."));
                ctx.treat(record, TreatmentType.HOLIDAY_PAID, line, what + ": paid as expected.");
            }
            default -> throw new IllegalStateException("Unexpected model " + model);
        }
    }

    private void taskOrShiftWork(Context ctx, LocalDate d, WorkRecord record, RateType type, EarningBasis basis, String what) {
        Long taskId = type == RateType.PER_TASK ? id(record.getTask()) : null;
        Long shiftId = type == RateType.PER_SHIFT ? id(record.getShift()) : null;
        if (taskId == null && shiftId == null) {
            noRate(ctx, record, "the record has no " + (type == RateType.PER_TASK ? "task" : "shift"));
            return;
        }
        Optional<ResolvedRate> rate = rate(ctx, type, d, taskId, shiftId);
        if (rate.isEmpty()) {
            noRate(ctx, record, "no " + PayService.describe(type) + " rate applies on " + d);
            return;
        }
        payWithRate(ctx, d, record, lineType(type), basis, rate.get(), what);
    }

    /** Completed: rate x 1. Partial: a suggested proportional amount (or a manual one) that needs review. */
    private void payWithRate(Context ctx, LocalDate d, WorkRecord record, EarningLineType type, EarningBasis basis,
                             ResolvedRate rate, String what) {
        Fraction full = Fraction.of(rate.rate().getAmountPaise());
        if (record.getOutcome() == WorkOutcome.COMPLETED) {
            PlannedLine line = ctx.add(fixedLine(type, basis, d, record, rate, null, null, full,
                    what + ": " + rateText(rate) + " x 1 = " + exactText(full) + "."));
            ctx.treat(record, TreatmentType.PAID, line, what + ": paid.");
            return;
        }
        Fraction share = partialShare(record);
        Fraction suggestion = share == null ? null : full.multiply(share);
        String key = "REC:" + record.getId() + (share == null ? ":MANUAL" : ":PARTIAL");
        String explanation = what + ": partly done. " + (share == null
                ? "There is no time or quantity baseline to compare with, so enter the amount for " + rateText(rate) + "."
                : "Suggested " + rateText(rate) + " x " + share.toDecimalString(4) + " (" + shareText(record) + ") = "
                + exactText(suggestion) + "; not paid until you accept or override it.");
        PlannedLine line = ctx.add(reviewLine(ctx, type, basis, d, record, rate, suggestion, key, null, explanation));
        ctx.treat(reviewTreatment(record, line, explanation));
    }

    private void hourlyWork(Context ctx, LocalDate d, WorkRecord record, RateType type, EarningBasis basis, String what) {
        Optional<ResolvedRate> rate = rate(ctx, type, d, null, null);
        if (rate.isEmpty()) {
            noRate(ctx, record, "no " + PayService.describe(type) + " rate applies on " + d);
            return;
        }
        if (record.getActualMinutes() == null) {
            String explanation = what + ": no actual time was recorded, so enter the amount for " + rateText(rate.get()) + ".";
            PlannedLine line = ctx.add(reviewLine(ctx, lineType(type), basis, d, record, rate.get(), null,
                    "REC:" + record.getId() + ":MANUAL", null, explanation));
            ctx.treat(reviewTreatment(record, line, explanation));
            return;
        }
        Fraction exact = Fraction.of(rate.get().rate().getAmountPaise()).multiply(Fraction.of(record.getActualMinutes(), 60));
        PlannedLine line = ctx.add(fixedLine(lineType(type), basis, d, record, rate.get(), null, record.getActualMinutes(), exact,
                what + ": " + rateText(rate.get()) + " x " + record.getActualMinutes() + " min / 60 = " + exactText(exact) + "."));
        ctx.treat(record, TreatmentType.PAID, line, what + ": paid for actual time.");
    }

    private void unitWork(Context ctx, LocalDate d, WorkRecord record, EarningBasis basis, String what) {
        if (record.getActualQuantityMilli() == null) {
            noRate(ctx, record, "per-unit pay needs a recorded quantity");
            return;
        }
        Optional<ResolvedRate> rate = rate(ctx, RateType.PER_UNIT, d, id(record.getTask()), null);
        if (rate.isEmpty()) {
            noRate(ctx, record, "no per-unit rate applies on " + d);
            return;
        }
        Fraction exact = Fraction.of(rate.get().rate().getAmountPaise()).multiply(Fraction.of(record.getActualQuantityMilli(), 1000));
        PlannedLine line = ctx.add(fixedLine(EarningLineType.PER_UNIT, basis, d, record, rate.get(), record.getActualQuantityMilli(),
                null, exact, what + ": " + rateText(rate.get()) + " per " + rate.get().rate().getUnitLabel() + " x "
                        + Fraction.of(record.getActualQuantityMilli(), 1000).toDecimalString(3) + " = " + exactText(exact) + "."));
        ctx.treat(record, TreatmentType.PAID, line, what + ": paid for the actual quantity.");
    }

    // ------------------------------------------------------------------ extra (unscheduled) work

    private void extras(Context ctx, LocalDate d, List<WorkRecord> unscheduled, boolean dayOff, PaymentModel model) {
        if (unscheduled.isEmpty()) {
            return;
        }
        if (dayOff) {
            Optional<ResolvedRate> extraDay = rate(ctx, RateType.EXTRA_DAY, d, null, null);
            if (model == PaymentModel.PER_UNIT) {
                extraDay.ifPresent(r -> ctx.warnings.add("An extra-day rate is configured but was not applied to per-unit "
                        + "work on " + d + "; quantities are paid at the per-unit rate."));
            } else if (extraDay.isPresent()) {
                extraDayLine(ctx, d, unscheduled, extraDay.get());
                return;
            }
        }
        for (WorkRecord record : unscheduled) {
            extraRecord(ctx, d, record, model, dayOff);
        }
    }

    /** One extra-day line per date covers every unscheduled record of that day off. */
    private void extraDayLine(Context ctx, LocalDate d, List<WorkRecord> unscheduled, ResolvedRate rate) {
        boolean anyDone = unscheduled.stream().anyMatch(r -> r.getOutcome() == WorkOutcome.COMPLETED);
        String ids = ids(unscheduled);
        PlannedLine line;
        if (anyDone) {
            Fraction exact = Fraction.of(rate.rate().getAmountPaise());
            line = ctx.add(fixedLine(EarningLineType.EXTRA_DAY, EarningBasis.EXTRA_WORK, d, null, rate, null, null, exact,
                    "Work on a regular day off, " + d + ": " + rateText(rate) + " x 1 = " + exactText(exact)
                            + " (covers records " + ids + ")."));
        } else {
            line = ctx.add(reviewLine(ctx, EarningLineType.EXTRA_DAY, EarningBasis.EXTRA_WORK, d, null, rate, null,
                    "DAY:" + d + ":EXTRA_DAY:" + ids, null, "Work on a regular day off, " + d
                            + ", was only partly done and has no baseline, so enter the amount for " + rateText(rate) + "."));
        }
        for (WorkRecord record : unscheduled) {
            ctx.treat(record, TreatmentType.COVERED_BY_EXTRA_DAY, line, DailyWorkService.describe(record) + " on " + d
                    + ": covered by the single extra-day line for this day off.");
        }
    }

    /** C3 precedence for one unscheduled record: extra-task, then primary, then extra-hour. */
    private void extraRecord(Context ctx, LocalDate d, WorkRecord record, PaymentModel model, boolean dayOff) {
        String what = DailyWorkService.describe(record) + " on " + d + (dayOff ? " (regular day off)" : " (additional activity)");
        if (model == PaymentModel.PER_UNIT && record.getActualQuantityMilli() != null) {
            unitWork(ctx, d, record, EarningBasis.EXTRA_WORK, what);
            return;
        }
        if (extraTask(ctx, d, record, what)) {
            return;
        }
        switch (model) {
            case PER_TASK -> {
                if (record.getTask() != null && rate(ctx, RateType.PER_TASK, d, id(record.getTask()), null).isPresent()) {
                    taskOrShiftWork(ctx, d, record, RateType.PER_TASK, EarningBasis.EXTRA_WORK, what);
                    return;
                }
            }
            case PER_SHIFT -> {
                if (record.getShift() != null && rate(ctx, RateType.PER_SHIFT, d, null, id(record.getShift())).isPresent()) {
                    taskOrShiftWork(ctx, d, record, RateType.PER_SHIFT, EarningBasis.EXTRA_WORK, what);
                    return;
                }
            }
            case HOURLY -> {
                if (record.getActualMinutes() != null && rate(ctx, RateType.HOURLY, d, null, null).isPresent()) {
                    hourlyWork(ctx, d, record, RateType.HOURLY, EarningBasis.EXTRA_WORK, what);
                    return;
                }
            }
            default -> {
                // Monthly salary has no primary step; per-unit without a quantity cannot be priced by the unit rate.
            }
        }
        if (extraHour(ctx, d, record, what)) {
            return;
        }
        noRate(ctx, record, "no configured rate applies to this unscheduled work");
    }

    private boolean extraTask(Context ctx, LocalDate d, WorkRecord record, String what) {
        if (record.getTask() == null) {
            return false;
        }
        Optional<ResolvedRate> rate = rate(ctx, RateType.EXTRA_TASK, d, id(record.getTask()), null);
        if (rate.isEmpty()) {
            return false;
        }
        payWithRate(ctx, d, record, EarningLineType.EXTRA_TASK, EarningBasis.EXTRA_WORK, rate.get(), what);
        return true;
    }

    private boolean extraHour(Context ctx, LocalDate d, WorkRecord record, String what) {
        if (record.getActualMinutes() == null || rate(ctx, RateType.EXTRA_HOUR, d, null, null).isEmpty()) {
            return false;
        }
        hourlyWork(ctx, d, record, RateType.EXTRA_HOUR, EarningBasis.EXTRA_WORK, what);
        return true;
    }

    // ------------------------------------------------------------------ per day (C1: at most one day line per date)

    private void perDay(Context ctx, LocalDate d, List<ScheduleEntry> entries, List<WorkRecord> scheduled,
                        List<WorkRecord> unscheduled) {
        Optional<ResolvedRate> dayRate = rate(ctx, RateType.PER_DAY, d, null, null);
        List<WorkRecord> remaining = new ArrayList<>();
        for (WorkRecord record : unscheduled) {
            String what = DailyWorkService.describe(record) + " on " + d + " (unscheduled)";
            if (!extraTask(ctx, d, record, what)) {
                remaining.add(record);
            }
        }
        for (WorkRecord record : scheduled) {
            if (record.getOutcome() == WorkOutcome.NOT_DONE && record.getAbsenceReason() == AbsenceReason.APPROVED_LEAVE) {
                ctx.treat(record, TreatmentType.UNPAID_LEAVE, null, DailyWorkService.describe(record) + " on " + d
                        + ": approved leave is unpaid.");
            } else if (record.getOutcome() == WorkOutcome.NOT_DONE && record.getAbsenceReason() == AbsenceReason.UNAUTHORIZED_ABSENCE) {
                ctx.treat(record, TreatmentType.ABSENCE_UNPAID, null, DailyWorkService.describe(record) + " on " + d
                        + ": unauthorized absence earns nothing (decision: " + record.getDeductionDecision() + "); no penalty is applied.");
            }
        }
        List<WorkRecord> scheduledDone = filter(scheduled, WorkOutcome.COMPLETED);
        List<WorkRecord> scheduledPartial = filter(scheduled, WorkOutcome.PARTIAL);
        List<WorkRecord> holidays = scheduled.stream().filter(r -> r.getOutcome() == WorkOutcome.NOT_DONE
                && r.getAbsenceReason() == AbsenceReason.HOUSEHOLD_HOLIDAY).toList();

        if (!scheduledDone.isEmpty()) {
            // The scheduled work pays the day; unscheduled extras use extra-hour or are covered.
            PlannedLine line = fullDayLine(ctx, d, dayRate, EarningBasis.WORK, scheduledDone, "scheduled work done");
            coverByDay(ctx, d, line, dayRate, scheduledDone, scheduledPartial, holidays);
            for (WorkRecord record : remaining) {
                String what = DailyWorkService.describe(record) + " on " + d + " (additional activity)";
                if (!extraHour(ctx, d, record, what)) {
                    coverOne(ctx, d, line, dayRate, record);
                }
            }
            return;
        }
        List<WorkRecord> unscheduledDone = filter(remaining, WorkOutcome.COMPLETED);
        if (!unscheduledDone.isEmpty()) {
            PlannedLine line = fullDayLine(ctx, d, dayRate, EarningBasis.EXTRA_WORK, unscheduledDone,
                    entries.isEmpty() ? "work on a regular day off" : "unscheduled work done");
            coverByDay(ctx, d, line, dayRate, unscheduledDone, scheduledPartial, holidays);
            for (WorkRecord record : filter(remaining, WorkOutcome.PARTIAL)) {
                coverOne(ctx, d, line, dayRate, record);
            }
            return;
        }
        List<WorkRecord> unscheduledPartial = filter(remaining, WorkOutcome.PARTIAL);
        List<WorkRecord> involved = new ArrayList<>(scheduledPartial);
        involved.addAll(holidays);
        involved.addAll(unscheduledPartial);
        if (involved.isEmpty()) {
            return;
        }
        if (dayRate.isEmpty()) {
            for (WorkRecord record : involved) {
                noRate(ctx, record, "no per-day rate applies on " + d);
            }
            return;
        }
        String key = "DAY:" + d + ":PER_DAY:" + ids(involved);
        boolean minutesKnown = !entries.isEmpty() && entries.stream().allMatch(e -> e.scheduledMinutes() != null && e.scheduledMinutes() > 0);
        long dayMinutes = minutesKnown ? entries.stream().mapToLong(ScheduleEntry::scheduledMinutes).sum() : 0;
        Fraction full = Fraction.of(dayRate.get().rate().getAmountPaise());

        if (scheduledPartial.isEmpty() && unscheduledPartial.isEmpty()) {
            // F4a: holidays only (with any leave/absence): pay the holiday share of the day.
            Fraction share = null;
            if (holidays.size() == entries.size()) {
                share = Fraction.ONE;
            } else if (minutesKnown) {
                long holidayMinutes = holidays.stream().mapToLong(r -> r.getScheduleEntry().scheduledMinutes()).sum();
                share = Fraction.of(holidayMinutes, dayMinutes);
            }
            if (share != null) {
                Fraction exact = full.multiply(share);
                PlannedLine line = ctx.add(fixedLine(EarningLineType.PER_DAY, EarningBasis.HOLIDAY, d, null, dayRate.get(), null, null,
                        exact, "Household holiday on " + d + ": " + rateText(dayRate.get()) + " x holiday share "
                                + share.toDecimalString(4) + " = " + exactText(exact) + "."));
                for (WorkRecord record : holidays) {
                    ctx.treat(record, TreatmentType.HOLIDAY_PAID, line, DailyWorkService.describe(record) + " on " + d
                            + ": household holiday, paid through the day line.");
                }
                return;
            }
            dayReview(ctx, d, dayRate.get(), null, key, involved,
                    "Household holiday on " + d + " is mixed with leave or absence and the activities have no scheduled "
                            + "minutes to weigh them, so enter the amount for " + rateText(dayRate.get()) + ".");
            return;
        }
        if (!holidays.isEmpty() || !unscheduledPartial.isEmpty()) {
            dayReview(ctx, d, dayRate.get(), null, key, involved, "Day " + d + " mixes partial work with "
                    + (holidays.isEmpty() ? "unscheduled work that has no baseline" : "a household holiday")
                    + ", so enter the amount for " + rateText(dayRate.get()) + ".");
            return;
        }
        Fraction suggestion = null;
        if (minutesKnown && scheduledPartial.stream().allMatch(r -> r.getActualMinutes() != null)) {
            long actual = scheduledPartial.stream().mapToLong(WorkRecord::getActualMinutes).sum();
            suggestion = full.multiply(Fraction.of(actual, dayMinutes));
        }
        dayReview(ctx, d, dayRate.get(), suggestion, key, involved, suggestion == null
                ? "Day " + d + " was partly worked without a reliable minutes baseline, so enter the amount for " + rateText(dayRate.get()) + "."
                : "Day " + d + " was partly worked: suggested " + rateText(dayRate.get()) + " x actual / usual scheduled minutes = "
                + exactText(suggestion) + "; not paid until you accept or override it.");
    }

    private PlannedLine fullDayLine(Context ctx, LocalDate d, Optional<ResolvedRate> dayRate, EarningBasis basis,
                                    List<WorkRecord> payers, String reason) {
        if (dayRate.isEmpty()) {
            return null;
        }
        Fraction exact = Fraction.of(dayRate.get().rate().getAmountPaise());
        PlannedLine line = ctx.add(fixedLine(EarningLineType.PER_DAY, basis, d, null, dayRate.get(), null, null, exact,
                "Day rate for " + d + " (" + reason + "): " + rateText(dayRate.get()) + " x 1 = " + exactText(exact)
                        + " (one day line per date)."));
        for (WorkRecord record : payers) {
            ctx.treat(record, TreatmentType.PAID, line, DailyWorkService.describe(record) + " on " + d + ": paid by the day line.");
        }
        return line;
    }

    private void coverByDay(Context ctx, LocalDate d, PlannedLine line, Optional<ResolvedRate> dayRate, List<WorkRecord> payers,
                            List<WorkRecord> partial, List<WorkRecord> holidays) {
        if (line == null) {
            for (WorkRecord record : payers) {
                noRate(ctx, record, "no per-day rate applies on " + d);
            }
        }
        for (WorkRecord record : partial) {
            coverOne(ctx, d, line, dayRate, record);
        }
        for (WorkRecord record : holidays) {
            coverOne(ctx, d, line, dayRate, record);
        }
    }

    private void coverOne(Context ctx, LocalDate d, PlannedLine line, Optional<ResolvedRate> dayRate, WorkRecord record) {
        if (line == null || dayRate.isEmpty()) {
            noRate(ctx, record, "no per-day rate applies on " + d);
            return;
        }
        ctx.treat(record, TreatmentType.COVERED_BY_DAY_RATE, line, DailyWorkService.describe(record) + " on " + d
                + ": covered by the day rate already paid for this date.");
    }

    private void dayReview(Context ctx, LocalDate d, ResolvedRate rate, Fraction suggestion, String key, List<WorkRecord> involved,
                           String explanation) {
        PlannedLine line = ctx.add(reviewLine(ctx, EarningLineType.PER_DAY, EarningBasis.WORK, d, null, rate, suggestion, key, null,
                explanation));
        for (WorkRecord record : involved) {
            ctx.treat(reviewTreatment(record, line, DailyWorkService.describe(record) + " on " + d + ": part of the day line under review."));
        }
    }

    // ------------------------------------------------------------------ unrecorded activities (F1 / C4)

    private void unrecordedCheck(Context ctx) {
        if (ctx.unrecorded.isEmpty()) {
            return;
        }
        String fingerprint = fingerprint(ctx.unrecorded);
        Optional<UnrecordedConfirmation> confirmation = confirmationRepository.findCurrent(ctx.worker.getId(), ctx.month.atDay(1));
        if (confirmation.isPresent() && confirmation.get().getFingerprint().equals(fingerprint)) {
            ctx.warnings.add(ctx.unrecorded.size() + " scheduled " + plural(ctx.unrecorded.size(), "activity", "activities")
                    + " left unrecorded, confirmed on " + confirmation.get().getConfirmedAt() + ": " + confirmation.get().getStatement());
            return;
        }
        String listed = ctx.unrecorded.stream().map(UnrecordedActivity::description).collect(Collectors.joining("; "));
        ctx.blockers.add(ctx.unrecorded.size() + " scheduled " + plural(ctx.unrecorded.size(), "activity is", "activities are")
                + " not recorded: " + listed + ". Record " + plural(ctx.unrecorded.size(), "it", "them")
                + " or confirm explicitly before finalizing."
                + (confirmation.isPresent() ? " The earlier confirmation no longer matches and has expired." : ""));
    }

    // ------------------------------------------------------------------ helpers

    private static PlannedLine fixedLine(EarningLineType type, EarningBasis basis, LocalDate d, WorkRecord record, ResolvedRate rate,
                                         Long quantityMilli, Integer minutes, Fraction exact, String explanation) {
        return new PlannedLine(type, basis, d, record, rate.rate(), rate.rate().getUnitLabel(), quantityMilli, minutes, null, null,
                exact, null, exact.roundHalfUp(), ReviewStatus.NONE, null, explanation);
    }

    /** A line whose amount is excluded until the household accepts the suggestion or enters an amount. */
    private PlannedLine reviewLine(Context ctx, EarningLineType type, EarningBasis basis, LocalDate d, WorkRecord record,
                                   ResolvedRate rate, Fraction suggestion, String key, Integer minutes, String explanation) {
        String snapshot = suggestion == null ? EarningReview.NO_SUGGESTION : suggestion.toString();
        EarningReview review = ctx.reviews.get(key);
        Long amount = null;
        ReviewStatus status = ReviewStatus.NEEDS_REVIEW;
        String text = explanation;
        if (review != null && review.appliesTo(snapshot)) {
            if (review.getDecision() == ReviewDecisionKind.ACCEPT_SUGGESTED && suggestion != null) {
                amount = suggestion.roundHalfUp();
                status = ReviewStatus.ACCEPTED;
                text = explanation + " Suggestion accepted.";
            } else if (review.getDecision() == ReviewDecisionKind.OVERRIDE && review.getAmountPaise() != null) {
                amount = review.getAmountPaise();
                status = ReviewStatus.OVERRIDDEN;
                text = explanation + " Amount entered: " + Money.format(amount) + " (" + review.getReason() + ").";
            }
        }
        return new PlannedLine(type, basis, d, record, rate.rate(), rate.rate().getUnitLabel(), record == null ? null : record.getActualQuantityMilli(),
                minutes, null, null, suggestion, suggestion == null ? null : suggestion.roundHalfUp(), amount, status, key, text);
    }

    private static PlannedTreatment reviewTreatment(WorkRecord record, PlannedLine line, String detail) {
        TreatmentType type = line.reviewStatus == ReviewStatus.NEEDS_REVIEW ? TreatmentType.NEEDS_REVIEW : TreatmentType.PAID;
        return new PlannedTreatment(record, type, line, null, null, ReviewStatus.NONE, null, null, null, detail);
    }

    private void noRate(Context ctx, WorkRecord record, String why) {
        String text = DailyWorkService.describe(record) + " on " + record.getWorkDate() + " is not paid: " + why + ".";
        ctx.treat(record, TreatmentType.NO_APPLICABLE_RATE, null, text);
        ctx.warnings.add(text);
    }

    /** Actual / expected minutes, else actual / expected quantity; null when there is no baseline. */
    private static Fraction partialShare(WorkRecord record) {
        if (record.getExpectedMinutes() != null && record.getActualMinutes() != null && record.getExpectedMinutes() > 0) {
            return Fraction.of(record.getActualMinutes(), record.getExpectedMinutes());
        }
        if (record.getExpectedQuantityMilli() != null && record.getActualQuantityMilli() != null && record.getExpectedQuantityMilli() > 0) {
            return Fraction.of(record.getActualQuantityMilli(), record.getExpectedQuantityMilli());
        }
        return null;
    }

    private static String shareText(WorkRecord record) {
        if (record.getExpectedMinutes() != null && record.getActualMinutes() != null) {
            return record.getActualMinutes() + " of " + record.getExpectedMinutes() + " usual minutes";
        }
        return "quantity " + record.getActualQuantityMilli() + " of " + record.getExpectedQuantityMilli() + " thousandths";
    }

    private static EarningLineType lineType(RateType type) {
        return EarningLineType.valueOf(type.name());
    }

    private static List<WorkRecord> filter(List<WorkRecord> records, WorkOutcome outcome) {
        return records.stream().filter(r -> r.getOutcome() == outcome).toList();
    }

    private static String ids(List<WorkRecord> records) {
        return records.stream().map(WorkRecord::getId).sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Long id(com.homeexpense.model.Task task) {
        return task == null ? null : task.getId();
    }

    private static Long id(com.homeexpense.model.Shift shift) {
        return shift == null ? null : shift.getId();
    }

    private static String rateText(ResolvedRate rate) {
        PayRate r = rate.rate();
        return PayService.describe(r.getRateType()) + " rate " + Money.format(r.getAmountPaise()) + " ("
                + (rate.specific() ? "specific" : "standard") + " rate #" + r.getId() + ")";
    }

    /** The recorded (HALF_UP) amount, plus the exact value when rounding changed it. */
    private static String exactText(Fraction exactPaise) {
        String rounded = Money.format(exactPaise.roundHalfUp());
        if (exactPaise.denominator().equals(java.math.BigInteger.ONE)) {
            return rounded;
        }
        return rounded + " (exactly ₹" + exactPaise.divide(Fraction.of(100)).toDecimalString(6) + ", rounded half-up)";
    }

    private static String plural(int count, String one, String many) {
        return count == 1 ? one : many;
    }
}
