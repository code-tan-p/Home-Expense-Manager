package com.homeexpense.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.homeexpense.dto.FinanceDtos.AllocationTarget;
import com.homeexpense.dto.FinanceDtos.PaymentResult;
import com.homeexpense.dto.FinanceDtos.ProposedAllocation;
import com.homeexpense.dto.FinanceDtos.RecordPayment;
import com.homeexpense.dto.FinanceDtos.ReplacementPreview;
import com.homeexpense.dto.FinanceDtos.ReplacementRequest;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.Advance;
import com.homeexpense.model.finance.AdvanceSource;
import com.homeexpense.model.finance.AllocationKind;
import com.homeexpense.model.finance.AllocationMode;
import com.homeexpense.model.finance.AllocationReversal;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.model.finance.PaymentAllocation;
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.model.finance.PaymentVoid;
import com.homeexpense.model.finance.Settlement;
import com.homeexpense.model.finance.SettlementStatus;
import com.homeexpense.model.finance.SettlementVersion;
import com.homeexpense.money.Money;
import com.homeexpense.repository.AdvanceRepository;
import com.homeexpense.repository.AllocationReversalRepository;
import com.homeexpense.repository.PaymentAllocationRepository;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.repository.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments and their allocation (Phase 5).
 *
 * <p>Automatic oldest-first allocation happens only when a payment is recorded with mode AUTO
 * (the default when no target is chosen, Q1). It never runs again later, so early payments and
 * unallocated credit stay unallocated until allocated explicitly (Q2, R1). Excess money is never
 * applied anywhere automatically (D5). Payments are never edited: mistakes are voided and
 * replaced after review, only while every allocation is to an OPEN month (D7, R2).
 */
@Service
@Transactional
public class PaymentService {

    static final String ENTITY_PAYMENT = "PAYMENT";

    private final PaymentRepository paymentRepository;
    private final PaymentVoidRepository voidRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final AllocationReversalRepository reversalRepository;
    private final AdvanceRepository advanceRepository;
    private final SettlementRepository settlementRepository;
    private final WorkerService workerService;
    private final FinanceLedger ledger;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public PaymentService(PaymentRepository paymentRepository, PaymentVoidRepository voidRepository,
                          PaymentAllocationRepository allocationRepository, AllocationReversalRepository reversalRepository,
                          AdvanceRepository advanceRepository, SettlementRepository settlementRepository, WorkerService workerService,
                          FinanceLedger ledger, AuditService auditService, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.voidRepository = voidRepository;
        this.allocationRepository = allocationRepository;
        this.reversalRepository = reversalRepository;
        this.advanceRepository = advanceRepository;
        this.settlementRepository = settlementRepository;
        this.workerService = workerService;
        this.ledger = ledger;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ================================================================== recording

    public PaymentResult record(RecordPayment request) {
        Worker worker = workerService.getWorker(request.workerId());
        LocalDate paidOn = requirePaidOn(request.paidOn());
        long amount = Money.parsePositiveRupees(request.amountRupees());
        PaymentMethod method = requireMethod(request.method());
        AllocationMode mode = request.mode() == null ? AllocationMode.AUTO : request.mode();
        if (mode != AllocationMode.EXPLICIT && request.targets() != null && !request.targets().isEmpty()) {
            throw new BusinessRuleException("Targets can only be given with explicit allocation.");
        }
        Map<Settlement, Long> plan = switch (mode) {
            case AUTO -> autoPlan(worker.getId(), amount, Map.of());
            case EXPLICIT -> explicitPlan(worker.getId(), amount, request.targets());
            case KEEP_UNALLOCATED -> Map.of();
        };
        Payment payment = paymentRepository.saveAndFlush(new Payment(worker, paidOn, amount, method, blankToNull(request.reference()),
                blankToNull(request.note()), mode, null, clock.instant()));
        List<PaymentAllocation> allocations = allocate(payment, plan, mode);
        auditService.record(worker.getId(), ENTITY_PAYMENT, payment.getId(), "RECORD", "paidOn=" + paidOn + "; amount="
                + Money.format(amount) + "; method=" + method + "; mode=" + mode + "; allocations=" + describe(allocations));
        return result(payment, allocations);
    }

    /** Explicit allocation of unallocated money (an early payment, credit or excess) to an outstanding month (D5 option 1). */
    public PaymentResult allocate(Long paymentId, YearMonth month, String amountRupees) {
        Payment payment = requireActivePayment(paymentId);
        long free = ledger.unallocated(payment);
        long amount = amountRupees == null || amountRupees.isBlank() ? free : Money.parsePositiveRupees(amountRupees);
        if (amount > free) {
            throw new BusinessRuleException("Only " + Money.format(free) + " of this payment is unallocated.");
        }
        Map<Settlement, Long> plan = explicitPlan(payment.getWorker().getId(), amount,
                List.of(new AllocationTarget(month, Money.toRupees(amount).toPlainString())));
        List<PaymentAllocation> allocations = allocate(payment, plan, AllocationMode.EXPLICIT);
        auditService.record(payment.getWorker().getId(), ENTITY_PAYMENT, payment.getId(), "ALLOCATE", describe(allocations));
        return result(payment, ledger.activeAllocations(payment));
    }

    /** D5 option 2: records unallocated money of a payment as an advance. */
    public Advance recordExcessAsAdvance(Long paymentId, String amountRupees, String note) {
        Payment payment = requireActivePayment(paymentId);
        long free = ledger.unallocated(payment);
        long amount = amountRupees == null || amountRupees.isBlank() ? free : Money.parsePositiveRupees(amountRupees);
        if (free <= 0 || amount > free) {
            throw new BusinessRuleException("Only " + Money.format(free) + " of this payment is unallocated.");
        }
        Advance advance = advanceRepository.saveAndFlush(new Advance(payment.getWorker(), payment.getPaidOn(), amount,
                AdvanceSource.PAYMENT_EXCESS, null, null, blankToNull(note), clock.instant()));
        PaymentAllocation allocation = allocationRepository.saveAndFlush(new PaymentAllocation(payment, null, advance, amount,
                AllocationKind.EXCESS_TO_ADVANCE, clock.instant()));
        auditService.record(payment.getWorker().getId(), SettlementService.ENTITY_ADVANCE, advance.getId(), "CREATE_FROM_PAYMENT",
                "payment=" + paymentId + "; allocation=" + allocation.getId() + "; amount=" + Money.format(amount));
        return advance;
    }

    @Transactional(readOnly = true)
    public PaymentResult view(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessRuleException("Payment #" + paymentId + " was not found."));
        return result(payment, ledger.activeAllocations(payment));
    }

    // ================================================================== void and replace (D7, R2)

    /** Shows what a replacement would do. Writes nothing. */
    @Transactional(readOnly = true)
    public ReplacementPreview previewReplacement(Long paymentId, ReplacementRequest request) {
        Payment original = requireReplaceable(paymentId);
        long amount = Money.parsePositiveRupees(request.amountRupees());
        AllocationMode mode = request.mode() == null ? original.getAllocationMode() : request.mode();
        Map<Long, Long> freed = new HashMap<>();
        for (PaymentAllocation allocation : ledger.activeAllocations(original)) {
            freed.merge(allocation.getSettlement().getId(), allocation.getAmountPaise(), Long::sum);
        }
        List<ProposedAllocation> proposals = new ArrayList<>();
        long used = 0;
        switch (mode) {
            case KEEP_UNALLOCATED -> {
                // nothing to allocate
            }
            case AUTO -> {
                for (Map.Entry<Settlement, Long> entry : autoPlan(original.getWorker().getId(), amount, freed).entrySet()) {
                    proposals.add(new ProposedAllocation(YearMonth.from(entry.getKey().getPeriodStart()), entry.getValue(), true, null));
                    used += entry.getValue();
                }
            }
            case EXPLICIT -> {
                Map<YearMonth, Long> targets = new LinkedHashMap<>();
                if (request.explicitTargets() != null) {
                    for (AllocationTarget target : request.explicitTargets()) {
                        targets.merge(requireMonth(target.month()), Money.parsePositiveRupees(target.amountRupees()), Long::sum);
                    }
                } else {
                    for (PaymentAllocation allocation : ledger.activeAllocations(original)) {
                        targets.merge(YearMonth.from(allocation.getSettlement().getPeriodStart()), allocation.getAmountPaise(), Long::sum);
                    }
                }
                for (Map.Entry<YearMonth, Long> target : targets.entrySet()) {
                    String problem = explicitProblem(original.getWorker().getId(), target.getKey(), target.getValue(), freed);
                    if (problem == null && used + target.getValue() > amount) {
                        problem = "more than the replacement amount";
                    }
                    proposals.add(new ProposedAllocation(target.getKey(), target.getValue(), problem == null, problem));
                    if (problem == null) {
                        used += target.getValue();
                    }
                }
            }
        }
        return new ReplacementPreview(amount, mode, List.copyOf(proposals), amount - used);
    }

    /**
     * Saves a reviewed replacement in one transaction. Rejected if the preview no longer
     * matches, or while any explicit target is invalid: invalid targets are never redirected.
     */
    public PaymentResult confirmReplacement(Long paymentId, ReplacementRequest request, ReplacementPreview reviewed, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Enter a reason for replacing the payment.");
        }
        ReplacementPreview current = previewReplacement(paymentId, request);
        if (!current.equals(reviewed)) {
            throw new BusinessRuleException("The replacement would now be allocated differently. Review it again before saving.");
        }
        if (current.hasInvalidTargets()) {
            throw new BusinessRuleException("Some original targets are no longer valid. Choose new explicit targets or keep the "
                    + "replacement unallocated; it is never redirected automatically.");
        }
        Payment original = requireReplaceable(paymentId);
        Worker worker = original.getWorker();
        String why = reason.strip();
        for (PaymentAllocation allocation : ledger.activeAllocations(original)) {
            reversalRepository.saveAndFlush(new AllocationReversal(allocation, "Payment replaced: " + why, clock.instant()));
        }
        Payment replacement = paymentRepository.saveAndFlush(new Payment(worker, requirePaidOn(request.paidOn()), current.amountPaise(),
                requireMethod(request.method()), blankToNull(request.reference()), blankToNull(request.note()), current.mode(), original,
                clock.instant()));
        voidRepository.saveAndFlush(new PaymentVoid(original, why, clock.instant(), replacement));
        Map<Settlement, Long> plan = new LinkedHashMap<>();
        for (ProposedAllocation proposal : current.allocations()) {
            plan.put(settlementRepository.findByWorkerIdAndPeriodStart(worker.getId(), proposal.month().atDay(1)).orElseThrow(),
                    proposal.amountPaise());
        }
        List<PaymentAllocation> allocations = allocate(replacement, plan, current.mode());
        auditService.record(worker.getId(), ENTITY_PAYMENT, original.getId(), "VOID", "replacement=" + replacement.getId() + "; reason=" + why);
        auditService.record(worker.getId(), ENTITY_PAYMENT, replacement.getId(), "RECORD_REPLACEMENT", "replaces=" + original.getId()
                + "; amount=" + Money.format(replacement.getAmountPaise()) + "; mode=" + current.mode() + "; allocations=" + describe(allocations));
        return result(replacement, allocations);
    }

    // ================================================================== helpers

    /** Oldest eligible month first, capped by each month's payable outstanding (plus amounts being freed by a replacement). */
    private Map<Settlement, Long> autoPlan(Long workerId, long amount, Map<Long, Long> freed) {
        Map<Settlement, Long> plan = new LinkedHashMap<>();
        long remaining = amount;
        for (Settlement settlement : settlementRepository.findByWorkerIdOrderByPeriodStartAsc(workerId)) {
            if (remaining == 0) {
                break;
            }
            long available = available(settlement, freed);
            long take = Math.min(remaining, available);
            if (take > 0) {
                plan.put(settlement, take);
                remaining -= take;
            }
        }
        return plan;
    }

    private Map<Settlement, Long> explicitPlan(Long workerId, long amount, List<AllocationTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new BusinessRuleException("Choose at least one month to allocate to.");
        }
        Map<Settlement, Long> plan = new LinkedHashMap<>();
        long total = 0;
        for (AllocationTarget target : targets) {
            YearMonth month = requireMonth(target.month());
            long part = Money.parsePositiveRupees(target.amountRupees());
            String problem = explicitProblem(workerId, month, part, Map.of());
            if (problem != null) {
                throw new BusinessRuleException("Cannot allocate " + Money.format(part) + " to " + month + ": " + problem + ".");
            }
            Settlement settlement = settlementRepository.findByWorkerIdAndPeriodStart(workerId, month.atDay(1)).orElseThrow();
            plan.merge(settlement, part, Long::sum);
            total += part;
        }
        if (total > amount) {
            throw new BusinessRuleException("The allocations (" + Money.format(total) + ") are more than the payment (" + Money.format(amount) + ").");
        }
        return plan;
    }

    private String explicitProblem(Long workerId, YearMonth month, long amount, Map<Long, Long> freed) {
        Optional<Settlement> settlement = settlementRepository.findByWorkerIdAndPeriodStart(workerId, month.atDay(1));
        if (settlement.isEmpty() || ledger.currentVersion(settlement.get()).isEmpty()) {
            return "there is no settlement for that month yet";
        }
        long available = available(settlement.get(), freed);
        if (available <= 0) {
            return "nothing is outstanding";
        }
        if (amount > available) {
            return "only " + Money.format(available) + " is outstanding";
        }
        return null;
    }

    private long available(Settlement settlement, Map<Long, Long> freed) {
        Optional<SettlementVersion> version = ledger.currentVersion(settlement);
        if (version.isEmpty() || version.get().getStatus() == SettlementStatus.SUPERSEDED) {
            return 0;
        }
        return ledger.outstanding(settlement).orElse(0L) + freed.getOrDefault(settlement.getId(), 0L);
    }

    private List<PaymentAllocation> allocate(Payment payment, Map<Settlement, Long> plan, AllocationMode mode) {
        AllocationKind kind = mode == AllocationMode.AUTO ? AllocationKind.AUTO_OLDEST_FIRST : AllocationKind.EXPLICIT;
        List<PaymentAllocation> saved = new ArrayList<>();
        for (Map.Entry<Settlement, Long> entry : plan.entrySet()) {
            saved.add(allocationRepository.saveAndFlush(new PaymentAllocation(payment, entry.getKey(), null, entry.getValue(), kind,
                    clock.instant())));
        }
        return saved;
    }

    private PaymentResult result(Payment payment, List<PaymentAllocation> allocations) {
        long free = ledger.unallocated(payment);
        List<String> warnings = new ArrayList<>();
        if (free > 0) {
            warnings.add(Money.format(free) + " of this payment is unallocated. Allocate it to an outstanding month, record it as an "
                    + "advance, or leave it as unallocated credit.");
        }
        return new PaymentResult(payment, List.copyOf(allocations), free, List.copyOf(warnings));
    }

    private Payment requireActivePayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessRuleException("Payment #" + paymentId + " was not found."));
        if (ledger.isVoided(payment)) {
            throw new BusinessRuleException("Payment #" + paymentId + " has been voided.");
        }
        return payment;
    }

    /** D7: only while unallocated or every allocation is to a month whose settlement is still OPEN. */
    private Payment requireReplaceable(Long paymentId) {
        Payment payment = requireActivePayment(paymentId);
        for (PaymentAllocation allocation : ledger.activeAllocations(payment)) {
            if (allocation.getAdvance() != null) {
                throw new BusinessRuleException("Part of this payment was recorded as an advance, so it cannot be replaced. Use an adjustment.");
            }
            boolean open = ledger.currentVersion(allocation.getSettlement()).map(SettlementVersion::isOpen).orElse(false);
            if (!open) {
                throw new BusinessRuleException("This payment covers " + YearMonth.from(allocation.getSettlement().getPeriodStart())
                        + ", which is settled. Correct it with an adjustment instead of replacing the payment.");
            }
        }
        return payment;
    }

    private LocalDate requirePaidOn(LocalDate paidOn) {
        if (paidOn == null) {
            throw new BusinessRuleException("Enter the date the payment was made.");
        }
        if (paidOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("A payment cannot be dated in the future.");
        }
        return paidOn;
    }

    private static PaymentMethod requireMethod(PaymentMethod method) {
        if (method == null) {
            throw new BusinessRuleException("Choose how the payment was made: cash, UPI, bank transfer or other.");
        }
        return method;
    }

    private static YearMonth requireMonth(YearMonth month) {
        if (month == null) {
            throw new BusinessRuleException("Choose a month.");
        }
        return month;
    }

    private static String describe(List<PaymentAllocation> allocations) {
        if (allocations.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (PaymentAllocation a : allocations) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(a.getSettlement() == null ? "advance #" + a.getAdvance().getId() : YearMonth.from(a.getSettlement().getPeriodStart()))
                    .append(' ').append(Money.format(a.getAmountPaise()));
        }
        return text.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
