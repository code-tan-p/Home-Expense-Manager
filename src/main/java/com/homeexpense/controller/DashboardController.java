package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.DayView;
import com.homeexpense.dto.FinanceDtos;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.service.CalendarService;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.PayService;
import com.homeexpense.service.EarningsService;
import com.homeexpense.service.SettlementService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The overview: who works here, what is still to record today, and what this month comes to. */
@Controller
public class DashboardController {

    /** A worker whose records for the month are not complete. */
    public record Alert(Worker worker, String message) {
    }

    private final WorkerService workers;
    private final DailyWorkService dailyWork;
    private final EarningsService earnings;
    private final SettlementService settlements;
    private final PaymentRepository payments;
    private final PaymentVoidRepository voids;
    private final Clock clock;
    private final CalendarService calendar;
    private final PayService payService;

    public DashboardController(WorkerService workers, DailyWorkService dailyWork, EarningsService earnings,
                               SettlementService settlements, PaymentRepository payments, PaymentVoidRepository voids, Clock clock,
                               CalendarService calendar, PayService payService) {
        this.calendar = calendar;
        this.payService = payService;
        this.workers = workers;
        this.dailyWork = dailyWork;
        this.earnings = earnings;
        this.settlements = settlements;
        this.payments = payments;
        this.voids = voids;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public String overview(Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        List<Worker> active = workers.workersEmployedOn(today);
        DayView view = dailyWork.dayView(today);
        long pending = view.workers().stream()
                .filter(w -> !isDayByDay(w.worker(), today))
                .mapToLong(DayView.WorkerDay::notRecordedCount).sum();

        long earned = 0;
        long paid = 0;
        long advances = 0;
        List<Alert> alerts = new ArrayList<>();
        List<Payment> recent = new ArrayList<>();
        for (Worker worker : workers.listAllWorkers()) {
            try {
                EarningsService.Preview preview = earnings.preview(worker.getId(), month);
                earned += preview.totalPaise();
                if (!isDayByDay(worker, today)) {
                    int missing = calendar.summary(worker, month).notRecorded();
                    if (missing > 0) {
                        alerts.add(new Alert(worker, missing + (missing == 1 ? " day is" : " days are") + " not recorded yet this month."));
                    }
                }
                for (String blocker : preview.blockers()) {
                    // The "not recorded" blockers are one long list of dates; the short line above says it better.
                    if (!blocker.contains("not recorded")) {
                        alerts.add(new Alert(worker, blocker));
                    }
                }
            } catch (BusinessRuleException ignored) {
                // Not employed this month: nothing to add.
            }
            for (Payment p : payments.findByWorkerIdOrderByPaidOnAscIdAsc(worker.getId())) {
                if (voids.findByPaymentId(p.getId()).isPresent()) {
                    continue;
                }
                recent.add(p);
                if (YearMonth.from(p.getPaidOn()).equals(month)) {
                    paid += p.getAmountPaise();
                }
            }
            advances += settlements.advances(worker.getId()).stream().mapToLong(FinanceDtos.AdvanceBalance::balancePaise).sum();
        }
        recent.sort((a, b) -> b.getPaidOn().compareTo(a.getPaidOn()) != 0 ? b.getPaidOn().compareTo(a.getPaidOn())
                : Long.compare(b.getId(), a.getId()));

        model.addAttribute("page", "overview");
        model.addAttribute("today", today);
        model.addAttribute("month", month);
        model.addAttribute("staffCount", active.size());
        model.addAttribute("pending", pending);
        model.addAttribute("earnedPaise", earned);
        model.addAttribute("paidPaise", paid);
        model.addAttribute("outstandingPaise", earned - paid);
        model.addAttribute("advancePaise", advances);
        model.addAttribute("alerts", alerts);
        model.addAttribute("recent", recent.stream().limit(5).toList());
        return "overview";
    }

    private boolean isDayByDay(Worker worker, LocalDate day) {
        return payService.primaryModelOn(worker.getId(), day)
                .filter(m -> m == com.homeexpense.model.PaymentModel.PER_DAY || m == com.homeexpense.model.PaymentModel.HOURLY
                        || m == com.homeexpense.model.PaymentModel.PER_TASK).isPresent();
    }
}
