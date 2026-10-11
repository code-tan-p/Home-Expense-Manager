package com.homeexpense.service;

import static com.homeexpense.service.Fixtures.JAN_1;
import static com.homeexpense.service.Fixtures.d;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.ResolvedRate;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.PayArrangement;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PayServiceIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private PayService payService;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EntityManager entityManager;

    private Fixtures fixtures;
    private Worker worker;
    private Task sweeping;
    private Task mopping;
    private Shift morning;
    private Shift evening;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(workerService, catalogService);
        worker = fixtures.maid("Sunita");
        sweeping = fixtures.task("Sweeping");
        mopping = fixtures.task("Mopping");
        morning = fixtures.shift("Morning", "07:00", "09:00");
        evening = fixtures.shift("Evening", "17:00", "19:00");
    }

    private PayRate rate(RateType type, Long taskId, Long shiftId, String unit, String amount, String from) {
        return payService.addRate(new NewPayRate(worker.getId(), type, taskId, shiftId, unit, amount, d(from), null));
    }

    private PayRate standard(RateType type, String amount, String from) {
        return rate(type, null, null, null, amount, from);
    }

    private long primaryPaise(String date, Long taskId, Long shiftId) {
        return payService.primaryRateFor(worker.getId(), d(date), taskId, shiftId).orElseThrow().rate().getAmountPaise();
    }

    @Test
    void monthlySalaryIsStoredAsExactPaise() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        PayRate salary = standard(RateType.MONTHLY_SALARY, "15000.50", "2026-01-01");

        assertThat(salary.getAmountPaise()).isEqualTo(1_500_050L);
        assertThat(payService.primaryModelOn(worker.getId(), d("2026-01-15"))).contains(PaymentModel.MONTHLY_SALARY);
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-01-15"), null, null)).get()
                .satisfies(resolved -> {
                    assertThat(resolved.rate().getId()).isEqualTo(salary.getId());
                    assertThat(resolved.specific()).isFalse();
                });
    }

    @Test
    void amountsAboveTheThirtyTwoBitLimitRoundTripExactly() {
        PayRate large = standard(RateType.MONTHLY_SALARY, "50000000.00", "2026-01-01");
        entityManager.flush();
        entityManager.clear();

        assertThat(payService.rateHistory(worker.getId())).singleElement()
                .satisfies(rate -> assertThat(rate.getAmountPaise()).isEqualTo(5_000_000_000L));
        assertThat(large.getId()).isNotNull();
    }

    @Test
    void rateChangeAddsAVersionAndKeepsTheOldAmount() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.HOURLY, JAN_1);
        PayRate original = standard(RateType.HOURLY, "80", "2026-01-01");

        PayRate raised = standard(RateType.HOURLY, "90", "2026-04-01");
        entityManager.flush();
        entityManager.clear();

        List<PayRate> history = payService.rateHistory(worker.getId());
        assertThat(history).extracting(PayRate::getId).containsExactly(original.getId(), raised.getId());
        assertThat(history.get(0).getAmountPaise()).isEqualTo(8000);
        assertThat(history.get(0).getEffectiveTo()).isEqualTo(d("2026-03-31"));
        assertThat(history.get(1).getAmountPaise()).isEqualTo(9000);
        assertThat(history.get(1).getEffectiveTo()).isNull();
        assertThat(primaryPaise("2026-03-31", null, null)).isEqualTo(8000);
        assertThat(primaryPaise("2026-04-01", null, null)).isEqualTo(9000);
    }

    @Test
    void pastEffectiveRateBetweenVersionsIsAllowed() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_DAY, JAN_1);
        standard(RateType.PER_DAY, "300", "2026-01-01");
        PayRate april = standard(RateType.PER_DAY, "400", "2026-04-01");

        PayRate march = standard(RateType.PER_DAY, "350", "2026-03-01");

        assertThat(march.getEffectiveTo()).isEqualTo(d("2026-03-31"));
        assertThat(primaryPaise("2026-02-28", null, null)).isEqualTo(30000);
        assertThat(primaryPaise("2026-03-15", null, null)).isEqualTo(35000);
        assertThat(primaryPaise("2026-04-01", null, null)).isEqualTo(40000);
        assertThat(payService.rateHistory(worker.getId())).filteredOn(r -> r.getId().equals(april.getId()))
                .singleElement().satisfies(r -> assertThat(r.getEffectiveTo()).isNull());
    }

    @Test
    void sameStartDateIsRejectedInsteadOfOverwritten() {
        standard(RateType.HOURLY, "80", "2026-01-01");

        assertThatThrownBy(() -> standard(RateType.HOURLY, "85", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already starts on 2026-01-01");
        assertThat(payService.rateHistory(worker.getId())).singleElement()
                .satisfies(r -> assertThat(r.getAmountPaise()).isEqualTo(8000));
    }

    @Test
    void taskSpecificRateWinsOverTheStandardRate() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, JAN_1);
        standard(RateType.PER_TASK, "50", "2026-01-01");
        rate(RateType.PER_TASK, sweeping.getId(), null, null, "60", "2026-01-01");

        ResolvedRate forSweeping = payService.primaryRateFor(worker.getId(), d("2026-02-01"), sweeping.getId(), null).orElseThrow();
        assertThat(forSweeping.specific()).isTrue();
        assertThat(forSweeping.rate().getAmountPaise()).isEqualTo(6000);

        ResolvedRate forMopping = payService.primaryRateFor(worker.getId(), d("2026-02-01"), mopping.getId(), null).orElseThrow();
        assertThat(forMopping.specific()).isFalse();
        assertThat(forMopping.rate().getAmountPaise()).isEqualTo(5000);
    }

    @Test
    void differentTasksCanHaveTheirOwnRatesAtTheSameTime() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, JAN_1);
        rate(RateType.PER_TASK, sweeping.getId(), null, null, "60", "2026-01-01");
        rate(RateType.PER_TASK, mopping.getId(), null, null, "70", "2026-01-01");

        assertThat(primaryPaise("2026-02-01", sweeping.getId(), null)).isEqualTo(6000);
        assertThat(primaryPaise("2026-02-01", mopping.getId(), null)).isEqualTo(7000);

        Task cooking = fixtures.task("Cooking");
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-02-01"), cooking.getId(), null))
                .as("no standard rate and no rate for this task").isEmpty();
    }

    @Test
    void shiftSpecificRateWinsOverTheStandardShiftRate() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_SHIFT, JAN_1);
        standard(RateType.PER_SHIFT, "150", "2026-01-01");
        rate(RateType.PER_SHIFT, null, evening.getId(), null, "200", "2026-01-01");

        assertThat(primaryPaise("2026-02-01", null, evening.getId())).isEqualTo(20000);
        assertThat(primaryPaise("2026-02-01", null, morning.getId())).isEqualTo(15000);
        assertThat(primaryPaise("2026-02-01", sweeping.getId(), morning.getId()))
                .as("a task is ignored for per-shift rates").isEqualTo(15000);
    }

    @Test
    void perUnitRatesNeedAUnit() {
        Task milk = fixtures.task("Milk delivery");
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_UNIT, JAN_1);

        assertThatThrownBy(() -> rate(RateType.PER_UNIT, milk.getId(), null, null, "56.50", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("unit");
        PayRate perLitre = rate(RateType.PER_UNIT, milk.getId(), null, " litre ", "56.50", "2026-01-01");

        assertThat(perLitre.getUnitLabel()).isEqualTo("litre");
        assertThat(primaryPaise("2026-01-10", milk.getId(), null)).isEqualTo(5650);
    }

    @Test
    void rateShapeIsValidated() {
        assertThatThrownBy(() -> rate(RateType.MONTHLY_SALARY, sweeping.getId(), null, null, "5000", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be for a specific task");
        assertThatThrownBy(() -> rate(RateType.HOURLY, null, morning.getId(), null, "80", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be for a specific shift");
        assertThatThrownBy(() -> rate(RateType.EXTRA_TASK, null, null, null, "100", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Choose the task");
        assertThatThrownBy(() -> rate(RateType.PER_DAY, null, null, "litre", "300", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Only per-unit rates have a unit");
        assertThatThrownBy(() -> payService.addRate(NewPayRate.standard(worker.getId(), RateType.PER_DAY, "300", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Enter the date");
        assertThat(payService.rateHistory(worker.getId())).isEmpty();
    }

    @Test
    void amountsAreNeverRoundedOrAcceptedWhenInvalid() {
        assertThatThrownBy(() -> standard(RateType.HOURLY, "80.005", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("more than two decimal places");
        assertThatThrownBy(() -> standard(RateType.HOURLY, "0", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("more than ₹0.00");
        assertThatThrownBy(() -> standard(RateType.HOURLY, "-80", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> standard(RateType.HOURLY, "eighty", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not a valid amount");
        assertThat(payService.rateHistory(worker.getId())).isEmpty();
    }

    @Test
    void extraRulesThatDuplicateThePrimaryModelAreRejected() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, JAN_1);
        assertThatThrownBy(() -> rate(RateType.EXTRA_TASK, sweeping.getId(), null, null, "100", "2026-01-01"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("pay the same work twice");

        Worker hourly = fixtures.maid("Hourly worker");
        payService.setPrimaryModel(hourly.getId(), PaymentModel.HOURLY, JAN_1);
        assertThatThrownBy(() -> payService.addRate(NewPayRate.standard(hourly.getId(), RateType.EXTRA_HOUR, "100", JAN_1)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("pay the same work twice");

        Worker daily = fixtures.maid("Daily worker");
        payService.setPrimaryModel(daily.getId(), PaymentModel.PER_DAY, JAN_1);
        assertThatThrownBy(() -> payService.addRate(NewPayRate.standard(daily.getId(), RateType.EXTRA_DAY, "100", JAN_1)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("pay the same work twice");
    }

    @Test
    void extraRulesAlongsideAMonthlySalaryAreAllowed() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        standard(RateType.MONTHLY_SALARY, "6000", "2026-01-01");
        standard(RateType.EXTRA_DAY, "250", "2026-01-01");
        standard(RateType.EXTRA_HOUR, "60", "2026-01-01");
        rate(RateType.EXTRA_TASK, sweeping.getId(), null, null, "100", "2026-01-01");

        assertThat(payService.rateFor(worker.getId(), RateType.EXTRA_DAY, d("2026-02-01"), null, null)).isPresent();
        assertThat(payService.rateFor(worker.getId(), RateType.EXTRA_TASK, d("2026-02-01"), sweeping.getId(), null)).get()
                .satisfies(resolved -> assertThat(resolved.specific()).isTrue());
        assertThat(payService.rateFor(worker.getId(), RateType.EXTRA_TASK, d("2026-02-01"), mopping.getId(), null))
                .as("extra-task rates never fall back to a standard rate").isEmpty();
    }

    @Test
    void switchingToAModelThatDuplicatesAnExtraRuleIsRejectedUntilTheRuleEnds() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        PayRate extraTask = rate(RateType.EXTRA_TASK, sweeping.getId(), null, null, "100", "2026-01-01");

        assertThatThrownBy(() -> payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, d("2026-04-01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("End those rates first");

        payService.endRate(extraTask.getId(), d("2026-03-31"));
        PayArrangement perTask = payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, d("2026-04-01"));

        assertThat(perTask.getEffectiveTo()).isNull();
        assertThat(payService.arrangementHistory(worker.getId())).extracting(PayArrangement::getEffectiveTo)
                .containsExactly(d("2026-03-31"), null);
    }

    @Test
    void changingThePaymentModelSelectsTheMatchingRatesByDate() {
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_TASK, JAN_1);
        PayRate perTask = standard(RateType.PER_TASK, "50", "2026-01-01");
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, d("2026-04-01"));
        PayRate salary = standard(RateType.MONTHLY_SALARY, "6000", "2026-04-01");

        assertThat(payService.primaryRateFor(worker.getId(), d("2026-03-31"), sweeping.getId(), null)).get()
                .satisfies(r -> assertThat(r.rate().getId()).isEqualTo(perTask.getId()));
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-04-01"), sweeping.getId(), null)).get()
                .satisfies(r -> assertThat(r.rate().getId()).isEqualTo(salary.getId()));
        assertThat(payService.rateHistory(worker.getId())).hasSize(2);
    }

    @Test
    void noArrangementMeansNoPrimaryRate() {
        standard(RateType.MONTHLY_SALARY, "6000", "2026-01-01");

        assertThat(payService.primaryModelOn(worker.getId(), d("2026-02-01"))).isEmpty();
        assertThat(payService.primaryRateFor(worker.getId(), d("2026-02-01"), null, null)).isEmpty();
    }

    @Test
    void mistakenRateCanBeCorrectedWithAnAuditEvent() {
        PayRate salary = standard(RateType.MONTHLY_SALARY, "600", "2026-01-01");

        PayRate corrected = payService.correctRateAmount(salary.getId(), "6000", "Typed one zero too few");

        assertThat(corrected.getId()).isEqualTo(salary.getId());
        assertThat(corrected.getAmountPaise()).isEqualTo(600000);
        assertThat(auditService.historyForWorker(worker.getId()))
                .filteredOn(event -> event.getAction().equals("CORRECT_AMOUNT")).singleElement()
                .satisfies(event -> {
                    assertThat(event.getEntityId()).isEqualTo(salary.getId());
                    assertThat(event.getDetails()).contains("from=₹600.00", "to=₹6000.00", "Typed one zero too few");
                });
    }

    @Test
    void correctionRules() {
        PayRate salary = standard(RateType.MONTHLY_SALARY, "6000", "2026-01-01");

        assertThatThrownBy(() -> payService.correctRateAmount(salary.getId(), "6500", " "))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> payService.correctRateAmount(salary.getId(), "6000.00", "No change"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already ₹6000.00");
    }

    @Test
    void endRateRules() {
        PayRate extraDay = standard(RateType.EXTRA_DAY, "250", "2026-02-01");

        assertThatThrownBy(() -> payService.endRate(extraDay.getId(), d("2026-01-31")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot end before");
        payService.endRate(extraDay.getId(), d("2026-05-31"));
        assertThatThrownBy(() -> payService.endRate(extraDay.getId(), d("2026-06-30")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already ends on 2026-05-31");
        assertThat(payService.rateFor(worker.getId(), RateType.EXTRA_DAY, d("2026-06-01"), null, null)).isEmpty();
    }

    @Test
    void payChangesDoNotChangeTheSchedule() {
        WorkSchedule schedule = scheduleService.addScheduleVersion(worker.getId(), JAN_1,
                List.of(NewScheduleEntry.task(MONDAY, sweeping.getId())), null);
        payService.setPrimaryModel(worker.getId(), PaymentModel.MONTHLY_SALARY, JAN_1);
        standard(RateType.MONTHLY_SALARY, "6000", "2026-01-01");

        standard(RateType.MONTHLY_SALARY, "7000", "2026-04-01");
        payService.setPrimaryModel(worker.getId(), PaymentModel.PER_DAY, d("2026-07-01"));

        assertThat(scheduleService.history(worker.getId())).singleElement().satisfies(version -> {
            assertThat(version.getId()).isEqualTo(schedule.getId());
            assertThat(version.getEffectiveTo()).isNull();
            assertThat(version.getEntries()).hasSize(1);
        });
    }

    @Test
    void rateChangesAreAudited() {
        PayRate first = standard(RateType.HOURLY, "80", "2026-01-01");
        PayRate second = standard(RateType.HOURLY, "90", "2026-04-01");

        assertThat(auditService.historyForWorker(worker.getId()))
                .filteredOn(event -> event.getEntityType().equals(PayService.ENTITY_RATE))
                .extracting(AuditEvent::getEntityId, AuditEvent::getAction)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.getId(), "CREATE"),
                        org.assertj.core.groups.Tuple.tuple(first.getId(), "END"),
                        org.assertj.core.groups.Tuple.tuple(second.getId(), "CREATE"));
    }
}
