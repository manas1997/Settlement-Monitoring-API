package com.yuno.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound representation of a single payment to be classified or ingested.
 *
 * <p>Timestamps are ISO-8601 instants, e.g. {@code 2026-06-01T14:30:00Z}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

  /** Optional client id; a UUID is generated if omitted. */
  private String paymentId;

  @NotBlank(message = "paymentMethod is required")
  private String paymentMethod;

  @NotBlank(message = "country is required")
  private String country;

  private String processor;

  @NotBlank(message = "currency is required")
  private String currency;

  @NotNull(message = "amount is required")
  @Positive(message = "amount must be > 0")
  private BigDecimal amount;

  @NotNull(message = "capturedAt is required")
  private Instant capturedAt;

  /** Null while the payment is still in transit. */
  private Instant settledAt;

  private boolean crossBorder;

  private boolean failed;
}
