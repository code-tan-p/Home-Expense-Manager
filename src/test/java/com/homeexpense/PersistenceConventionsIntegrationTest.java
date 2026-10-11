package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import com.homeexpense.model.EmploymentPeriod;
import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.WorkerType;
import com.homeexpense.repository.EmploymentPeriodRepository;
import com.homeexpense.repository.ShiftRepository;
import com.homeexpense.repository.TaskRepository;
import com.homeexpense.repository.WorkScheduleRepository;
import com.homeexpense.repository.WorkerRepository;
import com.homeexpense.repository.WorkerTypeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the storage conventions that every entity relies on: how dates, timestamps,
 * booleans and IDs are written to SQLite, and how constraint failures surface in Java.
 */
@Transactional
class PersistenceConventionsIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private WorkerTypeRepository workerTypeRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private EmploymentPeriodRepository employmentPeriodRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private WorkScheduleRepository workScheduleRepository;

    private Worker newWorker() {
        WorkerType maid = workerTypeRepository.findByNameIgnoreCase("maid").orElseThrow();
        return workerRepository.saveAndFlush(
                new Worker("Test Worker", maid, null, null, Instant.parse("2026-10-10T05:30:00.123456Z")));
    }

    @Test
    void identityColumnsGenerateIds() {
        Worker first = newWorker();
        Worker second = newWorker();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    @Test
    void localDateIsStoredAsIsoText() {
        Worker worker = newWorker();
        EmploymentPeriod period = employmentPeriodRepository.saveAndFlush(
                new EmploymentPeriod(worker, LocalDate.of(2026, 1, 5)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT start_date, typeof(start_date) AS t FROM employment_period WHERE id = ?", period.getId());
        assertThat(row).containsEntry("start_date", "2026-01-05").containsEntry("t", "text");
    }

    @Test
    void dateComparisonsInQueriesAreChronological() {
        Worker worker = newWorker();
        EmploymentPeriod period = new EmploymentPeriod(worker, LocalDate.of(2025, 9, 28));
        period.close(LocalDate.of(2025, 10, 2), null);
        employmentPeriodRepository.saveAndFlush(period);

        assertThat(employmentPeriodRepository.findCovering(worker.getId(), LocalDate.of(2025, 9, 27))).isEmpty();
        assertThat(employmentPeriodRepository.findCovering(worker.getId(), LocalDate.of(2025, 9, 28))).hasSize(1);
        assertThat(employmentPeriodRepository.findCovering(worker.getId(), LocalDate.of(2025, 10, 2))).hasSize(1);
        assertThat(employmentPeriodRepository.findCovering(worker.getId(), LocalDate.of(2025, 10, 3))).isEmpty();
    }

    @Test
    void instantIsStoredAsFixedWidthUtcText() {
        Worker worker = newWorker();

        String stored = jdbcTemplate.queryForObject(
                "SELECT created_at FROM worker WHERE id = ?", String.class, worker.getId());
        assertThat(stored).isEqualTo("2026-10-10T05:30:00.123Z");

        entityManager.clear();
        assertThat(workerRepository.findById(worker.getId()).orElseThrow().getCreatedAt())
                .isEqualTo(Instant.parse("2026-10-10T05:30:00.123Z"));
    }

    @Test
    void localTimeIsStoredAsHoursAndMinutesText() {
        Shift shift = shiftRepository.saveAndFlush(new Shift("Convention test shift", LocalTime.of(7, 5), LocalTime.of(9, 30)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT default_start, default_end, typeof(default_start) AS t FROM shift WHERE id = ?", shift.getId());
        assertThat(row).containsEntry("default_start", "07:05").containsEntry("default_end", "09:30")
                .containsEntry("t", "text");
        entityManager.clear();
        assertThat(shiftRepository.findById(shift.getId()).orElseThrow().getDefaultStart()).isEqualTo(LocalTime.of(7, 5));
    }

    @Test
    void dayOfWeekIsStoredAsIsoNumber() {
        Worker worker = newWorker();
        Task task = taskRepository.saveAndFlush(new Task("Convention test task"));
        WorkSchedule schedule = new WorkSchedule(worker, LocalDate.of(2026, 1, 1), null, null);
        new ScheduleEntry(schedule, DayOfWeek.SUNDAY, task, null, null, null, null, null);
        workScheduleRepository.saveAndFlush(schedule);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT day_of_week FROM schedule_entry WHERE schedule_id = ?", Integer.class, schedule.getId())).isEqualTo(7);
    }

    @Test
    void booleanIsStoredAsZeroOrOne() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT built_in, typeof(built_in) AS t FROM worker_type WHERE name = 'Cook'");
        assertThat(row).containsEntry("built_in", 1).containsEntry("t", "integer");

        WorkerType custom = workerTypeRepository.saveAndFlush(new WorkerType("Watchman"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT built_in FROM worker_type WHERE id = ?", Integer.class, custom.getId())).isZero();
        assertThat(workerTypeRepository.findByNameIgnoreCase("COOK").orElseThrow().isBuiltIn()).isTrue();
    }

    @Test
    void checkConstraintViolationThroughJpaBecomesDataIntegrityViolation() {
        Worker worker = newWorker();
        EmploymentPeriod invalid = new EmploymentPeriod(worker, LocalDate.of(2026, 5, 10));
        invalid.close(LocalDate.of(2026, 5, 9), null);

        assertThatThrownBy(() -> employmentPeriodRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void triggerViolationThroughJpaBecomesDataIntegrityViolation() {
        Worker worker = newWorker();
        EmploymentPeriod first = new EmploymentPeriod(worker, LocalDate.of(2026, 1, 1));
        first.close(LocalDate.of(2026, 3, 31), null);
        employmentPeriodRepository.saveAndFlush(first);

        EmploymentPeriod overlapping = new EmploymentPeriod(worker, LocalDate.of(2026, 3, 1));
        overlapping.close(LocalDate.of(2026, 4, 30), null);

        assertThatThrownBy(() -> employmentPeriodRepository.saveAndFlush(overlapping))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("must not overlap");
    }
}
