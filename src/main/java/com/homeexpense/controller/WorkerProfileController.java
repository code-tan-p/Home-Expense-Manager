package com.homeexpense.controller;

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

    public WorkerProfileController(WorkerService workers, PayService payService) {
        this.workers = workers;
        this.payService = payService;
    }

    @GetMapping("/staff/{id}/profile")
    public String profile(@PathVariable Long id, Model model) {
        model.addAttribute("page", "staff");
        model.addAttribute("worker", workers.getWorker(id));
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
