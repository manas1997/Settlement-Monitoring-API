package com.yuno.settlement;

import com.yuno.settlement.config.SettlementProperties;
import com.yuno.settlement.config.SettlementProperties.WindowRule;
import com.yuno.settlement.model.Payment;
import com.yuno.settlement.model.enums.WindowUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared builders for unit tests. */
public final class TestFixtures {

  private TestFixtures() {}

  /** Minimal, deterministic settlement config used across unit tests. */
  public static SettlementProperties properties() {
    SettlementProperties p = new SettlementProperties();
    p.setCrossBorderSurchargeDays(3);
    p.setRiskMultiplier(2.0);
    p.setSkipWeekends(true);
    p.setZoneId("UTC");
    p.setDefaultWindow(new WindowRule(null, "*", 5, WindowUnit.BUSINESS_DAYS));
    p.setWindows(
        List.of(
            new WindowRule("pix", "BR", 1, WindowUnit.HOURS),
            new WindowRule("e_wallet", "*", 1, WindowUnit.CALENDAR_DAYS),
            new WindowRule("credit_card", "MX", 3, WindowUnit.BUSINESS_DAYS),
            new WindowRule("credit_card", "*", 5, WindowUnit.BUSINESS_DAYS),
            new WindowRule("bank_transfer", "BR", 7, WindowUnit.BUSINESS_DAYS)));
    p.setFxRatesToUsd(Map.of("USD", 1.0, "BRL", 0.18, "MXN", 0.058));
    return p;
  }

  public static Payment.PaymentBuilder payment() {
    return Payment.builder()
        .paymentId("pay_test")
        .paymentMethod("bank_transfer")
        .country("BR")
        .processor("dlocal")
        .currency("BRL")
        .amount(new BigDecimal("1000.00"))
        .capturedAt(Instant.parse("2026-06-01T10:00:00Z"))
        .crossBorder(false)
        .failed(false);
  }
}
