package br.com.agregador.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class RequestBodyLimitFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(8193);
    if (body.length > 8192) {
      ApiErrors.write(
          request, response, 413, "REQUEST_TOO_LARGE", "O corpo da requisição excede 8 KiB.");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("Leitura assíncrona indisponível");
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
