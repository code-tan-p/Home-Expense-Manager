package com.homeexpense.model;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

/** A named household shift, such as Morning 07:00-09:00. Schedule entries may override the times. */
@Entity
@Table(name = "shift")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "default_start", nullable = false)
    private LocalTime defaultStart;

    @Column(name = "default_end", nullable = false)
    private LocalTime defaultEnd;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    private boolean active;

    protected Shift() {
        // Required by JPA
    }

    public Shift(String name, LocalTime defaultStart, LocalTime defaultEnd) {
        this.name = name;
        this.defaultStart = defaultStart;
        this.defaultEnd = defaultEnd;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public LocalTime getDefaultStart() {
        return defaultStart;
    }

    public LocalTime getDefaultEnd() {
        return defaultEnd;
    }

    public boolean isActive() {
        return active;
    }
}
