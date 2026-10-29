package br.com.agregador.auth;

import java.util.Map;

public final class HttpParameters {
  private HttpParameters() {}

  public static void noParameters(Map<String, String> parameters) {
    if (!parameters.isEmpty()) throw new UnsupportedParameters();
  }

  public static class UnsupportedParameters extends IllegalArgumentException {}
}
