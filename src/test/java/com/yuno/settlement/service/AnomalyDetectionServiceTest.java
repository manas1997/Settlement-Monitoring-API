package com.yuno.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuno.settlement.TestFixtures;
import com.yuno.settlement.dto.Anomaly;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnomalyDetectionServiceTest {

  private final AnomalyDetectionService service =
      new AnomalyDetectionService(TestFixtures.properties());

  private final Instant now = Instant.parse("2026-06-30T00:00:00Z");

  private EnrichedPayment settled(Instant captured, double hours) {
    return EnrichedPayment.builder()
        .paymentMethod("bank_transfer")
        .country("BR")
        .settlementStatus(SettlementStatus.SETTLED)
        .capturedAt(captured)
        .timeInTransitHours(hours)
        .build();
  }

  @Test
  void detectsSlowdownAgainstBaseline() {
    List<EnrichedPayment> payments = new ArrayList<>();
    // baseline: 12 settlements averaging ~48h, all older than the 7-day recent window
    for (int i = 1; i <= 12; i++) {
      payments.add(settled(Instant.parse("2026-06-0" + (i % 9 + 1) + "T00:00:00Z"), 48));
    }
    // recent: 6 settlements averaging 120h (2.5x slower)
    for (int i = 24; i <= 29; i++) {
      payments.add(settled(Instant.parse("2026-06-" + i + "T00:00:00Z"), 120));
    }

    List<Anomaly> anomalies = service.detect(payments, now);

    assertThat(anomalies).hasSize(1);
    Anomaly a = anomalies.get(0);
    assertThat(a.getType()).contains("SLOWDOWN");
    assertThat(a.getSeverity()).isEqualTo("HIGH");
    assertThat(a.getDegradationRatio()).isEqualTo(2.5);
    assertThat(a.getConfidence()).isGreaterThan(0.5);
  }

  @Test
  void noAnomalyWhenStable() {
    List<EnrichedPayment> payments = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      payments.add(settled(Instant.parse("2026-06-0" + (i % 9 + 1) + "T00:00:00Z"), 48));
    }
    for (int i = 24; i <= 29; i++) {
      payments.add(settled(Instant.parse("2026-06-" + i + "T00:00:00Z"), 50)); // ~stable
    }
    assertThat(service.detect(payments, now)).isEmpty();
  }
}
