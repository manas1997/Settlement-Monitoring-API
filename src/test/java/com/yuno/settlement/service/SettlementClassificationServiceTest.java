package com.yuno.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuno.settlement.TestFixtures;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.Payment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SettlementClassificationServiceTest {

  private final SettlementWindowResolver resolver =
      new SettlementWindowResolver(TestFixtures.properties());
  private final CurrencyConverter fx = new CurrencyConverter(TestFixtures.properties());
  private final SettlementClassificationService service =
      new SettlementClassificationService(resolver, fx);

  // bank_transfer BR (no cross-border): deadline Jun 10, risk Jun 19 for capture Jun 1.

  @Test
  void pendingWhenWithinWindow() {
    Payment p = TestFixtures.payment().build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-05T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_SETTLEMENT);
    assertThat(e.isNeedsAttention()).isFalse();
    assertThat(e.getTimeInTransitHours()).isEqualTo(96.0);
    assertThat(e.getAmountUsd()).isEqualByComparingTo("180.00");
  }

  @Test
  void delayedWhenPastWindowButUnderRisk() {
    Payment p = TestFixtures.payment().build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-15T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.DELAYED);
    assertThat(e.isNeedsAttention()).isTrue();
  }

  @Test
  void atRiskWhenBeyondTwiceWindow() {
    Payment p = TestFixtures.payment().build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-20T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.AT_RISK);
    assertThat(e.isNeedsAttention()).isTrue();
  }

  @Test
  void settledWithinWindow() {
    Payment p = TestFixtures.payment().settledAt(Instant.parse("2026-06-08T10:00:00Z")).build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-20T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(e.getWithinExpectedWindow()).isTrue();
  }

  @Test
  void settledLateStillSettledButFlaggedOutsideWindow() {
    Payment p = TestFixtures.payment().settledAt(Instant.parse("2026-06-12T10:00:00Z")).build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-20T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
    assertThat(e.getWithinExpectedWindow()).isFalse();
  }

  @Test
  void failedTakesPrecedence() {
    Payment p =
        TestFixtures.payment()
            .failed(true)
            .settledAt(null)
            .amount(new BigDecimal("500.00"))
            .build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-20T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
    assertThat(e.isNeedsAttention()).isTrue();
  }

  @Test
  void crossBorderExtendsTheWindow() {
    // With surcharge, deadline moves Jun 10 -> Jun 15, so Jun 12 is still PENDING.
    Payment p = TestFixtures.payment().crossBorder(true).build();
    EnrichedPayment e = service.classify(p, Instant.parse("2026-06-12T10:00:00Z"));
    assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.PENDING_SETTLEMENT);
    assertThat(e.getExpectedWindow()).contains("cross-border");
  }
}
