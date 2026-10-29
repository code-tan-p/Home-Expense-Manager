package com.homeexpense.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.homeexpense.dto.FinanceDtos.AdvanceBalance;
import com.homeexpense.dto.FinanceDtos.MonthlySummary;
import com.homeexpense.dto.FinanceDtos.OutstandingChoice;
import com.homeexpense.dto.FinanceDtos.OverpaymentChoice;
import com.homeexpense.dto.FinanceDtos.SettleRequest;
import com.homeexpense.dto.FinanceDtos.SettlementView;
import com.homeexpense.dto.FinanceDtos.ShortfallChoice;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningRecordTreatment;
import com.homeexpense.model.earnings.EarningRun;
import com.homeexpense.model.earnings.EarningRunNote;
import com.homeexpense.model.earnings.NoteKind;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.model.earnings.TreatmentType;
import com.homeexpense.model.finance.AdjustmentKind;
import com.homeexpense.model.finance.Advance;
import com.homeexpense.model.finance.AdvanceRecovery;
import com.homeexpense.model.finance.AdvanceSource;
import com.homeexpense.model.finance.AllocationKind;
import com.homeexpense.model.finance.AllocationReversal;
import com.homeexpense.model.finance.FinancialAdjustment;
import com.homeexpense.model.finance.PaymentAllocation;
import com.homeexpense.model.finance.RecoveryReversal;
import com.homeexpense.model.finance.SettleTreatment;
import com.homeexpense.model.finance.Settlement;
import com.homeexpense.model.finance.SettlementItem;
import com.homeexpense.model.finance.SettlementItemType;
import com.homeexpense.model.finance.SettlementRounding;
import com.homeexpense.model.finance.SettlementStatus;
import com.homeexpense.model.finance.SettlementVersion;
import com.homeexpense.model.finance.ShortfallTreatment;
import com.homeexpense.money.Money;
import com.homeexpense.repository.AdvanceRecoveryRepository;
import com.homeexpense.repository.AdvanceRepository;
import com.homeexpense.repository.AllocationReversalRepository;
import com.homeexpense.repository.EarningRecordTreatmentRepository;
import com.homeexpense.repository.EarningRunNoteRepository;
import com.homeexpense.repository.EarningRunRepository;
import com.homeexpense.repository.FinancialAdjustmentRepository;
import com.homeexpense.repository.PaymentAllocationRepository;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.RecoveryReversalRepository;
import com.homeexpense.repository.SettlementItemRepository;
import com.homeexpense.repository.SettlementRepository;
import com.homeexpense.repository.SettlementVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly settlements (Phase 5).
 *
 * <p>Calculation order for a month (Q5, R3, R4, D2, D10):
 * <ol>
 *   <li>E: finalized earnings of the active run. Approved unpaid time is shown, not subtracted (D11).</li>
 *   <li>N and P: negative and positive adjustments targeting the month, applied before recovery.</li>
 *   <li>If E + N &lt; 0 the shortfall blocks settling until each part is converted to an advance
 *   (only money declared owed by the worker, U1), carried to a later month, or left pending.</li>
 *   <li>Advance recovery, oldest advance first, capped by each balance and by E + N; positive
 *   carries never raise the cap. Recoveries become records only when the month is settled (Q8).</li>
 *   <li>Rounding of the net payable to rupees, as a separate item (exact by default).</li>
 * </ol>
 * Every public method is one transaction; history is never updated or deleted.
 */
@Service
@Transactional
public class SettlementService {

    static final String ENTITY_SETTLEMENT = "SETTLEMENT";
    static final String ENTITY_VERSION = "SETTLEMENT_VERSION";
    static final String ENTITY_ADJUSTMENT = "FINANCIAL_ADJUSTMENT";
    static final String ENTITY_ADVANCE = "ADVANCE";
    static final String ENTITY_RECOVERY = "ADVANCE_RECOVERY";
    static final String ENTITY_ALLOCATION = "PAYMENT_ALLOCATION";

    private final SettlementRepository settlementRepository;
    private final SettlementVersionRepository versionRepository;
    private final SettlementItemRepository itemRepository;
    private final FinancialAdjustmentRepository adjustmentRepository;
    private final AdvanceRepository advanceRepository;
    private final AdvanceRecoveryRepository recoveryRepository;
    private final RecoveryReversalRepository recoveryReversalRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final PaymentRepository paymentRepository;
    private final AllocationReversalRepository allocationReversalRepository;
    private final EarningRunRepository runRepository;
    private final EarningRunNoteRepository runNoteRepository;
    private final EarningRecordTreatmentRepository treatmentRepository;
    private final EarningsService earningsService;
    private final WorkerService workerService;
    private final FinanceLedger ledger;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public SettlementService(SettlementRepository settlementRepository, SettlementVersionRepository versionRepository,
                             SettlementItemRepository itemRepository, FinancialAdjustmentRepository adjustmentRepository,
                             AdvanceRepository advanceRepository, AdvanceRecoveryRepository recoveryRepository,
                             RecoveryReversalRepository recoveryReversalRepository, PaymentAllocationRepository allocationRepository,
                             PaymentRepository paymentRepository, AllocationReversalRepository allocationReversalRepository, EarningRunRepository runRepository,
                             EarningRunNoteRepository runNoteRepository, EarningRecordTreatmentRepository treatmentRepository,
                             EarningsService earningsService, WorkerService workerService, FinanceLedger ledger,
                             AuditService auditService, Clock clock) {
        this.settlementRepository = settlementRepository;
        this.versionRepository = versionRepository;
        this.itemRepository = itemRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.advanceRepository = advanceRepository;
        this.recoveryRepository = recoveryRepository;
        this.recoveryReversalRepository = recoveryReversalRepository;
        this.allocationRepository = allocationRepository;
        this.paymentRepository = paymentRepository;
        this.allocationReversalRepository = allocationReversalRepository;
        this.runRepository = runRepository;
        this.runNoteRepository = runNoteRepository;
        this.treatmentRepository = treatmentRepository;
        this.earningsService = earningsService;
        this.workerService = workerService;
        this.ledger = ledger;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ================================================================== calculation

    private record PlannedItem(SettlementItemType type, long amount, EarningRun run, FinancialAdjustment adjustment,
                               Advance advance, String explanation) {
    }

    private record Computation(long earnings, long negative, long positive, long shortfall, long shortfallHandled,
                               long owedAvailable, long recovery, Map<Advance, Long> recoveries, long rounding, long net,
                               List<PlannedItem> items, List<String> blockers, List<String> warnings) {

        long unresolvedShortfall() {
            return Math.max(0, shortfall - shortfallHandled);
        }
    }

    private Computation compute(Settlement settlement, SettlementVersion version) {
        Worker worker = settlement.getWorker();
        YearMonth month = YearMonth.from(settlement.getPeriodStart());
        EarningRun run = version.getEarningRun();
        List<PlannedItem> items = new ArrayList<>();
        Set<String> blockers = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();

        long earnings = run.getTotalPaise();
        items.add(new PlannedItem(SettlementItemType.EARNINGS, earnings, run, null, null, "Finalized earnings for " + month
                + " (earnings calculation #" + run.getId() + "): " + Money.format(earnings) + "."));
        for (EarningRunNote note : runNoteRepository.findByRunId(run.getId())) {
            if (note.getKind() == NoteKind.WARNING) {
                warnings.add("Earnings: " + note.getMessage());
            }
        }
        List<String> unpaid = unpaidTime(run);
        if (!unpaid.isEmpty()) {
            items.add(new PlannedItem(SettlementItemType.UNPAID_TIME_INFO, 0, null, null, null, "For information, already reflected "
                    + "in the earnings and not deducted again: " + String.join(" ", unpaid)));
        }

        // Adjustments targeting this month, net of their reversals.
        long negative = 0;
        long positive = 0;
        long nonOwedNegative = 0;
        Map<FinancialAdjustment, Long> incoming = FinanceLedger.netByRoot(
                adjustmentRepository.findTargeting(worker.getId(), settlement.getPeriodStart()));
        for (Map.Entry<FinancialAdjustment, Long> entry : incoming.entrySet()) {
            long amount = entry.getValue();
            if (amount == 0) {
                continue;
            }
            FinancialAdjustment root = entry.getKey();
            if (amount < 0) {
                negative += amount;
                if (!Boolean.TRUE.equals(root.getMoneyOwedByWorker())) {
                    nonOwedNegative -= amount;
                }
            } else {
                positive += amount;
            }
            items.add(new PlannedItem(SettlementItemType.ADJUSTMENT, amount, null, root, null, describeAdjustment(root, amount)));
        }

        // Shortfall (R4): negative adjustments larger than the earnings.
        long base = earnings + negative;
        long shortfall = Math.max(0, -base);
        long handled = ledger.shortfallHandled(settlement);
        long owedAvailable = 0;
        if (shortfall > 0) {
            long owedCapacity = Math.max(0, shortfall - nonOwedNegative);
            long owedUsed = adjustmentRepository.findBySource(settlement.getId()).stream()
                    .filter(FinancialAdjustment::isAffectsSourceShortfall)
                    .filter(f -> Boolean.TRUE.equals(f.getMoneyOwedByWorker()))
                    .mapToLong(f -> -f.getAmountPaise()).sum();
            owedAvailable = Math.max(0, owedCapacity - owedUsed);
            items.add(new PlannedItem(SettlementItemType.SHORTFALL, shortfall, null, null, null, "Negative adjustments ("
                    + Money.format(-negative) + ") are more than the earnings (" + Money.format(earnings) + ") by "
                    + Money.format(shortfall) + "; that remainder is handled separately and never paid from this month."));
            long unresolved = shortfall - handled;
            if (handled > shortfall) {
                blockers.add("More of the shortfall has been handled (" + Money.format(handled) + ") than now exists ("
                        + Money.format(shortfall) + "). Reverse the extra remainder adjustment first.");
            } else if (unresolved > 0 && version.getShortfallTreatment() != ShortfallTreatment.PENDING) {
                blockers.add(Money.format(unresolved) + " of negative adjustments is more than this month's earnings. Choose how to "
                        + "handle it: convert to an advance (only money owed by the worker, up to " + Money.format(Math.min(unresolved, owedAvailable))
                        + "), carry it to a later month, or leave it pending.");
            } else if (unresolved > 0) {
                warnings.add(Money.format(unresolved) + " of negative adjustments beyond this month's earnings is left pending.");
            }
        } else if (handled > 0) {
            blockers.add("A shortfall remainder of " + Money.format(handled) + " was handled, but there is no shortfall now. "
                    + "Reverse that remainder adjustment first.");
        }

        // Advance recovery (D2, R3): oldest first, capped by each balance and by E + N.
        long cap = Math.max(0, base);
        Map<Advance, Long> recoveries = new LinkedHashMap<>();
        long recovery = 0;
        for (Advance advance : advanceRepository.findByWorkerIdOrderByGivenOnAscIdAsc(worker.getId())) {
            if (!recoverableFrom(advance, settlement)) {
                continue;
            }
            long take = Math.min(ledger.balanceExcluding(advance, settlement), cap - recovery);
            if (take > 0) {
                recoveries.put(advance, take);
                recovery += take;
                items.add(new PlannedItem(SettlementItemType.ADVANCE_RECOVERY, -take, null, null, advance, "Recovery of advance #"
                        + advance.getId() + " given on " + advance.getGivenOn() + " (" + Money.format(advance.getAmountPaise())
                        + ", oldest first): " + Money.format(take) + "."));
            }
        }

        long beforeRounding = cap + positive - recovery;
        long rounding = rounding(beforeRounding, version.getRoundingMode());
        if (rounding != 0) {
            items.add(new PlannedItem(SettlementItemType.ROUNDING, rounding, null, null, null, "Rounding "
                    + version.getRoundingMode().name().toLowerCase() + " to whole rupees: " + Money.format(beforeRounding)
                    + " becomes " + Money.format(beforeRounding + rounding) + "."));
        }
        return new Computation(earnings, negative, positive, shortfall, handled, owedAvailable, recovery, recoveries, rounding,
                beforeRounding + rounding, items, List.copyOf(blockers), List.copyOf(warnings));
    }

    /**
     * Which advances a month can recover (the "eligible" advances of D2). An advance is not
     * recovered from a month if it was given after that month ended, if it is an overpayment of
     * that very month, or if the payment that funded it also paid that month: otherwise the
     * same money would count both as the advance and as the month's pay.
     */
    private boolean recoverableFrom(Advance advance, Settlement settlement) {
        if (advance.getGivenOn().isAfter(YearMonth.from(settlement.getPeriodStart()).atEndOfMonth())) {
            return false;
        }
        if (advance.getSourceSettlement() != null && advance.getSourceSettlement().getId().equals(settlement.getId())) {
            return false;
        }
        for (PaymentAllocation funding : allocationRepository.findActiveByAdvance(advance.getId())) {
            boolean paidThisMonth = allocationRepository.findActiveByPayment(funding.getPayment().getId()).stream()
                    .anyMatch(a -> a.getSettlement() != null && a.getSettlement().getId().equals(settlement.getId()));
            if (paidThisMonth) {
                return false;
            }
        }
        return true;
    }

    /** D10: exact by default; up, down or nearest (half-up) whole rupee. */
    static long rounding(long paise, SettlementRounding mode) {
        long remainder = Math.floorMod(paise, 100);
        if (remainder == 0 || mode == SettlementRounding.EXACT) {
            return 0;
        }
        return switch (mode) {
            case UP -> 100 - remainder;
            case DOWN -> -remainder;
            case NEAREST -> remainder >= 50 ? 100 - remainder : -remainder;
            case EXACT -> 0;
        };
    }

    private List<String> unpaidTime(EarningRun run) {
        List<String> lines = new ArrayList<>();
        for (EarningRecordTreatment treatment : treatmentRepository.findByRunId(run.getId())) {
            boolean unpaid = treatment.getTreatment() == TreatmentType.UNPAID_LEAVE
                    || treatment.getTreatment() == TreatmentType.ABSENCE_APPROVED_UNPAID
                    || (treatment.getTreatment() == TreatmentType.SALARY_PARTIAL_REDUCTION
                    && treatment.getReviewStatus() != ReviewStatus.NEEDS_REVIEW);
            if (unpaid) {
                lines.add(treatment.getDetail());
            }
        }
        return lines;
    }

    private static String describeAdjustment(FinancialAdjustment root, long net) {
        String from = root.getSourceSettlement() == null ? "" : " from " + YearMonth.from(root.getSourceSettlement().getPeriodStart());
        return "Adjustment #" + root.getId() + " (" + root.getKind().name().toLowerCase().replace('_', ' ') + from + "): "
                + Money.format(net) + (net != root.getAmountPaise() ? " after reversal" : "") + ". Reason: " + root.getReason();
    }

    /** Recalculates an OPEN version and replaces its items. */
    private Computation refresh(Settlement settlement, SettlementVersion version) {
        Computation c = compute(settlement, version);
        itemRepository.deleteAll(itemRepository.findByVersionId(version.getId()));
        itemRepository.flush();
        version.recordFigures(c.earnings(), c.negative(), c.shortfall(), c.positive(),
                c.recovery(), c.rounding(), c.net(), clock.instant());
        versionRepository.saveAndFlush(version);
        int lineNo = 1;
        for (PlannedItem item : c.items()) {
            itemRepository.save(new SettlementItem(version, lineNo++, item.type(), item.amount(), item.run(), item.adjustment(),
                    item.advance(), item.explanation()));
        }
        itemRepository.flush();
        return c;
    }

    // ================================================================== preparing and viewing

    /** Prepares (or recalculates) the OPEN settlement for a month with finalized earnings (D1). */
    public SettlementView prepare(Long workerId, YearMonth month) {
        Worker worker = workerService.getWorker(workerId);
        EarningRun run = runRepository.findActive(workerId, month.atDay(1))
                .filter(r -> !r.isDraft())
                .orElseThrow(() -> new BusinessRuleException("Finalize " + worker.getName() + "'s earnings for " + month
                        + " before preparing the settlement."));
        Settlement settlement = settlementRepository.findByWorkerIdAndPeriodStart(workerId, month.atDay(1)).orElseGet(() -> {
            Settlement created = settlementRepository.saveAndFlush(new Settlement(worker, month.atDay(1), clock.instant()));
            auditService.record(workerId, ENTITY_SETTLEMENT, created.getId(), "CREATE", "month=" + month);
            return created;
        });
        SettlementVersion version = ledger.currentVersion(settlement).orElse(null);
        if (version != null && version.getStatus() == SettlementStatus.SETTLED) {
            throw new BusinessRuleException(month + " is already settled. Reopen it, with a reason, to change it.");
        }
        if (version == null) {
            int next = versionRepository.findBySettlementIdOrderByVersionNoAsc(settlement.getId()).size() + 1;
            version = versionRepository.saveAndFlush(new SettlementVersion(settlement, next, run, clock.instant()));
            auditService.record(workerId, ENTITY_VERSION, version.getId(), "PREPARE", "month=" + month + "; version=" + next
                    + "; earningsRun=" + run.getId());
        }
        refresh(settlement, version);
        return view(settlement, version);
    }

    @Transactional(readOnly = true)
    public SettlementView view(Long workerId, YearMonth month) {
        Settlement settlement = requireSettlement(workerId, month);
        SettlementVersion version = ledger.currentVersion(settlement)
                .orElseThrow(() -> new BusinessRuleException(month + " has no current settlement; prepare it first."));
        return view(settlement, version);
    }

    private SettlementView view(Settlement settlement, SettlementVersion version) {
        long allocated = ledger.allocated(settlement);
        long carried = ledger.carriedOut(settlement);
        long outstanding = version.getNetPayablePaise() - allocated - carried;
        long credit = unallocatedCredit(settlement.getWorker().getId());
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (version.isOpen()) {
            Computation c = compute(settlement, version);
            blockers.addAll(c.blockers());
            warnings.addAll(c.warnings());
        }
        if (credit > 0) {
            warnings.add(Money.format(credit) + " of payments is unallocated credit. Allocate it explicitly when ready.");
        }
        if (version.getSettleTreatment() == SettleTreatment.OVERPAYMENT_PENDING) {
            warnings.add("An overpayment of " + Money.format(Math.max(0, -outstanding)) + " is pending a decision.");
        }
        return new SettlementView(settlement, version, itemRepository.findByVersionId(version.getId()), allocated, carried,
                outstanding, credit, List.copyOf(blockers), List.copyOf(warnings));
    }

    private long unallocatedCredit(Long workerId) {
        return paymentRepository.findByWorkerIdOrderByPaidOnAscIdAsc(workerId).stream().mapToLong(ledger::unallocated).sum();
    }

    // ================================================================== rounding, shortfall, corrections

    /** Sets the rounding mode of an OPEN settlement; EXACT undoes rounding (D10). */
    public SettlementView setRounding(Long workerId, YearMonth month, SettlementRounding mode) {
        Settlement settlement = requireSettlement(workerId, month);
        SettlementVersion version = requireOpen(settlement, month);
        if (mode == null) {
            throw new BusinessRuleException("Choose a rounding mode.");
        }
        SettlementRounding before = version.getRoundingMode();
        version.useRounding(mode);
        Computation c = refresh(settlement, version);
        auditService.record(workerId, ENTITY_VERSION, version.getId(), "ROUNDING", "month=" + month + "; from=" + before + "; to="
                + mode + "; rounding=" + Money.format(c.rounding()) + "; net=" + Money.format(c.net()));
        return view(settlement, version);
    }

    /** R4/U1: handles the part of negative adjustments that is larger than the month's earnings. */
    public SettlementView resolveShortfall(Long workerId, YearMonth month, ShortfallChoice choice, String amountRupees,
                                           YearMonth target, String reason) {
        Settlement settlement = requireSettlement(workerId, month);
        SettlementVersion version = requireOpen(settlement, month);
        String why = requireReason(reason);
        Computation c = compute(settlement, version);
        long unresolved = c.unresolvedShortfall();
        if (unresolved <= 0) {
            throw new BusinessRuleException("There is no unresolved shortfall in " + month + ".");
        }
        if (choice == null) {
            throw new BusinessRuleException("Choose how to handle the shortfall.");
        }
        Worker worker = settlement.getWorker();
        LocalDate today = LocalDate.now(clock);
        switch (choice) {
            case PENDING -> {
                version.leaveShortfallPending();
                versionRepository.saveAndFlush(version);
                auditService.record(workerId, ENTITY_VERSION, version.getId(), "SHORTFALL_PENDING", "month=" + month
                        + "; amount=" + Money.format(unresolved) + "; reason=" + why);
            }
            case TO_ADVANCE -> {
                long amount = amountWithin(amountRupees, unresolved);
                if (amount > c.owedAvailable()) {
                    throw new BusinessRuleException("Only " + Money.format(c.owedAvailable()) + " of this shortfall comes from adjustments "
                            + "declared as money owed by the worker, so only that much can become an advance. Carry the rest to a "
                            + "later month or leave it pending.");
                }
                FinancialAdjustment marker = adjustmentRepository.saveAndFlush(new FinancialAdjustment(worker,
                        AdjustmentKind.REMAINDER_TO_ADVANCE, -amount, why, today, settlement, version, null, true, false, true, null,
                        clock.instant()));
                Advance advance = advanceRepository.saveAndFlush(new Advance(worker, today, amount, AdvanceSource.REMAINDER,
                        settlement, marker, why, clock.instant()));
                auditService.record(workerId, ENTITY_ADVANCE, advance.getId(), "CREATE_FROM_SHORTFALL", "month=" + month
                        + "; amount=" + Money.format(amount) + "; adjustment=" + marker.getId() + "; reason=" + why);
            }
            case CARRY -> {
                long amount = amountWithin(amountRupees, unresolved);
                LocalDate targetStart = validTarget(settlement, target);
                long owedPart = Math.min(amount, c.owedAvailable());
                if (owedPart > 0) {
                    saveCarry(worker, AdjustmentKind.REMAINDER_CARRY, -owedPart, why, settlement, version, targetStart, true, false, true);
                }
                if (amount - owedPart > 0) {
                    saveCarry(worker, AdjustmentKind.REMAINDER_CARRY, -(amount - owedPart), why, settlement, version, targetStart,
                            false, false, true);
                }
            }
        }
        refresh(settlement, version);
        refreshOpenMonth(workerId, target == null ? null : target.atDay(1));
        return view(settlement, version);
    }

    /** A manual correction to a month that is not settled. Negative amounts must declare whether the worker owes the money (U1). */
    public FinancialAdjustment addCorrection(Long workerId, YearMonth targetMonth, String amountRupees, Boolean moneyOwedByWorker,
                                             String reason) {
        Worker worker = workerService.getWorker(workerId);
        String why = requireReason(reason);
        if (targetMonth == null) {
            throw new BusinessRuleException("Choose the month this correction applies to.");
        }
        long amount = Money.parseRupees(amountRupees);
        if (amount == 0) {
            throw new BusinessRuleException("A correction must change the amount.");
        }
        if (amount < 0 && moneyOwedByWorker == null) {
            throw new BusinessRuleException("Say whether this reduction is money the worker owes (for example, money paid by mistake).");
        }
        if (ledger.isMonthSettled(workerId, targetMonth.atDay(1))) {
            throw new BusinessRuleException(targetMonth + " is already settled. Choose a later month, or reopen it first.");
        }
        FinancialAdjustment adjustment = adjustmentRepository.saveAndFlush(new FinancialAdjustment(worker, AdjustmentKind.CORRECTION,
                amount, why, LocalDate.now(clock), null, null, targetMonth.atDay(1), amount < 0 ? moneyOwedByWorker : null, false, false,
                null, clock.instant()));
        auditService.record(workerId, ENTITY_ADJUSTMENT, adjustment.getId(), "CREATE", "kind=CORRECTION; target=" + targetMonth
                + "; amount=" + Money.format(amount) + "; moneyOwedByWorker=" + adjustment.getMoneyOwedByWorker() + "; reason=" + why);
        refreshOpenMonth(workerId, targetMonth.atDay(1));
        return adjustment;
    }

    /** Reverses an adjustment (fully, or partly with an amount) with a linked mirror record. */
    public FinancialAdjustment reverseAdjustment(Long adjustmentId, String amountRupees, String reason) {
        FinancialAdjustment original = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new BusinessRuleException("Adjustment #" + adjustmentId + " was not found."));
        String why = requireReason(reason);
        if (original.getKind() == AdjustmentKind.REVERSAL) {
            throw new BusinessRuleException("A reversal cannot itself be reversed.");
        }
        if (adjustmentRepository.isReversed(adjustmentId)) {
            throw new BusinessRuleException("Adjustment #" + adjustmentId + " has already been reversed.");
        }
        if (original.getKind() == AdjustmentKind.REMAINDER_TO_ADVANCE) {
            throw new BusinessRuleException("A shortfall converted to an advance is recovered from earnings; it cannot be reversed.");
        }
        long size = Math.abs(original.getAmountPaise());
        long amount = amountRupees == null || amountRupees.isBlank() ? size : Money.parsePositiveRupees(amountRupees);
        if (amount > size) {
            throw new BusinessRuleException("A reversal cannot be more than the original " + Money.format(size) + ".");
        }
        if (original.getTargetPeriodStart() != null && ledger.isMonthSettled(original.getWorker().getId(), original.getTargetPeriodStart())) {
            throw new BusinessRuleException(YearMonth.from(original.getTargetPeriodStart()) + " is already settled, so this adjustment "
                    + "can no longer be reversed. Use a negative adjustment to a later month instead.");
        }
        FinancialAdjustment reversal = adjustmentRepository.saveAndFlush(reversalOf(original, amount, why));
        auditService.record(original.getWorker().getId(), ENTITY_ADJUSTMENT, reversal.getId(), "REVERSE", "reverses=" + adjustmentId
                + "; amount=" + Money.format(reversal.getAmountPaise()) + "; reason=" + why);
        refreshOpenMonth(original.getWorker().getId(), original.getTargetPeriodStart());
        if (original.getSourceSettlement() != null) {
            refreshOpenMonth(original.getWorker().getId(), original.getSourceSettlement().getPeriodStart());
        }
        return reversal;
    }

    private FinancialAdjustment reversalOf(FinancialAdjustment original, long amount, String why) {
        long signed = original.getAmountPaise() > 0 ? -amount : amount;
        return new FinancialAdjustment(original.getWorker(), AdjustmentKind.REVERSAL, signed, why, LocalDate.now(clock),
                original.getSourceSettlement(), original.getSourceVersion(), original.getTargetPeriodStart(),
                original.getMoneyOwedByWorker(), original.isAffectsSourceCover(), original.isAffectsSourceShortfall(), original,
                clock.instant());
    }

    /**
     * U2: the part of an overpayment caused by an earlier carry-forward (not by money paid).
     * Reverses the carry while its target month is unsettled; otherwise moves the excess as a
     * negative correction to a later month you choose. Never creates an advance.
     */
    public SettlementView correctCarry(Long workerId, YearMonth month, YearMonth laterTarget, String reason) {
        Settlement settlement = requireSettlement(workerId, month);
        SettlementVersion version = requireOpen(settlement, month);
        String why = requireReason(reason);
        refresh(settlement, version);
        long overpaid = -(version.getNetPayablePaise() - ledger.allocated(settlement) - ledger.carriedOut(settlement));
        long remaining = Math.min(Math.max(0, overpaid), carriedForward(settlement));
        if (remaining <= 0) {
            throw new BusinessRuleException("No part of " + month + "'s balance comes from an earlier carry-forward.");
        }
        List<Map.Entry<FinancialAdjustment, Long>> carries = new ArrayList<>(FinanceLedger.netByRoot(
                adjustmentRepository.findBySource(settlement.getId())).entrySet());
        Collections.reverse(carries);
        for (Map.Entry<FinancialAdjustment, Long> carry : carries) {
            FinancialAdjustment root = carry.getKey();
            if (remaining == 0 || root.getKind() != AdjustmentKind.CARRY_FORWARD || carry.getValue() <= 0
                    || adjustmentRepository.isReversed(root.getId()) || ledger.isMonthSettled(workerId, root.getTargetPeriodStart())) {
                continue;
            }
            long take = Math.min(remaining, carry.getValue());
            FinancialAdjustment reversal = adjustmentRepository.saveAndFlush(reversalOf(root, take, why));
            auditService.record(workerId, ENTITY_ADJUSTMENT, reversal.getId(), "REVERSE_CARRY", "reverses=" + root.getId()
                    + "; amount=" + Money.format(take) + "; reason=" + why);
            refreshOpenMonth(workerId, root.getTargetPeriodStart());
            remaining -= take;
        }
        if (remaining > 0) {
            if (laterTarget == null) {
                throw new BusinessRuleException(Money.format(remaining) + " was carried into a month that is already settled. Choose "
                        + "a later month for a negative correction.");
            }
            LocalDate targetStart = validTarget(settlement, laterTarget);
            saveCarry(settlement.getWorker(), AdjustmentKind.CARRY_CORRECTION, -remaining, why, settlement, version, targetStart, false,
                    true, false);
            refreshOpenMonth(workerId, targetStart);
        }
        refresh(settlement, version);
        return view(settlement, version);
    }

    /**
     * The carry-backed amount still in a settlement's cover (U2): carry-forwards out of it,
     * net of their reversals, minus the carry corrections already made against them. Never
     * negative. Whatever overpayment exceeds this is payment-backed and gets the D9 choices.
     */
    private long carriedForward(Settlement settlement) {
        long carried = 0;
        long corrected = 0;
        for (Map.Entry<FinancialAdjustment, Long> entry : FinanceLedger.netByRoot(
                adjustmentRepository.findBySource(settlement.getId())).entrySet()) {
            AdjustmentKind kind = entry.getKey().getKind();
            if (kind == AdjustmentKind.CARRY_FORWARD && entry.getValue() > 0) {
                carried += entry.getValue();
            } else if (kind == AdjustmentKind.CARRY_CORRECTION && entry.getValue() < 0) {
                corrected -= entry.getValue();
            }
        }
        return Math.max(0, carried - corrected);
    }

    // ================================================================== settling

    /**
     * Settles the OPEN version in one transaction: recalculates and checks the reviewed net,
     * applies the outstanding (D6) or overpayment (D9) decision, records the recoveries (Q8)
     * and freezes the snapshot.
     */
    public SettlementView settle(Long workerId, YearMonth month, SettleRequest request) {
        Settlement settlement = requireSettlement(workerId, month);
        SettlementVersion version = requireOpen(settlement, month);
        Computation c = refresh(settlement, version);
        if (c.net() != request.expectedNetPaise()) {
            throw new BusinessRuleException("The net payable changed from " + Money.format(request.expectedNetPaise()) + " to "
                    + Money.format(c.net()) + " since it was reviewed. Review the settlement again.");
        }
        if (!c.blockers().isEmpty()) {
            throw new BusinessRuleException(month + " cannot be settled yet: " + String.join(" ", c.blockers()));
        }
        long outstanding = c.net() - ledger.allocated(settlement) - ledger.carriedOut(settlement);
        SettleTreatment treatment = SettleTreatment.NONE;
        if (outstanding > 0) {
            treatment = settleOutstanding(settlement, version, outstanding, request, month);
        } else if (outstanding < 0) {
            treatment = settleOverpayment(settlement, version, -outstanding, request, month);
        }
        applyRecoveries(settlement, version, c.recoveries());
        long finalOutstanding = c.net() - ledger.allocated(settlement) - ledger.carriedOut(settlement);
        version.settle(treatment, finalOutstanding, clock.instant());
        versionRepository.saveAndFlush(version);
        auditService.record(workerId, ENTITY_VERSION, version.getId(), "SETTLE", "month=" + month + "; version=" + version.getVersionNo()
                + "; earnings=" + Money.format(c.earnings()) + "; adjustments=" + Money.format(c.negative() + c.positive())
                + "; recovery=" + Money.format(c.recovery()) + "; rounding=" + Money.format(c.rounding()) + "; net=" + Money.format(c.net())
                + "; outstanding=" + Money.format(finalOutstanding) + "; treatment=" + treatment
                + (request.reason() == null ? "" : "; reason=" + request.reason().strip()));
        return view(settlement, version);
    }

    private SettleTreatment settleOutstanding(Settlement settlement, SettlementVersion version, long outstanding, SettleRequest request,
                                              YearMonth month) {
        if (request.outstanding() == null) {
            throw new BusinessRuleException(Money.format(outstanding) + " of " + month + " is still unpaid. Choose to carry it forward to "
                    + "a later month or to leave it outstanding.");
        }
        if (request.outstanding() == OutstandingChoice.LEAVE_OUTSTANDING) {
            return SettleTreatment.LEAVE_OUTSTANDING;
        }
        String why = requireReason(request.reason());
        LocalDate target = validTarget(settlement, request.target() == null ? month.plusMonths(1) : request.target());
        saveCarry(settlement.getWorker(), AdjustmentKind.CARRY_FORWARD, outstanding, why, settlement, version, target, null, true, false);
        return SettleTreatment.CARRY_FORWARD;
    }

    private SettleTreatment settleOverpayment(Settlement settlement, SettlementVersion version, long overpaid, SettleRequest request,
                                              YearMonth month) {
        long carryBacked = Math.min(overpaid, carriedForward(settlement));
        if (carryBacked > 0) {
            throw new BusinessRuleException(Money.format(carryBacked) + " of the overpayment comes from an earlier carry-forward, not from "
                    + "money paid. Correct the carry first.");
        }
        if (request.overpayment() == null) {
            throw new BusinessRuleException(month + " has been overpaid by " + Money.format(overpaid) + ". Choose: convert it to an advance, "
                    + "carry it as a negative adjustment to a later month, or leave it pending.");
        }
        String why = requireReason(request.reason());
        return switch (request.overpayment()) {
            case PENDING -> SettleTreatment.OVERPAYMENT_PENDING;
            case CARRY -> {
                LocalDate target = validTarget(settlement, request.target() == null ? month.plusMonths(1) : request.target());
                saveCarry(settlement.getWorker(), AdjustmentKind.OVERPAYMENT_CARRY, -overpaid, why, settlement, version, target, true,
                        true, false);
                yield SettleTreatment.OVERPAYMENT_CARRY;
            }
            case TO_ADVANCE -> {
                overpaymentToAdvance(settlement, overpaid, why);
                yield SettleTreatment.OVERPAYMENT_TO_ADVANCE;
            }
        };
    }

    /** D9 option 1: the excess payment money is re-allocated from the month to a new advance (newest allocations first). */
    private void overpaymentToAdvance(Settlement settlement, long overpaid, String why) {
        Worker worker = settlement.getWorker();
        List<PaymentAllocation> allocations = new ArrayList<>(allocationRepository.findActiveBySettlement(settlement.getId()));
        Collections.reverse(allocations);
        // The advance dates from when the money was actually paid: the latest payment moved into it.
        LocalDate givenOn = null;
        long counted = 0;
        for (PaymentAllocation allocation : allocations) {
            if (counted >= overpaid) {
                break;
            }
            LocalDate paidOn = allocation.getPayment().getPaidOn();
            givenOn = givenOn == null || paidOn.isAfter(givenOn) ? paidOn : givenOn;
            counted += allocation.getAmountPaise();
        }
        Advance advance = advanceRepository.saveAndFlush(new Advance(worker, givenOn == null ? LocalDate.now(clock) : givenOn, overpaid,
                AdvanceSource.OVERPAYMENT, settlement, null, why, clock.instant()));
        long remaining = overpaid;
        List<long[]> keep = new ArrayList<>();
        List<PaymentAllocation> moved = new ArrayList<>();
        for (PaymentAllocation allocation : allocations) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, allocation.getAmountPaise());
            allocationReversalRepository.saveAndFlush(new AllocationReversal(allocation, "Overpayment converted to advance #"
                    + advance.getId() + ": " + why, clock.instant()));
            keep.add(new long[] {allocation.getAmountPaise() - take, take});
            moved.add(allocation);
            remaining -= take;
        }
        for (int i = 0; i < moved.size(); i++) {
            PaymentAllocation original = moved.get(i);
            long kept = keep.get(i)[0];
            long take = keep.get(i)[1];
            if (kept > 0) {
                allocationRepository.saveAndFlush(new PaymentAllocation(original.getPayment(), settlement, null, kept,
                        AllocationKind.KEPT_AFTER_REALLOCATION, clock.instant()));
            }
            PaymentAllocation toAdvance = allocationRepository.saveAndFlush(new PaymentAllocation(original.getPayment(), null, advance, take,
                    AllocationKind.OVERPAYMENT_TO_ADVANCE, clock.instant()));
            auditService.record(worker.getId(), ENTITY_ALLOCATION, toAdvance.getId(), "REALLOCATE_TO_ADVANCE", "payment="
                    + original.getPayment().getId() + "; reversedAllocation=" + original.getId() + "; amount=" + Money.format(take)
                    + "; advance=" + advance.getId());
        }
        auditService.record(worker.getId(), ENTITY_ADVANCE, advance.getId(), "CREATE_FROM_OVERPAYMENT", "month="
                + YearMonth.from(settlement.getPeriodStart()) + "; amount=" + Money.format(overpaid) + "; reason=" + why);
    }

    /** Q8: recovery records are created (or reconciled after reopening) only when the month is settled. */
    private void applyRecoveries(Settlement settlement, SettlementVersion version, Map<Advance, Long> planned) {
        Long workerId = settlement.getWorker().getId();
        Map<Long, Long> desired = new LinkedHashMap<>();
        planned.forEach((advance, amount) -> desired.put(advance.getId(), amount));
        Set<Long> keep = new LinkedHashSet<>();
        for (AdvanceRecovery existing : recoveryRepository.findActiveBySettlement(settlement.getId())) {
            Long advanceId = existing.getAdvance().getId();
            if (desired.getOrDefault(advanceId, 0L) == existing.getAmountPaise()) {
                keep.add(advanceId);
                continue;
            }
            recoveryReversalRepository.saveAndFlush(new RecoveryReversal(existing, "Recalculated when " + YearMonth.from(
                    settlement.getPeriodStart()) + " was settled again (version " + version.getVersionNo() + ")", clock.instant()));
            auditService.record(workerId, ENTITY_RECOVERY, existing.getId(), "REVERSE", "advance=" + advanceId + "; amount="
                    + Money.format(existing.getAmountPaise()));
        }
        for (Map.Entry<Advance, Long> entry : planned.entrySet()) {
            if (keep.contains(entry.getKey().getId())) {
                continue;
            }
            AdvanceRecovery recovery = recoveryRepository.saveAndFlush(new AdvanceRecovery(entry.getKey(), settlement, version,
                    entry.getValue(), clock.instant()));
            auditService.record(workerId, ENTITY_RECOVERY, recovery.getId(), "RECOVER", "advance=" + entry.getKey().getId()
                    + "; month=" + YearMonth.from(settlement.getPeriodStart()) + "; amount=" + Money.format(entry.getValue()));
        }
    }

    // ================================================================== reopening

    /** D8: reopens a settled month together with its earnings. Every earlier record is kept. */
    public SettlementVersion reopenSettlement(Long workerId, YearMonth month, String reason) {
        Settlement settlement = requireSettlement(workerId, month);
        String why = requireReason(reason);
        SettlementVersion version = ledger.currentVersion(settlement)
                .filter(v -> v.getStatus() == SettlementStatus.SETTLED)
                .orElseThrow(() -> new BusinessRuleException(month + " is not settled, so there is nothing to reopen."));
        version.supersede(why, clock.instant());
        versionRepository.saveAndFlush(version);
        auditService.record(workerId, ENTITY_VERSION, version.getId(), "REOPEN", "month=" + month + "; version=" + version.getVersionNo()
                + "; net=" + Money.format(version.getNetPayablePaise()) + "; reason=" + why);
        EarningRun run = version.getEarningRun();
        if (runRepository.findActive(workerId, month.atDay(1)).map(r -> r.getId().equals(run.getId())).orElse(false)) {
            earningsService.supersedeRun(run, why);
        }
        return version;
    }

    // ================================================================== summaries and warnings

    @Transactional(readOnly = true)
    public MonthlySummary summary(Long workerId, YearMonth month) {
        SettlementView view = view(workerId, month);
        SettlementVersion v = view.version();
        return new MonthlySummary(month, v, v.getEarningsPaise(), unpaidTime(v.getEarningRun()), v.getNegativeAdjustmentsPaise(),
                v.getPositiveAdjustmentsPaise(), v.getShortfallPaise(), v.getRecoveryPaise(), v.getRoundingPaise(), v.getNetPayablePaise(),
                view.allocatedPaise(), view.carriedOutPaise(), view.outstandingPaise(), view.unallocatedCreditPaise(), view.warnings());
    }

    @Transactional(readOnly = true)
    public List<AdvanceBalance> advances(Long workerId) {
        return advanceRepository.findByWorkerIdOrderByGivenOnAscIdAsc(workerId).stream()
                .map(a -> new AdvanceBalance(a, ledger.recovered(a), a.getAmountPaise() - ledger.recovered(a)))
                .toList();
    }

    /** D13: what is still open financially. Deactivation is allowed; nothing is deleted. */
    @Transactional(readOnly = true)
    public List<String> deactivationWarnings(Long workerId) {
        List<String> warnings = new ArrayList<>();
        for (AdvanceBalance balance : advances(workerId)) {
            if (balance.balancePaise() > 0) {
                warnings.add("Advance #" + balance.advance().getId() + " given on " + balance.advance().getGivenOn() + " still has "
                        + Money.format(balance.balancePaise()) + " to recover.");
            }
        }
        for (Settlement settlement : settlementRepository.findByWorkerIdOrderByPeriodStartAsc(workerId)) {
            Optional<SettlementVersion> current = ledger.currentVersion(settlement);
            if (current.isEmpty()) {
                continue;
            }
            long outstanding = current.get().getNetPayablePaise() - ledger.allocated(settlement) - ledger.carriedOut(settlement);
            YearMonth month = YearMonth.from(settlement.getPeriodStart());
            if (outstanding > 0) {
                warnings.add(month + " has " + Money.format(outstanding) + " unpaid.");
            } else if (outstanding < 0) {
                warnings.add(month + " is overpaid by " + Money.format(-outstanding) + ".");
            }
            if (current.get().getShortfallTreatment() == ShortfallTreatment.PENDING) {
                warnings.add(month + " has a pending shortfall.");
            }
        }
        long credit = unallocatedCredit(workerId);
        if (credit > 0) {
            warnings.add(Money.format(credit) + " of payments is unallocated credit.");
        }
        return warnings;
    }

    // ================================================================== helpers

    private void saveCarry(Worker worker, AdjustmentKind kind, long amount, String why, Settlement source, SettlementVersion version,
                           LocalDate target, Boolean moneyOwed, boolean cover, boolean shortfall) {
        FinancialAdjustment adjustment = adjustmentRepository.saveAndFlush(new FinancialAdjustment(worker, kind, amount, why,
                LocalDate.now(clock), source, version, target, moneyOwed, cover, shortfall, null, clock.instant()));
        auditService.record(worker.getId(), ENTITY_ADJUSTMENT, adjustment.getId(), "CREATE", "kind=" + kind + "; source="
                + YearMonth.from(source.getPeriodStart()) + "; sourceVersion=" + version.getVersionNo() + "; target="
                + YearMonth.from(target) + "; amount=" + Money.format(amount) + "; moneyOwedByWorker=" + moneyOwed + "; reason=" + why);
        refreshOpenMonth(worker.getId(), target);
    }

    /** Q7/R6: the target must be later than the source month and not already settled; nothing is reopened automatically. */
    private LocalDate validTarget(Settlement source, YearMonth target) {
        if (target == null) {
            throw new BusinessRuleException("Choose the month to carry the amount to.");
        }
        YearMonth sourceMonth = YearMonth.from(source.getPeriodStart());
        if (!target.isAfter(sourceMonth)) {
            throw new BusinessRuleException("The target month must be later than " + sourceMonth + ".");
        }
        if (ledger.isMonthSettled(source.getWorker().getId(), target.atDay(1))) {
            throw new BusinessRuleException(target + " is already settled. Choose a later month; settled months are only reopened "
                    + "explicitly.");
        }
        return target.atDay(1);
    }

    /** Keeps an OPEN target month's figures current after an adjustment changes it. */
    private void refreshOpenMonth(Long workerId, LocalDate periodStart) {
        if (periodStart == null) {
            return;
        }
        settlementRepository.findByWorkerIdAndPeriodStart(workerId, periodStart).ifPresent(s ->
                ledger.currentVersion(s).filter(SettlementVersion::isOpen).ifPresent(v -> refresh(s, v)));
    }

    private Settlement requireSettlement(Long workerId, YearMonth month) {
        if (month == null) {
            throw new BusinessRuleException("Choose a month.");
        }
        workerService.getWorker(workerId);
        return settlementRepository.findByWorkerIdAndPeriodStart(workerId, month.atDay(1))
                .orElseThrow(() -> new BusinessRuleException("There is no settlement for " + month + " yet; prepare it first."));
    }

    private SettlementVersion requireOpen(Settlement settlement, YearMonth month) {
        return ledger.currentVersion(settlement).filter(SettlementVersion::isOpen)
                .orElseThrow(() -> new BusinessRuleException(month + " is not open. Prepare it, or reopen it with a reason."));
    }

    private static long amountWithin(String amountRupees, long max) {
        long amount = amountRupees == null || amountRupees.isBlank() ? max : Money.parsePositiveRupees(amountRupees);
        if (amount > max) {
            throw new BusinessRuleException("The amount cannot be more than " + Money.format(max) + ".");
        }
        return amount;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Enter a reason.");
        }
        return reason.strip();
    }
}
