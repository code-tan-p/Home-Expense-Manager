package com.homeexpense.controller;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.Payment;
import com.homeexpense.repository.PaymentRepository;
import com.homeexpense.repository.PaymentVoidRepository;
import com.homeexpense.service.CalendarService;
import com.homeexpense.service.EarningsService;
import com.homeexpense.service.WorkerService;
import com.homeexpense.money.Money;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Reports for a month, as a page to print and as spreadsheet files (CSV), plus a printable salary slip. */
@Controller
public class ReportsController {

    /** One worker's month. */
    public record SalaryRow(Worker worker, CalendarService.Summary summary, long earnedPaise, long paidPaise, boolean closed,
                            String problem) {
        public long stillToPayPaise() {
            return earnedPaise - paidPaise;
        }
    }

    private final WorkerService workers;
    private final CalendarService calendar;
    private final EarningsService earnings;
    private final PaymentRepository payments;
    private final PaymentVoidRepository voids;
    private final Clock clock;

    public ReportsController(WorkerService workers, CalendarService calendar, EarningsService earnings,
                             PaymentRepository payments, PaymentVoidRepository voids, Clock clock) {
        this.workers = workers;
        this.calendar = calendar;
        this.earnings = earnings;
        this.payments = payments;
        this.voids = voids;
        this.clock = clock;
    }

    @GetMapping("/reports")
    public String reports(@RequestParam(required = false) YearMonth month, Model model) {
        YearMonth shown = month(month);
        List<SalaryRow> rows = rows(shown);
        model.addAttribute("page", "reports");
        model.addAttribute("month", shown);
        model.addAttribute("previous", shown.minusMonths(1));
        model.addAttribute("next", shown.plusMonths(1));
        model.addAttribute("rows", rows);
        model.addAttribute("totalEarned", rows.stream().mapToLong(SalaryRow::earnedPaise).sum());
        model.addAttribute("totalPaid", rows.stream().mapToLong(SalaryRow::paidPaise).sum());
        model.addAttribute("payments", paymentsBetween(shown.atDay(1), shown.atEndOfMonth()));
        return "reports";
    }

    @GetMapping("/reports/salary.csv")
    public ResponseEntity<byte[]> salaryCsv(@RequestParam(required = false) YearMonth month) {
        YearMonth shown = month(month);
        StringBuilder csv = new StringBuilder("Worker,Job,From,To,Days came,Leave,Holiday,Did not come,Litres,Hours,Earned (Rs),Paid (Rs),Still to pay (Rs),Month closed\r\n");
        for (SalaryRow r : rows(shown)) {
            var s = r.summary();
            csv.append(cell(r.worker().getName())).append(',').append(cell(r.worker().getWorkerType().getName())).append(',')
                    .append(s.from() == null ? "" : s.from()).append(',').append(s.to() == null ? "" : s.to()).append(',')
                    .append(s.came()).append(',').append(s.leave()).append(',').append(s.holiday() + s.noDelivery()).append(',')
                    .append(s.absent()).append(',').append(s.getLitres()).append(',').append(s.getHours()).append(',')
                    .append(Money.plain(r.earnedPaise())).append(',').append(Money.plain(r.paidPaise())).append(',')
                    .append(Money.plain(r.stillToPayPaise())).append(',').append(r.closed() ? "yes" : "no").append("\r\n");
        }
        return csv("salary-" + shown + ".csv", csv);
    }

    @GetMapping("/reports/payments.csv")
    public ResponseEntity<byte[]> paymentsCsv(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from == null ? today.withDayOfMonth(1) : InputRules.requireDate(from, today);
        LocalDate end = to == null ? today : InputRules.requireDate(to, today);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The end date is before the start date.");
        }
        StringBuilder csv = new StringBuilder("Date,Worker,Amount (Rs),Method,Reference,Note\r\n");
        for (Payment p : paymentsBetween(start, end)) {
            csv.append(p.getPaidOn()).append(',').append(cell(p.getWorker().getName())).append(',').append(Money.plain(p.getAmountPaise()))
                    .append(',').append(p.getMethod()).append(',').append(cell(p.getReference())).append(',').append(cell(p.getNote()))
                    .append("\r\n");
        }
        return csv("payments-" + start + "-to-" + end + ".csv", csv);
    }

    @GetMapping("/reports/slip/{workerId}")
    public String slip(@PathVariable Long workerId, @RequestParam(required = false) YearMonth month, Model model) {
        YearMonth shown = month(month);
        Worker worker = workers.getWorker(workerId);
        var summary = calendar.summary(worker, shown);
        model.addAttribute("page", "reports");
        model.addAttribute("worker", worker);
        model.addAttribute("month", shown);
        model.addAttribute("summary", summary);
        model.addAttribute("earnings", earnings.preview(workerId, shown));
        model.addAttribute("paidPaise", paidInMonth(workerId, shown));
        return "slip";
    }

    // ----------------------------------------------------------------------------- helpers

    private YearMonth month(YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        return month == null ? YearMonth.from(today) : InputRules.requireMonth(month, today);
    }

    private List<SalaryRow> rows(YearMonth month) {
        List<SalaryRow> rows = new ArrayList<>();
        for (Worker worker : workers.listAllWorkers()) {
            var summary = calendar.summary(worker, month);
            if (summary.from() == null && !workers.isEmployedOn(worker.getId(), month.atEndOfMonth())
                    && !workers.isEmployedOn(worker.getId(), month.atDay(1))) {
                continue;
            }
            long paid = paidInMonth(worker.getId(), month);
            try {
                var preview = earnings.preview(worker.getId(), month);
                rows.add(new SalaryRow(worker, summary, preview.totalPaise(), paid, preview.finalized(), null));
            } catch (BusinessRuleException e) {
                rows.add(new SalaryRow(worker, summary, 0, paid, false, e.getMessage()));
            }
        }
        return rows;
    }

    private long paidInMonth(Long workerId, YearMonth month) {
        return paymentsBetween(month.atDay(1), month.atEndOfMonth()).stream()
                .filter(p -> p.getWorker().getId().equals(workerId)).mapToLong(Payment::getAmountPaise).sum();
    }

    private List<Payment> paymentsBetween(LocalDate from, LocalDate to) {
        List<Payment> found = new ArrayList<>();
        for (Worker worker : workers.listAllWorkers()) {
            for (Payment p : payments.findByWorkerIdOrderByPaidOnAscIdAsc(worker.getId())) {
                if (!p.getPaidOn().isBefore(from) && !p.getPaidOn().isAfter(to) && voids.findByPaymentId(p.getId()).isEmpty()) {
                    found.add(p);
                }
            }
        }
        found.sort((a, b) -> a.getPaidOn().compareTo(b.getPaidOn()) != 0 ? a.getPaidOn().compareTo(b.getPaidOn())
                : Long.compare(a.getId(), b.getId()));
        return found;
    }

    /** A CSV cell: quoted, and made safe so a spreadsheet never treats typed text as a formula. */
    static String cell(String text) {
        String value = text == null ? "" : text;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static ResponseEntity<byte[]> csv(String filename, StringBuilder body) {
        byte[] bytes = ("﻿" + body).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }
}
