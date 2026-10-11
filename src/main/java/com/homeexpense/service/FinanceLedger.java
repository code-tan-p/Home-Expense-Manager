package com.homeexpense.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.homeexpense.model.finance.Advance;
import com.homeexpense.model.finance.AdvanceRecovery;
import com.homeexpense.model.finance.FinancialAdjustment;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.model.finance.PaymentAllocation;
import com.homeexpense.model.finance.Settlement;
import com.homeexpense.model.finance.SettlementStatus;
import com.homeexpense.model.finance.SettlementVersion;
import com.homeexpense.repository.AdvanceRecoveryRepository;
import com.homeexpense.repository.FinancialAdjustmentRepository;
import com.homeexpense.repository.PaymentAllocationRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.repository.SettlementRepository;
import com.homeexpense.repository.SettlementVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only balances derived from the immutable financial records. Nothing here is stored:
 * every figure is worked out from payments, allocations, reversals, recoveries and adjustments.
 *
 * <p>Payable outstanding of a settlement = current version's net payable - active allocations
 * - amounts moved out of it by carries and carry corrections (R5). Negative means overpaid.
 */
@Component
@Transactional(readOnly = true)
public class FinanceLedger {

    private final SettlementRepository settlementRepository;
    private final SettlementVersionRepository versionRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final PaymentVoidRepository voidRepository;
    private final AdvanceRecoveryRepository recoveryRepository;
    private final FinancialAdjustmentRepository adjustmentRepository;

    public FinanceLedger(SettlementRepository settlementRepository, SettlementVersionRepository versionRepository,
                         PaymentAllocationRepository allocationRepository, PaymentVoidRepository voidRepository,
                         AdvanceRecoveryRepository recoveryRepository, FinancialAdjustmentRepository adjustmentRepository) {
        this.settlementRepository = settlementRepository;
        this.versionRepository = versionRepository;
        this.allocationRepository = allocationRepository;
        this.voidRepository = voidRepository;
        this.recoveryRepository = recoveryRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    public record Eligible(Settlement settlement, SettlementVersion version, long outstandingPaise) {
    }

    public Optional<SettlementVersion> currentVersion(Settlement settlement) {
        return versionRepository.findCurrent(settlement.getId());
    }

    public long allocated(Settlement settlement) {
        return allocationRepository.findActiveBySettlement(settlement.getId()).stream().mapToLong(PaymentAllocation::getAmountPaise).sum();
    }

    /** Amounts moved out of this settlement by carry-forward, overpayment carry and carry correction (signed). */
    public long carriedOut(Settlement settlement) {
        return adjustmentRepository.findBySource(settlement.getId()).stream()
                .filter(FinancialAdjustment::isAffectsSourceCover).mapToLong(FinancialAdjustment::getAmountPaise).sum();
    }

    /** How much of a shortfall has been handled by remainder carries or advances (a positive number). */
    public long shortfallHandled(Settlement settlement) {
        return -adjustmentRepository.findBySource(settlement.getId()).stream()
                .filter(FinancialAdjustment::isAffectsSourceShortfall).mapToLong(FinancialAdjustment::getAmountPaise).sum();
    }

    /** Net per original adjustment after its reversal, for the given rows (insertion order kept). */
    public static Map<FinancialAdjustment, Long> netByRoot(List<FinancialAdjustment> rows) {
        Map<Long, FinancialAdjustment> roots = new LinkedHashMap<>();
        Map<Long, Long> sums = new LinkedHashMap<>();
        for (FinancialAdjustment row : rows) {
            FinancialAdjustment root = row.root();
            roots.putIfAbsent(root.getId(), root);
            sums.merge(root.getId(), row.getAmountPaise(), Long::sum);
        }
        Map<FinancialAdjustment, Long> result = new LinkedHashMap<>();
        roots.forEach((id, root) -> result.put(root, sums.get(id)));
        return result;
    }

    public Optional<Long> outstanding(Settlement settlement) {
        return currentVersion(settlement).map(v -> v.getNetPayablePaise() - allocated(settlement) - carriedOut(settlement));
    }

    /** Settlements that can receive a payment now, oldest month first (Q3, Q4). */
    public List<Eligible> eligibleForPayment(Long workerId) {
        List<Eligible> result = new ArrayList<>();
        for (Settlement settlement : settlementRepository.findByWorkerIdOrderByPeriodStartAsc(workerId)) {
            Optional<SettlementVersion> version = currentVersion(settlement);
            if (version.isEmpty() || version.get().getStatus() == SettlementStatus.SUPERSEDED) {
                continue;
            }
            long outstanding = version.get().getNetPayablePaise() - allocated(settlement) - carriedOut(settlement);
            if (outstanding > 0) {
                result.add(new Eligible(settlement, version.get(), outstanding));
            }
        }
        return result;
    }

    public boolean isVoided(Payment payment) {
        return voidRepository.findByPaymentId(payment.getId()).isPresent();
    }

    public List<PaymentAllocation> activeAllocations(Payment payment) {
        return allocationRepository.findActiveByPayment(payment.getId());
    }

    public long unallocated(Payment payment) {
        if (isVoided(payment)) {
            return 0;
        }
        return payment.getAmountPaise() - activeAllocations(payment).stream().mapToLong(PaymentAllocation::getAmountPaise).sum();
    }

    public long recovered(Advance advance) {
        return recoveryRepository.findActiveByAdvance(advance.getId()).stream().mapToLong(AdvanceRecovery::getAmountPaise).sum();
    }

    /** Balance still to recover, ignoring recoveries already made in the given settlement (recalculated when it settles). */
    public long balanceExcluding(Advance advance, Settlement settlement) {
        long elsewhere = recoveryRepository.findActiveByAdvance(advance.getId()).stream()
                .filter(r -> settlement == null || !r.getSettlement().getId().equals(settlement.getId()))
                .mapToLong(AdvanceRecovery::getAmountPaise).sum();
        return advance.getAmountPaise() - elsewhere;
    }

    public boolean isMonthSettled(Long workerId, java.time.LocalDate periodStart) {
        return settlementRepository.findByWorkerIdAndPeriodStart(workerId, periodStart)
                .flatMap(this::currentVersion)
                .map(v -> v.getStatus() == SettlementStatus.SETTLED)
                .orElse(false);
    }
}
