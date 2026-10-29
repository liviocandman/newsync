package br.com.agregador.sources;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class CollectionActivity {
  private record Attempt(UUID id, long version) {}

  private final AtomicReference<Attempt> current = new AtomicReference<>();

  public boolean matches(UUID id, long version) {
    return new Attempt(id, version).equals(current.get());
  }

  public boolean start(UUID id, long version) {
    return current.compareAndSet(null, new Attempt(id, version));
  }

  public void clear() {
    current.set(null);
  }
}
