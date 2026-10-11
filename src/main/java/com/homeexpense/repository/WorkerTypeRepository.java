package com.homeexpense.repository;

import java.util.List;
import java.util.Optional;

import com.homeexpense.model.WorkerType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerTypeRepository extends JpaRepository<WorkerType, Long> {

    Optional<WorkerType> findByNameIgnoreCase(String name);

    List<WorkerType> findAllByOrderByNameAsc();
}
