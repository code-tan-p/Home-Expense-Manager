package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.ExpenseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Expenses: add, summarise by category, correct (the old entry stays), remove, repeat monthly, download, never delete. */
@AutoConfigureMockMvc
class ExpenseTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ExpenseService expenses;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void expensesCanBeAddedCorrectedRemovedAndAreNeverDeleted() throws Exception {
        mockMvc.perform(post("/expenses").param("categoryId", "1").param("spentOn", "2026-05-03").param("amount", "450")
                        .param("method", "UPI").param("note", "Weekly groceries"))
                .andExpect(flash().attribute("ok", containsString("₹450.00")));
        mockMvc.perform(post("/expenses").param("categoryId", "6").param("spentOn", "2026-05-01").param("amount", "12000")
                .param("method", "BANK_TRANSFER").param("note", "May rent").param("recurring", "yes"));

        mockMvc.perform(get("/expenses").param("month", "2026-05")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Weekly groceries")))
                .andExpect(content().string(containsString("₹12450.00")))
                .andExpect(content().string(containsString("Groceries")));

        long id = expenses.list(YearMonth.of(2026, 5), 1L, false).getFirst().id();
        mockMvc.perform(post("/expenses/" + id + "/correct").param("categoryId", "2").param("spentOn", "2026-05-03")
                        .param("amount", "400").param("method", "UPI").param("reason", "wrong category"))
                .andExpect(flash().attribute("ok", containsString("corrected")));
        mockMvc.perform(get("/expenses").param("month", "2026-05").param("all", "true"))
                .andExpect(content().string(containsString("Corrected or removed")))
                .andExpect(content().string(containsString("Corrected: wrong category")));
        mockMvc.perform(get("/expenses").param("month", "2026-05"))
                .andExpect(content().string(containsString("₹12400.00")));

        // the database itself refuses deleting or editing
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update("DELETE FROM expense"))
                .getMessage()).contains("never deleted");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.update("UPDATE expense SET amount_paise = 1 WHERE id = ?", id)).getMessage()).contains("voided");

        // repeating monthly entries move forward once
        mockMvc.perform(post("/expenses/recurring").param("month", "2026-06")).andExpect(flash().attribute("ok", containsString("1 repeating")));
        mockMvc.perform(post("/expenses/recurring").param("month", "2026-06")).andExpect(flash().attribute("ok", containsString("Nothing to add")));
        mockMvc.perform(get("/expenses").param("month", "2026-06")).andExpect(content().string(containsString("May rent")));

        long rent = expenses.list(YearMonth.of(2026, 6), null, false).getFirst().id();
        mockMvc.perform(post("/expenses/" + rent + "/remove").param("reason", "paid elsewhere").param("month", "2026-06"))
                .andExpect(flash().attribute("ok", containsString("removed")));
        mockMvc.perform(get("/expenses").param("month", "2026-06")).andExpect(content().string(not(containsString("12000.00 "))));

        String csv = mockMvc.perform(get("/expenses.csv").param("month", "2026-05")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(csv).contains("Date,Category").contains("400.00");

        mockMvc.perform(post("/expenses/categories").param("name", "Pets").param("month", "2026-05"))
                .andExpect(flash().attribute("ok", containsString("Pets")));
        mockMvc.perform(post("/expenses/categories").header("Referer", "http://localhost/expenses").param("name", "pets").param("month", "2026-05"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/expenses").header("Referer", "http://localhost/expenses").param("categoryId", "1").param("spentOn", "2026-05-03")
                .param("amount", "-5").param("method", "CASH")).andExpect(flash().attributeExists("error"));
    }
}
