package com.homeexpense.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

/** A kind of household worker, such as Maid or Cook. Built-in types are seeded by migration. */
@Entity
@Table(name = "worker_type")
public class WorkerType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    protected WorkerType() {
        // Required by JPA
    }

    public WorkerType(String name) {
        this.name = name;
        this.builtIn = false;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }
}
