package com.yuno.settlement.repository;

import com.yuno.settlement.model.Payment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Payment}.
 *
 * <p>Status-based filtering is intentionally NOT a DB query: settlement status is derived at read
 * time, so callers filter the enriched results in the service layer instead.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {

  List<Payment> findByPaymentMethodIgnoreCase(String paymentMethod);

  List<Payment> findByCountryIgnoreCase(String country);

  List<Payment> findByProcessorIgnoreCase(String processor);
}
