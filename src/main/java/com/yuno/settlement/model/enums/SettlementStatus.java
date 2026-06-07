package com.yuno.settlement.model.enums;

/**
 * Lifecycle settlement status for a captured payment.
 *
 * <p>Ordering of states by "health":
 *
 * <ul>
 *   <li>{@link #SETTLED} — funds confirmed in the merchant account (terminal, success).
 *   <li>{@link #PENDING_SETTLEMENT} — captured, still inside the expected window (healthy).
 *   <li>{@link #DELAYED} — past the expected window but below the AT_RISK threshold.
 *   <li>{@link #AT_RISK} — significantly overdue (age &gt; riskMultiplier x window).
 *   <li>{@link #FAILED} — settlement confirmed failed; funds returned to the customer (terminal).
 * </ul>
 */
public enum SettlementStatus {
  PENDING_SETTLEMENT,
  SETTLED,
  DELAYED,
  AT_RISK,
  FAILED
}
