package com.yuno.settlement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-wide beans. */
@Configuration
public class AppConfig {

  /**
   * A single, injectable {@link Clock}. The whole domain is time-sensitive, so centralizing "now"
   * here lets tests freeze time deterministically instead of sprinkling {@code Instant.now()}.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public OpenAPI settlementOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Settlement Monitoring API")
                .description(
                    "Tracks cross-border payments from capture through settlement, classifies "
                        + "settlement status, surfaces analytics and detects anomalies.")
                .version("1.0.0")
                .contact(new Contact().name("OceanTrade / Yuno"))
                .license(new License().name("Proprietary")));
  }
}
