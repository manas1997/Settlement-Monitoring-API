package com.yuno.settlement.controller;

import com.yuno.settlement.dto.ClassifyRequest;
import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import com.yuno.settlement.service.PaymentService;
import com.yuno.settlement.service.SettlementClassificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Settlement status tracking: stateless classification + persisted ingest/query. */
@RestController
@RequestMapping("/api/v1/settlement")
@Tag(name = "Settlement", description = "Classify and track payment settlement status")
public class SettlementController {

  private final SettlementClassificationService classifier;
  private final PaymentService paymentService;
  private final Clock clock;

  public SettlementController(
      SettlementClassificationService classifier, PaymentService paymentService, Clock clock) {
    this.classifier = classifier;
    this.paymentService = paymentService;
    this.clock = clock;
  }

  @GetMapping("/health")
  @Operation(summary = "Liveness check")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("Settlement Monitoring API is running");
  }

  /** Requirement 1: stateless batch classification — submit payments, get enriched status back. */
  @PostMapping("/classify")
  @Operation(summary = "Classify a batch of payments without persisting them")
  public List<EnrichedPayment> classify(@Valid @RequestBody ClassifyRequest request) {
    return classifier.classifyRequests(request.getPayments(), clock.instant());
  }

  /** Persist a batch of payments (idempotent on paymentId) and return their enriched view. */
  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Ingest & persist payments")
  public List<EnrichedPayment> ingest(@Valid @RequestBody ClassifyRequest request) {
    return paymentService.ingest(request.getPayments());
  }

  @GetMapping("/payments/{paymentId}")
  @Operation(summary = "Fetch a single enriched payment by id")
  public EnrichedPayment getOne(@PathVariable String paymentId) {
    return paymentService.getOne(paymentId);
  }

  /** Query stored payments, optionally filtering by status / method / country / processor. */
  @GetMapping("/payments")
  @Operation(summary = "Query stored payments with optional filters")
  public List<EnrichedPayment> query(
      @RequestParam(required = false) SettlementStatus status,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String processor) {
    return paymentService.query(status, method, country, processor);
  }
}
