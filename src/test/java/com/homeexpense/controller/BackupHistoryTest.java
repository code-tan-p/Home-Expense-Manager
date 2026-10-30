package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The Backup page lists the nightly copies and lets one be downloaded; no other file can be asked for. The overview shows spending. */
@AutoConfigureMockMvc
class BackupHistoryTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BackupService backupService;

    @Test
    void automaticBackupsAreListedAndDownloadableButNothingElseIs() throws Exception {
        backupService.backUpCurrentHousehold(1);
        String name = "home-expense-" + LocalDate.now() + ".db";
        mockMvc.perform(get("/backup")).andExpect(status().isOk()).andExpect(content().string(containsString("Automatic backups")))
                .andExpect(content().string(containsString(name)));
        byte[] file = mockMvc.perform(get("/backup/automatic/" + name)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, 0, 15)).isEqualTo("SQLite format 3");
        mockMvc.perform(get("/backup/automatic/..%2F..%2Fapplication.properties")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/backup/automatic/secret.db")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/backup/automatic/home-expense-1999-01-01.db")).andExpect(status().isBadRequest());
    }

    @Test
    void overviewShowsHouseholdSpending() throws Exception {
        mockMvc.perform(post("/expenses").param("categoryId", "1").param("spentOn", LocalDate.now().toString()).param("amount", "321")
                .param("method", "CASH"));
        mockMvc.perform(get("/overview")).andExpect(content().string(containsString("Household spending this month")))
                .andExpect(content().string(containsString("₹321.00")));
    }
}
