package com.stokr.arbitrage;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "option_arb_executed_trades")
public class ExecutedTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    @Column(name = "underlying", nullable = false)
    private String underlying;

    @Column(name = "strike", nullable = false)
    private Integer strike;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "ce_symbol")
    private String ceSymbol;

    @Column(name = "pe_symbol")
    private String peSymbol;

    @Column(name = "fut_symbol")
    private String futSymbol;

    @Column(name = "ce_order_id")
    private String ceOrderId;

    @Column(name = "pe_order_id")
    private String peOrderId;

    @Column(name = "fut_order_id")
    private String futOrderId;

    @Column(name = "ce_entry_price")
    private Double ceEntryPrice;

    @Column(name = "pe_entry_price")
    private Double peEntryPrice;

    @Column(name = "fut_entry_price")
    private Double futEntryPrice;

    @Column(name = "lot_size")
    private Integer lotSize;

    @Column(name = "status")
    private String status = "OPEN";

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_ce_order_id")
    private String closeCeOrderId;

    @Column(name = "close_pe_order_id")
    private String closePeOrderId;

    @Column(name = "close_fut_order_id")
    private String closeFutOrderId;

    @Column(name = "close_ce_price")
    private Double closeCePrice;

    @Column(name = "close_pe_price")
    private Double closePePrice;

    @Column(name = "close_fut_price")
    private Double closeFutPrice;

    @Column(name = "pnl_points")
    private Double pnlPoints;

    @Column(name = "pnl_amount")
    private Double pnlAmount;

    @Column(name = "rollover_from_id")
    private Long rolloverFromId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @PrePersist
    public void prePersist() {
        if (this.executedAt == null) this.executedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOpportunityId() { return opportunityId; }
    public void setOpportunityId(Long opportunityId) { this.opportunityId = opportunityId; }
    public String getUnderlying() { return underlying; }
    public void setUnderlying(String underlying) { this.underlying = underlying; }
    public Integer getStrike() { return strike; }
    public void setStrike(Integer strike) { this.strike = strike; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getCeSymbol() { return ceSymbol; }
    public void setCeSymbol(String ceSymbol) { this.ceSymbol = ceSymbol; }
    public String getPeSymbol() { return peSymbol; }
    public void setPeSymbol(String peSymbol) { this.peSymbol = peSymbol; }
    public String getFutSymbol() { return futSymbol; }
    public void setFutSymbol(String futSymbol) { this.futSymbol = futSymbol; }
    public String getCeOrderId() { return ceOrderId; }
    public void setCeOrderId(String ceOrderId) { this.ceOrderId = ceOrderId; }
    public String getPeOrderId() { return peOrderId; }
    public void setPeOrderId(String peOrderId) { this.peOrderId = peOrderId; }
    public String getFutOrderId() { return futOrderId; }
    public void setFutOrderId(String futOrderId) { this.futOrderId = futOrderId; }
    public Double getCeEntryPrice() { return ceEntryPrice; }
    public void setCeEntryPrice(Double ceEntryPrice) { this.ceEntryPrice = ceEntryPrice; }
    public Double getPeEntryPrice() { return peEntryPrice; }
    public void setPeEntryPrice(Double peEntryPrice) { this.peEntryPrice = peEntryPrice; }
    public Double getFutEntryPrice() { return futEntryPrice; }
    public void setFutEntryPrice(Double futEntryPrice) { this.futEntryPrice = futEntryPrice; }
    public Integer getLotSize() { return lotSize; }
    public void setLotSize(Integer lotSize) { this.lotSize = lotSize; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getCloseCeOrderId() { return closeCeOrderId; }
    public void setCloseCeOrderId(String closeCeOrderId) { this.closeCeOrderId = closeCeOrderId; }
    public String getClosePeOrderId() { return closePeOrderId; }
    public void setClosePeOrderId(String closePeOrderId) { this.closePeOrderId = closePeOrderId; }
    public String getCloseFutOrderId() { return closeFutOrderId; }
    public void setCloseFutOrderId(String closeFutOrderId) { this.closeFutOrderId = closeFutOrderId; }
    public Double getCloseCePrice() { return closeCePrice; }
    public void setCloseCePrice(Double closeCePrice) { this.closeCePrice = closeCePrice; }
    public Double getClosePePrice() { return closePePrice; }
    public void setClosePePrice(Double closePePrice) { this.closePePrice = closePePrice; }
    public Double getCloseFutPrice() { return closeFutPrice; }
    public void setCloseFutPrice(Double closeFutPrice) { this.closeFutPrice = closeFutPrice; }
    public Double getPnlPoints() { return pnlPoints; }
    public void setPnlPoints(Double pnlPoints) { this.pnlPoints = pnlPoints; }
    public Double getPnlAmount() { return pnlAmount; }
    public void setPnlAmount(Double pnlAmount) { this.pnlAmount = pnlAmount; }
    public Long getRolloverFromId() { return rolloverFromId; }
    public void setRolloverFromId(Long rolloverFromId) { this.rolloverFromId = rolloverFromId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}
