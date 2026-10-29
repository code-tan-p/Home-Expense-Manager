package com.homeexpense.model.earnings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A warning or blocker shown with an earnings calculation. */
@Entity
@Table(name = "earning_run_note")
public class EarningRunNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EarningRun run;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoteKind kind;

    @Column(nullable = false)
    private String message;

    protected EarningRunNote() {
        // Required by JPA
    }

    public EarningRunNote(EarningRun run, int ordinal, NoteKind kind, String message) {
        this.run = run;
        this.ordinal = ordinal;
        this.kind = kind;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public NoteKind getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }
}
