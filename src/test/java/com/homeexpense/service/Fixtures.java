package com.homeexpense.service;

import java.time.LocalDate;
import java.time.LocalTime;

import com.homeexpense.dto.NewWorker;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.Worker;

/** Small helpers for creating test data through the real services. */
final class Fixtures {

    static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    private final WorkerService workerService;
    private final CatalogService catalogService;

    Fixtures(WorkerService workerService, CatalogService catalogService) {
        this.workerService = workerService;
        this.catalogService = catalogService;
    }

    Worker worker(String name, String type, LocalDate start) {
        Long typeId = workerService.listWorkerTypes().stream()
                .filter(t -> t.getName().equals(type))
                .findFirst().orElseThrow().getId();
        return workerService.createWorker(new NewWorker(name, typeId, null, null, start)).worker();
    }

    Worker maid(String name) {
        return worker(name, "Maid", JAN_1);
    }

    Task task(String name) {
        return catalogService.addTask(name);
    }

    Shift shift(String name, String start, String end) {
        return catalogService.addShift(name, LocalTime.parse(start), LocalTime.parse(end));
    }

    static LocalDate d(String text) {
        return LocalDate.parse(text);
    }
}
