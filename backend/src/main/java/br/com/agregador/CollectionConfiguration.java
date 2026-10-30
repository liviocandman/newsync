package br.com.agregador;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CollectionConfiguration {
  @Bean
  public Clock collectionClock() {
    return Clock.systemUTC();
  }
}
