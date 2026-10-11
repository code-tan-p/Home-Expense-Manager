package com.homeexpense.model.finance;


import com.homeexpense.model.earnings.EarningRun;
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

/** One explained line of a settlement version. */
@Entity
@Table(name = "settlement_item")
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private SettlementVersion version;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false)
    private SettlementItemType itemType;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "earning_run_id")
    private EarningRun earningRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id")
    private FinancialAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advance_id")
    private Advance advance;

    @Column(name = "explanation", nullable = false)
    private String explanation;

    protected SettlementItem() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public SettlementItem(SettlementVersion version, int lineNo, SettlementItemType itemType, long amountPaise, EarningRun earningRun, FinancialAdjustment adjustment, Advance advance, String explanation) {
        this.version = version;
        this.lineNo = lineNo;
        this.itemType = itemType;
        this.amountPaise = amountPaise;
        this.earningRun = earningRun;
        this.adjustment = adjustment;
        this.advance = advance;
        this.explanation = explanation;
    }

    public Long getId() {
        return id;
    }

    public SettlementVersion getVersion() {
        return version;
    }

    public int getLineNo() {
        return lineNo;
    }

    public SettlementItemType getItemType() {
        return itemType;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public EarningRun getEarningRun() {
        return earningRun;
    }

    public FinancialAdjustment getAdjustment() {
        return adjustment;
    }

    public Advance getAdvance() {
        return advance;
    }

    public String getExplanation() {
        return explanation;
    }
}
