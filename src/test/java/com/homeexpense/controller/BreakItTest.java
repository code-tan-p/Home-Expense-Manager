package com.homeexpense.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.DayView;
import com.homeexpense.service.BackupService;
import com.homeexpense.service.DailyWorkService;
import com.homeexpense.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tries to break the website with the kinds of input and clicks a real household would produce by
 * accident: wrong numbers, wrong dates, double clicks, hostile text, bad files. Every case must end in a
 * plain message and unchanged records, never an error page or a saved nonsense record.
 */
@AutoConfigureMockMvc
class BreakItTest extends SqliteIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkerService workerService;
    @Autowired
    private DailyWorkService dailyWorkService;
    @Autowired
    private BackupService backupService;

    private Long addWorker(String name, String pay, String rate) throws Exception {
        mockMvc.perform(post("/staff").param("name", name).param("typeId", "1").param("start", "2026-06-01")
                .param("pay", pay).param("rate", rate)
                .param("days", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        return workerService.listAllWorkers().stream().filter(w -> w.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private DayView.ExpectedActivity activity(Long workerId, LocalDate day) {
        return dailyWorkService.dayView(day).workers().stream().filter(w -> w.worker().getId().equals(workerId))
                .findFirst().orElseThrow().expected().getFirst();
    }

    private boolean recorded(Long workerId, LocalDate day) {
        return activity(workerId, day).isRecorded();
    }

    private void tryDone(Long workerId, LocalDate day, String field, String value) throws Exception {
        mockMvc.perform(post("/work/done").param("date", day.toString()).param("workerId", workerId.toString())
                .param("entryId", activity(workerId, day).entry().getId().toString()).param(field, value));
    }

    // ----------------------------------------------------------------------------- routing

    @Test
    void everyPostOnlyAddressRefusesAPlainLinkWithoutAnErrorPage() throws Exception {
        for (String path : new String[]{"/work/done", "/work/absent", "/work/undo", "/work/all-present", "/work/deduction",
                "/staff/1/stop", "/staff/1/restart", "/staff/1/pay", "/staff/1/days", "/pay/payment", "/pay/advance",
                "/pay/close", "/backup/download", "/backup/restore"}) {
            mockMvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        }
        mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
    }

    @Test
    void postFromAnotherSiteOrWithOriginNullIsRefused() throws Exception {
        mockMvc.perform(post("/work/all-present").param("date", DAY.toString()).header("Origin", "null"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/work/all-present").param("date", DAY.toString()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/work/all-present").param("date", DAY.toString()).header("Referer", "https://evil.example/x"))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------------------- dates

    @Test
    void impossibleOrExtremeDatesShowAMessageInsteadOfLoopingOrCrashing() throws Exception {
        for (String date : new String[]{"9999-12-31", "+999999999-12-31", "1900-01-01", "0000-01-01", "2026-02-30", "abc"}) {
            mockMvc.perform(get("/").param("date", date))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Something did not work")));
        }
        for (String month : new String[]{"9999-12", "+999999999-12", "1900-01", "2026-13", "abc"}) {
            mockMvc.perform(get("/pay").param("month", month))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Something did not work")));
        }
        mockMvc.perform(get("/").param("date", "2024-02-29")).andExpect(status().isOk());
        mockMvc.perform(get("/").param("date", "2000-01-01")).andExpect(status().isOk());
    }

    // ----------------------------------------------------------------------------- numbers

    @Test
    void milkmanMustHaveSensibleLitresBeforeAnythingIsSaved() throws Exception {
        Long id = addWorker("Break Milkman", "PER_UNIT", "70");
        for (String litres : new String[]{"", " ", "0", "-5", "0.0001", "1.5.5", "1e9", "NaN", "abc", "1001", "99999999999999"}) {
            tryDone(id, DAY, "quantity", litres);
            assertThat(recorded(id, DAY)).as("litres '%s' must not be saved", litres).isFalse();
        }
        tryDone(id, DAY, "hours", "3");
        assertThat(recorded(id, DAY)).as("hours alone must not save a milkman").isFalse();
        tryDone(id, DAY, "quantity", "1,25");
        assertThat(activity(id, DAY).record().getActualQuantityMilli()).isEqualTo(1250L);
    }

    @Test
    void hourlyWorkerMustHaveHoursBetweenZeroAndTwentyFour() throws Exception {
        Long id = addWorker("Break Driver", "HOURLY", "120");
        for (String hours : new String[]{"", "0", "-1", "24.01", "25", "1e9", "abc", "0.001"}) {
            tryDone(id, DAY, "hours", hours);
            assertThat(recorded(id, DAY)).as("hours '%s' must not be saved", hours).isFalse();
        }
        tryDone(id, DAY, "hours", "24");
        assertThat(activity(id, DAY).record().getActualMinutes()).isEqualTo(1440);
    }

    @Test
    void payAmountsAreLimitedAndMustBeNumbers() throws Exception {
        Long id = addWorker("Break Payee", "MONTHLY_SALARY", "3000");
        for (String amount : new String[]{"", "0", "-5", "abc", "1e9", "99999999999999", "1000001", "5.555"}) {
            mockMvc.perform(post("/pay/payment").param("workerId", id.toString()).param("amount", amount)
                            .param("method", "CASH").param("month", "2026-06"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("error"));
        }
        for (String rate : new String[]{"0", "-1", "abc", "1e9", "2000000"}) {
            mockMvc.perform(post("/staff").param("name", "Rate Rejected").param("typeId", "1").param("start", "2026-06-01")
                            .param("pay", "MONTHLY_SALARY").param("rate", rate).param("days", "MONDAY"))
                    .andExpect(flash().attributeExists("error"));
        }
        assertThat(workerService.listAllWorkers()).noneMatch(w -> w.getName().equals("Rate Rejected"));
    }

    // ----------------------------------------------------------------------------- text

    @Test
    void hostileAndLongNamesAreEscapedOrRefused() throws Exception {
        String script = "<script>alert(1)</script>";
        addWorker(script, "MONTHLY_SALARY", "1000");
        String page = mockMvc.perform(get("/staff")).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain(script).contains("&lt;script&gt;");
        String today = mockMvc.perform(get("/").param("date", DAY.toString())).andReturn().getResponse().getContentAsString();
        assertThat(today).doesNotContain(script);

        mockMvc.perform(post("/staff").param("name", "x".repeat(101)).param("typeId", "1").param("start", "2026-06-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "100").param("days", "MONDAY"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/staff").param("name", "   ").param("typeId", "1").param("start", "2026-06-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "100").param("days", "MONDAY"))
                .andExpect(flash().attributeExists("error"));
        addWorker("Mango 🥭 Emoji", "MONTHLY_SALARY", "1000");
        mockMvc.perform(get("/staff")).andExpect(content().string(containsString("Mango")));
    }

    @Test
    void aMilkmanMustBePaidByTheLitreAndTheFormExplainsIt() throws Exception {
        // typeId 3 is the built-in "Milkman" job.
        mockMvc.perform(post("/staff").param("name", "Salaried Milkman").param("typeId", "3").param("start", "2026-06-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "5000").param("days", "MONDAY"))
                .andExpect(flash().attribute("error", containsString("paid by the litre")));
        assertThat(workerService.listAllWorkers()).noneMatch(w -> w.getName().equals("Salaried Milkman"));
        mockMvc.perform(post("/staff").param("name", "Litre Milkman").param("typeId", "3").param("start", "2026-06-01")
                        .param("pay", "PER_UNIT").param("rate", "72").param("days", "MONDAY"))
                .andExpect(flash().attribute("ok", containsString("was added")));
        mockMvc.perform(get("/staff"))
                .andExpect(content().string(containsString("Price per litre")))
                .andExpect(content().string(containsString("per litre (pay depends on litres delivered)")));
    }

    @Test
    void sameNameTwiceIsAllowedButWarned() throws Exception {
        addWorker("Twin Tara", "MONTHLY_SALARY", "1000");
        mockMvc.perform(post("/staff").param("name", "Twin Tara").param("typeId", "1").param("start", "2026-06-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "1000").param("days", "MONDAY"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void onlyAMonthlyWorkerNeedsWeekdaysOthersAreListedEveryDay() throws Exception {
        // A monthly salary is tied to weekdays, so at least one is required.
        mockMvc.perform(post("/staff").param("name", "No Days Dev").param("typeId", "1").param("start", "2026-06-01")
                        .param("pay", "MONTHLY_SALARY").param("rate", "100"))
                .andExpect(flash().attributeExists("error"));
        assertThat(workerService.listAllWorkers()).noneMatch(w -> w.getName().equals("No Days Dev"));
        // Everyone else is on the Today screen every day without ticking weekdays.
        mockMvc.perform(post("/staff").param("name", "Hourly Hari").param("typeId", "4").param("start", "2026-06-01")
                .param("pay", "HOURLY").param("rate", "100"));
        Long hari = workerService.listAllWorkers().stream().filter(w -> w.getName().equals("Hourly Hari")).findFirst().orElseThrow().getId();
        for (LocalDate day : List.of(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7))) {
            assertThat(dailyWorkService.dayView(day).workers()).anyMatch(w -> w.worker().getId().equals(hari) && !w.expected().isEmpty());
        }
    }

    // ----------------------------------------------------------------------------- replays

    @Test
    void doubleClickedPaymentAndAdvanceAreRecordedOnce() throws Exception {
        Long id = addWorker("Twice Tina", "MONTHLY_SALARY", "3000");
        for (String path : new String[]{"/pay/payment", "/pay/advance"}) {
            mockMvc.perform(post(path).param("workerId", id.toString()).param("amount", "123")
                    .param("method", "CASH").param("month", "2026-06")).andExpect(flash().attributeExists("ok"));
            mockMvc.perform(post(path).param("workerId", id.toString()).param("amount", "123")
                            .param("method", "CASH").param("month", "2026-06"))
                    .andExpect(flash().attribute("error", containsString("same amount sent twice")));
        }
    }

    @Test
    void markAllTwiceAndDoneTwiceChangeNothingTheSecondTime() throws Exception {
        Long id = addWorker("Replay Rani", "MONTHLY_SALARY", "3000");
        LocalDate day = LocalDate.of(2026, 6, 16);
        mockMvc.perform(post("/work/all-present").param("date", day.toString()));
        Long first = activity(id, day).record().getId();
        mockMvc.perform(post("/work/all-present").param("date", day.toString()));
        assertThat(activity(id, day).record().getId()).isEqualTo(first);
        tryDone(id, day, "quantity", "");
        assertThat(activity(id, day).record().getId()).isEqualTo(first);
    }

    @Test
    void closingAMonthTwiceIsRefused() throws Exception {
        Long id = addWorker("Close Chandra", "MONTHLY_SALARY", "3000");
        for (int d = 1; d <= 30; d++) {
            mockMvc.perform(post("/work/all-present").param("date", LocalDate.of(2026, 6, d).toString()));
        }
        mockMvc.perform(post("/pay/close").param("workerId", id.toString()).param("month", "2026-06"))
                .andExpect(flash().attributeExists("ok"));
        mockMvc.perform(post("/pay/close").param("workerId", id.toString()).param("month", "2026-06"))
                .andExpect(flash().attribute("error", containsString("already closed")));
        mockMvc.perform(get("/pay").param("month", "2026-06")).andExpect(content().string(containsString("Month closed")));
    }

    // ----------------------------------------------------------------------------- pay rules

    @Test
    void approvedLeaveIsUnpaidForSalariedStaffAsInTheOriginalRules() throws Exception {
        Long id = addWorker("Leave Leela", "MONTHLY_SALARY", "3100");
        mockMvc.perform(post("/work/absent").param("date", "2026-06-10").param("workerId", id.toString())
                .param("reason", "APPROVED_LEAVE"));
        for (int d = 1; d <= 30; d++) {
            mockMvc.perform(post("/work/all-present").param("date", LocalDate.of(2026, 6, d).toString()));
        }
        // June has 30 scheduled days; one unpaid leave day: 3100 x 29 / 30 = 2996.67
        mockMvc.perform(get("/pay").param("month", "2026-06"))
                .andExpect(content().string(containsString("₹2996.67")));
    }

    // ----------------------------------------------------------------------------- backups

    @Test
    void restoreRefusesDamagedAndForeignFilesButAcceptsARealBackup() throws Exception {
        byte[] garbage = ("SQLite format 3\0" + "x".repeat(2000)).getBytes();
        mockMvc.perform(multipart("/backup/restore").file(new MockMultipartFile("file", "b.db", "application/octet-stream", garbage))
                        .header("Referer", "http://localhost/backup"))
                .andExpect(flash().attributeExists("error"));
        mockMvc.perform(multipart("/backup/restore").file(new MockMultipartFile("file", "e.db", "application/octet-stream", new byte[0]))
                        .header("Referer", "http://localhost/backup"))
                .andExpect(flash().attributeExists("error"));

        byte[] real = backupService.snapshot();
        mockMvc.perform(multipart("/backup/restore").file(new MockMultipartFile("file", "ok.db", "application/octet-stream", real)))
                .andExpect(flash().attributeExists("ok"));
        mockMvc.perform(get("/staff")).andExpect(status().isOk());
    }

    @Test
    void automaticBackupWritesACopyAndTheDownloadLeavesNoTempFile() throws Exception {
        backupService.backUpCurrentHousehold(1);
        try (var files = Files.list(DATA_DIR.resolve("backups").resolve("1"))) {
            assertThat(files.toList()).anyMatch(p -> p.getFileName().toString().startsWith("home-expense-"));
        }
        byte[] copy = backupService.snapshot();
        assertThat(new String(copy, 0, 15)).isEqualTo("SQLite format 3");
        mockMvc.perform(post("/backup/download")).andExpect(status().isOk());
    }

    @Test
    void errorPageNeverShowsTechnicalWords() throws Exception {
        mockMvc.perform(get("/").param("date", "abc"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("Whitelabel"))));
    }
}
