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

/** Name, phone and notes can be edited; the history is shown. */
@AutoConfigureMockMvc
class WorkerProfileTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;

    @Test
    void detailsCanBeEditedAndTheProfileShowsHistory() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Profile Cook").param("typeId", "2").param("start", "2026-03-01")
                .param("pay", "MONTHLY_SALARY").param("rate", "4000").param("days", "MONDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("Profile Cook")).findFirst().orElseThrow().getId();

        mockMvc.perform(get("/staff/" + id + "/profile")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Employment history")))
                .andExpect(content().string(containsString("calendar")))
                .andExpect(content().string(containsString("cal-cell")))
                .andExpect(content().string(containsString("₹4000.00")));
        mockMvc.perform(post("/staff/" + id + "/details").param("name", "Profile Cook Renamed").param("phone", "98765")
                        .param("notes", "Allergic to nuts"))
                .andExpect(flash().attribute("ok", containsString("saved")));
        mockMvc.perform(get("/staff/" + id + "/profile")).andExpect(content().string(containsString("Profile Cook Renamed")))
                .andExpect(content().string(containsString("98765"))).andExpect(content().string(containsString("Allergic to nuts")));
        mockMvc.perform(post("/staff/" + id + "/details").header("Referer", "http://localhost/staff/" + id + "/profile")
                        .param("name", "  "))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(get("/staff")).andExpect(content().string(containsString("Profile and history")));
    }
}
