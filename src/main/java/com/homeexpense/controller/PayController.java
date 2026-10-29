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
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.service.EarningsService;
import com.homeexpense.service.PaymentService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Monthly Pay" screen: what each worker earned in a month, and recording a payment. */
@Controller
public class PayController {

    /** One worker's month: the calculated earnings, or the reason there are none. */
    public record PayRow(Worker worker, EarningsResult earnings, String problem) {
    }

    private final WorkerService workerService;
    private final EarningsService earningsService;
    private final PaymentService paymentService;
    private final Clock clock;

    public PayController(WorkerService workerService, EarningsService earningsService, PaymentService paymentService,
                         Clock clock) {
        this.workerService = workerService;
        this.earningsService = earningsService;
        this.paymentService = paymentService;
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
            try {
                rows.add(new PayRow(worker, earningsService.calculate(worker.getId(), shown), null));
            } catch (BusinessRuleException e) {
                rows.add(new PayRow(worker, null, e.getMessage()));
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

    @PostMapping("/pay/payment")
    public String pay(@RequestParam Long workerId, @RequestParam String amount, @RequestParam PaymentMethod method,
                      @RequestParam YearMonth month, RedirectAttributes flash) {
        paymentService.record(new RecordPayment(workerId, LocalDate.now(clock), amount, method, null, null,
                AllocationMode.AUTO, List.of()));
        flash.addFlashAttribute("ok", "Payment saved.");
        return "redirect:/pay?month=" + month;
    }
}
