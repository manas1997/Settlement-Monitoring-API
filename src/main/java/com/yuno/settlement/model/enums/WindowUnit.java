package com.yuno.settlement.model.enums;

/**
 * Unit in which an expected settlement window is expressed.
 *
 * <ul>
 *   <li>{@link #HOURS} — wall-clock hours (used for instant rails like PIX, ~1h).
 *   <li>{@link #CALENDAR_DAYS} — wall-clock days including weekends.
 *   <li>{@link #BUSINESS_DAYS} — days excluding Saturdays/Sundays (most banking rails).
 * </ul>
 */
public enum WindowUnit {
  HOURS,
  CALENDAR_DAYS,
  BUSINESS_DAYS
}
