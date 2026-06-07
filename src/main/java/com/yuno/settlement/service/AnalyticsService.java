package com.yuno.settlement.service;

import com.yuno.settlement.dto.AnalyticsResponse;
import com.yuno.settlement.dto.AnalyticsResponse.ComboStat;
import com.yuno.settlement.dto.AnalyticsResponse.StatusBucket;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Computes aggregate settlement analytics from a population of already-classified payments.
 *
 * <p>Pure and stateless: it takes the enriched payments and returns numbers. Keeping it free of
 * persistence makes it trivially unit-testable and reusable over either the stored population or an
 * ad-hoc batch.
 */
@Service
public class AnalyticsService {

  /** Max number of (method, country) combos returned in {@code worstCombinations}. */
  private static final int WORST_COMBO_LIMIT = 15;

  public AnalyticsResponse compute(List<EnrichedPayment> payments) {
    BigDecimal totalValue = sum(payments, p -> true);

    Map<SettlementStatus, StatusBucket> byStatus = byStatus(payments, totalValue);

    BigDecimal inTransit =
        sum(payments, p -> isInTransit(p.getSettlementStatus()));
    BigDecimal needsAttention =
        sum(
            payments,
            p ->
                p.getSettlementStatus() == SettlementStatus.DELAYED
                    || p.getSettlementStatus() == SettlementStatus.AT_RISK);

    return AnalyticsResponse.builder()
        .generatedAt(Instant.now())
        .totalTransactions(payments.size())
        .totalValueUsd(totalValue)
        .totalInTransitUsd(inTransit)
        .needsAttentionUsd(needsAttention)
        .byStatus(byStatus)
        .avgSettlementHoursByMethod(avgSettlementHoursBy(payments, EnrichedPayment::getPaymentMethod))
        .avgSettlementHoursByCountry(avgSettlementHoursBy(payments, EnrichedPayment::getCountry))
        .settlementEfficiencyRate(round(efficiency(payments), 4))
        .settlementEfficiencyPct(round(efficiency(payments) * 100.0, 2))
        .worstCombinations(worstCombinations(payments))
        .build();
  }

  // --- status buckets ----------------------------------------------------

  private Map<SettlementStatus, StatusBucket> byStatus(
      List<EnrichedPayment> payments, BigDecimal totalValue) {
    Map<SettlementStatus, StatusBucket> result = new EnumMap<>(SettlementStatus.class);
    for (SettlementStatus s : SettlementStatus.values()) {
      List<EnrichedPayment> bucket =
          payments.stream().filter(p -> p.getSettlementStatus() == s).collect(Collectors.toList());
      BigDecimal value = bucket.stream().map(EnrichedPayment::getAmountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
      double pct =
          totalValue.signum() == 0
              ? 0.0
              : value.divide(totalValue, 6, RoundingMode.HALF_UP).doubleValue() * 100.0;
      result.put(
          s,
          StatusBucket.builder()
              .count(bucket.size())
              .valueUsd(value.setScale(2, RoundingMode.HALF_UP))
              .pctOfValue(round(pct, 2))
              .build());
    }
    return result;
  }

  // --- averages ----------------------------------------------------------

  private Map<String, Double> avgSettlementHoursBy(
      List<EnrichedPayment> payments, java.util.function.Function<EnrichedPayment, String> key) {
    Map<String, Double> out = new LinkedHashMap<>();
    payments.stream()
        .filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED)
        .collect(Collectors.groupingBy(key, Collectors.averagingDouble(EnrichedPayment::getTimeInTransitHours)))
        .entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .forEach(e -> out.put(e.getKey(), round(e.getValue(), 2)));
    return out;
  }

  // --- efficiency --------------------------------------------------------

  /** On-time settlements / total settlements. Returns 0 when nothing has settled yet. */
  private double efficiency(List<EnrichedPayment> payments) {
    long settled =
        payments.stream().filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED).count();
    if (settled == 0) {
      return 0.0;
    }
    long onTime =
        payments.stream()
            .filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED)
            .filter(p -> Boolean.TRUE.equals(p.getWithinExpectedWindow()))
            .count();
    return (double) onTime / settled;
  }

  // --- worst combos ------------------------------------------------------

  private List<ComboStat> worstCombinations(List<EnrichedPayment> payments) {
    Map<String, List<EnrichedPayment>> grouped =
        payments.stream()
            .collect(Collectors.groupingBy(p -> p.getPaymentMethod() + "|" + p.getCountry()));

    return grouped.values().stream()
        .map(this::comboStat)
        .sorted(
            Comparator.comparing(ComboStat::getDelayedAtRiskValueUsd)
                .reversed()
                .thenComparing(Comparator.comparingLong(ComboStat::getAtRisk).reversed()))
        .limit(WORST_COMBO_LIMIT)
        .collect(Collectors.toList());
  }

  private ComboStat comboStat(List<EnrichedPayment> group) {
    EnrichedPayment any = group.get(0);
    long settled = countStatus(group, SettlementStatus.SETTLED);
    long onTime =
        group.stream()
            .filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED)
            .filter(p -> Boolean.TRUE.equals(p.getWithinExpectedWindow()))
            .count();
    Double avgHours =
        group.stream()
            .filter(p -> p.getSettlementStatus() == SettlementStatus.SETTLED)
            .mapToDouble(EnrichedPayment::getTimeInTransitHours)
            .average()
            .stream()
            .boxed()
            .findFirst()
            .map(v -> round(v, 2))
            .orElse(null);

    BigDecimal delayedAtRisk =
        group.stream()
            .filter(
                p ->
                    p.getSettlementStatus() == SettlementStatus.DELAYED
                        || p.getSettlementStatus() == SettlementStatus.AT_RISK)
            .map(EnrichedPayment::getAmountUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

    return ComboStat.builder()
        .paymentMethod(any.getPaymentMethod())
        .country(any.getCountry())
        .total(group.size())
        .settled(settled)
        .pending(countStatus(group, SettlementStatus.PENDING_SETTLEMENT))
        .delayed(countStatus(group, SettlementStatus.DELAYED))
        .atRisk(countStatus(group, SettlementStatus.AT_RISK))
        .failed(countStatus(group, SettlementStatus.FAILED))
        .delayedAtRiskValueUsd(delayedAtRisk)
        .efficiencyRate(settled == 0 ? 0.0 : round((double) onTime / settled, 4))
        .avgSettlementHours(avgHours)
        .build();
  }

  // --- helpers -----------------------------------------------------------

  private static long countStatus(List<EnrichedPayment> g, SettlementStatus s) {
    return g.stream().filter(p -> p.getSettlementStatus() == s).count();
  }

  private static boolean isInTransit(SettlementStatus s) {
    return s == SettlementStatus.PENDING_SETTLEMENT
        || s == SettlementStatus.DELAYED
        || s == SettlementStatus.AT_RISK;
  }

  private static BigDecimal sum(
      List<EnrichedPayment> payments, java.util.function.Predicate<EnrichedPayment> filter) {
    return payments.stream()
        .filter(filter)
        .map(EnrichedPayment::getAmountUsd)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static double round(double v, int places) {
    return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
  }
}
