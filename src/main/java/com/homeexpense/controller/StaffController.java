package com.homeexpense.controller;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.NewWorker;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.Task;
import com.homeexpense.model.Worker;
import com.homeexpense.service.CatalogService;
import com.homeexpense.service.PayService;
import com.homeexpense.service.ScheduleService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Staff" screen: see everyone, add a worker with pay and weekdays in one form. */
@Controller
public class StaffController {

    static final String DEFAULT_TASK = "Daily work";

    /** The pay choices offered in the form, with plain-language names. */
    public enum PayChoice {
        MONTHLY_SALARY("Monthly salary", "per month", PaymentModel.MONTHLY_SALARY),
        PER_DAY("Per day worked", "per day", PaymentModel.PER_DAY),
        HOURLY("Per hour", "per hour", PaymentModel.HOURLY),
        PER_UNIT("Per litre (milk)", "per litre", PaymentModel.PER_UNIT),
        PER_TASK("Per task done", "per task", PaymentModel.PER_TASK);

        private final String label;
        private final String unit;
        private final PaymentModel model;

        PayChoice(String label, String unit, PaymentModel model) {
            this.label = label;
            this.unit = unit;
            this.model = model;
        }

        public String getLabel() {
            return label;
        }

        public String getUnit() {
            return unit;
        }
    }

    public record StaffRow(Worker worker, boolean active, String payText) {
    }

    private final WorkerService workerService;
    private final PayService payService;
    private final ScheduleService scheduleService;
    private final CatalogService catalogService;
    private final Clock clock;

    public StaffController(WorkerService workerService, PayService payService, ScheduleService scheduleService,
                           CatalogService catalogService, Clock clock) {
        this.workerService = workerService;
        this.payService = payService;
        this.scheduleService = scheduleService;
        this.catalogService = catalogService;
        this.clock = clock;
    }

    @GetMapping("/staff")
    public String staff(Model model) {
        LocalDate today = LocalDate.now(clock);
        List<StaffRow> rows = new ArrayList<>();
        for (Worker worker : workerService.listAllWorkers()) {
            rows.add(new StaffRow(worker, workerService.isActiveToday(worker.getId()), payText(worker, today)));
        }
        model.addAttribute("page", "staff");
        model.addAttribute("rows", rows);
        model.addAttribute("types", workerService.listWorkerTypes());
        model.addAttribute("choices", PayChoice.values());
        model.addAttribute("days", DayOfWeek.values());
        model.addAttribute("today", today);
        return "staff";
    }

    /** One transaction: if the pay or schedule is rejected, the worker is not created either. */
    @PostMapping("/staff")
    @org.springframework.transaction.annotation.Transactional
    public String add(@RequestParam String name, @RequestParam Long typeId, @RequestParam LocalDate start,
                      @RequestParam(required = false) String phone, @RequestParam PayChoice pay, @RequestParam String rate,
                      @RequestParam(required = false) List<DayOfWeek> days, RedirectAttributes flash) {
        com.homeexpense.money.Money.parsePositiveRupees(rate); // reject a bad amount before anything is saved
        Worker worker = workerService.createWorker(new NewWorker(name, typeId, phone, null, start)).worker();
        payService.setPrimaryModel(worker.getId(), pay.model, start);
        String unit = pay.model == PaymentModel.PER_UNIT ? "litre" : null;
        payService.addRate(new NewPayRate(worker.getId(), pay.model.rateType(), null, null, unit, rate, start, null));
        if (days != null && !days.isEmpty()) {
            Long taskId = defaultTask().getId();
            List<NewScheduleEntry> entries = days.stream().map(day -> NewScheduleEntry.task(day, taskId)).toList();
            scheduleService.addScheduleVersion(worker.getId(), start, entries, null);
        }
        flash.addFlashAttribute("ok", name.strip() + " was added.");
        return "redirect:/staff";
    }

    /** The last working day defaults to today but can be an earlier date. */
    @PostMapping("/staff/{id}/stop")
    public String stop(@PathVariable Long id, @RequestParam(required = false) LocalDate last, RedirectAttributes flash) {
        workerService.deactivate(id, last == null ? LocalDate.now(clock) : last, null);
        flash.addFlashAttribute("ok", workerService.getWorker(id).getName() + " is marked as no longer working here.");
        return "redirect:/staff";
    }

    @PostMapping("/staff/{id}/restart")
    public String restart(@PathVariable Long id, RedirectAttributes flash) {
        workerService.reactivate(id, LocalDate.now(clock));
        flash.addFlashAttribute("ok", workerService.getWorker(id).getName() + " is marked as working again.");
        return "redirect:/staff";
    }

    /** A raise (or cut): a new rate from a date. Earlier months keep the old rate. */
    @PostMapping("/staff/{id}/pay")
    public String changePay(@PathVariable Long id, @RequestParam String rate, @RequestParam LocalDate from,
                            RedirectAttributes flash) {
        PaymentModel model = payService.primaryModelOn(id, from)
                .orElseThrow(() -> new com.homeexpense.exception.BusinessRuleException("No pay is set for that date."));
        String unit = model == PaymentModel.PER_UNIT ? "litre" : null;
        payService.addRate(new NewPayRate(id, model.rateType(), null, null, unit, rate, from, null));
        flash.addFlashAttribute("ok", "New pay saved from " + from + ".");
        return "redirect:/staff";
    }

    /** New weekdays from a date. Days already recorded are never changed. */
    @PostMapping("/staff/{id}/days")
    public String changeDays(@PathVariable Long id, @RequestParam LocalDate from,
                             @RequestParam(required = false) List<DayOfWeek> days, RedirectAttributes flash) {
        Long taskId = defaultTask().getId();
        List<NewScheduleEntry> entries = days == null ? List.of()
                : days.stream().map(day -> NewScheduleEntry.task(day, taskId)).toList();
        scheduleService.addScheduleVersion(id, from, entries, null);
        flash.addFlashAttribute("ok", "New weekdays saved from " + from + ".");
        return "redirect:/staff";
    }

    private Task defaultTask() {
        return catalogService.activeTasks().stream()
                .filter(task -> DEFAULT_TASK.equalsIgnoreCase(task.getName()))
                .findFirst()
                .orElseGet(() -> catalogService.addTask(DEFAULT_TASK));
    }

    private String payText(Worker worker, LocalDate today) {
        return payService.primaryModelOn(worker.getId(), today)
                .flatMap(model -> payService.primaryRateFor(worker.getId(), today, null, null))
                .map(resolved -> describe(resolved.rate()))
                .orElse("No pay set yet");
    }

    private static String describe(PayRate rate) {
        return com.homeexpense.money.Money.format(rate.getAmountPaise()) + " " + rate.getRateType().name().toLowerCase().replace('_', ' ');
    }
}
