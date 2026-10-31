package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Before closing: unrecorded days of a monthly worker can be confirmed as not worked, then the month closes. */
@AutoConfigureMockMvc
class ReviewScreenTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;

    @Test
    void unrecordedDaysAreConfirmedAsNotWorkedAndThenTheMonthCloses() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Review Maid").param("typeId", "1").param("start", "2026-02-01")
                .param("pay", "MONTHLY_SALARY").param("rate", "2800").param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("Review Maid")).findFirst().orElseThrow().getId();
        // Record only the first Monday of February; the other scheduled days stay empty.
        mockMvc.perform(post("/work/all-present").param("date", "2026-02-02"));

        mockMvc.perform(get("/pay").param("month", "2026-02")).andExpect(content().string(containsString("Review and fix before closing")));
        mockMvc.perform(get("/pay/review/" + id).param("month", "2026-02")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Days that were not recorded")));

        mockMvc.perform(post("/pay/close").header("Referer", "http://localhost/pay").param("workerId", id.toString()).param("month", "2026-02"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/pay/review/" + id + "/unrecorded").header("Referer", "http://localhost/pay/review/" + id).param("month", "2026-02"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/pay/review/" + id + "/unrecorded").param("month", "2026-02").param("sure", "yes"))
                .andExpect(flash().attribute("ok", containsString("not worked")));

        mockMvc.perform(get("/pay/review/" + id).param("month", "2026-02"))
                .andExpect(content().string(containsString("Nothing is left to decide")));
        mockMvc.perform(post("/pay/close").param("workerId", id.toString()).param("month", "2026-02"))
                .andExpect(flash().attribute("ok", containsString("closed")));
    }
}
