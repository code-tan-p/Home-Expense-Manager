package com.homeexpense.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A month at a glance for one worker: for every day, did they come, what kind of day off, how many litres
 * or hours. Read-only. The same numbers feed the Calendar page and the summary lines on Monthly Pay.
 */
@Service
@Transactional(readOnly = true)
public class CalendarService {

    /** What happened on one day, in words a household would use. */
    public enum Status {
        CAME("Came"),
        PARTIAL("Part day"),
        LEAVE("On leave"),
        HOLIDAY("Holiday"),
        NO_DELIVERY("No delivery"),
        ABSENT("Didn't come"),
        DAY_OFF("Day off"),
        NOT_RECORDED("Not recorded"),
        FUTURE(""),
        NOT_EMPLOYED("");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One square of the calendar. {@code detail} is the litres or hours, for example "2.5 L" or "8 h". */
    public record DayCell(LocalDate date, Status status, String detail) {

        public int getDay() {
            return date.getDayOfMonth();
        }

        public String getCss() {
            return status.name().toLowerCase().replace('_', '-');
        }
    }

    /** Totals for the month, over the days the person was employed up to today. */
    public record Summary(LocalDate from, LocalDate to, boolean soFar, int came, int leave, int holiday, int noDelivery,
                          int absent, int notRecorded, int dayOff, long litresMilli, int minutes, int deliveryDays) {

        public String getLitres() {
            return plain(BigDecimal.valueOf(litresMilli, 3));
        }

        public String getHours() {
            return plain(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP));
        }

        public boolean hasPeriod() {
            return from != null;
        }

        /** Days came out of the days they were expected and answered for (came, leave, did not come). Holidays and unrecorded days are left out. Null when there is nothing to compare. */
        public Integer getAttendancePercent() {
            int expected = came + leave + absent;
            return expected == 0 ? null : (int) Math.round(came * 100.0 / expected);
        }
    }

    public record MonthCalendar(Worker worker, YearMonth month, boolean milk, boolean hourly,
                                List<List<DayCell>> weeks, Summary summary) {

        /** Days that should have a record and have none yet. */
        public List<LocalDate> missingDays() {
            List<LocalDate> missing = new ArrayList<>();
            for (List<DayCell> week : weeks) {
                for (DayCell c : week) {
                    if (c != null && c.status() == Status.NOT_RECORDED) {
                        missing.add(c.date());
                    }
                }
            }
            return missing;
        }
    }

    private final DailyWorkService dailyWorkService;
    private final ScheduleService scheduleService;
    private final WorkerService workerService;
    private final PayService payService;
    private final Clock clock;

    public CalendarService(DailyWorkService dailyWorkService, ScheduleService scheduleService, WorkerService workerService,
                           PayService payService, Clock clock) {
        this.dailyWorkService = dailyWorkService;
        this.scheduleService = scheduleService;
        this.workerService = workerService;
        this.payService = payService;
        this.clock = clock;
    }

    /** The whole month as weeks (Monday first); days outside the month are {@code null}. */
    public MonthCalendar month(Worker worker, YearMonth month) {
        Map<LocalDate, DayCell> cells = cells(worker, month);
        List<List<DayCell>> weeks = new ArrayList<>();
        List<DayCell> week = new ArrayList<>();
        for (int i = 1; i < month.atDay(1).getDayOfWeek().getValue(); i++) {
            week.add(null);
        }
        for (DayCell cell : cells.values()) {
            week.add(cell);
            if (cell.date().getDayOfWeek() == DayOfWeek.SUNDAY) {
                weeks.add(week);
                week = new ArrayList<>();
            }
        }
        if (!week.isEmpty()) {
            while (week.size() < 7) {
                week.add(null);
            }
            weeks.add(week);
        }
        return new MonthCalendar(worker, month, kind(worker, month) == PaymentModel.PER_UNIT,
                kind(worker, month) == PaymentModel.HOURLY, weeks, summarize(cells));
    }

    /** Just the totals, for the Monthly Pay page. */
    public Summary summary(Worker worker, YearMonth month) {
        return summarize(cells(worker, month));
    }

    private PaymentModel kind(Worker worker, YearMonth month) {
        return payService.primaryModelOn(worker.getId(), month.atEndOfMonth())
                .or(() -> payService.primaryModelOn(worker.getId(), month.atDay(1))).orElse(null);
    }

    private Map<LocalDate, DayCell> cells(Worker worker, YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        Map<LocalDate, List<WorkRecord>> byDay = new TreeMap<>();
        for (WorkRecord record : dailyWorkService.history(worker.getId(), month.atDay(1), month.atEndOfMonth())) {
            if (record.isCurrent()) {
                byDay.computeIfAbsent(record.getWorkDate(), d -> new ArrayList<>()).add(record);
            }
        }
        boolean milk = kind(worker, month) == PaymentModel.PER_UNIT;
        Map<LocalDate, DayCell> cells = new TreeMap<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            cells.put(d, cell(worker, d, today, byDay.getOrDefault(d, List.of()), milk));
        }
        return cells;
    }

    private DayCell cell(Worker worker, LocalDate d, LocalDate today, List<WorkRecord> records, boolean milk) {
        if (!workerService.isEmployedOn(worker.getId(), d)) {
            return new DayCell(d, Status.NOT_EMPLOYED, "");
        }
        long litres = 0;
        int minutes = 0;
        boolean worked = false;
        boolean partial = false;
        Status off = null;
        for (WorkRecord r : records) {
            if (r.getOutcome() == WorkOutcome.NOT_DONE) {
                AbsenceReason reason = r.getAbsenceReason();
                off = reason == AbsenceReason.APPROVED_LEAVE ? Status.LEAVE
                        : reason == AbsenceReason.HOUSEHOLD_HOLIDAY ? (milk ? Status.NO_DELIVERY : Status.HOLIDAY)
                        : Status.ABSENT;
            } else {
                worked = true;
                partial |= r.getOutcome() == WorkOutcome.PARTIAL;
                litres += r.getActualQuantityMilli() == null ? 0 : r.getActualQuantityMilli();
                minutes += r.getActualMinutes() == null ? 0 : r.getActualMinutes();
            }
        }
        if (worked) {
            String detail = litres > 0 ? plain(BigDecimal.valueOf(litres, 3)) + " L"
                    : minutes > 0 ? plain(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)) + " h"
                    : "";
            return new DayCell(d, partial || off != null ? Status.PARTIAL : Status.CAME, detail);
        }
        if (off != null) {
            return new DayCell(d, off, "");
        }
        if (scheduleService.entriesOn(worker.getId(), d).isEmpty()) {
            return new DayCell(d, Status.DAY_OFF, "");
        }
        return new DayCell(d, d.isAfter(today) ? Status.FUTURE : Status.NOT_RECORDED, "");
    }

    private Summary summarize(Map<LocalDate, DayCell> cells) {
        LocalDate from = null;
        LocalDate to = null;
        int came = 0, leave = 0, holiday = 0, noDelivery = 0, absent = 0, notRecorded = 0, dayOff = 0, minutes = 0, deliveryDays = 0;
        long litres = 0;
        LocalDate today = LocalDate.now(clock);
        for (DayCell c : cells.values()) {
            if (c.status() == Status.NOT_EMPLOYED) {
                continue;
            }
            if (from == null) {
                from = c.date();
            }
            if (!c.date().isAfter(today)) {
                to = c.date();
            }
            switch (c.status()) {
                case CAME, PARTIAL -> {
                    came++;
                    litres += parseLitres(c.detail());
                    minutes += parseMinutes(c.detail());
                    if (c.detail().endsWith(" L")) {
                        deliveryDays++;
                    }
                }
                case LEAVE -> leave++;
                case HOLIDAY -> holiday++;
                case NO_DELIVERY -> noDelivery++;
                case ABSENT -> absent++;
                case NOT_RECORDED -> {
                    if (!c.date().equals(today)) {
                        notRecorded++;
                    }
                }
                case DAY_OFF -> dayOff++;
                default -> { }
            }
        }
        if (to == null || (from != null && to.isBefore(from))) {
            from = null;
            to = null;
        }
        YearMonth month = cells.isEmpty() ? null : YearMonth.from(cells.keySet().iterator().next());
        boolean soFar = to != null && month != null && to.isBefore(month.atEndOfMonth()) && !LocalDate.now(clock).isAfter(to);
        return new Summary(from, to, soFar, came, leave, holiday, noDelivery, absent, notRecorded, dayOff, litres, minutes, deliveryDays);
    }

    private static long parseLitres(String detail) {
        return detail.endsWith(" L") ? new BigDecimal(detail.substring(0, detail.length() - 2)).movePointRight(3).longValueExact() : 0;
    }

    private static int parseMinutes(String detail) {
        return detail.endsWith(" h")
                ? new BigDecimal(detail.substring(0, detail.length() - 2)).multiply(BigDecimal.valueOf(60))
                        .setScale(0, java.math.RoundingMode.HALF_UP).intValueExact()
                : 0;
    }

    static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
