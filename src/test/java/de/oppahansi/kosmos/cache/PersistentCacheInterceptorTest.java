package de.oppahansi.kosmos.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PersistentCache} actually wires a real {@code @CacheResult} method through to L2 —
 * {@link PersistentCacheServiceTest} covers {@link PersistentCacheService}'s own read/write/TTL
 * semantics directly, this covers the reflection glue (cache name from {@code @CacheResult}, key
 * from parameters, TTL from config, generic return type via Jackson) that {@link
 * PersistentCacheInterceptor} adds on top.
 */
@QuarkusTest
class PersistentCacheInterceptorTest {

  @Inject Probe probe;
  @Inject CacheManager cacheManager;

  @Test
  void survivesAnL1EvictionByServingFromL2WithoutRerunningTheLoader() {
    String key = UUID.randomUUID().toString();

    Optional<String> first = probe.lookup(key);
    assertEquals(Optional.of("value-for-" + key), first);
    assertEquals(1, probe.calls());

    // Simulate what a restart does to Caffeine's L1 — the JVM-heap cache is gone, but the L2 row
    // PersistentCacheInterceptor wrote underneath it should still answer without re-invoking the
    // loader.
    Cache l1 = cacheManager.getCache("tmdb-movie-by-id").orElseThrow();
    l1.invalidate(key).await().indefinitely();

    Optional<String> second = probe.lookup(key);
    assertEquals(Optional.of("value-for-" + key), second);
    assertEquals(1, probe.calls(), "loader must not run again once L2 has the value");
  }

  @Test
  void distinctKeysGetDistinctCacheRows() {
    String keyA = UUID.randomUUID().toString();
    String keyB = UUID.randomUUID().toString();

    probe.lookup(keyA);
    probe.lookup(keyB);

    assertTrue(probe.calls() >= 2);
  }

  @ApplicationScoped
  static class Probe {
    final AtomicInteger calls = new AtomicInteger();

    // Reuses a real cache name from application.properties (so the interceptor's TTL lookup
    // resolves) — the L1/L2 cache is genuinely keyed by (cacheName, key), so a synthetic
    // randomUUID() key here can never collide with a real fetchMovieById lookup.
    @CacheResult(cacheName = "tmdb-movie-by-id")
    @PersistentCache
    Optional<String> lookup(String key) {
      calls.incrementAndGet();
      return Optional.of("value-for-" + key);
    }

    int calls() {
      return calls.get();
    }
  }
}
