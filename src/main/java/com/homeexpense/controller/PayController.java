package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.FinanceDtos;
import com.homeexpense.dto.FinanceDtos.RecordPayment;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.AllocationMode;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.money.Money;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.service.EarningsService;
import com.homeexpense.service.PaymentService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The "Monthly Pay" screen: what each worker earned in a month, what was paid in that month, and
 * what is still to pay. Payments are not matched to a settlement here; "paid" is the sum of that
 * month's payments that were not voided.
 */
@Controller
public class PayController {

    /** One worker's month. {@code paidPaise} counts payments dated inside the month. */
    public record PayRow(Worker worker, EarningsService.Preview earnings, String problem, long paidPaise,
                         long advancePaise) {

        /** Still to pay, or negative when more than the earnings so far was paid. */
        public long stillToPayPaise() {
            return earnings == null ? 0 : earnings.totalPaise() - paidPaise;
        }
    }

    private final WorkerService workerService;
    private final EarningsService earningsService;
    private final PaymentService paymentService;
    private final com.homeexpense.service.SettlementService settlementService;
    private final PaymentRepository paymentRepository;
    private final PaymentVoidRepository paymentVoidRepository;
    private final Clock clock;

    public PayController(WorkerService workerService, EarningsService earningsService, PaymentService paymentService,
                         com.homeexpense.service.SettlementService settlementService,
                         PaymentRepository paymentRepository, PaymentVoidRepository paymentVoidRepository, Clock clock) {
        this.workerService = workerService;
        this.earningsService = earningsService;
        this.paymentService = paymentService;
        this.settlementService = settlementService;
        this.paymentRepository = paymentRepository;
        this.paymentVoidRepository = paymentVoidRepository;
        this.clock = clock;
    }

    @GetMapping("/pay")
    public String pay(@RequestParam(required = false) YearMonth month, Model model) {
        YearMonth shown = month == null ? YearMonth.now(clock) : month;
        List<PayRow> rows = new ArrayList<>();
        for (Worker worker : workerService.listAllWorkers()) {
            if (!workerService.isEmployedOn(worker.getId(), shown.atEndOfMonth())
                    && !workerService.isEmployedOn(worker.getId(), shown.atDay(1))) {
                continue;
            }
            long paid = paidInMonth(worker.getId(), shown);
            long advance = settlementService.advances(worker.getId()).stream()
                    .mapToLong(FinanceDtos.AdvanceBalance::balancePaise).sum();
            try {
                rows.add(new PayRow(worker, earningsService.preview(worker.getId(), shown), null, paid, advance));
            } catch (BusinessRuleException e) {
                rows.add(new PayRow(worker, null, e.getMessage(), paid, advance));
            }
        }
        model.addAttribute("page", "pay");
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("rows", rows);
        model.addAttribute("ended", shown.isBefore(YearMonth.now(clock)));
        model.addAttribute("methods", PaymentMethod.values());
        return "pay";
    }

    /** A payment is dated today for the current month, or the last day of an earlier month. */
    @PostMapping("/pay/payment")
    public String pay(@RequestParam Long workerId, @RequestParam String amount, @RequestParam PaymentMethod method,
                      @RequestParam YearMonth month, RedirectAttributes flash) {
        LocalDate today = LocalDate.now(clock);
        LocalDate paidOn = YearMonth.from(today).equals(month) ? today : month.atEndOfMonth();
        long beforePaise = paidInMonth(workerId, month);
        paymentService.record(new RecordPayment(workerId, paidOn, amount, method, null, null, AllocationMode.AUTO, List.of()));
        Worker worker = workerService.getWorker(workerId);
        String message = "Payment of " + Money.format(Money.parsePositiveRupees(amount)) + " recorded for " + worker.getName() + ".";
        try {
            long earned = earningsService.preview(workerId, month).totalPaise();
            long over = beforePaise + Money.parsePositiveRupees(amount) - earned;
            if (over > 0) {
                message += " That is " + Money.format(over) + " more than earned so far this month.";
            }
        } catch (BusinessRuleException ignored) {
            // No earnings calculated yet: nothing to compare with.
        }
        flash.addFlashAttribute("ok", message);
        return "redirect:/pay?month=" + month;
    }

    /**
     * Closes a finished month for one worker: locks the earnings, puts the payments already made
     * against the month, takes back any advance and records what is still owed. After that, a new
     * payment is matched to the month automatically.
     */
    @PostMapping("/pay/close")
    public String close(@RequestParam Long workerId, @RequestParam YearMonth month, RedirectAttributes flash) {
        var draft = earningsService.calculate(workerId, month);
        if (!draft.canFinalize()) {
            throw new BusinessRuleException("This month is not ready to close. " + String.join(" ", draft.blockers()));
        }
        earningsService.finalizeMonth(workerId, month, draft.totalPaise());
        settlementService.prepare(workerId, month);
        long net = settlementService.view(workerId, month).version().getNetPayablePaise();
        long toPlace = net;
        for (Payment payment : paymentRepository.findByWorkerIdOrderByPaidOnAscIdAsc(workerId)) {
            if (toPlace <= 0) {
                break;
            }
            if (paymentVoidRepository.findByPaymentId(payment.getId()).isPresent()) {
                continue;
            }
            long free = paymentService.view(payment.getId()).unallocatedPaise();
            long use = Math.min(free, toPlace);
            if (use > 0) {
                paymentService.allocate(payment.getId(), month, Money.toRupees(use).toPlainString());
                toPlace -= use;
            }
        }
        var settled = settlementService.settle(workerId, month, new FinanceDtos.SettleRequest(net,
                FinanceDtos.OutstandingChoice.LEAVE_OUTSTANDING, FinanceDtos.OverpaymentChoice.TO_ADVANCE, null,
                "Closed from Monthly Pay"));
        long owed = settled.outstandingPaise();
        flash.addFlashAttribute("ok", workerService.getWorker(workerId).getName() + "'s " + month + " is closed. "
                + (owed > 0 ? "Still to pay: " + Money.format(owed) + "." : "Fully paid."));
        return "redirect:/pay?month=" + month;
    }

    /** Money handed over ahead of pay. It is taken back from later months when they are closed. */
    @PostMapping("/pay/advance")
    public String advance(@RequestParam Long workerId, @RequestParam String amount, @RequestParam PaymentMethod method,
                          @RequestParam YearMonth month, RedirectAttributes flash) {
        var result = paymentService.record(new RecordPayment(workerId, LocalDate.now(clock), amount, method, null,
                "Advance", AllocationMode.KEEP_UNALLOCATED, List.of()));
        paymentService.recordExcessAsAdvance(result.payment().getId(), amount, "Advance given");
        flash.addFlashAttribute("ok", "Advance of " + Money.format(Money.parsePositiveRupees(amount)) + " recorded for "
                + workerService.getWorker(workerId).getName() + ". It is taken back from later pay.");
        return "redirect:/pay?month=" + month;
    }

    private long paidInMonth(Long workerId, YearMonth month) {
        return paymentRepository.findByWorkerIdOrderByPaidOnAscIdAsc(workerId).stream()
                .filter(p -> YearMonth.from(p.getPaidOn()).equals(month))
                .filter(p -> paymentVoidRepository.findByPaymentId(p.getId()).isEmpty())
                .mapToLong(Payment::getAmountPaise)
                .sum();
    }
}
