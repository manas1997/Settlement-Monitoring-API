package com.yuno.settlement.controller;

import com.yuno.settlement.dto.AnalyticsResponse;
import com.yuno.settlement.dto.Anomaly;
import com.yuno.settlement.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Requirement 2 + stretch: aggregate analytics and anomaly detection over stored payments. */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Aggregate settlement performance and anomaly detection")
public class AnalyticsController {

  private final PaymentService paymentService;

  public AnalyticsController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @GetMapping("/settlement")
  @Operation(summary = "Aggregate settlement analytics (value by status, avg times, efficiency)")
  public AnalyticsResponse settlement() {
    return paymentService.analytics();
  }

  @GetMapping("/anomalies")
  @Operation(summary = "Detected settlement anomalies with severity and confidence")
  public List<Anomaly> anomalies() {
    return paymentService.anomalies();
  }
}
