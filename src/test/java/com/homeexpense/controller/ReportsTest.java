package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Reports page, spreadsheet files (safe against formula text) and the salary slip. */
@AutoConfigureMockMvc
class ReportsTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;

    @Test
    void reportCsvAndSlipShowTheMonth() throws Exception {
        mockMvc.perform(post("/staff").param("name", "=HYPERLINK(x)").param("typeId", "1").param("start", "2026-04-01")
                .param("pay", "MONTHLY_SALARY").param("rate", "3000").param("days", "MONDAY", "TUESDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().startsWith("=HYPER")).findFirst().orElseThrow().getId();
        mockMvc.perform(post("/pay/payment").param("workerId", id.toString()).param("amount", "500").param("method", "CASH")
                .param("month", YearMonth.now().toString()));

        mockMvc.perform(get("/reports").param("month", "2026-04")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Salary for")))
                .andExpect(content().string(containsString("Download salary (CSV)")));

        String csv = mockMvc.perform(get("/reports/salary.csv").param("month", "2026-04")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Worker,Job").contains("\"'=HYPERLINK(x)\"");

        String payments = mockMvc.perform(get("/reports/payments.csv").param("from", LocalDate.now().withDayOfMonth(1).toString())
                .param("to", LocalDate.now().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(payments).contains("500.00").contains("CASH");

        mockMvc.perform(get("/reports/slip/" + id).param("month", "2026-04")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Salary slip"))).andExpect(content().string(containsString("Balance")));
        mockMvc.perform(get("/reports/payments.csv").param("from", "2026-05-02").param("to", "2026-05-01")).andExpect(status().isBadRequest());
    }
}
