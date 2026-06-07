package com.yuno.settlement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuno.settlement.dto.EnrichedPayment;
import com.yuno.settlement.model.enums.SettlementStatus;
import com.yuno.settlement.service.PaymentService;
import com.yuno.settlement.service.SettlementClassificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettlementController.class)
@AutoConfigureMockMvc(addFilters = false) // security tested separately; focus on web behavior here
class SettlementControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private SettlementClassificationService classifier;
  @MockBean private PaymentService paymentService;
  @MockBean private Clock clock;

  @Test
  void classifyReturnsEnrichedPayments() throws Exception {
    when(clock.instant()).thenReturn(Instant.parse("2026-06-15T10:00:00Z"));
    EnrichedPayment enriched =
        EnrichedPayment.builder()
            .paymentId("pay_1")
            .settlementStatus(SettlementStatus.DELAYED)
            .needsAttention(true)
            .build();
    when(classifier.classifyRequests(any(), any())).thenReturn(List.of(enriched));

    String body =
        """
        {"payments":[{"paymentId":"pay_1","paymentMethod":"bank_transfer","country":"BR",
        "currency":"BRL","amount":1000,"capturedAt":"2026-06-01T10:00:00Z"}]}
        """;

    mockMvc
        .perform(
            post("/api/v1/settlement/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].paymentId").value("pay_1"))
        .andExpect(jsonPath("$[0].settlementStatus").value("DELAYED"))
        .andExpect(jsonPath("$[0].needsAttention").value(true));
  }

  @Test
  void emptyBatchIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/settlement/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payments\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void missingRequiredFieldIsRejectedWith400() throws Exception {
    // amount + capturedAt missing -> validation error
    String body =
        "{\"payments\":[{\"paymentMethod\":\"pix\",\"country\":\"BR\",\"currency\":\"BRL\"}]}";
    mockMvc
        .perform(
            post("/api/v1/settlement/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
