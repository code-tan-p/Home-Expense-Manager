package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.CreateWorkerResult;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.NewWorker;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.EmploymentPeriod;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.Worker;
import com.homeexpense.model.WorkerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;

@Transactional
class WorkerServiceIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private PayService payService;

    @Autowired
    private AuditService auditService;

    private Fixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(workerService, catalogService);
    }

    private Long maidTypeId() {
        return workerService.listWorkerTypes().stream()
                .filter(type -> type.getName().equals("Maid")).findFirst().orElseThrow().getId();
    }

    @Test
    void createsWorkerWithFirstEmploymentPeriod() {
        CreateWorkerResult result = workerService.createWorker(
                new NewWorker("  Sunita  ", maidTypeId(), "98765 43210", "", d("2026-02-01")));

        Worker worker = result.worker();
        assertThat(worker.getName()).isEqualTo("Sunita");
        assertThat(worker.getNotes()).isNull();
        assertThat(result.warnings()).isEmpty();
        assertThat(workerService.employmentHistory(worker.getId())).singleElement()
                .satisfies(period -> {
                    assertThat(period.getStartDate()).isEqualTo(d("2026-02-01"));
                    assertThat(period.isOpen()).isTrue();
                });
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-01-31"))).isFalse();
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-02-01"))).isTrue();
        assertThat(auditService.historyForWorker(worker.getId())).extracting(AuditEvent::getAction)
                .containsExactly("CREATE");
    }

    @Test
    void rejectsMissingWorkerDetails() {
        Long type = maidTypeId();
        assertThatThrownBy(() -> workerService.createWorker(new NewWorker("  ", type, null, null, JAN_1)))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Enter the worker's name.");
        assertThatThrownBy(() -> workerService.createWorker(new NewWorker("Asha", null, null, null, JAN_1)))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Choose a worker type.");
        assertThatThrownBy(() -> workerService.createWorker(new NewWorker("Asha", 9999L, null, null, JAN_1)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> workerService.createWorker(new NewWorker("Asha", type, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("started working");
    }

    @Test
    void duplicateNamesAreAllowedWithAWarning() {
        Worker first = fixtures.maid("Sunita");

        CreateWorkerResult second = workerService.createWorker(new NewWorker("SUNITA", maidTypeId(), null, null, JAN_1));

        assertThat(second.worker().getId()).isNotEqualTo(first.getId());
        assertThat(second.warnings()).singleElement().asString()
                .contains("Another worker named 'Sunita' already exists (worker #" + first.getId() + ")");
    }

    @Test
    void customWorkerTypesCanBeAddedButNotDuplicated() {
        WorkerType watchman = workerService.addWorkerType(" Watchman ");

        assertThat(watchman.getName()).isEqualTo("Watchman");
        assertThat(watchman.isBuiltIn()).isFalse();
        assertThat(workerService.listWorkerTypes()).extracting(WorkerType::getName)
                .contains("Maid", "Cook", "Milkman", "Driver", "Cleaner", "Gardener", "Watchman");
        assertThatThrownBy(() -> workerService.addWorkerType("watchman"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> workerService.addWorkerType("cook"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already exists");
    }

    @Test
    void deactivationClosesThePeriodAndPreservesAllHistory() {
        Worker worker = fixtures.maid("Sunita");
        Task sweeping = fixtures.task("Sweeping");
        scheduleService.addScheduleVersion(worker.getId(), JAN_1,
                List.of(NewScheduleEntry.task(DayOfWeek.MONDAY, sweeping.getId())), null);
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        payService.addRate(NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "5000", JAN_1));

        EmploymentPeriod closed = workerService.deactivate(worker.getId(), d("2026-03-31"), "Moved away");

        assertThat(closed.getEndDate()).isEqualTo(d("2026-03-31"));
        assertThat(closed.getEndReason()).isEqualTo("Moved away");
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-03-31"))).isTrue();
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-04-01"))).isFalse();
        assertThat(workerService.listAllWorkers()).extracting(Worker::getId).contains(worker.getId());
        assertThat(scheduleService.history(worker.getId())).hasSize(1);
        assertThat(payService.rateHistory(worker.getId())).singleElement()
                .satisfies(rate -> assertThat(rate.getAmountPaise()).isEqualTo(500000));
        assertThat(auditService.historyForWorker(worker.getId())).extracting(AuditEvent::getAction)
                .contains("DEACTIVATE");
    }

    @Test
    void deactivationRules() {
        Worker worker = fixtures.worker("Ramesh", "Driver", d("2026-02-01"));

        assertThatThrownBy(() -> workerService.deactivate(worker.getId(), d("2026-01-31"), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be before");
        assertThatThrownBy(() -> workerService.deactivate(worker.getId(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("last working day");

        workerService.deactivate(worker.getId(), d("2026-02-01"), null);
        assertThatThrownBy(() -> workerService.deactivate(worker.getId(), d("2026-03-01"), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not currently active");
    }

    @Test
    void reactivationAddsAPeriodToTheSameWorkerWithoutDuplicatingHistory() {
        Worker worker = fixtures.maid("Sunita");
        Task sweeping = fixtures.task("Sweeping");
        scheduleService.addScheduleVersion(worker.getId(), JAN_1,
                List.of(NewScheduleEntry.task(DayOfWeek.MONDAY, sweeping.getId())), null);
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        payService.addRate(NewPayRate.standard(worker.getId(), RateType.MONTHLY_SALARY, "5000", JAN_1));
        int workersBefore = workerService.listAllWorkers().size();
        workerService.deactivate(worker.getId(), d("2026-03-31"), null);

        EmploymentPeriod second = workerService.reactivate(worker.getId(), d("2026-06-01"));

        assertThat(second.getWorker().getId()).isEqualTo(worker.getId());
        assertThat(workerService.listAllWorkers()).hasSize(workersBefore);
        assertThat(workerService.employmentHistory(worker.getId())).hasSize(2);
        assertThat(scheduleService.history(worker.getId())).hasSize(1);
        assertThat(payService.rateHistory(worker.getId())).hasSize(1);
        assertThat(payService.arrangementHistory(worker.getId())).hasSize(1);

        // Schedules and rates carry over; nothing is expected during the gap.
        assertThat(scheduleService.entriesOn(worker.getId(), d("2026-05-04"))).isEmpty();
        assertThat(scheduleService.entriesOn(worker.getId(), d("2026-06-01"))).hasSize(1);
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-06-01"), null, null)).isPresent();
        assertThat(auditService.historyForWorker(worker.getId())).extracting(AuditEvent::getAction)
                .contains("DEACTIVATE", "REACTIVATE");
    }

    @Test
    void reactivationRules() {
        Worker worker = fixtures.maid("Sunita");

        assertThatThrownBy(() -> workerService.reactivate(worker.getId(), d("2026-06-01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already active");

        workerService.deactivate(worker.getId(), d("2026-03-31"), null);
        assertThatThrownBy(() -> workerService.reactivate(worker.getId(), d("2026-03-31")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("must be after");
        assertThatThrownBy(() -> workerService.reactivate(worker.getId(), d("2026-02-01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("must be after");

        workerService.reactivate(worker.getId(), d("2026-04-01"));
        assertThat(workerService.isEmployedOn(worker.getId(), d("2026-04-01"))).isTrue();
    }

    @Test
    void workersEmployedOnADateExcludesInactiveWorkers() {
        Worker active = fixtures.maid("Active worker");
        Worker left = fixtures.maid("Former worker");
        workerService.deactivate(left.getId(), d("2026-02-28"), null);

        assertThat(workerService.workersEmployedOn(d("2026-03-01"))).extracting(Worker::getId)
                .contains(active.getId()).doesNotContain(left.getId());
        assertThat(workerService.workersEmployedOn(d("2026-02-28"))).extracting(Worker::getId)
                .contains(active.getId(), left.getId());
    }

    @Test
    void unknownWorkerIsReportedPlainly() {
        assertThatThrownBy(() -> workerService.getWorker(424242L))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Worker #424242 was not found.");
    }
}
