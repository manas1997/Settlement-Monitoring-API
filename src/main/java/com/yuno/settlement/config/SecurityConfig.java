package com.yuno.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security is profile-gated so the challenge demo runs open while production stays locked down.
 *
 * <ul>
 *   <li>Default (no {@code secure} profile): permit everything — convenient for local/demo use.
 *   <li>{@code secure} profile: stateless JWT (OAuth2 resource server) with scope-based authority.
 *       GET endpoints require {@code SCOPE_settlement.read}; writes require {@code
 *       SCOPE_settlement.write}. Health and API docs stay public.
 * </ul>
 *
 * <p>Why a resource server rather than rolling our own JWT parsing: token issuance and key rotation
 * belong to a dedicated IdP (Auth0/Cognito/Keycloak). The service only validates, which is exactly
 * what Spring's resource server gives us — less code, fewer footguns, OWASP-aligned defaults.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  @Profile("!secure")
  public SecurityFilterChain devChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Profile("secure")
  public SecurityFilterChain prodChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                    .hasAuthority("SCOPE_settlement.read")
                    .requestMatchers("/api/v1/**")
                    .hasAuthority("SCOPE_settlement.write")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
