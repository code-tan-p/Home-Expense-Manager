package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.service.CalendarService;
import com.homeexpense.service.CatalogService;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Every attendance record of a worker for a month, including corrected and undone ones, with a way to correct a day. */
@Controller
public class AttendanceHistoryController {

    private final DailyWorkService dailyWork;
    private final WorkerService workers;
    private final Clock clock;
    private final CalendarService calendar;
    private final CatalogService catalog;

    public AttendanceHistoryController(DailyWorkService dailyWork, WorkerService workers, Clock clock,
                                       CalendarService calendar, CatalogService catalog) {
        this.calendar = calendar;
        this.catalog = catalog;
        this.dailyWork = dailyWork;
        this.workers = workers;
        this.clock = clock;
    }

    @GetMapping("/attendance/{workerId}")
    public String history(@PathVariable Long workerId, @RequestParam(required = false) YearMonth month, Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth shown = month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
        List<WorkRecord> records = dailyWork.history(workerId, shown.atDay(1), shown.atEndOfMonth());
        model.addAttribute("page", "calendar");
        model.addAttribute("worker", workers.getWorker(workerId));
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("records", records.reversed());
        var cal = calendar.month(workers.getWorker(workerId), shown);
        model.addAttribute("summary", cal.summary());
        model.addAttribute("missing", cal.missingDays().stream().filter(d -> !d.equals(today)).toList());
        model.addAttribute("tasks", catalog.activeTasks());
        model.addAttribute("today", today);
        return "attendance-history";
    }

    /** Work that was not on the schedule: extra work on a working day, or work on a day off. */
    @PostMapping("/attendance/{workerId}/extra")
    public String extra(@PathVariable Long workerId, @RequestParam LocalDate date, @RequestParam Long taskId,
                        @RequestParam(defaultValue = "") String litres, @RequestParam(defaultValue = "") String hours,
                        @RequestParam(defaultValue = "") String note, @RequestParam(defaultValue = "") String confirm,
                        RedirectAttributes flash) {
        InputRules.requireDate(date, LocalDate.now(clock));
        Long quantity = litres.isBlank() ? null : InputRules.requireLitresMilli(litres);
        Integer minutes = hours.isBlank() ? null : InputRules.requireMinutes(hours);
        if (quantity == null && minutes == null) {
            throw new com.homeexpense.exception.BusinessRuleException("Enter the hours or litres of the extra work.");
        }
        WorkDetails details = new WorkDetails(WorkOutcome.COMPLETED, null, null, null, minutes, quantity, InputRules.limited(note, 200, "the note"));
        var result = dailyWork.recordUnscheduled(workerId, date, taskId, null, details, !confirm.isBlank());
        if (result.record() == null) {
            flash.addFlashAttribute("error", String.join(" ", result.warnings()) + " Tick the box to save it anyway.");
        } else {
            flash.addFlashAttribute("ok", "Extra work recorded for " + date + ".");
        }
        return "redirect:/attendance/" + workerId + "?month=" + YearMonth.from(date);
    }

    @PostMapping("/attendance/{workerId}/correct")
    public String correct(@PathVariable Long workerId, @RequestParam Long recordId, @RequestParam WorkOutcome outcome,
                          @RequestParam(required = false) AbsenceReason absence, @RequestParam(defaultValue = "") String litres,
                          @RequestParam(defaultValue = "") String hours, @RequestParam(defaultValue = "") String note,
                          @RequestParam String reason, @RequestParam YearMonth month, RedirectAttributes flash) {
        Long quantity = null;
        Integer minutes = null;
        AbsenceReason why = null;
        if (outcome == WorkOutcome.NOT_DONE) {
            why = absence == null ? AbsenceReason.UNAUTHORIZED_ABSENCE : absence;
        } else if (!litres.isBlank()) {
            quantity = InputRules.requireLitresMilli(litres);
        } else if (!hours.isBlank()) {
            minutes = InputRules.requireMinutes(hours);
        }
        WorkDetails details = new WorkDetails(outcome, why, null, null, minutes, quantity, InputRules.limited(note, 200, "the note"));
        var result = dailyWork.correct(recordId, details, reason, false);
        flash.addFlashAttribute("ok", "Record corrected. The earlier version is kept in the list.");
        if (!result.warnings().isEmpty()) {
            flash.addFlashAttribute("error", String.join(" ", result.warnings()));
        }
        return "redirect:/attendance/" + workerId + "?month=" + month;
    }
}
