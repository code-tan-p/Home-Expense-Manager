package com.homeexpense.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.UnrecordedActivity;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningRecordTreatment;
import com.homeexpense.model.earnings.EarningReview;
import com.homeexpense.model.earnings.EarningRun;
import com.homeexpense.model.earnings.EarningRunSupersession;
import com.homeexpense.model.earnings.EarningRunNote;
import com.homeexpense.model.earnings.NoteKind;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.model.earnings.TreatmentType;
import com.homeexpense.model.earnings.UnrecordedConfirmation;
import com.homeexpense.money.Fraction;
import com.homeexpense.money.Money;
import com.homeexpense.repository.EarningLineRepository;
import com.homeexpense.repository.EarningRecordTreatmentRepository;
import com.homeexpense.repository.EarningReviewRepository;
import com.homeexpense.repository.EarningRunNoteRepository;
import com.homeexpense.repository.EarningRunRepository;
import com.homeexpense.repository.EarningRunSupersessionRepository;
import com.homeexpense.repository.SettlementRepository;
import com.homeexpense.repository.SettlementVersionRepository;
import com.homeexpense.repository.UnrecordedConfirmationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves earnings calculations and the household's decisions about them.
 *
 * <p>Each public method is one transaction: a failure leaves the previous draft exactly as it
 * was. Recalculating a draft replaces its lines; a finalized run can no longer change and its
 * dates are locked (see {@link FinalizedEarningsGuard}) until the run is reopened, which keeps
 * it unchanged and lets a replacement run be calculated for the same month.
 */
@Service
@Transactional
public class EarningsService {

    static final String ENTITY_RUN = "EARNING_RUN";
    static final String ENTITY_REVIEW = "EARNING_REVIEW";
    static final String ENTITY_CONFIRMATION = "UNRECORDED_CONFIRMATION";

    static final String SALARY_STATEMENT = "No unpaid leave or approved absence occurred on these unrecorded dates: ";
    static final String OTHER_STATEMENT = "No payable work is missing for these dates and activities; they will earn nothing in this month: ";

    private final EarningsCalculator calculator;
    private final EarningRunRepository runRepository;
    private final EarningLineRepository lineRepository;
    private final EarningRecordTreatmentRepository treatmentRepository;
    private final EarningRunNoteRepository noteRepository;
    private final EarningReviewRepository reviewRepository;
    private final UnrecordedConfirmationRepository confirmationRepository;
    private final EarningRunSupersessionRepository supersessionRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementVersionRepository versionRepository;
    private final WorkerService workerService;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public EarningsService(EarningsCalculator calculator, EarningRunRepository runRepository, EarningLineRepository lineRepository,
                           EarningRecordTreatmentRepository treatmentRepository, EarningRunNoteRepository noteRepository,
                           EarningReviewRepository reviewRepository, UnrecordedConfirmationRepository confirmationRepository,
                           EarningRunSupersessionRepository supersessionRepository, SettlementRepository settlementRepository,
                           SettlementVersionRepository versionRepository, WorkerService workerService, AuditService auditService,
                           Clock clock) {
        this.calculator = calculator;
        this.runRepository = runRepository;
        this.lineRepository = lineRepository;
        this.treatmentRepository = treatmentRepository;
        this.noteRepository = noteRepository;
        this.reviewRepository = reviewRepository;
        this.confirmationRepository = confirmationRepository;
        this.supersessionRepository = supersessionRepository;
        this.settlementRepository = settlementRepository;
        this.versionRepository = versionRepository;
        this.workerService = workerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Calculates (or recalculates) the draft for a worker and month. Running it again on unchanged data changes nothing. */
    public EarningsResult calculate(Long workerId, YearMonth month) {
        Worker worker = workerService.getWorker(workerId);
        requireMonth(month);
        EarningRun run = runRepository.findActive(workerId, month.atDay(1)).orElse(null);
        if (run != null && !run.isDraft()) {
            throw new BusinessRuleException("Earnings for " + worker.getName() + " in " + month
                    + " are finalized and cannot be recalculated. Reopen them first, with a reason.");
        }
        EarningsCalculator.Calculation calc = calculator.calculate(worker, month);
        Long previousTotal = null;
        if (run == null) {
            run = runRepository.saveAndFlush(new EarningRun(worker, month.atDay(1), month.atEndOfMonth(),
                    EarningsCalculator.ENGINE_VERSION, clock.instant()));
            EarningRun replacement = run;
            supersessionRepository.findAwaitingReplacement(workerId, month.atDay(1)).ifPresent(reopening -> {
                reopening.linkReplacement(replacement);
                supersessionRepository.saveAndFlush(reopening);
            });
        } else {
            previousTotal = run.getTotalPaise();
            clearDraft(run);
        }

        Map<EarningsCalculator.PlannedLine, EarningLine> saved = new HashMap<>();
        int lineNo = 1;
        long total = 0;
        for (EarningsCalculator.PlannedLine planned : calc.lines()) {
            EarningLine line = lineRepository.save(new EarningLine(run, lineNo++, planned.type, planned.basis, planned.date,
                    planned.record, planned.rate, planned.unitLabel, planned.quantityMilli, planned.minutes, planned.paidDayUnits,
                    planned.scheduledDayUnits, planned.exactPaise == null ? null : planned.exactPaise.toString(),
                    planned.suggestedAmountPaise, planned.amountPaise, planned.reviewStatus, planned.reviewKey, planned.explanation));
            saved.put(planned, line);
            if (planned.amountPaise != null) {
                total = Math.addExact(total, planned.amountPaise);
            }
        }
        for (EarningsCalculator.PlannedTreatment planned : calc.treatments()) {
            treatmentRepository.save(new EarningRecordTreatment(run, planned.record, planned.type,
                    planned.line == null ? null : saved.get(planned.line), planned.reviewKey, planned.reviewStatus,
                    planned.suggestedValue, planned.maxValue, planned.appliedValue, planned.detail));
        }
        int ordinal = 1;
        for (String blocker : calc.blockers()) {
            noteRepository.save(new EarningRunNote(run, ordinal++, NoteKind.BLOCKER, blocker));
        }
        for (String warning : calc.warnings()) {
            noteRepository.save(new EarningRunNote(run, ordinal++, NoteKind.WARNING, warning));
        }
        run.recordCalculation(clock.instant(), EarningsCalculator.ENGINE_VERSION, total, calc.needsReviewCount(),
                calc.blockers().size());
        runRepository.saveAndFlush(run);
        auditService.record(workerId, ENTITY_RUN, run.getId(), "CALCULATE", "month=" + month
                + "; previousTotal=" + (previousTotal == null ? "none" : Money.format(previousTotal))
                + "; total=" + Money.format(total) + "; lines=" + calc.lines().size()
                + "; needsReview=" + calc.needsReviewCount() + "; blockers=" + calc.blockers().size());
        return result(run, calc.unrecorded());
    }

    /** Accepts a line's suggested amount, or enters an amount with a reason; then recalculates the draft. */
    public EarningsResult reviewLine(Long lineId, ReviewDecisionKind decision, String amountRupees, String reason) {
        EarningLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new BusinessRuleException("Earning line #" + lineId + " was not found."));
        EarningRun run = requireDraft(line.getRun());
        if (line.getReviewKey() == null) {
            throw new BusinessRuleException("This amount is calculated from a rate and does not need review.");
        }
        Long amount = null;
        if (decision == ReviewDecisionKind.ACCEPT_SUGGESTED) {
            if (line.getSuggestedAmountPaise() == null) {
                throw new BusinessRuleException("There is no suggested amount for this line; enter the amount instead.");
            }
        } else if (decision == ReviewDecisionKind.OVERRIDE) {
            amount = Money.parseRupees(amountRupees);
            if (amount < 0) {
                throw new BusinessRuleException("The amount cannot be negative.");
            }
            requireReason(reason);
        } else {
            throw new BusinessRuleException("Choose whether to accept the suggestion or enter an amount.");
        }
        String snapshot = line.getExactPaise() == null ? EarningReview.NO_SUGGESTION : line.getExactPaise();
        saveDecision(run.getWorker(), line.getReviewKey(), decision, amount, null, snapshot, reason);
        return calculate(run.getWorker().getId(), YearMonth.from(run.getPeriodStart()));
    }

    /**
     * Accepts the suggested salary reduction for partial work, or enters the unpaid day-units
     * (between 0 and the activity's share of the day) with a reason; then recalculates.
     */
    public EarningsResult reviewSalaryPartial(Long treatmentId, ReviewDecisionKind decision, String unpaidDayUnits, String reason) {
        EarningRecordTreatment treatment = treatmentRepository.findById(treatmentId)
                .orElseThrow(() -> new BusinessRuleException("Treatment #" + treatmentId + " was not found."));
        EarningRun run = requireDraft(treatment.getRun());
        if (treatment.getTreatment() != TreatmentType.SALARY_PARTIAL_REDUCTION) {
            throw new BusinessRuleException("Only partial work by a salaried worker has a salary reduction to review.");
        }
        String units = null;
        if (decision == ReviewDecisionKind.ACCEPT_SUGGESTED) {
            if (treatment.getSuggestedValue() == null) {
                throw new BusinessRuleException("There is no reliable suggestion for this partial work; enter the unpaid day-units instead.");
            }
        } else if (decision == ReviewDecisionKind.OVERRIDE) {
            requireReason(reason);
            Fraction value;
            try {
                value = Fraction.of(new BigDecimal(unpaidDayUnits == null ? "" : unpaidDayUnits.strip()));
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("Enter the unpaid day-units as a number, for example 0.25.");
            }
            Fraction max = Fraction.parse(treatment.getMaxValue());
            if (value.signum() < 0 || value.compareTo(max) > 0) {
                throw new BusinessRuleException("The unpaid day-units must be between 0 and " + max.toDecimalString(4)
                        + ", this activity's share of the day.");
            }
            units = value.toString();
        } else {
            throw new BusinessRuleException("Choose whether to accept the suggestion or enter the unpaid day-units.");
        }
        String snapshot = treatment.getSuggestedValue() == null ? EarningReview.NO_SUGGESTION : treatment.getSuggestedValue();
        saveDecision(run.getWorker(), treatment.getReviewKey(), decision, null, units, snapshot, reason);
        return calculate(run.getWorker().getId(), YearMonth.from(run.getPeriodStart()));
    }

    /**
     * Records the household's explicit statement about the activities currently left
     * unrecorded in the month. It lists every date and activity and stops applying as soon as
     * that set changes.
     */
    public EarningsResult confirmUnrecorded(Long workerId, YearMonth month) {
        Worker worker = workerService.getWorker(workerId);
        requireMonth(month);
        EarningRun existing = runRepository.findActive(workerId, month.atDay(1)).orElse(null);
        if (existing != null) {
            requireDraft(existing);
        }
        List<UnrecordedActivity> unrecorded = calculator.unrecorded(worker, month);
        if (unrecorded.isEmpty()) {
            throw new BusinessRuleException("Every scheduled activity in " + month + " is recorded; there is nothing to confirm.");
        }
        String statement = statement(unrecorded);
        String items = unrecorded.stream().map(UnrecordedActivity::key).sorted().collect(Collectors.joining(";"));
        confirmationRepository.findCurrent(workerId, month.atDay(1)).ifPresent(previous -> {
            previous.supersede(clock.instant());
            confirmationRepository.saveAndFlush(previous);
        });
        UnrecordedConfirmation confirmation = confirmationRepository.saveAndFlush(new UnrecordedConfirmation(worker,
                month.atDay(1), items, EarningsCalculator.fingerprint(unrecorded), statement, clock.instant()));
        auditService.record(workerId, ENTITY_CONFIRMATION, confirmation.getId(), "CONFIRM_UNRECORDED",
                "month=" + month + "; " + statement);
        return calculate(workerId, month);
    }

    /**
     * Finalizes a draft after the month has ended. The draft is recalculated first; finalizing
     * is refused while anything blocks it or if the total differs from the one reviewed.
     */
    public EarningsResult finalizeMonth(Long workerId, YearMonth month, long reviewedTotalPaise) {
        Worker worker = workerService.getWorker(workerId);
        requireMonth(month);
        LocalDate today = LocalDate.now(clock);
        if (!month.atEndOfMonth().isBefore(today)) {
            throw new BusinessRuleException("Earnings for " + month + " can be finalized after the month ends (from "
                    + month.atEndOfMonth().plusDays(1) + ").");
        }
        EarningRun run = runRepository.findActive(workerId, month.atDay(1))
                .orElseThrow(() -> new BusinessRuleException("Calculate " + worker.getName() + "'s earnings for " + month + " first."));
        requireDraft(run);
        EarningsResult result = calculate(workerId, month);
        if (!result.blockers().isEmpty()) {
            throw new BusinessRuleException("Earnings for " + worker.getName() + " in " + month + " cannot be finalized yet: "
                    + String.join(" ", result.blockers()));
        }
        if (result.totalPaise() != reviewedTotalPaise) {
            throw new BusinessRuleException("The total changed from " + Money.format(reviewedTotalPaise) + " to "
                    + Money.format(result.totalPaise()) + " since it was reviewed. Review the new calculation before finalizing.");
        }
        run.finalizeRun(clock.instant());
        runRepository.saveAndFlush(run);
        auditService.record(workerId, ENTITY_RUN, run.getId(), "FINALIZE", "month=" + month + "; total="
                + Money.format(run.getTotalPaise()) + "; warnings=" + result.warnings().size());
        return result(run, result.unrecorded());
    }

    /**
     * Reopens finalized earnings that have not been settled (D8). The finalized run is kept
     * exactly as it was and stops being active, which unlocks the month's dates; an OPEN
     * settlement version based on it is superseded too. A settled month must be reopened
     * through {@link SettlementService#reopenSettlement}, which reopens its earnings with it.
     */
    public EarningRun reopenEarnings(Long workerId, YearMonth month, String reason) {
        Worker worker = workerService.getWorker(workerId);
        requireMonth(month);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Enter a reason for reopening the earnings.");
        }
        EarningRun run = runRepository.findActive(workerId, month.atDay(1))
                .orElseThrow(() -> new BusinessRuleException("There are no earnings for " + month + " to reopen."));
        if (run.isDraft()) {
            throw new BusinessRuleException("Earnings for " + month + " are not finalized, so they can simply be recalculated.");
        }
        settlementRepository.findByWorkerIdAndPeriodStart(workerId, month.atDay(1))
                .flatMap(settlement -> versionRepository.findCurrent(settlement.getId()))
                .ifPresent(version -> {
                    if (!version.isOpen()) {
                        throw new BusinessRuleException(worker.getName() + "'s " + month
                                + " is settled. Reopen the settlement, which reopens its earnings with it.");
                    }
                    version.supersede("Earnings reopened: " + reason.strip(), clock.instant());
                    versionRepository.saveAndFlush(version);
                    auditService.record(workerId, SettlementService.ENTITY_VERSION, version.getId(), "SUPERSEDE",
                            "month=" + month + "; reason=Earnings reopened: " + reason.strip());
                });
        supersedeRun(run, reason.strip());
        return run;
    }

    /** Marks a finalized run as reopened without changing it. Used by both reopen paths. */
    void supersedeRun(EarningRun run, String reason) {
        EarningRunSupersession reopening = supersessionRepository.saveAndFlush(
                new EarningRunSupersession(run, reason, clock.instant()));
        auditService.record(run.getWorker().getId(), ENTITY_RUN, run.getId(), "REOPEN", "month="
                + YearMonth.from(run.getPeriodStart()) + "; total=" + Money.format(run.getTotalPaise())
                + "; reason=" + reason + "; supersession=" + reopening.getId());
    }

    /** The saved calculation as it is, without recalculating. */
    @Transactional(readOnly = true)
    public EarningsResult view(Long workerId, YearMonth month) {
        EarningRun run = runRepository.findActive(workerId, month.atDay(1))
                .orElseThrow(() -> new BusinessRuleException("There is no earnings calculation for " + month + " yet."));
        return result(run, List.of());
    }

    // ------------------------------------------------------------------ helpers

    private void clearDraft(EarningRun run) {
        treatmentRepository.deleteAll(treatmentRepository.findByRunId(run.getId()));
        treatmentRepository.flush();
        noteRepository.deleteAll(noteRepository.findByRunId(run.getId()));
        noteRepository.flush();
        lineRepository.deleteAll(lineRepository.findByRunIdOrderByLineNo(run.getId()));
        lineRepository.flush();
    }

    private EarningsResult result(EarningRun run, List<UnrecordedActivity> unrecorded) {
        List<EarningRunNote> notes = noteRepository.findByRunId(run.getId());
        return new EarningsResult(run, lineRepository.findByRunIdOrderByLineNo(run.getId()),
                treatmentRepository.findByRunId(run.getId()),
                notes.stream().filter(n -> n.getKind() == NoteKind.WARNING).map(EarningRunNote::getMessage).toList(),
                notes.stream().filter(n -> n.getKind() == NoteKind.BLOCKER).map(EarningRunNote::getMessage).toList(),
                List.copyOf(unrecorded));
    }

    private void saveDecision(Worker worker, String key, ReviewDecisionKind decision, Long amount, String units, String snapshot,
                              String reason) {
        reviewRepository.findCurrent(worker.getId(), key).ifPresent(previous -> {
            previous.supersede(clock.instant());
            reviewRepository.saveAndFlush(previous);
        });
        EarningReview review = reviewRepository.saveAndFlush(new EarningReview(worker, key, decision, amount, units, snapshot,
                reason == null || reason.isBlank() ? null : reason.strip(), clock.instant()));
        auditService.record(worker.getId(), ENTITY_REVIEW, review.getId(), decision.name(), "key=" + key
                + (amount == null ? "" : "; amount=" + Money.format(amount))
                + (units == null ? "" : "; unpaidDayUnits=" + units)
                + "; suggestion=" + snapshot + (reason == null || reason.isBlank() ? "" : "; reason=" + reason.strip()));
    }

    static String statement(List<UnrecordedActivity> unrecorded) {
        List<String> parts = new ArrayList<>();
        List<String> salary = unrecorded.stream().filter(a -> a.model() == PaymentModel.MONTHLY_SALARY)
                .map(UnrecordedActivity::description).toList();
        List<String> other = unrecorded.stream().filter(a -> a.model() != PaymentModel.MONTHLY_SALARY)
                .map(UnrecordedActivity::description).toList();
        if (!salary.isEmpty()) {
            parts.add(SALARY_STATEMENT + String.join("; ", salary) + ".");
        }
        if (!other.isEmpty()) {
            parts.add(OTHER_STATEMENT + String.join("; ", other) + ".");
        }
        return String.join(" ", parts);
    }

    private static EarningRun requireDraft(EarningRun run) {
        if (!run.isDraft()) {
            throw new BusinessRuleException("These earnings are finalized and cannot be changed.");
        }
        return run;
    }

    private static void requireMonth(YearMonth month) {
        if (month == null) {
            throw new BusinessRuleException("Choose a month.");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Enter a reason for the amount you are entering.");
        }
    }

    /** For tests and reports: the treatments that still need a decision. */
    @Transactional(readOnly = true)
    public List<EarningRecordTreatment> pendingSalaryReviews(EarningsResult result) {
        return result.treatments().stream()
                .filter(t -> t.getTreatment() == TreatmentType.SALARY_PARTIAL_REDUCTION && t.getReviewStatus() == ReviewStatus.NEEDS_REVIEW)
                .toList();
    }
}
