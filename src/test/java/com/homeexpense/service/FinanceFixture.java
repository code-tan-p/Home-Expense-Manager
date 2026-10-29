package com.homeexpense.service;

import static java.time.DayOfWeek.MONDAY;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.FinanceDtos.AllocationTarget;
import com.homeexpense.dto.FinanceDtos.PaymentResult;
import com.homeexpense.dto.FinanceDtos.RecordPayment;
import com.homeexpense.dto.FinanceDtos.SettleRequest;
import com.homeexpense.dto.FinanceDtos.SettlementView;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.AllocationMode;
import com.homeexpense.model.finance.PaymentMethod;

/**
 * Builds Phase 5 scenarios through the real services. A worker mops 10:00-11:00 every Monday
 * at a per-task rate. In 2026 January, February, April and May have four Mondays and March
 * five, so at ₹50 a task January earns ₹200.00 and March ₹250.00.
 */
final class FinanceFixture {

    static final YearMonth JAN = YearMonth.of(2026, 1);
    static final YearMonth FEB = YearMonth.of(2026, 2);
    static final YearMonth MAR = YearMonth.of(2026, 3);
    static final YearMonth APR = YearMonth.of(2026, 4);
    static final LocalDate PAID_ON = LocalDate.of(2026, 1, 10);

    final EarningsFixture earnings;
    final SettlementService settlementService;
    final PaymentService paymentService;
    final Task mopping;
    private final java.util.Map<Long, Long> entryIds = new java.util.HashMap<>();

    FinanceFixture(EarningsFixture earnings, SettlementService settlementService, PaymentService paymentService, String taskName) {
        this.earnings = earnings;
        this.settlementService = settlementService;
        this.paymentService = paymentService;
        this.mopping = earnings.task(taskName);
    }

    Worker worker(String name, String rate) {
        Worker worker = earnings.worker(name, LocalDate.of(2026, 1, 1), PaymentModel.PER_TASK);
        ScheduleEntry entry = earnings.scheduleService.addScheduleVersion(worker.getId(), LocalDate.of(2026, 1, 1),
                List.of(EarningsFixture.timed(MONDAY, mopping, "10:00", "11:00")), null).getEntries().getFirst();
        entryIds.put(worker.getId(), entry.getId());
        earnings.rate(worker, RateType.PER_TASK, rate, LocalDate.of(2026, 1, 1));
        return worker;
    }

    /** The worker's Monday schedule entry (remembered, so it also works outside a transaction). */
    Long entryId(Worker worker) {
        return entryIds.get(worker.getId());
    }

    static List<LocalDate> mondays(YearMonth month) {
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate d = month.atDay(1).with(TemporalAdjusters.firstInMonth(MONDAY)); YearMonth.from(d).equals(month); d = d.plusWeeks(1)) {
            result.add(d);
        }
        return result;
    }

    /** Records every Monday of the month as done (if not yet recorded) and finalizes the month. */
    long finalizeMonth(Worker worker, YearMonth month) {
        for (LocalDate monday : mondays(month)) {
            boolean recorded = earnings.dailyWorkService.history(worker.getId(), monday, monday).stream().anyMatch(WorkRecord::isCurrent);
            if (!recorded) {
                earnings.dailyWorkService.recordScheduled(worker.getId(), monday, entryId(worker), WorkDetails.completed());
            }
        }
        long total = earnings.earningsService.calculate(worker.getId(), month).totalPaise();
        earnings.earningsService.finalizeMonth(worker.getId(), month, total);
        return total;
    }

    SettlementView prepare(Worker worker, YearMonth month) {
        return settlementService.prepare(worker.getId(), month);
    }

    SettlementView finalizeAndPrepare(Worker worker, YearMonth month) {
        finalizeMonth(worker, month);
        return prepare(worker, month);
    }

    PaymentResult pay(Worker worker, String amount) {
        return paymentService.record(new RecordPayment(worker.getId(), PAID_ON, amount, PaymentMethod.CASH, null, null, null, null));
    }

    PaymentResult payKeepUnallocated(Worker worker, String amount) {
        return paymentService.record(new RecordPayment(worker.getId(), PAID_ON, amount, PaymentMethod.UPI, "UPI-REF", "weekly",
                AllocationMode.KEEP_UNALLOCATED, null));
    }

    PaymentResult payExplicit(Worker worker, String amount, YearMonth month, String part) {
        return paymentService.record(new RecordPayment(worker.getId(), PAID_ON, amount, PaymentMethod.BANK_TRANSFER, null, null,
                AllocationMode.EXPLICIT, List.of(new AllocationTarget(month, part))));
    }

    SettlementView settle(Worker worker, YearMonth month) {
        long net = settlementService.view(worker.getId(), month).version().getNetPayablePaise();
        return settlementService.settle(worker.getId(), month, SettleRequest.exact(net));
    }

    SettlementView settle(Worker worker, YearMonth month, SettleRequest withChoices) {
        return settlementService.settle(worker.getId(), month, withChoices);
    }

    long net(Worker worker, YearMonth month) {
        return settlementService.view(worker.getId(), month).version().getNetPayablePaise();
    }
}
