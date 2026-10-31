package com.homeexpense.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.homeexpense.dto.FinanceDtos;
import com.homeexpense.dto.FinanceDtos.ReplacementPreview;
import com.homeexpense.dto.FinanceDtos.ReplacementRequest;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.model.finance.PaymentVoid;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.service.PaymentService;
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
 * One worker's money trail: every payment (including replaced ones, with the reason), advances still open,
 * and a way to correct a payment. A correction never edits the old payment: it is voided and replaced.
 */
@Controller
public class PaymentHistoryController {

    /** A payment with what happened to it. */
    public record PaymentLine(Payment payment, PaymentVoid voided) {
        public boolean current() {
            return voided == null;
        }
    }

    private final PaymentRepository payments;
    private final PaymentVoidRepository voids;
    private final PaymentService paymentService;
    private final SettlementService settlementService;
    private final WorkerService workerService;
    private final Clock clock;

    public PaymentHistoryController(PaymentRepository payments, PaymentVoidRepository voids, PaymentService paymentService,
                                    SettlementService settlementService, WorkerService workerService, Clock clock) {
        this.clock = clock;
        this.payments = payments;
        this.voids = voids;
        this.paymentService = paymentService;
        this.settlementService = settlementService;
        this.workerService = workerService;
    }

    @GetMapping("/pay/history/{workerId}")
    public String history(@PathVariable Long workerId, Model model) {
        List<PaymentLine> lines = payments.findByWorkerIdOrderByPaidOnAscIdAsc(workerId).stream()
                .map(p -> new PaymentLine(p, voids.findByPaymentId(p.getId()).orElse(null))).toList();
        model.addAttribute("page", "pay");
        model.addAttribute("worker", workerService.getWorker(workerId));
        model.addAttribute("lines", lines.reversed());
        model.addAttribute("advances", settlementService.advances(workerId).stream().filter(a -> a.balancePaise() > 0).toList());
        model.addAttribute("methods", PaymentMethod.values());
        model.addAttribute("today", LocalDate.now(clock));
        return "payment-history";
    }

    /** Replaces a payment. The preview is worked out here and saved only if it still holds. */
    @PostMapping("/pay/history/{workerId}/correct")
    public String correct(@PathVariable Long workerId, @RequestParam Long paymentId, @RequestParam String amount,
                          @RequestParam PaymentMethod method, @RequestParam LocalDate paidOn,
                          @RequestParam(defaultValue = "") String reference, @RequestParam(defaultValue = "") String note,
                          @RequestParam String reason, RedirectAttributes flash) {
        amount = InputRules.requireRupees(amount, "the amount paid");
        InputRules.requireDate(paidOn, LocalDate.now(clock));
        ReplacementRequest request = new ReplacementRequest(paidOn, amount, method, InputRules.limited(reference, 60, "the reference"),
                InputRules.limited(note, 200, "the note"), null, null);
        ReplacementPreview preview = paymentService.previewReplacement(paymentId, request);
        paymentService.confirmReplacement(paymentId, request, preview, reason);
        flash.addFlashAttribute("ok", "Payment corrected. The old one is kept in the list as replaced.");
        return "redirect:/pay/history/" + workerId;
    }
}
