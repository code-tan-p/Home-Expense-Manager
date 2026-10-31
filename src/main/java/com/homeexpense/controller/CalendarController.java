package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.model.Worker;
import com.homeexpense.service.CalendarService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The "Calendar" screen: one person, one month, every day at a glance. */
@Controller
public class CalendarController {

    private final CalendarService calendarService;
    private final WorkerService workerService;
    private final Clock clock;

    public CalendarController(CalendarService calendarService, WorkerService workerService, Clock clock) {
        this.calendarService = calendarService;
        this.workerService = workerService;
        this.clock = clock;
    }

    @GetMapping("/calendar")
    public String calendar(@RequestParam(required = false) Long workerId, @RequestParam(required = false) YearMonth month,
                           Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth shown = month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
        List<Worker> workers = workerService.listAllWorkers();
        model.addAttribute("page", "calendar");
        model.addAttribute("workers", workers);
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        if (!workers.isEmpty()) {
            Worker worker = workerId == null ? workers.getFirst() : workerService.getWorker(workerId);
            model.addAttribute("worker", worker);
            model.addAttribute("cal", calendarService.month(worker, shown));
        }
        return "calendar";
    }
}
