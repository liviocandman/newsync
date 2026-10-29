package br.com.agregador.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      Environment env,
      @Value("${app.base-url}") String baseUrl,
      @Value("${server.address}") String address)
      throws Exception {
    boolean demo = env.acceptsProfiles(Profiles.of("demo"));
    if (demo) {
      URI origin;
      try {
        origin = URI.create(baseUrl);
      } catch (IllegalArgumentException invalid) {
        // A mensagem do parser de URI pode conter credenciais da configuração.
        throw new IllegalStateException("Demo exige endereço e origem HTTP de loopback");
      }
      if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(address)
          || origin.getHost() == null
          || !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(origin.getHost())
          || !"http".equals(origin.getScheme())
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || (origin.getPath() != null && !origin.getPath().isEmpty()))
        throw new IllegalStateException("Demo exige endereço e origem HTTP de loopback");
    }
    http.authorizeHttpRequests(
        auth -> {
          auth.requestMatchers("/health/ready", "/api/v1/auth/csrf").permitAll();
          if (demo) auth.requestMatchers("/api/v1/**").permitAll();
          auth.anyRequest().denyAll();
        });
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (req, res, e) ->
                        ApiErrors.write(req, res, 401, "AUTH_REQUIRED", "Autenticação necessária."))
                .accessDeniedHandler(
                    (req, res, e) ->
                        ApiErrors.write(
                            req,
                            res,
                            403,
                            "FORBIDDEN",
                            "Operação recusada. Obtenha um novo token CSRF.")));
    http.addFilterBefore(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
                && !baseUrl.equals(req.getHeader("Origin"))) {
              ApiErrors.write(req, res, 403, "ORIGIN_REJECTED", "Origem da requisição recusada.");
              return;
            }
            chain.doFilter(req, res);
          }
        },
        CsrfFilter.class);
    http.headers(
        headers ->
            headers
                .contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER)));
    return http.build(); // CSRF ativo, sem login público ou HTTP Basic.
  }
}
