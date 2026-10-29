package br.com.agregador.auth;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class CsrfController {
  public record Token(String token, String headerName) {}

  @GetMapping("/api/v1/auth/csrf")
  public Token csrf(CsrfToken token) {
    return new Token(token.getToken(), token.getHeaderName());
  }
}
