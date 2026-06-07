package com.yuno.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuno.settlement.TestFixtures;
import com.yuno.settlement.config.SettlementProperties.WindowRule;
import com.yuno.settlement.model.enums.WindowUnit;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SettlementWindowResolverTest {

  private final SettlementWindowResolver resolver =
      new SettlementWindowResolver(TestFixtures.properties());

  @Test
  void exactRuleBeatsWildcard() {
    WindowRule rule = resolver.resolveRule("credit_card", "MX");
    assertThat(rule.getValue()).isEqualTo(3);
    assertThat(rule.getUnit()).isEqualTo(WindowUnit.BUSINESS_DAYS);
  }

  @Test
  void wildcardUsedWhenNoCountryMatch() {
    WindowRule rule = resolver.resolveRule("credit_card", "VN");
    assertThat(rule.getValue()).isEqualTo(5);
  }

  @Test
  void defaultUsedWhenNoMethodMatch() {
    WindowRule rule = resolver.resolveRule("unknown_method", "VN");
    assertThat(rule.getValue()).isEqualTo(5);
    assertThat(rule.getUnit()).isEqualTo(WindowUnit.BUSINESS_DAYS);
  }

  @Test
  void hourlyWindowForPix() {
    Instant captured = Instant.parse("2026-06-01T10:00:00Z"); // Monday
    Instant deadline = resolver.expectedDeadline(captured, "pix", "BR", false);
    assertThat(deadline).isEqualTo(Instant.parse("2026-06-01T11:00:00Z"));
  }

  @Test
  void businessDaysSkipWeekends() {
    // Mon Jun 1 + 7 business days -> Wed Jun 10 (Jun 6-7 are weekend).
    Instant captured = Instant.parse("2026-06-01T10:00:00Z");
    Instant deadline = resolver.expectedDeadline(captured, "bank_transfer", "BR", false);
    assertThat(deadline).isEqualTo(Instant.parse("2026-06-10T10:00:00Z"));
  }

  @Test
  void crossBorderAddsSurchargeBusinessDays() {
    // Wed Jun 10 + 3 business days -> Mon Jun 15 (Jun 13-14 weekend).
    Instant captured = Instant.parse("2026-06-01T10:00:00Z");
    Instant deadline = resolver.expectedDeadline(captured, "bank_transfer", "BR", true);
    assertThat(deadline).isEqualTo(Instant.parse("2026-06-15T10:00:00Z"));
  }

  @Test
  void riskDeadlineIsDoubleTheWindow() {
    Instant captured = Instant.parse("2026-06-01T10:00:00Z");
    // window = 9 calendar days (Jun 1 -> Jun 10); risk = +18 days = Jun 19.
    Instant risk = resolver.riskDeadline(captured, "bank_transfer", "BR", false);
    assertThat(risk).isEqualTo(Instant.parse("2026-06-19T10:00:00Z"));
  }
}
