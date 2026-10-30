package com.homeexpense.controller;

import java.time.Clock;
import java.time.YearMonth;

import com.homeexpense.service.CalendarService;
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

    public WorkerProfileController(WorkerService workers, PayService payService, CalendarService calendar, Clock clock) {
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
}
