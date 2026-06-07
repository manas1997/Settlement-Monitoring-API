package com.yuno.settlement.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuno.settlement.config.SettlementProperties;
import com.yuno.settlement.dto.PaymentRequest;
import com.yuno.settlement.service.PaymentService;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads the demo dataset into the in-memory store on startup so the GET analytics endpoints have
 * data to chew on immediately. No-op if seeding is disabled or the store is already populated.
 */
@Component
public class SeedDataLoader implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

  private final SettlementProperties props;
  private final PaymentService paymentService;
  private final ResourceLoader resourceLoader;
  private final ObjectMapper objectMapper;

  public SeedDataLoader(
      SettlementProperties props,
      PaymentService paymentService,
      ResourceLoader resourceLoader,
      ObjectMapper objectMapper) {
    this.props = props;
    this.paymentService = paymentService;
    this.resourceLoader = resourceLoader;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.isSeedOnStartup()) {
      log.info("Seeding disabled (settlement.seed-on-startup=false).");
      return;
    }
    if (paymentService.count() > 0) {
      log.info("Store already populated ({} payments); skipping seed.", paymentService.count());
      return;
    }
    Resource resource = resourceLoader.getResource(props.getSeedResource());
    if (!resource.exists()) {
      log.warn("Seed resource {} not found; starting empty.", props.getSeedResource());
      return;
    }
    try (InputStream in = resource.getInputStream()) {
      List<PaymentRequest> payments =
          objectMapper.readValue(
              in,
              objectMapper
                  .getTypeFactory()
                  .constructCollectionType(List.class, PaymentRequest.class));
      paymentService.ingest(payments);
      log.info("Seeded {} payments from {}.", payments.size(), props.getSeedResource());
    } catch (Exception e) {
      log.error("Failed to seed payments from {}: {}", props.getSeedResource(), e.getMessage(), e);
    }
  }
}
