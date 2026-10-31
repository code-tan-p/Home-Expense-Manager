package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A wrong day is corrected, the earlier version stays visible, and the milk litres change. */
@AutoConfigureMockMvc
class AttendanceHistoryTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;
    @Autowired
    private DailyWorkService dailyWork;

    @Test
    void correctingALitresEntryKeepsTheOldOneInTheList() throws Exception {
        mockMvc.perform(post("/staff").param("name", "Record Milkman").param("typeId", "3").param("start", "2026-04-01")
                .param("pay", "PER_UNIT").param("rate", "70")
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        Long id = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("Record Milkman")).findFirst().orElseThrow().getId();
        LocalDate day = LocalDate.of(2026, 4, 6);
        Long entry = dailyWork.dayView(day).workers().stream().filter(w -> w.worker().getId().equals(id)).findFirst().orElseThrow()
                .expected().getFirst().entry().getId();
        mockMvc.perform(post("/work/done").param("date", day.toString()).param("workerId", id.toString())
                .param("entryId", entry.toString()).param("quantity", "2"));

        mockMvc.perform(get("/attendance/" + id).param("month", "2026-04")).andExpect(status().isOk())
                .andExpect(content().string(containsString("2 L")));
        // the Today screen offers one-tap litre buttons for a milkman
        mockMvc.perform(get("/").param("date", "2026-04-07")).andExpect(status().isOk())
                .andExpect(content().string(containsString("1.5 L")));

        Long recordId = dailyWork.history(id, day, day).getFirst().getId();
        mockMvc.perform(post("/attendance/" + id + "/correct").param("recordId", recordId.toString()).param("outcome", "COMPLETED")
                        .param("litres", "1.5").param("reason", "typed 2 by mistake").param("month", "2026-04"))
                .andExpect(flash().attribute("ok", containsString("corrected")));

        mockMvc.perform(get("/attendance/" + id).param("month", "2026-04"))
                .andExpect(content().string(containsString("1.5 L")))
                .andExpect(content().string(containsString("Replaced or undone")))
                .andExpect(content().string(containsString("Corrected: typed 2 by mistake")));
        // attendance percentage, missing days and the extra-work form
        mockMvc.perform(get("/attendance/" + id).param("month", "2026-04"))
                .andExpect(content().string(containsString("Attendance of the days recorded")))
                .andExpect(content().string(containsString("100%")))
                .andExpect(content().string(containsString("Days not recorded yet")))
                .andExpect(content().string(containsString("Add extra or missed work")));
        mockMvc.perform(post("/attendance/" + id + "/extra").param("date", "2026-04-07").param("taskId", "1").param("litres", "1")
                        .param("confirm", "yes").param("note", "carried guests luggage"))
                .andExpect(flash().attribute("ok", containsString("Extra work recorded")));
        mockMvc.perform(get("/attendance/" + id).param("month", "2026-04"))
                .andExpect(content().string(containsString("carried guests luggage")));
        mockMvc.perform(post("/attendance/" + id + "/extra").header("Referer", "http://localhost/attendance/" + id)
                        .param("date", "2026-04-08").param("taskId", "1")).andExpect(flash().attributeExists("error"));
        mockMvc.perform(get("/calendar").param("workerId", id.toString()).param("month", "2026-04"))
                .andExpect(content().string(containsString("1.5 L")))
                .andExpect(content().string(containsString("Records and corrections")));
    }
}
