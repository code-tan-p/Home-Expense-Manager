package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.dto.EarningsResult;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.service.EarningsService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Review before closing": the things that must be decided before a month can be closed. Days that were not recorded can
 * be confirmed as "not worked", and amounts that need a decision can be accepted as suggested or set with a reason.
 */
@Controller
public class ReviewController {

    private final EarningsService earnings;
    private final WorkerService workers;
    private final Clock clock;

    public ReviewController(EarningsService earnings, WorkerService workers, Clock clock) {
        this.earnings = earnings;
        this.workers = workers;
        this.clock = clock;
    }

    @GetMapping("/pay/review/{workerId}")
    public String review(@PathVariable Long workerId, @RequestParam(required = false) YearMonth month, Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth shown = month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
        EarningsResult result = earnings.calculate(workerId, shown);
        List<EarningLine> lines = result.lines().stream().filter(l -> l.getReviewStatus() == ReviewStatus.NEEDS_REVIEW).toList();
        model.addAttribute("page", "pay");
        model.addAttribute("worker", workers.getWorker(workerId));
        model.addAttribute("month", shown);
        model.addAttribute("lines", lines);
        model.addAttribute("partials", earnings.pendingSalaryReviews(result));
        model.addAttribute("unrecorded", result.unrecorded());
        model.addAttribute("blockers", result.blockers());
        model.addAttribute("total", result.totalPaise());
        return "pay-review";
    }

    @PostMapping("/pay/review/{workerId}/unrecorded")
    public String confirmUnrecorded(@PathVariable Long workerId, @RequestParam YearMonth month,
                                    @RequestParam(defaultValue = "") String sure, RedirectAttributes flash) {
        InputRules.requireMonth(month, LocalDate.now(clock));
        if (sure.isBlank()) {
            throw new BusinessRuleException("Tick the box to confirm that these days were not worked.");
        }
        earnings.confirmUnrecorded(workerId, month);
        flash.addFlashAttribute("ok", "Done. Those days count as not worked.");
        return "redirect:/pay/review/" + workerId + "?month=" + month;
    }

    @PostMapping("/pay/review/{workerId}/line")
    public String reviewLine(@PathVariable Long workerId, @RequestParam YearMonth month, @RequestParam Long lineId,
                             @RequestParam ReviewDecisionKind decision, @RequestParam(defaultValue = "") String amount,
                             @RequestParam(defaultValue = "") String reason, RedirectAttributes flash) {
        String cleaned = decision == ReviewDecisionKind.OVERRIDE ? InputRules.requireRupeesOrZero(amount, "the amount") : null;
        earnings.reviewLine(lineId, decision, cleaned, InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/pay/review/" + workerId + "?month=" + month;
    }

    @PostMapping("/pay/review/{workerId}/partial")
    public String reviewPartial(@PathVariable Long workerId, @RequestParam YearMonth month, @RequestParam Long treatmentId,
                                @RequestParam ReviewDecisionKind decision, @RequestParam(defaultValue = "") String units,
                                @RequestParam(defaultValue = "") String reason, RedirectAttributes flash) {
        earnings.reviewSalaryPartial(treatmentId, decision, units, InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/pay/review/" + workerId + "?month=" + month;
    }
}
