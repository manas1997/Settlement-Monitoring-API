package com.yuno.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuno.settlement.dto.AnalyticsResponse;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

  private final AnalyticsService analytics = new AnalyticsService();

  private EnrichedPayment ep(
      String method,
      String country,
      SettlementStatus status,
      String usd,
      Boolean within,
      double hours) {
    return EnrichedPayment.builder()
        .paymentMethod(method)
        .country(country)
        .settlementStatus(status)
        .amount(new BigDecimal(usd))
        .amountUsd(new BigDecimal(usd))
        .withinExpectedWindow(within)
        .timeInTransitHours(hours)
        .build();
  }

  private List<EnrichedPayment> sample() {
    return List.of(
        ep("credit_card", "BR", SettlementStatus.SETTLED, "100", true, 24),
        ep("credit_card", "BR", SettlementStatus.SETTLED, "100", false, 48),
        ep("e_wallet", "MX", SettlementStatus.PENDING_SETTLEMENT, "50", null, 5),
        ep("bank_transfer", "BR", SettlementStatus.DELAYED, "30", null, 200),
        ep("bank_transfer", "BR", SettlementStatus.AT_RISK, "20", null, 400),
        ep("bank_transfer", "BR", SettlementStatus.FAILED, "10", null, 0));
  }

  @Test
  void aggregatesValueByStatus() {
    AnalyticsResponse r = analytics.compute(sample());
    assertThat(r.getTotalTransactions()).isEqualTo(6);
    assertThat(r.getTotalValueUsd()).isEqualByComparingTo("310");
    assertThat(r.getByStatus().get(SettlementStatus.SETTLED).getCount()).isEqualTo(2);
    assertThat(r.getByStatus().get(SettlementStatus.SETTLED).getValueUsd())
        .isEqualByComparingTo("200");
  }

  @Test
  void computesInTransitAndNeedsAttention() {
    AnalyticsResponse r = analytics.compute(sample());
    assertThat(r.getTotalInTransitUsd()).isEqualByComparingTo("100"); // 50 + 30 + 20
    assertThat(r.getNeedsAttentionUsd()).isEqualByComparingTo("50"); // 30 + 20
  }

  @Test
  void settlementEfficiencyIsOnTimeOverSettled() {
    AnalyticsResponse r = analytics.compute(sample());
    assertThat(r.getSettlementEfficiencyRate()).isEqualTo(0.5); // 1 of 2 settled on time
    assertThat(r.getSettlementEfficiencyPct()).isEqualTo(50.0);
  }

  @Test
  void avgSettlementTimeByMethod() {
    AnalyticsResponse r = analytics.compute(sample());
    assertThat(r.getAvgSettlementHoursByMethod().get("credit_card")).isEqualTo(36.0); // (24+48)/2
  }

  @Test
  void worstComboIsBankTransferBr() {
    AnalyticsResponse r = analytics.compute(sample());
    AnalyticsResponse.ComboStat worst = r.getWorstCombinations().get(0);
    assertThat(worst.getPaymentMethod()).isEqualTo("bank_transfer");
    assertThat(worst.getCountry()).isEqualTo("BR");
    assertThat(worst.getDelayedAtRiskValueUsd()).isEqualByComparingTo("50");
  }
}
