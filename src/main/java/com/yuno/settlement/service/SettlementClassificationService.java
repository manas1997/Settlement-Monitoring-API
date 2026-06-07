package com.yuno.settlement.service;

import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.dto.PaymentRequest;
import com.yuno.settlement.model.Payment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The core engine: turns a raw payment into an {@link EnrichedPayment} with a settlement status.
 *
 * <p>Decision table (evaluated top-down):
 *
 * <pre>
 *   failed flag set            -> FAILED
 *   settledAt present          -> SETTLED       (+ withinExpectedWindow flag)
 *   age <= expected window     -> PENDING_SETTLEMENT
 *   age <= riskMultiplier x w  -> DELAYED
 *   otherwise                  -> AT_RISK
 * </pre>
 *
 * "age" is measured from {@code capturedAt} (when the settlement clock starts) to now.
 */
@Service
public class SettlementClassificationService {

  private final SettlementWindowResolver windows;
  private final CurrencyConverter fx;

  public SettlementClassificationService(SettlementWindowResolver windows, CurrencyConverter fx) {
    this.windows = windows;
    this.fx = fx;
  }

  public List<EnrichedPayment> classifyRequests(List<PaymentRequest> requests, Instant now) {
    return requests.stream().map(r -> classify(toPayment(r), now)).collect(Collectors.toList());
  }

  public List<EnrichedPayment> classifyAll(List<Payment> payments, Instant now) {
    return payments.stream().map(p -> classify(p, now)).collect(Collectors.toList());
  }

  /** Classify a single payment against the clock {@code now}. */
  public EnrichedPayment classify(Payment p, Instant now) {
    Instant deadline =
        windows.expectedDeadline(
            p.getCapturedAt(), p.getPaymentMethod(), p.getCountry(), p.isCrossBorder());
    Instant riskDeadline =
        windows.riskDeadline(
            p.getCapturedAt(), p.getPaymentMethod(), p.getCountry(), p.isCrossBorder());

    Instant transitEnd = p.getSettledAt() != null ? p.getSettledAt() : now;
    Duration inTransit = Duration.between(p.getCapturedAt(), transitEnd);
    double hours = inTransit.toMinutes() / 60.0;

    SettlementStatus status;
    Boolean withinWindow = null;
    String reason;

    if (p.isFailed()) {
      status = SettlementStatus.FAILED;
      reason = "Processor reported settlement failure; funds returned to customer.";
    } else if (p.getSettledAt() != null) {
      status = SettlementStatus.SETTLED;
      withinWindow = !p.getSettledAt().isAfter(deadline);
      reason =
          withinWindow
              ? "Settled within the expected window."
              : "Settled, but later than the expected window.";
    } else if (!now.isAfter(deadline)) {
      status = SettlementStatus.PENDING_SETTLEMENT;
      reason = "In transit and still within the expected settlement window.";
    } else if (!now.isAfter(riskDeadline)) {
      status = SettlementStatus.DELAYED;
      reason = "Past the expected window but under the at-risk threshold.";
    } else {
      status = SettlementStatus.AT_RISK;
      reason = "Significantly overdue (beyond 2x the expected window) — investigate.";
    }

    boolean needsAttention =
        status == SettlementStatus.DELAYED
            || status == SettlementStatus.AT_RISK
            || status == SettlementStatus.FAILED;

    return EnrichedPayment.builder()
        .paymentId(p.getPaymentId())
        .paymentMethod(p.getPaymentMethod())
        .country(p.getCountry())
        .processor(p.getProcessor())
        .currency(p.getCurrency())
        .amount(p.getAmount())
        .amountUsd(fx.toUsd(p.getAmount(), p.getCurrency()))
        .capturedAt(p.getCapturedAt())
        .settledAt(p.getSettledAt())
        .crossBorder(p.isCrossBorder())
        .settlementStatus(status)
        .expectedWindow(windows.describe(p.getPaymentMethod(), p.getCountry(), p.isCrossBorder()))
        .expectedSettlementDeadline(deadline)
        .timeInTransitHours(round(hours, 2))
        .timeInTransitDays(round(hours / 24.0, 2))
        .withinExpectedWindow(withinWindow)
        .needsAttention(needsAttention)
        .reason(reason)
        .build();
  }

  /** Maps an inbound request to a {@link Payment} (assigning a UUID if no id was supplied). */
  public Payment toPayment(PaymentRequest r) {
    return Payment.builder()
        .paymentId(
            r.getPaymentId() != null && !r.getPaymentId().isBlank()
                ? r.getPaymentId()
                : UUID.randomUUID().toString())
        .paymentMethod(r.getPaymentMethod())
        .country(r.getCountry())
        .processor(r.getProcessor())
        .currency(r.getCurrency())
        .amount(r.getAmount())
        .capturedAt(r.getCapturedAt())
        .settledAt(r.getSettledAt())
        .crossBorder(r.isCrossBorder())
        .failed(r.isFailed())
        .ingestedAt(Instant.now())
        .build();
  }

  private static double round(double v, int places) {
    return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
  }
}
