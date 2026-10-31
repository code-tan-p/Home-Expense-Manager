package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A wrong payment is corrected by replacing it; the old one stays in the list with the reason. */
@AutoConfigureMockMvc
class PaymentHistoryTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;
    @Autowired
    private PaymentRepository payments;

    @Test
    void aPaymentIsListedCorrectedAndTheOldOneIsKept() throws Exception {
        mockMvc.perform(post("/staff").param("name", "History Maid").param("typeId", "1").param("start", "2026-04-01")
                .param("pay", "MONTHLY_SALARY").param("rate", "3000").param("days", "MONDAY", "TUESDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("History Maid")).findFirst().orElseThrow().getId();
        mockMvc.perform(post("/pay/payment").param("workerId", id.toString()).param("amount", "1000").param("method", "UPI")
                .param("month", YearMonth.now().toString()).param("reference", "UTR123"));

        mockMvc.perform(get("/pay/history/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("₹1000.00"))).andExpect(content().string(containsString("UTR123")));

        Long paymentId = payments.findByWorkerIdOrderByPaidOnAscIdAsc(id).getFirst().getId();
        mockMvc.perform(post("/pay/history/" + id + "/correct").param("paymentId", paymentId.toString()).param("amount", "1200")
                        .param("method", "CASH").param("paidOn", java.time.LocalDate.now().toString()).param("reason", "typed wrong"))
                .andExpect(flash().attribute("ok", containsString("corrected")));

        mockMvc.perform(get("/pay/history/" + id))
                .andExpect(content().string(containsString("₹1200.00")))
                .andExpect(content().string(containsString("Replaced")))
                .andExpect(content().string(containsString("typed wrong")));

        // Replacing the old (already replaced) payment again is refused.
        mockMvc.perform(post("/pay/history/" + id + "/correct").header("Referer", "http://localhost/pay/history/" + id)
                        .param("paymentId", paymentId.toString()).param("amount", "1300").param("method", "CASH")
                        .param("paidOn", java.time.LocalDate.now().toString()).param("reason", "again"))
                .andExpect(flash().attributeExists("error"));
    }
}
