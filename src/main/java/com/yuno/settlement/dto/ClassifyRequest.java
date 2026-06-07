package com.yuno.settlement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Batch request body for the stateless classification endpoint. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassifyRequest {

  @NotEmpty(message = "payments must not be empty")
  @Valid
  private List<PaymentRequest> payments;
}
