package com.yuno.settlement.config;

import com.yuno.settlement.model.enums.WindowUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed, externalized settlement configuration bound from the {@code settlement.*} block
 * of {@code application.yml}.
 *
 * <p>This is the single knob the finance/ops team turns to change behaviour: expected windows per
 * (method, country), the cross-border surcharge, the AT_RISK multiplier and FX rates — all without
 * recompiling.
 */
@Component
@ConfigurationProperties(prefix = "settlement")
@Data
public class SettlementProperties {

  /** Extra business days added to the expected window for cross-border payments. */
  private double crossBorderSurchargeDays = 3;

  /** AT_RISK once age &gt; riskMultiplier x expected window. */
  private double riskMultiplier = 2.0;

  /** Whether weekends are skipped when evaluating BUSINESS_DAYS windows. */
  private boolean skipWeekends = true;

  /** Zone used to evaluate business-day boundaries. */
  private String zoneId = "UTC";

  /** Fallback when no specific rule matches. */
  private WindowRule defaultWindow = new WindowRule(null, "*", 5, WindowUnit.BUSINESS_DAYS);

  /** Ordered list of window rules; most specific (method + country) wins. */
  private List<WindowRule> windows = new ArrayList<>();

  /** currency code -&gt; value of 1 unit in USD. */
  private Map<String, Double> fxRatesToUsd = new LinkedHashMap<>();

  /** Anomaly-detection tunables (stretch goal). */
  private Anomaly anomaly = new Anomaly();

  /** Seed the DB from a classpath dataset on startup. */
  private boolean seedOnStartup = true;

  /** Classpath/file location of the seed dataset. */
  private String seedResource = "classpath:test-data/payments.json";

  /** A single expected-window rule. */
  @Data
  @NoArgsConstructor
  public static class WindowRule {
    private String method;

    /** ISO-style country code, or "*" wildcard. */
    private String country;

    private double value;
    private WindowUnit unit;

    public WindowRule(String method, String country, double value, WindowUnit unit) {
      this.method = method;
      this.country = country;
      this.value = value;
      this.unit = unit;
    }
  }

  /** Anomaly-detection tunables. */
  @Data
  public static class Anomaly {
    private int recentWindowDays = 7;
    private int minRecentSamples = 5;
    private int minBaselineSamples = 10;
    private double degradationRatio = 1.5;
    private double delayedShareThreshold = 0.30;
  }
}
