package com.yuno.settlement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A captured payment in transit toward settlement.
 *
 * <p>We deliberately persist only raw facts (timestamps, money, rails) and never the derived
 * settlement status. Status is time-dependent — a payment that is PENDING today can be AT_RISK
 * tomorrow — so it is always computed on read by {@code SettlementClassificationService}, keeping a
 * single source of truth.
 */
@Entity
@Table(
    name = "payments",
    indexes = {
      @Index(name = "idx_payment_method", columnList = "paymentMethod"),
      @Index(name = "idx_payment_country", columnList = "country"),
      @Index(name = "idx_payment_processor", columnList = "processor")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

  /** Client-supplied id (idempotency key). Generated if absent. */
  @Id private String paymentId;

  /** Payment rail, e.g. credit_card, bank_transfer, pix, oxxo, e_wallet. */
  @Column(nullable = false)
  private String paymentMethod;

  /** ISO-style country code of the payer, e.g. BR, MX, CL, CO, VN, ID, TH. */
  @Column(nullable = false)
  private String country;

  /** Acquiring processor / connector that handled the payment. */
  private String processor;

  /** ISO-4217 currency of {@link #amount}. */
  @Column(nullable = false)
  private String currency;

  /** Transaction amount in {@link #currency} (NOT normalized to USD). */
  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  /** When the merchant captured the funds (settlement clock starts here). */
  @Column(nullable = false)
  private Instant capturedAt;

  /** When funds actually landed in the merchant account; null while in transit. */
  private Instant settledAt;

  /** True for cross-border payments (adds the configured surcharge to the window). */
  private boolean crossBorder;

  /** True if the processor confirmed the settlement failed and funds were returned. */
  private boolean failed;

  /** Bookkeeping: when this record was ingested. */
  private Instant ingestedAt;

  /** Optimistic-locking version to guard against lost updates under concurrent writes. */
  @Version private Long version;
}
