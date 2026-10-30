package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.homeexpense.dto.DayView;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.PayService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Today" screen: one tap per worker to say what happened on a day. */
@Controller
public class HomeController {

    private final DailyWorkService dailyWorkService;
    private final PayService payService;
    private final Clock clock;

    public HomeController(DailyWorkService dailyWorkService, PayService payService, Clock clock) {
        this.dailyWorkService = dailyWorkService;
        this.payService = payService;
        this.clock = clock;
    }

    @GetMapping("/")
    public String today(@RequestParam(required = false) LocalDate date, Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate day = date == null ? today : InputRules.requireDate(date, today);
        DayView view = dailyWorkService.dayView(day);
        // How each worker is paid decides which box they need: litres for milk, hours for hourly pay.
        Map<Long, PaymentModel> payModels = new HashMap<>();
        for (DayView.WorkerDay workerDay : view.workers()) {
            Long id = workerDay.worker().getId();
            payService.primaryModelOn(id, day).ifPresent(m -> payModels.put(id, m));
        }
        model.addAttribute("page", "today");
        model.addAttribute("date", day);
        model.addAttribute("previous", day.minusDays(1));
        model.addAttribute("next", day.plusDays(1));
        model.addAttribute("isToday", day.equals(today));
        model.addAttribute("view", view);
        model.addAttribute("payModels", payModels);
        model.addAttribute("pending", view.workers().stream().mapToLong(DayView.WorkerDay::notRecordedCount).sum());
        return "index";
    }

    /** Marks one expected activity as done. Milk needs litres and hourly work needs hours. */
    @PostMapping("/work/done")
    public String done(@RequestParam LocalDate date, @RequestParam Long workerId, @RequestParam Long entryId,
                       @RequestParam(required = false) String quantity, @RequestParam(required = false) String hours,
                       RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        PaymentModel model = payService.primaryModelOn(workerId, date).orElse(null);
        WorkDetails details = switch (model == null ? PaymentModel.MONTHLY_SALARY : model) {
            case PER_UNIT -> {
                if (quantity == null || quantity.isBlank()) {
                    throw new BusinessRuleException("Enter the litres first, or press 'No delivery'.");
                }
                yield WorkDetails.completedQuantity(InputRules.requireLitresMilli(quantity));
            }
            case HOURLY -> {
                if (hours == null || hours.isBlank()) {
                    throw new BusinessRuleException("Enter the hours worked first.");
                }
                yield WorkDetails.completedMinutes(InputRules.requireMinutes(hours));
            }
            default -> WorkDetails.completed();
        };
        dailyWorkService.recordScheduled(workerId, date, entryId, details);
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/?date=" + date;
    }

    /** Records the same reason for everything a worker was expected to do that day. */
    @PostMapping("/work/absent")
    public String absent(@RequestParam LocalDate date, @RequestParam Long workerId, @RequestParam AbsenceReason reason,
                         RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        dailyWorkService.markWholeDay(workerId, date, reason, null);
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/?date=" + date;
    }

    /** The household's answer to "Didn't come": reduce that day's pay, or pay in full. */
    @PostMapping("/work/deduction")
    public String deduction(@RequestParam LocalDate date, @RequestParam Long recordId,
                            @RequestParam DeductionDecision decision, RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        dailyWorkService.decideDeduction(recordId, decision, null);
        flash.addFlashAttribute("ok", decision == DeductionDecision.APPROVED
                ? "Pay will be reduced for that day." : "That day will be paid in full.");
        return "redirect:/?date=" + date;
    }

    /** Takes back a wrong entry. The activity becomes "not recorded" again; the history keeps the mistake. */
    @PostMapping("/work/undo")
    public String undo(@RequestParam LocalDate date, @RequestParam Long recordId, RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        dailyWorkService.voidRecord(recordId, "Entered by mistake");
        flash.addFlashAttribute("ok", "Entry removed. You can record it again.");
        return "redirect:/?date=" + date;
    }

    /**
     * Marks every not-yet-recorded activity as worked. Milk and hourly workers are skipped because
     * they need litres or hours; their names are listed so they can be filled in by hand.
     */
    @PostMapping("/work/all-present")
    public String allPresent(@RequestParam LocalDate date, RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        List<String> skipped = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int saved = 0;
        for (DayView.WorkerDay day : dailyWorkService.dayView(date).workers()) {
            Long workerId = day.worker().getId();
            PaymentModel model = payService.primaryModelOn(workerId, date).orElse(null);
            boolean needsAmount = model == PaymentModel.PER_UNIT || model == PaymentModel.HOURLY;
            for (DayView.ExpectedActivity activity : day.expected()) {
                if (activity.isRecorded()) {
                    continue;
                }
                if (needsAmount) {
                    if (!skipped.contains(day.worker().getName())) {
                        skipped.add(day.worker().getName());
                    }
                    continue;
                }
                try {
                    dailyWorkService.recordScheduled(workerId, date, activity.entry().getId(), WorkDetails.completed());
                    saved++;
                } catch (BusinessRuleException e) {
                    problems.add(day.worker().getName() + ": " + e.getMessage());
                }
            }
        }
        flash.addFlashAttribute("ok", saved + " marked as worked."
                + (skipped.isEmpty() ? "" : " Fill in litres or hours for: " + String.join(", ", skipped) + "."));
        if (!problems.isEmpty()) {
            flash.addFlashAttribute("error", String.join(" ", problems));
        }
        return "redirect:/?date=" + date;
    }
}
