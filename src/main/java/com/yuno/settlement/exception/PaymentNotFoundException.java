package com.yuno.settlement.exception;

/** Thrown when a payment id cannot be found in the store. Maps to HTTP 404. */
public class PaymentNotFoundException extends RuntimeException {
  public PaymentNotFoundException(String paymentId) {
    super("Payment not found: " + paymentId);
  }
}
