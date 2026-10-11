package com.homeexpense.service;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningLineType;
import com.homeexpense.model.earnings.EarningRecordTreatment;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.model.earnings.TreatmentType;

/**
 * Builds earnings scenarios through the real services. January 2026 starts on a Thursday;
 * Mondays are the 5th, 12th, 19th and 26th, Sundays the 4th, 11th, 18th and 25th. A Monday
 * to Saturday schedule has 27 scheduled days in January 2026.
 */
final class EarningsFixture {

    static final YearMonth JANUARY = YearMonth.of(2026, 1);
    static final List<DayOfWeek> MON_TO_SAT = List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY);

    final WorkerService workerService;
    final CatalogService catalogService;
    final ScheduleService scheduleService;
    final PayService payService;
    final DailyWorkService dailyWorkService;
    final EarningsService earningsService;
    final Fixtures fixtures;

    EarningsFixture(WorkerService workerService, CatalogService catalogService, ScheduleService scheduleService,
                    PayService payService, DailyWorkService dailyWorkService, EarningsService earningsService) {
        this.workerService = workerService;
        this.catalogService = catalogService;
        this.scheduleService = scheduleService;
        this.payService = payService;
        this.dailyWorkService = dailyWorkService;
        this.earningsService = earningsService;
        this.fixtures = new Fixtures(workerService, catalogService);
    }

    static LocalDate d(String text) {
        return LocalDate.parse(text);
    }

    Worker worker(String name, LocalDate start, PaymentModel model) {
        Worker worker = fixtures.worker(name, "Maid", start);
        payService.setPrimaryModel(worker.getId(), model, d("2026-01-01"));
        return worker;
    }

    Task task(String name) {
        return catalogService.addTask(name);
    }

    static NewScheduleEntry timed(DayOfWeek day, Task task, String start, String end) {
        return new NewScheduleEntry(day, task.getId(), null, LocalTime.parse(start), LocalTime.parse(end), null, null);
    }

    /** The same entries on every listed day. */
    WorkSchedule weekly(Worker worker, LocalDate from, List<DayOfWeek> days, java.util.function.Function<DayOfWeek, List<NewScheduleEntry>> perDay) {
        List<NewScheduleEntry> entries = new ArrayList<>();
        for (DayOfWeek day : days) {
            entries.addAll(perDay.apply(day));
        }
        return scheduleService.addScheduleVersion(worker.getId(), from, entries, null);
    }

    PayRate rate(Worker worker, RateType type, String amount, LocalDate from) {
        return payService.addRate(NewPayRate.standard(worker.getId(), type, amount, from));
    }

    PayRate taskRate(Worker worker, RateType type, Task task, String amount, LocalDate from) {
        return payService.addRate(new NewPayRate(worker.getId(), type, task.getId(), null, null, amount, from, null));
    }

    static ScheduleEntry entryOn(WorkSchedule schedule, DayOfWeek day, Task task) {
        return schedule.getEntries().stream()
                .filter(e -> e.getDayOfWeek() == day && e.getTask() != null && e.getTask().getId().equals(task.getId()))
                .findFirst().orElseThrow();
    }

    WorkRecord scheduled(Worker worker, String date, ScheduleEntry entry, WorkDetails details) {
        return dailyWorkService.recordScheduled(worker.getId(), d(date), entry.getId(), details).record();
    }

    WorkRecord unscheduled(Worker worker, String date, Task task, WorkDetails details) {
        return dailyWorkService.recordUnscheduled(worker.getId(), d(date), task.getId(), null, details, true).record();
    }

    EarningsResult january(Worker worker) {
        return earningsService.calculate(worker.getId(), JANUARY);
    }

    static List<EarningLine> lines(EarningsResult result, EarningLineType type) {
        return result.lines().stream().filter(l -> l.getLineType() == type).toList();
    }

    static List<EarningLine> linesOn(EarningsResult result, String date) {
        return result.lines().stream().filter(l -> d(date).equals(l.getWorkDate())).toList();
    }

    static EarningRecordTreatment treatmentOf(EarningsResult result, WorkRecord record) {
        return result.treatments().stream().filter(t -> t.getWorkRecord().getId().equals(record.getId()))
                .findFirst().orElseThrow();
    }

    static TreatmentType typeOf(EarningsResult result, WorkRecord record) {
        return treatmentOf(result, record).getTreatment();
    }

    static long counted(EarningsResult result) {
        return result.lines().stream().filter(l -> l.getAmountPaise() != null).mapToLong(EarningLine::getAmountPaise).sum();
    }

    static List<EarningLine> pending(EarningsResult result) {
        return result.lines().stream().filter(l -> l.getReviewStatus() == ReviewStatus.NEEDS_REVIEW).toList();
    }
}
