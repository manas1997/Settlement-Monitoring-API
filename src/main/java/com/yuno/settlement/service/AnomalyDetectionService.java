package com.yuno.settlement.service;

import com.yuno.settlement.config.SettlementProperties;
import com.yuno.settlement.dto.Anomaly;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Stretch goal: detect settlement degradation per (method, country) segment.
 *
 * <p>Approach — a lightweight change-point heuristic rather than a heavy ML model, because it must
 * be explainable to finance and run cheaply on every refresh:
 *
 * <ol>
 *   <li>Split each segment's payments into a <em>recent</em> window (last N days) and a
 *       <em>baseline</em> (everything older).
 *   <li>Compare the recent mean settlement time against the baseline mean (a SLOWDOWN), and the
 *       recent delayed/at-risk share against a threshold (a DELAY_SPIKE).
 *   <li>Emit an anomaly with a severity and a confidence score driven by both effect size and
 *       sample size, so a 2-transaction blip does not page anyone.
 * </ol>
 */
@Service
public class AnomalyDetectionService {

  private final SettlementProperties.Anomaly cfg;

  public AnomalyDetectionService(SettlementProperties props) {
    this.cfg = props.getAnomaly();
  }

  public List<Anomaly> detect(List<EnrichedPayment> payments, Instant now) {
    Instant recentCutoff = now.minus(Duration.ofDays(cfg.getRecentWindowDays()));

    Map<String, List<EnrichedPayment>> segments =
        payments.stream()
            .collect(Collectors.groupingBy(p -> p.getPaymentMethod() + "|" + p.getCountry()));

    List<Anomaly> anomalies = new ArrayList<>();
    for (List<EnrichedPayment> segment : segments.values()) {
      detectForSegment(segment, recentCutoff).ifPresent(anomalies::add);
    }
    anomalies.sort(Comparator.comparingDouble(Anomaly::getConfidence).reversed());
    return anomalies;
  }

  private java.util.Optional<Anomaly> detectForSegment(
      List<EnrichedPayment> segment, Instant recentCutoff) {
    List<EnrichedPayment> recent =
        segment.stream()
            .filter(p -> !p.getCapturedAt().isBefore(recentCutoff))
            .collect(Collectors.toList());
    List<EnrichedPayment> baseline =
        segment.stream()
            .filter(p -> p.getCapturedAt().isBefore(recentCutoff))
            .collect(Collectors.toList());

    if (recent.size() < cfg.getMinRecentSamples()
        || baseline.size() < cfg.getMinBaselineSamples()) {
      return java.util.Optional.empty();
    }

    double baselineAvg = avgSettledHours(baseline);
    double recentAvg = avgSettledHours(recent);
    double degradation = baselineAvg > 0 ? recentAvg / baselineAvg : 0.0;

    double recentDelayedShare =
        (double)
                recent.stream()
                    .filter(
                        p ->
                            p.getSettlementStatus() == SettlementStatus.DELAYED
                                || p.getSettlementStatus() == SettlementStatus.AT_RISK)
                    .count()
            / recent.size();

    boolean slowdown = baselineAvg > 0 && degradation >= cfg.getDegradationRatio();
    boolean delaySpike = recentDelayedShare >= cfg.getDelayedShareThreshold();
    if (!slowdown && !delaySpike) {
      return java.util.Optional.empty();
    }

    String type =
        slowdown && delaySpike ? "SLOWDOWN_AND_DELAY_SPIKE" : slowdown ? "SLOWDOWN" : "DELAY_SPIKE";

    double effect =
        Math.max(
            slowdown ? clamp((degradation - 1.0)) : 0.0,
            delaySpike ? clamp(recentDelayedShare) : 0.0);
    double sampleConfidence = clamp((double) recent.size() / (cfg.getMinRecentSamples() * 2.0));
    double confidence = round(clamp(0.5 * effect + 0.5 * sampleConfidence), 2);

    String severity = effect >= 0.66 ? "HIGH" : effect >= 0.33 ? "MEDIUM" : "LOW";

    EnrichedPayment any = segment.get(0);
    String description =
        String.format(
            "%s on %s/%s: recent avg settlement %.1fh vs baseline %.1fh (%.2fx); %.0f%% of recent"
                + " txns delayed/at-risk over %d recent samples.",
            type,
            any.getPaymentMethod(),
            any.getCountry(),
            recentAvg,
            baselineAvg,
            degradation,
            recentDelayedShare * 100,
            recent.size());

    return java.util.Optional.of(
        Anomaly.builder()
            .paymentMethod(any.getPaymentMethod())
            .country(any.getCountry())
            .processor(any.getProcessor())
            .type(type)
            .severity(severity)
            .confidence(confidence)
            .baselineAvgHours(round(baselineAvg, 2))
            .recentAvgHours(round(recentAvg, 2))
            .degradationRatio(round(degradation, 2))
            .recentDelayedShare(round(recentDelayedShare, 4))
            .recentSampleSize(recent.size())
            .baselineSampleSize(baseline.size())
            .description(description)
            .build());
  }

  private static double avgSettledHours(List<EnrichedPayment> list) {
    return list.stream()
        .filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED)
        .mapToDouble(EnrichedPayment::getTimeInTransitHours)
        .average()
        .orElse(0.0);
  }

  private static double clamp(double v) {
    return Math.max(0.0, Math.min(1.0, v));
  }

  private static double round(double v, int places) {
    return java.math.BigDecimal.valueOf(v)
        .setScale(places, java.math.RoundingMode.HALF_UP)
        .doubleValue();
  }
}
