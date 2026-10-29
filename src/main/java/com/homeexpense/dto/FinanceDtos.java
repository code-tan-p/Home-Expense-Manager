package com.homeexpense.dto;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.model.finance.Advance;
import com.homeexpense.model.finance.AllocationMode;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.model.finance.PaymentAllocation;
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.model.finance.Settlement;
import com.homeexpense.model.finance.SettlementItem;
import com.homeexpense.model.finance.SettlementVersion;

/** Requests and results for payments and settlements. Amounts in requests are rupee text; results are paise. */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    /** Part of a payment to apply to one month. */
    public record AllocationTarget(YearMonth month, String amountRupees) {
    }

    public record RecordPayment(Long workerId, LocalDate paidOn, String amountRupees, PaymentMethod method, String reference,
                                String note, AllocationMode mode, List<AllocationTarget> targets) {
    }

    public record PaymentResult(Payment payment, List<PaymentAllocation> allocations, long unallocatedPaise, List<String> warnings) {
    }

    /** New details for a replacement payment. A null mode keeps the original's; null targets reuse the original's (R2). */
    public record ReplacementRequest(LocalDate paidOn, String amountRupees, PaymentMethod method, String reference, String note,
                                     AllocationMode mode, List<AllocationTarget> explicitTargets) {
    }

    public record ProposedAllocation(YearMonth month, long amountPaise, boolean valid, String problem) {
    }

    /** What a replacement would do. Saving requires exactly this preview to still hold. */
    public record ReplacementPreview(long amountPaise, AllocationMode mode, List<ProposedAllocation> allocations, long unallocatedPaise) {

        public boolean hasInvalidTargets() {
            return allocations.stream().anyMatch(a -> !a.valid());
        }
    }

    public enum OutstandingChoice {
        CARRY_FORWARD,
        LEAVE_OUTSTANDING
    }

    public enum OverpaymentChoice {
        TO_ADVANCE,
        CARRY,
        PENDING
    }

    public enum ShortfallChoice {
        TO_ADVANCE,
        CARRY,
        PENDING
    }

    /** Settling: the reviewed net, and the decisions needed when money is outstanding or overpaid. */
    public record SettleRequest(long expectedNetPaise, OutstandingChoice outstanding, OverpaymentChoice overpayment,
                                YearMonth target, String reason) {

        public static SettleRequest exact(long expectedNetPaise) {
            return new SettleRequest(expectedNetPaise, null, null, null, null);
        }
    }

    public record SettlementView(Settlement settlement, SettlementVersion version, List<SettlementItem> items, long allocatedPaise,
                                 long carriedOutPaise, long outstandingPaise, long unallocatedCreditPaise, List<String> blockers,
                                 List<String> warnings) {

        public long overpaymentPaise() {
            return Math.max(0, -outstandingPaise);
        }
    }

    public record AdvanceBalance(Advance advance, long recoveredPaise, long balancePaise) {
    }

    /** Monthly summary (CLAUDE.md section 13). Unpaid time is informational and already inside earnings (D11). */
    public record MonthlySummary(YearMonth month, SettlementVersion version, long earningsPaise, List<String> unpaidTimeInfo,
                                 long negativeAdjustmentsPaise, long positiveAdjustmentsPaise, long shortfallNeutralisedPaise,
                                 long recoveryPaise, long roundingPaise, long netPayablePaise, long paidPaise, long carriedOutPaise,
                                 long outstandingPaise, long unallocatedCreditPaise, List<String> warnings) {
    }
}
