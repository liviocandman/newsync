package br.com.agregador.auth;

import br.com.agregador.web.PackagedUi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
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
      ConfigurableEnvironment env,
      PackagedUi ui,
      @Value("${app.base-url}") String baseUrl,
      @Value("${server.address}") String address,
      @Value("${app.deployment:local}") String deployment)
      throws Exception {
    boolean demo = env.acceptsProfiles(Profiles.of("demo"));
    boolean container = deployment.equals("container-local");
    if (!Set.of("local", "container-local").contains(deployment)
        || (container && (demo || !ui.mode().equals("normal"))))
      throw new IllegalStateException("Modo de implantação inválido");
    if (container) requireContainerSecret(env);
    {
      URI origin;
      try {
        origin = URI.create(baseUrl);
      } catch (IllegalArgumentException invalid) {
        // The URI parser message may contain configuration credentials.
        throw new IllegalStateException("Aplicação exige endereço e origem HTTP de loopback");
      }
      if (!(container
              ? address.equals("0.0.0.0")
              : Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(address))
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
                  req ->
                      Set.of("GET", "HEAD").contains(req.getMethod())
                          && (ui.navigation(req.getServletPath(), demo)
                              || req.getServletPath().startsWith("/assets/")))
              .permitAll();
          auth.requestMatchers(
                  HttpMethod.GET,
                  "/health/ready",
                  "/api/v1/auth/csrf",
                  "/api/v1/sources",
                  "/api/v1/sources/{id}",
                  "/api/v1/articles",
                  "/api/v1/search",
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
                .addHeaderWriter(
                    (req, res) ->
                        res.setHeader(
                            "Content-Security-Policy",
                            res.getContentType() != null
                                    && res.getContentType().startsWith("text/html")
                                ? PackagedUi.DOCUMENT_CSP
                                : PackagedUi.API_CSP))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER)));
    return http.build(); // CSRF stays enabled; no public login or HTTP Basic.
  }

  private static void requireContainerSecret(ConfigurableEnvironment env) {
    String mountedPassword = null;
    for (var source : env.getPropertySources()) {
      if (source instanceof ConfigTreePropertySource tree
          && tree.getSource().toString().equals("/run/secrets")) {
        mountedPassword =
            env.getConversionService()
                .convert(tree.getProperty("spring.datasource.password"), String.class);
      }
    }
    if (!"configtree:/run/secrets/".equals(env.getProperty("spring.config.import"))
        || mountedPassword == null
        || mountedPassword.isBlank()
        || !mountedPassword.equals(env.getProperty("spring.datasource.password")))
      throw new IllegalStateException("Contêiner exige configtree obrigatório e secret legível");
  }
}
