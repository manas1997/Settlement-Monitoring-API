package com.yuno.settlement.service;

import com.yuno.settlement.dto.AnalyticsResponse;
import com.yuno.settlement.dto.Anomaly;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.dto.PaymentRequest;
import com.yuno.settlement.exception.PaymentNotFoundException;
import com.yuno.settlement.model.Payment;
import com.yuno.settlement.model.enums.SettlementStatus;
import com.yuno.settlement.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service orchestrating the persisted-payment workflows: ingest, query, analytics and
 * anomaly detection over the stored population.
 *
 * <p>A {@link Clock} is injected (rather than calling {@code Instant.now()} directly) so that "now"
 * is controllable in tests — essential when the whole domain is time-sensitive.
 */
@Service
public class PaymentService {

  private final PaymentRepository repository;
  private final SettlementClassificationService classifier;
  private final AnalyticsService analytics;
  private final AnomalyDetectionService anomalyDetection;
  private final Clock clock;

  public PaymentService(
      PaymentRepository repository,
      SettlementClassificationService classifier,
      AnalyticsService analytics,
      AnomalyDetectionService anomalyDetection,
      Clock clock) {
    this.repository = repository;
    this.classifier = classifier;
    this.analytics = analytics;
    this.anomalyDetection = anomalyDetection;
    this.clock = clock;
  }

  /**
   * Persist a batch of payments and return their enriched view. {@code paymentId} is the natural
   * idempotency key: re-ingesting the same id upserts rather than duplicating.
   */
  @Transactional
  public List<EnrichedPayment> ingest(List<PaymentRequest> requests) {
    List<Payment> entities = requests.stream().map(classifier::toPayment).collect(Collectors.toList());

    // Idempotent upsert: re-ingesting an existing paymentId must update, not fail. With @Version,
    // Spring Data treats a null-version entity as new (INSERT). So for ids that already exist we
    // carry over the stored version, turning the save into a version-checked UPDATE (merge).
    List<String> ids = entities.stream().map(Payment::getPaymentId).collect(Collectors.toList());
    Map<String, Long> existingVersions =
        repository.findAllById(ids).stream()
            .collect(
                Collectors.toMap(
                    Payment::getPaymentId, p -> p.getVersion() == null ? 0L : p.getVersion()));
    entities.forEach(
        e -> {
          Long v = existingVersions.get(e.getPaymentId());
          if (v != null) {
            e.setVersion(v);
          }
        });

    List<Payment> saved = repository.saveAll(entities);
    return classifier.classifyAll(saved, clock.instant());
  }

  @Transactional(readOnly = true)
  public EnrichedPayment getOne(String paymentId) {
    Payment p = repository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    return classifier.classify(p, clock.instant());
  }

  /** Returns all stored payments enriched, optionally filtered by status/method/country/processor. */
  @Transactional(readOnly = true)
  public List<EnrichedPayment> query(
      SettlementStatus status, String method, String country, String processor) {
    Instant now = clock.instant();
    return classifier.classifyAll(repository.findAll(), now).stream()
        .filter(p -> status == null || p.getSettlementStatus() == status)
        .filter(p -> method == null || method.equalsIgnoreCase(p.getPaymentMethod()))
        .filter(p -> country == null || country.equalsIgnoreCase(p.getCountry()))
        .filter(p -> processor == null || processor.equalsIgnoreCase(p.getProcessor()))
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public AnalyticsResponse analytics() {
    return analytics.compute(classifier.classifyAll(repository.findAll(), clock.instant()));
  }

  @Transactional(readOnly = true)
  public List<Anomaly> anomalies() {
    Instant now = clock.instant();
    return anomalyDetection.detect(classifier.classifyAll(repository.findAll(), now), now);
  }

  @Transactional(readOnly = true)
  public long count() {
    return repository.count();
  }
}
