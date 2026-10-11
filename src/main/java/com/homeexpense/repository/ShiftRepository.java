package com.homeexpense.repository;

import java.util.List;
import java.util.Optional;

import com.homeexpense.model.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByNameIgnoreCase(String name);

    List<Shift> findByActiveTrueOrderByDefaultStartAsc();
}
