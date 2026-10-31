package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.homeexpense.dto.FinanceDtos;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.finance.Settlement;
import com.homeexpense.model.finance.SettlementVersion;
import com.homeexpense.repository.SettlementRepository;
import com.homeexpense.repository.SettlementVersionRepository;
import com.homeexpense.service.SettlementService;
import com.homeexpense.service.WorkerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The month-end money picture for one worker: how the amount to pay was put together, what has been paid or carried
 * forward, every earlier version of the settlement, advances, and the ways to correct it (reopen, or add a correction).
 */
@Controller
public class SettlementController {

    private final SettlementService settlements;
    private final SettlementRepository settlementRepository;
    private final SettlementVersionRepository versions;
    private final WorkerService workers;
    private final Clock clock;

    public SettlementController(SettlementService settlements, SettlementRepository settlementRepository,
                                SettlementVersionRepository versions, WorkerService workers, Clock clock) {
        this.settlements = settlements;
        this.settlementRepository = settlementRepository;
        this.versions = versions;
        this.workers = workers;
        this.clock = clock;
    }

    @GetMapping("/settlement/{workerId}")
    public String settlement(@PathVariable Long workerId, @RequestParam(required = false) YearMonth month, Model model) {
        LocalDate today = LocalDate.now(clock);
        YearMonth shown = month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
        Settlement settlement = settlementRepository.findByWorkerIdAndPeriodStart(workerId, shown.atDay(1)).orElse(null);
        FinanceDtos.SettlementView view = null;
        List<SettlementVersion> history = List.of();
        if (settlement != null) {
            history = versions.findBySettlementIdOrderByVersionNoAsc(settlement.getId()).reversed();
            try {
                view = settlements.view(workerId, shown);
            } catch (BusinessRuleException ignored) {
                // Reopened and not prepared again: only the history is shown.
            }
        }
        model.addAttribute("page", "pay");
        model.addAttribute("worker", workers.getWorker(workerId));
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("view", view);
        model.addAttribute("history", history);
        model.addAttribute("advances", settlements.advances(workerId));
        model.addAttribute("settled", view != null && view.version().getStatus() == com.homeexpense.model.finance.SettlementStatus.SETTLED);
        return "settlement";
    }

    @PostMapping("/settlement/{workerId}/reopen")
    public String reopen(@PathVariable Long workerId, @RequestParam YearMonth month, @RequestParam String reason,
                         @RequestParam(defaultValue = "") String sure, RedirectAttributes flash) {
        if (sure.isBlank()) {
            throw new BusinessRuleException("Tick the box to confirm that you want to reopen the month.");
        }
        settlements.reopenSettlement(workerId, month, InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "The month is reopened. Its days and pay can be changed now, then close it again from Monthly Pay.");
        return "redirect:/settlement/" + workerId + "?month=" + month;
    }

    /** Moves the part of a balance that came from an earlier carry-forward to a later month. */
    @PostMapping("/settlement/{workerId}/carry")
    public String carry(@PathVariable Long workerId, @RequestParam YearMonth month, @RequestParam YearMonth laterMonth,
                        @RequestParam String reason, RedirectAttributes flash) {
        LocalDate today = LocalDate.now(clock);
        InputRules.requireMonth(month, today);
        InputRules.requireMonth(laterMonth, today);
        settlements.correctCarry(workerId, month, laterMonth, InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Moved to " + laterMonth + ".");
        return "redirect:/settlement/" + workerId + "?month=" + month;
    }

    @PostMapping("/settlement/{workerId}/correction")
    public String correction(@PathVariable Long workerId, @RequestParam YearMonth month, @RequestParam String direction,
                             @RequestParam String amount, @RequestParam(defaultValue = "") String owed, @RequestParam String reason,
                             RedirectAttributes flash) {
        InputRules.requireMonth(month, LocalDate.now(clock));
        String cleaned = InputRules.requireRupees(amount, "the amount");
        boolean add = "ADD".equals(direction);
        settlements.addCorrection(workerId, month, add ? cleaned : "-" + cleaned, add ? null : !owed.isBlank(),
                InputRules.limited(reason, 200, "the reason"));
        flash.addFlashAttribute("ok", "Correction added to " + month + ".");
        return "redirect:/settlement/" + workerId + "?month=" + month;
    }
}
