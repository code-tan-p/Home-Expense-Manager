package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.EarningsResult;
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
    public record PayRow(Worker worker, EarningsResult earnings, String problem, long paidPaise) {

        /** Still to pay, or negative when more than the earnings so far was paid. */
        public long stillToPayPaise() {
            return earnings == null ? 0 : earnings.totalPaise() - paidPaise;
        }
    }

    private final WorkerService workerService;
    private final EarningsService earningsService;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentVoidRepository paymentVoidRepository;
    private final Clock clock;

    public PayController(WorkerService workerService, EarningsService earningsService, PaymentService paymentService,
                         PaymentRepository paymentRepository, PaymentVoidRepository paymentVoidRepository, Clock clock) {
        this.workerService = workerService;
        this.earningsService = earningsService;
        this.paymentService = paymentService;
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
            try {
                rows.add(new PayRow(worker, earningsService.calculate(worker.getId(), shown), null, paid));
            } catch (BusinessRuleException e) {
                rows.add(new PayRow(worker, null, e.getMessage(), paid));
            }
        }
        model.addAttribute("page", "pay");
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("rows", rows);
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
            long earned = earningsService.view(workerId, month).totalPaise();
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

    private long paidInMonth(Long workerId, YearMonth month) {
        return paymentRepository.findByWorkerIdOrderByPaidOnAscIdAsc(workerId).stream()
                .filter(p -> YearMonth.from(p.getPaidOn()).equals(month))
                .filter(p -> paymentVoidRepository.findByPaymentId(p.getId()).isEmpty())
                .mapToLong(Payment::getAmountPaise)
                .sum();
    }
}
