package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

/** A per-day worker is only marked on days they came: nothing nags about the other days. A monthly worker still is asked. */
@AutoConfigureMockMvc
class DayByDayTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void perDayWorkerIsNeverNaggedButMonthlyWorkerIs() throws Exception {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        mockMvc.perform(post("/staff").param("name", "Nag Dayo").param("typeId", "5").param("start", first.toString())
                .param("pay", "PER_DAY").param("rate", "300"));
        String day = first.toString();

        // His own card has no "not recorded" note and no Leave / Absent buttons.
        String page = mockMvc.perform(get("/").param("date", day)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card = page.substring(page.indexOf("Nag Dayo"));
        card = card.substring(0, card.indexOf("</section>"));
        assertThat(card).contains("Done").doesNotContain("not recorded yet").doesNotContain("Absent (did not tell)");
        mockMvc.perform(get("/overview")).andExpect(content().string(not(containsString("Nag Dayo:"))));

        mockMvc.perform(post("/staff").param("name", "Nag Maid").param("typeId", "1").param("start", first.toString())
                .param("pay", "MONTHLY_SALARY").param("rate", "3000")
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        String page2 = mockMvc.perform(get("/").param("date", day)).andReturn().getResponse().getContentAsString();
        String maid = page2.substring(page2.indexOf("Nag Maid"));
        maid = maid.substring(0, maid.indexOf("</section>"));
        assertThat(maid).contains("not recorded yet").contains("Absent (did not tell)");
    }
}
