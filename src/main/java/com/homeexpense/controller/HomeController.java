package com.homeexpense.controller;

import java.math.BigDecimal;
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
        LocalDate day = date == null ? LocalDate.now(clock) : date;
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
        model.addAttribute("isToday", day.equals(LocalDate.now(clock)));
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
        WorkDetails details = WorkDetails.completed();
        if (quantity != null && !quantity.isBlank()) {
            details = WorkDetails.completedQuantity(toMilli(quantity));
        } else if (hours != null && !hours.isBlank()) {
            BigDecimal minutes = parseNumber(hours).multiply(BigDecimal.valueOf(60));
            if (minutes.stripTrailingZeros().scale() > 0) {
                throw new BusinessRuleException("Enter hours as a number such as 2 or 1.5.");
            }
            details = WorkDetails.completedMinutes(minutes.intValueExact());
        }
        dailyWorkService.recordScheduled(workerId, date, entryId, details);
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/?date=" + date;
    }

    /** Records the same reason for everything a worker was expected to do that day. */
    @PostMapping("/work/absent")
    public String absent(@RequestParam LocalDate date, @RequestParam Long workerId, @RequestParam AbsenceReason reason,
                         RedirectAttributes flash) {
        dailyWorkService.markWholeDay(workerId, date, reason, null);
        flash.addFlashAttribute("ok", "Saved.");
        return "redirect:/?date=" + date;
    }

    /** Takes back a wrong entry. The activity becomes "not recorded" again; the history keeps the mistake. */
    @PostMapping("/work/undo")
    public String undo(@RequestParam LocalDate date, @RequestParam Long recordId, RedirectAttributes flash) {
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

    /** Litres (or any unit) typed like 1.5 or 1,5, up to three decimals, as thousandths. */
    static long toMilli(String text) {
        BigDecimal value = parseNumber(text);
        if (value.signum() <= 0) {
            throw new BusinessRuleException("Enter more than zero. For no delivery, use the 'No delivery' button.");
        }
        BigDecimal milli = value.movePointRight(3);
        if (milli.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("Use at most three decimals, for example 1.5 or 0.25.");
        }
        return milli.longValueExact();
    }

    private static BigDecimal parseNumber(String text) {
        try {
            return new BigDecimal(text.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new BusinessRuleException("'" + text.strip() + "' is not a number. Type it like 1.5.");
        }
    }
}
