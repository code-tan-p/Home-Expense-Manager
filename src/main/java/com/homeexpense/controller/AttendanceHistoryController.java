package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
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

    public AttendanceHistoryController(DailyWorkService dailyWork, WorkerService workers, Clock clock) {
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
        return "attendance-history";
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
