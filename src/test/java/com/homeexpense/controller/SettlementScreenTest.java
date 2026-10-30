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

/** Month-end screen: the settled picture, reopening with a reason, and the version history. */
@AutoConfigureMockMvc
class SettlementScreenTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;

    @Test
    void closedMonthShowsItsPictureCanBeReopenedAndKeepsTheOldVersion() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Settle Maid").param("typeId", "1").param("start", "2026-03-30")
                .param("pay", "MONTHLY_SALARY").param("rate", "3100")
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("Settle Maid")).findFirst().orElseThrow().getId();
        mockMvc.perform(post("/work/all-present").param("date", "2026-03-30"));
        mockMvc.perform(post("/work/all-present").param("date", "2026-03-31"));

        mockMvc.perform(get("/settlement/" + id).param("month", "2026-03")).andExpect(status().isOk())
                .andExpect(content().string(containsString("has not been closed yet")));

        mockMvc.perform(post("/pay/payment").param("workerId", id.toString()).param("amount", "100").param("method", "CASH")
                .param("month", "2026-03"));
        mockMvc.perform(post("/pay/close").param("workerId", id.toString()).param("month", "2026-03"))
                .andExpect(flash().attribute("ok", containsString("closed")));

        mockMvc.perform(get("/settlement/" + id).param("month", "2026-03")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Settled")))
                .andExpect(content().string(containsString("Amount to pay")))
                .andExpect(content().string(containsString("Reopen this month")));

        mockMvc.perform(post("/settlement/" + id + "/reopen").header("Referer", "http://localhost/settlement/" + id)
                        .param("month", "2026-03").param("reason", "wrong days"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/settlement/" + id + "/reopen").param("month", "2026-03").param("reason", "wrong days").param("sure", "yes"))
                .andExpect(flash().attribute("ok", containsString("reopened")));

        mockMvc.perform(get("/settlement/" + id).param("month", "2026-03")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Earlier versions")))
                .andExpect(content().string(containsString("wrong days")))
                .andExpect(content().string(containsString("Replaced")));
    }
}
