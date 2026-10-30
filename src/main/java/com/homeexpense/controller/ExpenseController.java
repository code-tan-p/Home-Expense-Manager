package com.homeexpense.controller;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import com.homeexpense.money.Money;
import com.homeexpense.service.ExpenseService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Household expenses: add, filter by month and category, correct, remove, repeat monthly ones, download. */
@Controller
public class ExpenseController {

    private final ExpenseService expenses;
    private final Clock clock;

    public ExpenseController(ExpenseService expenses, Clock clock) {
        this.expenses = expenses;
        this.clock = clock;
    }

    @GetMapping("/expenses")
    public String expenses(@RequestParam(required = false) YearMonth month, @RequestParam(required = false) Long category,
                           @RequestParam(defaultValue = "false") boolean all, Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth shown = month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
        var list = expenses.list(shown, category, all);
        model.addAttribute("page", "expenses");
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("category", category);
        model.addAttribute("all", all);
        model.addAttribute("categories", expenses.categories());
        model.addAttribute("methods", ExpenseService.METHODS);
        model.addAttribute("list", list);
        model.addAttribute("totals", expenses.totalsByCategory(shown));
        model.addAttribute("total", list.stream().filter(e -> !e.voided()).mapToLong(ExpenseService.Expense::amountPaise).sum());
        model.addAttribute("today", today);
        return "expenses";
    }

    @PostMapping("/expenses")
    public String add(@RequestParam Long categoryId, @RequestParam LocalDate spentOn, @RequestParam String amount,
                      @RequestParam String method, @RequestParam(defaultValue = "") String note,
                      @RequestParam(defaultValue = "") String recurring, RedirectAttributes flash) {
        LocalDate today = LocalDate.now(clock);
        InputRules.requireDate(spentOn, today);
        long paise = Money.parsePositiveRupees(InputRules.requireRupees(amount, "the amount"));
        expenses.record(categoryId, spentOn, paise, method, InputRules.limited(note, 200, "the note"), !recurring.isBlank());
        flash.addFlashAttribute("ok", "Expense of " + Money.format(paise) + " recorded.");
        return "redirect:/expenses?month=" + YearMonth.from(spentOn);
    }

    @PostMapping("/expenses/{id}/correct")
    public String correct(@PathVariable Long id, @RequestParam Long categoryId, @RequestParam LocalDate spentOn,
                          @RequestParam String amount, @RequestParam String method, @RequestParam(defaultValue = "") String note,
                          @RequestParam(defaultValue = "") String recurring, @RequestParam String reason, RedirectAttributes flash) {
        InputRules.requireDate(spentOn, LocalDate.now(clock));
        long paise = Money.parsePositiveRupees(InputRules.requireRupees(amount, "the amount"));
        expenses.correct(id, categoryId, spentOn, paise, method, InputRules.limited(note, 200, "the note"), !recurring.isBlank(),
                InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Expense corrected. The earlier entry is kept (tick 'show corrected entries' to see it).");
        return "redirect:/expenses?month=" + YearMonth.from(spentOn);
    }

    @PostMapping("/expenses/{id}/remove")
    public String remove(@PathVariable Long id, @RequestParam String reason, @RequestParam YearMonth month, RedirectAttributes flash) {
        expenses.voidExpense(id, InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Expense removed. It stays in the history.");
        return "redirect:/expenses?month=" + month;
    }

    @PostMapping("/expenses/categories")
    public String addCategory(@RequestParam String name, @RequestParam YearMonth month, RedirectAttributes flash) {
        var created = expenses.addCategory(name);
        flash.addFlashAttribute("ok", "Category " + created.name() + " added.");
        return "redirect:/expenses?month=" + month;
    }

    @PostMapping("/expenses/recurring")
    public String recurring(@RequestParam YearMonth month, RedirectAttributes flash) {
        InputRules.requireMonth(month, LocalDate.now(clock));
        int added = expenses.addRecurring(month);
        flash.addFlashAttribute("ok", added == 0 ? "Nothing to add: last month's repeating expenses are already here."
                : added + " repeating expense(s) added from last month.");
        return "redirect:/expenses?month=" + month;
    }

    @GetMapping("/expenses.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) YearMonth month) {
        YearMonth shown = month == null ? YearMonth.now(clock) : InputRules.requireMonth(month, LocalDate.now(clock));
        StringBuilder out = new StringBuilder("Date,Category,Amount (Rs),Method,Note,Repeats monthly\r\n");
        for (var e : expenses.list(shown, null, false)) {
            out.append(e.spentOn()).append(',').append(ReportsController.cell(e.category())).append(',').append(Money.plain(e.amountPaise()))
                    .append(',').append(e.method()).append(',').append(ReportsController.cell(e.note())).append(',')
                    .append(e.recurring() ? "yes" : "no").append("\r\n");
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("expenses-" + shown + ".csv").build().toString())
                .body(("﻿" + out).getBytes(StandardCharsets.UTF_8));
    }
}
