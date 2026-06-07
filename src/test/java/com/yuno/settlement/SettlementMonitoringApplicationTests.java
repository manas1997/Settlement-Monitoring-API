package com.yuno.settlement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SettlementMonitoringApplicationTests {

  @Autowired private MockMvc mockMvc;

  @Test
  void contextLoads() {}

  @Test
  void testHealthEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/settlement/health")).andExpect(status().isOk());
  }

  @Test
  void analyticsEndpointReturnsSeededAggregates() throws Exception {
    // Seed loader ingests the demo dataset on startup, so analytics has data.
    mockMvc
        .perform(get("/api/v1/analytics/settlement"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalTransactions").isNumber())
        .andExpect(jsonPath("$.byStatus").exists());
  }
}
