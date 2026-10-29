package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.homeexpense.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HomeControllerTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void todayPageRenders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("Today")));
    }

    @Test
    void staffPayAndBackupPagesRender() throws Exception {
        mockMvc.perform(get("/staff")).andExpect(status().isOk()).andExpect(content().string(containsString("Add a worker")));
        mockMvc.perform(get("/pay")).andExpect(status().isOk()).andExpect(content().string(containsString("Monthly Pay")));
        mockMvc.perform(get("/backup")).andExpect(status().isOk()).andExpect(content().string(containsString("Download backup")));
    }

    @Test
    void addedWorkerAppearsOnStaffAndTodayScreens() throws Exception {
        mockMvc.perform(post("/staff")
                        .param("name", "Anita Sharma").param("typeId", "1").param("start", "2026-01-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "5500")
                        .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/staff")).andExpect(content().string(containsString("Anita Sharma")))
                .andExpect(content().string(containsString("5500.00")));
        mockMvc.perform(get("/").param("date", "2026-03-02")).andExpect(content().string(containsString("Anita Sharma")));
    }

    @Test
    void milkmanIsAskedForLitresAndNoDeliveryIsOffered() throws Exception {
        mockMvc.perform(post("/staff")
                        .param("name", "Ramesh Yadav").param("typeId", "1").param("start", "2026-01-01")
                        .param("pay", "PER_UNIT").param("rate", "72")
                        .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/").param("date", "2026-03-02"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Litres")))
                .andExpect(content().string(containsString("No delivery")));
        mockMvc.perform(post("/work/done").param("date", "2026-03-02").param("workerId", "1").param("entryId", "1")
                        .param("quantity", "1,5"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/").param("date", "2026-03-02"))
                .andExpect(content().string(containsString("1.50 litres")));
    }

    @Test
    void paymentIsCountedAsPaidSoFarAndShownOnMonthlyPay() throws Exception {
        mockMvc.perform(post("/staff")
                        .param("name", "Sunita Devi").param("typeId", "2").param("start", "2026-03-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "3100").param("days", "MONDAY"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/pay/payment").param("workerId", "1").param("amount", "500")
                        .param("method", "UPI").param("month", "2026-03"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("ok", containsString("500.00")));
        mockMvc.perform(get("/pay").param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Paid so far")))
                .andExpect(content().string(containsString("₹500.00")));
    }

    @Test
    void missingFieldShowsAMessageNotAnErrorPage() throws Exception {
        mockMvc.perform(post("/pay/payment").param("workerId", "1"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void referrerFromAnotherSiteIsNeverFollowed() {
        org.junit.jupiter.api.Assertions.assertEquals("/", WebErrorAdvice.samePagePath("https://evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/staff", WebErrorAdvice.samePagePath("http://127.0.0.1:8080/staff"));
    }

    @Test
    void badInputShowsAMessageInsteadOfAnErrorPage() throws Exception {
        mockMvc.perform(post("/staff").header("Referer", "/staff")
                        .param("name", " ").param("typeId", "1").param("start", "2026-01-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "5500"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/staff"));
    }

    @Test
    void backupDownloadsADatabaseFile() throws Exception {
        mockMvc.perform(get("/backup/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("home-expense-")));
    }
}
