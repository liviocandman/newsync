package br.com.agregador.auth;

import br.com.agregador.auth.HttpParameters.UnsupportedParameters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
  public record Error(String code, String message, String requestId) {}

  static String requestId(HttpServletRequest request) {
    return (String) request.getAttribute("requestId");
  }

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + code
                + "\",\"message\":\""
                + message
                + "\",\"requestId\":\""
                + requestId(request)
                + "\"}");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> handle(Exception error, HttpServletRequest request) {
    int status;
    String code;
    String message;
    if (error instanceof br.com.agregador.sources.SourceService.Rejected rejected) {
      var response = ResponseEntity.status(rejected.status()).header("Cache-Control", "no-store");
      if (rejected.retryAfter() > 0)
        response.header("Retry-After", Long.toString(rejected.retryAfter()));
      return response.body(new Error(rejected.code(), rejected.getMessage(), requestId(request)));
    } else if (error instanceof UnsupportedParameters) {
      status = 400;
      code = "UNSUPPORTED_PARAMETERS";
      message = "Parâmetros ainda não implementados.";
    } else if (error instanceof NoSuchElementException
        || error instanceof NoResourceFoundException) {
      status = 404;
      code = "NOT_FOUND";
      message = "Recurso não encontrado.";
    } else if (DatabaseFailures.temporary(error)) {
      status = 503;
      code = "DATABASE_UNAVAILABLE";
      message = "Banco temporariamente indisponível.";
    } else if (error instanceof IllegalArgumentException
        || error instanceof HttpMediaTypeNotSupportedException
        || error instanceof HttpMessageNotReadableException
        || error instanceof MethodArgumentTypeMismatchException) {
      status = 400;
      code = "INVALID_REQUEST";
      message = "Requisição inválida.";
    } else {
      status = 500;
      code = "INTERNAL_ERROR";
      message = "Não foi possível concluir a operação.";
    }
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(new Error(code, message, requestId(request)));
  }
}
