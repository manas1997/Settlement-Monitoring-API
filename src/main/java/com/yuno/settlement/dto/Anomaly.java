package com.yuno.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A detected settlement anomaly for a (method, country) [and optionally processor] segment. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Anomaly {

  private String paymentMethod;
  private String country;
  private String processor;

  /** What tripped the detector, e.g. SLOWDOWN or DELAY_SPIKE. */
  private String type;

  /** LOW / MEDIUM / HIGH. */
  private String severity;

  /** 0..1 confidence the anomaly is real (driven by sample size + effect size). */
  private double confidence;

  private double baselineAvgHours;
  private double recentAvgHours;

  /** recentAvg / baselineAvg. */
  private double degradationRatio;

  /** Fraction of recent txns that are delayed or at risk. */
  private double recentDelayedShare;

  private long recentSampleSize;
  private long baselineSampleSize;

  /** Human-readable summary suitable for an alert. */
  private String description;
}
