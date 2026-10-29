package com.homeexpense.service;

import java.time.LocalTime;
import java.util.List;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.repository.ShiftRepository;
import com.homeexpense.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The household-wide lists of tasks and shifts. */
@Service
@Transactional
public class CatalogService {

    private final TaskRepository taskRepository;
    private final ShiftRepository shiftRepository;
    private final AuditService auditService;

    public CatalogService(TaskRepository taskRepository, ShiftRepository shiftRepository, AuditService auditService) {
        this.taskRepository = taskRepository;
        this.shiftRepository = shiftRepository;
        this.auditService = auditService;
    }

    public Task addTask(String name) {
        String trimmed = WorkerService.requireText(name, "Enter a name for the task.", 100);
        if (taskRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new BusinessRuleException("A task named '" + trimmed + "' already exists.");
        }
        Task task = taskRepository.save(new Task(trimmed));
        auditService.record(null, "TASK", task.getId(), "CREATE", "name=" + trimmed);
        return task;
    }

    public Shift addShift(String name, LocalTime defaultStart, LocalTime defaultEnd) {
        String trimmed = WorkerService.requireText(name, "Enter a name for the shift.", 50);
        if (shiftRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new BusinessRuleException("A shift named '" + trimmed + "' already exists.");
        }
        validateTimes(defaultStart, defaultEnd, "The shift");
        Shift shift = shiftRepository.save(new Shift(trimmed, defaultStart, defaultEnd));
        auditService.record(null, "SHIFT", shift.getId(), "CREATE",
                "name=" + trimmed + "; " + defaultStart + "-" + defaultEnd);
        return shift;
    }

    @Transactional(readOnly = true)
    public List<Task> activeTasks() {
        return taskRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Shift> activeShifts() {
        return shiftRepository.findByActiveTrueOrderByDefaultStartAsc();
    }

    @Transactional(readOnly = true)
    public Task getActiveTask(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessRuleException("Task #" + taskId + " was not found."));
        if (!task.isActive()) {
            throw new BusinessRuleException("The task '" + task.getName() + "' is no longer in use.");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public Shift getActiveShift(Long shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new BusinessRuleException("Shift #" + shiftId + " was not found."));
        if (!shift.isActive()) {
            throw new BusinessRuleException("The shift '" + shift.getName() + "' is no longer in use.");
        }
        return shift;
    }

    /** Times are to the minute on a 24-hour clock and may not cross midnight in version 1. */
    static void validateTimes(LocalTime start, LocalTime end, String subject) {
        if (start == null || end == null) {
            throw new BusinessRuleException(subject + " needs both a start time and an end time.");
        }
        if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
            throw new BusinessRuleException(subject + " times must be whole minutes, for example 07:30.");
        }
        if (!end.isAfter(start)) {
            throw new BusinessRuleException(subject + " must end after it starts (" + start + " to " + end
                    + "). Overnight shifts are not supported.");
        }
    }
}
