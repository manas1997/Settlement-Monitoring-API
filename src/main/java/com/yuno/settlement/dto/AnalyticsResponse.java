package com.yuno.settlement.dto;

import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Aggregate settlement analytics across a population of payments. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsResponse {

  private Instant generatedAt;
  private long totalTransactions;

  /** Total transaction value (USD) across all payments. */
  private BigDecimal totalValueUsd;

  /** Money not yet settled and not failed: PENDING + DELAYED + AT_RISK. */
  private BigDecimal totalInTransitUsd;

  /** Money that needs investigation now: DELAYED + AT_RISK. */
  private BigDecimal needsAttentionUsd;

  /** Count + value per status. */
  private Map<SettlementStatus, StatusBucket> byStatus;

  /** Average realized settlement time (hours) per payment method, settled txns only. */
  private Map<String, Double> avgSettlementHoursByMethod;

  /** Average realized settlement time (hours) per country, settled txns only. */
  private Map<String, Double> avgSettlementHoursByCountry;

  /** % of terminal-or-in-window payments settling within their expected window (0..1). */
  private double settlementEfficiencyRate;

  /** Same value as a percentage (0..100), rounded for display. */
  private double settlementEfficiencyPct;

  /** (method, country) combos ranked worst-first by at-risk + delayed exposure. */
  private List<ComboStat> worstCombinations;

  /** Count + USD value for one settlement status. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StatusBucket {
    private long count;
    private BigDecimal valueUsd;
    private double pctOfValue;
  }

  /** Per-(method, country) breakdown used to localize the problem. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ComboStat {
    private String paymentMethod;
    private String country;
    private long total;
    private long settled;
    private long pending;
    private long delayed;
    private long atRisk;
    private long failed;

    /** USD value currently delayed or at risk for this combo. */
    private BigDecimal delayedAtRiskValueUsd;

    /** Settlement efficiency for this combo (0..1). */
    private double efficiencyRate;

    /** Avg realized settlement time (hours) for settled txns in this combo. */
    private Double avgSettlementHours;
  }
}
