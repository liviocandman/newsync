package br.com.agregador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class NewsApplication {
  public static void main(String[] args) {
    SpringApplication.run(NewsApplication.class, args);
  }
}
