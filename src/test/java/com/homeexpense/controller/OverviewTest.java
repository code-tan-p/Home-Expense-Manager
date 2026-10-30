package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.homeexpense.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The overview adds up staff, today's pending work, earnings, payments and advances. */
@AutoConfigureMockMvc
class OverviewTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overviewShowsTotalsAndLinksAndIsInTheMenu() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Overview Maid").param("typeId", "1")
                .param("start", LocalDate.now().withDayOfMonth(1).toString()).param("pay", "MONTHLY_SALARY").param("rate", "3000")
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        mockMvc.perform(get("/overview")).andExpect(status().isOk())
                .andExpect(content().string(containsString("People working")))
                .andExpect(content().string(containsString("Earned this month")))
                .andExpect(content().string(containsString("Still to pay")))
                .andExpect(content().string(containsString("Advances to take back")));
        mockMvc.perform(get("/staff")).andExpect(content().string(containsString("Overview")));
    }
}
