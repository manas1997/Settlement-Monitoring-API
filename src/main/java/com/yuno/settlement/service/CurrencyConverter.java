package com.yuno.settlement.service;

import com.yuno.settlement.config.SettlementProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Converts transaction amounts to USD using the configurable {@code fx-rates-to-usd} table. */
@Component
public class CurrencyConverter {

  private final SettlementProperties props;

  public CurrencyConverter(SettlementProperties props) {
    this.props = props;
  }

  /**
   * Converts {@code amount} in {@code currency} to USD. Unknown currencies fall back to a 1:1 rate
   * so analytics never silently drop value (a warning-worthy event in production).
   */
  public BigDecimal toUsd(BigDecimal amount, String currency) {
    if (amount == null) {
      return BigDecimal.ZERO;
    }
    Double rate = currency == null ? null : props.getFxRatesToUsd().get(currency.toUpperCase());
    if (rate == null) {
      rate = 1.0;
    }
    return amount.multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.HALF_UP);
  }
}
