package com.homeexpense.repository;

import java.util.List;
import java.util.Optional;

import com.homeexpense.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByNameIgnoreCase(String name);

    List<Task> findByActiveTrueOrderByNameAsc();
}
