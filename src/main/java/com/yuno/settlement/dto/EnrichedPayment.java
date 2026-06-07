package com.yuno.settlement.dto;

import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A payment enriched with its computed settlement status and timing context. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedPayment {

  // --- echoed input ---
  private String paymentId;
  private String paymentMethod;
  private String country;
  private String processor;
  private String currency;
  private BigDecimal amount;
  private BigDecimal amountUsd;
  private Instant capturedAt;
  private Instant settledAt;
  private boolean crossBorder;

  // --- derived enrichment ---
  private SettlementStatus settlementStatus;

  /** Human-readable expected window, e.g. "7 BUSINESS_DAYS (+3 cross-border)". */
  private String expectedWindow;

  /** Absolute deadline by which settlement is expected (captured + window). */
  private Instant expectedSettlementDeadline;

  /** Time the payment has been (or was) in transit, in hours. */
  private double timeInTransitHours;

  /** Same value expressed in (fractional) days, for readability. */
  private double timeInTransitDays;

  /** For settled payments: did it settle on/before the expected deadline? */
  private Boolean withinExpectedWindow;

  /** True for DELAYED / AT_RISK / FAILED — i.e. finance should look at it. */
  private boolean needsAttention;

  /** Short human explanation of the classification decision. */
  private String reason;
}
