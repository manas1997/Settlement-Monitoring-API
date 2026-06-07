package com.yuno.settlement.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

  @GetMapping("/health")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("Settlement Monitoring API is running");
  }
}
