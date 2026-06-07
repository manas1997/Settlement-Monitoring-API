package com.yuno.settlement.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** RFC-7807-ish error envelope returned for all handled errors. */
@Data
@Builder
@AllArgsConstructor
public class ApiError {
  private Instant timestamp;
  private int status;
  private String error;
  private String message;
  private String path;

  /** Field -&gt; validation message, populated for 400 validation failures. */
  private Map<String, String> fieldErrors;

  /** Optional extra details. */
  private List<String> details;
}
