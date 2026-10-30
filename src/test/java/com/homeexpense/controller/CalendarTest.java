package com.homeexpense.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The calendar shows who came, what kind of day off, and the litres or hours; Monthly Pay repeats the totals. */
@AutoConfigureMockMvc
class CalendarTest extends SqliteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;
    @Autowired
    private DailyWorkService dailyWorkService;

    private Long addWorker(String name, String typeId, String pay, String rate) throws Exception {
        mockMvc.perform(post("/staff").param("name", name).param("typeId", typeId).param("start", "2026-04-01")
                .param("pay", pay).param("rate", rate)
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        return workerService.listAllWorkers().stream().filter(w -> w.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private Long entry(Long workerId, LocalDate day) {
        return dailyWorkService.dayView(day).workers().stream().filter(w -> w.worker().getId().equals(workerId))
                .findFirst().orElseThrow().expected().getFirst().entry().getId();
    }

    private void litres(Long id, int day, String litres) throws Exception {
        LocalDate d = LocalDate.of(2026, 4, day);
        mockMvc.perform(post("/work/done").param("date", d.toString()).param("workerId", id.toString())
                .param("entryId", entry(id, d).toString()).param("quantity", litres));
    }

    private void absent(Long id, int day, String reason) throws Exception {
        mockMvc.perform(post("/work/absent").param("date", LocalDate.of(2026, 4, day).toString())
                .param("workerId", id.toString()).param("reason", reason));
    }

    @Test
    void milkmanCalendarShowsLitresNoDeliveryDaysAndTotals() throws Exception {
        Long id = addWorker("Calendar Milkman", "3", "PER_UNIT", "70");
        litres(id, 1, "2.5");
        litres(id, 2, "1");
        litres(id, 3, "3");
        absent(id, 4, "HOUSEHOLD_HOLIDAY");

        mockMvc.perform(get("/calendar").param("workerId", id.toString()).param("month", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Calendar Milkman")))
                .andExpect(content().string(containsString("2.5 L")))
                .andExpect(content().string(containsString("3 L")))
                .andExpect(content().string(containsString("No delivery")))
                .andExpect(content().string(containsString("6.5 litres")))
                .andExpect(content().string(containsString("days came")))
                .andExpect(content().string(containsString("not recorded yet")));

        mockMvc.perform(get("/pay").param("month", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pay counted from")))
                .andExpect(content().string(containsString("1 Apr")))
                .andExpect(content().string(containsString("30 Apr 2026")))
                .andExpect(content().string(containsString("6.5 litres")))
                .andExpect(content().string(containsString(">3</span> delivery days")))
                .andExpect(content().string(containsString("See calendar")))
                .andExpect(content().string(containsString("How this was worked out")));
    }

    @Test
    void maidCalendarSeparatesLeaveHolidayAndAbsence() throws Exception {
        Long id = addWorker("Calendar Maid", "1", "MONTHLY_SALARY", "3000");
        mockMvc.perform(post("/work/all-present").param("date", "2026-04-01"));
        absent(id, 2, "APPROVED_LEAVE");
        absent(id, 3, "HOUSEHOLD_HOLIDAY");
        absent(id, 4, "UNAUTHORIZED_ABSENCE");

        mockMvc.perform(get("/calendar").param("workerId", id.toString()).param("month", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("On leave")))
                .andExpect(content().string(containsString("Holiday")))
                .andExpect(content().string(containsString("Didn't come")))
                .andExpect(content().string(containsString("1</strong> on leave")))
                .andExpect(content().string(containsString("1</strong> holiday")))
                .andExpect(content().string(containsString("1</strong> didn't come")))
                // a maid is never shown litres
                .andExpect(content().string(not(containsString("Milk delivered"))));
    }

    @Test
    void calendarWithNoWorkersOrABadMonthIsFriendly() throws Exception {
        mockMvc.perform(get("/calendar").param("month", "9999-12")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/calendar").param("workerId", "999999")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/calendar")).andExpect(status().isOk());
    }
}
