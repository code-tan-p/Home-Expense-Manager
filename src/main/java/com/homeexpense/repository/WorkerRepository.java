package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.Worker;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    List<Worker> findByNameIgnoreCase(String name);

    List<Worker> findAllByOrderByNameAscIdAsc();
}
