package com.yuno.settlement.service;

import com.yuno.settlement.config.SettlementProperties;
import com.yuno.settlement.config.SettlementProperties.WindowRule;
import com.yuno.settlement.model.enums.WindowUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Resolves the expected settlement window for a (method, country) pair and turns it into concrete
 * deadlines on the timeline.
 *
 * <p>All the messy time arithmetic — business days vs. calendar hours, the cross-border surcharge,
 * the AT_RISK multiplier — lives here so the classification service stays a clean decision table.
 */
@Component
public class SettlementWindowResolver {

  private final SettlementProperties props;
  private final ZoneId zone;

  public SettlementWindowResolver(SettlementProperties props) {
    this.props = props;
    this.zone = ZoneId.of(props.getZoneId());
  }

  /**
   * Most-specific match wins: an exact (method, country) rule beats a (method, "*") wildcard, which
   * beats the configured default window.
   */
  public WindowRule resolveRule(String method, String country) {
    String m = safe(method);
    String c = safe(country);
    return props.getWindows().stream()
        .filter(r -> safe(r.getMethod()).equals(m))
        .filter(r -> isWildcard(r.getCountry()) || safe(r.getCountry()).equals(c))
        // exact country match (specificity = 1) ranked before wildcard (0)
        .max(Comparator.comparingInt(r -> isWildcard(r.getCountry()) ? 0 : 1))
        .orElse(props.getDefaultWindow());
  }

  /** Absolute deadline by which settlement is expected: capturedAt + window (+ surcharge). */
  public Instant expectedDeadline(
      Instant capturedAt, String method, String country, boolean crossBorder) {
    WindowRule rule = resolveRule(method, country);
    ZonedDateTime base = capturedAt.atZone(zone);
    ZonedDateTime deadline = addWindow(base, rule.getUnit(), rule.getValue());
    if (crossBorder && props.getCrossBorderSurchargeDays() > 0) {
      deadline = addBusinessDays(deadline, props.getCrossBorderSurchargeDays());
    }
    return deadline.toInstant();
  }

  /** Expected window expressed as a {@link Duration} from capture (incl. surcharge). */
  public Duration expectedWindowDuration(
      Instant capturedAt, String method, String country, boolean crossBorder) {
    return Duration.between(capturedAt, expectedDeadline(capturedAt, method, country, crossBorder));
  }

  /** AT_RISK threshold = capturedAt + riskMultiplier x expected window. */
  public Instant riskDeadline(
      Instant capturedAt, String method, String country, boolean crossBorder) {
    Duration window = expectedWindowDuration(capturedAt, method, country, crossBorder);
    long scaledSeconds = (long) Math.ceil(window.getSeconds() * props.getRiskMultiplier());
    return capturedAt.plusSeconds(scaledSeconds);
  }

  /** Human-readable description, e.g. {@code "7 BUSINESS_DAYS (+3 cross-border)"}. */
  public String describe(String method, String country, boolean crossBorder) {
    WindowRule rule = resolveRule(method, country);
    String base = trimNumber(rule.getValue()) + " " + rule.getUnit();
    if (crossBorder && props.getCrossBorderSurchargeDays() > 0) {
      return base + " (+" + trimNumber(props.getCrossBorderSurchargeDays()) + " cross-border)";
    }
    return base;
  }

  // --- time math ---------------------------------------------------------

  private ZonedDateTime addWindow(ZonedDateTime start, WindowUnit unit, double value) {
    switch (unit) {
      case HOURS:
        return start.plusSeconds(secondsOf(value, 3600));
      case CALENDAR_DAYS:
        return start.plusSeconds(secondsOf(value, 86400));
      case BUSINESS_DAYS:
      default:
        return addBusinessDays(start, value);
    }
  }

  /** Adds {@code days} business days; any fractional remainder is added as wall-clock time. */
  private ZonedDateTime addBusinessDays(ZonedDateTime start, double days) {
    long whole = (long) Math.floor(days);
    double frac = days - whole;
    ZonedDateTime cur = start;
    long added = 0;
    while (added < whole) {
      cur = cur.plusDays(1);
      if (props.isSkipWeekends() && isWeekend(cur)) {
        continue;
      }
      added++;
    }
    if (frac > 0) {
      cur = cur.plusSeconds((long) (frac * 86400));
    }
    return cur;
  }

  private static boolean isWeekend(ZonedDateTime dt) {
    switch (dt.getDayOfWeek()) {
      case SATURDAY:
      case SUNDAY:
        return true;
      default:
        return false;
    }
  }

  private static long secondsOf(double value, int unitSeconds) {
    return (long) Math.round(value * unitSeconds);
  }

  private static boolean isWildcard(String s) {
    return s == null || "*".equals(s.trim());
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim().toLowerCase();
  }

  private static String trimNumber(double d) {
    return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
  }
}
