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
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
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
    {
      URI origin;
      try {
        origin = URI.create(baseUrl);
      } catch (IllegalArgumentException invalid) {
        // A mensagem do parser de URI pode conter credenciais da configuração.
        throw new IllegalStateException("Aplicação exige endereço e origem HTTP de loopback");
      }
      if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(address)
          || origin.getHost() == null
          || !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(origin.getHost())
          || !"http".equals(origin.getScheme())
          || origin.getPort() == 0
          || origin.getPort() > 65535
          || origin.getRawAuthority().endsWith(":")
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || (origin.getPath() != null && !origin.getPath().isEmpty()))
        throw new IllegalStateException("Aplicação exige endereço e origem HTTP de loopback");
    }
    http.authorizeHttpRequests(
        auth -> {
          auth.requestMatchers(
                  HttpMethod.GET,
                  "/health/ready",
                  "/api/v1/auth/csrf",
                  "/api/v1/sources",
                  "/api/v1/sources/{id}",
                  "/api/v1/articles",
                  "/api/v1/articles/{id}")
              .permitAll();
          auth.requestMatchers(HttpMethod.PATCH, "/api/v1/articles/{id}/state").permitAll();
          if (!demo) {
            auth.requestMatchers(HttpMethod.POST, "/api/v1/sources", "/api/v1/sources/{id}/refresh")
                .permitAll();
            auth.requestMatchers(HttpMethod.PATCH, "/api/v1/sources/{id}").permitAll();
            auth.requestMatchers(HttpMethod.DELETE, "/api/v1/sources/{id}").permitAll();
          }
          auth.anyRequest().denyAll();
        });
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (req, res, e) ->
                        ApiErrors.write(req, res, 403, "FORBIDDEN", "Operação recusada."))
                .accessDeniedHandler(
                    (req, res, e) ->
                        ApiErrors.write(
                            req,
                            res,
                            403,
                            e instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                            e instanceof CsrfException
                                ? "Token CSRF inválido."
                                : "Operação recusada.")));
    http.requestCache(cache -> cache.disable());
    http.addFilterAfter(new RequestBodyLimitFilter(), CsrfFilter.class);
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
