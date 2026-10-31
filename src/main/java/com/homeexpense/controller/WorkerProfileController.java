package com.homeexpense.controller;

import java.time.Clock;
import java.time.YearMonth;

import java.time.LocalDate;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.service.CalendarService;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.PayService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** One worker's profile: contact details that can be edited, employment history and pay history. */
@Controller
public class WorkerProfileController {

    private final WorkerService workers;
    private final PayService payService;
    private final CalendarService calendar;
    private final Clock clock;
    private final DailyWorkService dailyWork;

    public WorkerProfileController(WorkerService workers, PayService payService, CalendarService calendar, Clock clock,
                                   DailyWorkService dailyWork) {
        this.dailyWork = dailyWork;
        this.calendar = calendar;
        this.clock = clock;
        this.workers = workers;
        this.payService = payService;
    }

    @GetMapping("/staff/{id}/profile")
    public String profile(@PathVariable Long id, Model model) {
        model.addAttribute("page", "staff");
        var worker = workers.getWorker(id);
        YearMonth month = YearMonth.now(clock);
        model.addAttribute("worker", worker);
        model.addAttribute("month", month);
        model.addAttribute("cal", calendar.month(worker, month));
        model.addAttribute("employment", workers.employmentHistory(id).reversed());
        model.addAttribute("rates", payService.rateHistory(id).reversed());
        return "worker-profile";
    }

    @PostMapping("/staff/{id}/details")
    public String update(@PathVariable Long id, @RequestParam String name, @RequestParam(defaultValue = "") String phone,
                         @RequestParam(defaultValue = "") String notes, RedirectAttributes flash) {
        workers.updateDetails(id, InputRules.requireName(name), InputRules.optionalPhone(phone),
                InputRules.limited(notes, 500, "the notes"));
        flash.addFlashAttribute("ok", "Details saved.");
        return "redirect:/staff/" + id + "/profile";
    }

    /**
     * A holiday or leave for this worker only, for one day or a run of days (up to 31). It is recorded day by day, so
     * it shows on the calendar and in the pay exactly like a holiday marked on the Today screen. Days that are this
     * worker's regular days off are skipped, and days already recorded are left as they are.
     */
    @PostMapping("/staff/{id}/holiday")
    public String holiday(@PathVariable Long id, @RequestParam LocalDate from, @RequestParam(required = false) LocalDate to,
                          @RequestParam AbsenceReason kind, RedirectAttributes flash) {
        LocalDate today = LocalDate.now(clock);
        LocalDate last = to == null ? from : to;
        InputRules.requireDate(from, today);
        InputRules.requireDate(last, today);
        if (last.isBefore(from)) {
            throw new BusinessRuleException("The last day is before the first day.");
        }
        if (kind == AbsenceReason.UNAUTHORIZED_ABSENCE) {
            throw new BusinessRuleException("Choose Holiday or Leave. For a day they did not come, use the Today screen.");
        }
        if (from.plusDays(31).isBefore(last)) {
            throw new BusinessRuleException("Please add at most 31 days at a time.");
        }
        int recorded = 0;
        for (LocalDate day = from; !day.isAfter(last); day = day.plusDays(1)) {
            if (!workers.isEmployedOn(id, day)) {
                continue;
            }
            recorded += dailyWork.markWholeDay(id, day, kind, null).records().size();
        }
        flash.addFlashAttribute("ok", recorded == 0
                ? "Nothing was added: those days are regular days off or already recorded."
                : recorded + (recorded == 1 ? " day" : " days") + " saved as "
                        + (kind == AbsenceReason.HOUSEHOLD_HOLIDAY ? "holiday" : "leave") + ".");
        return "redirect:/staff/" + id + "/profile";
    }
}
