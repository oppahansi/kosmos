package de.oppahansi.kosmos.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PersistentCacheServiceTest {

  @Inject PersistentCacheService cacheService;

  @Test
  void secondCallServesFromCacheWithoutInvokingLoaderAgain() {
    AtomicInteger loaderCalls = new AtomicInteger();
    String cacheName = "test-" + System.nanoTime();

    String first =
        cacheService.getOrCompute(
            cacheName,
            "key-1",
            Duration.ofHours(1),
            new TypeReference<>() {},
            () -> {
              loaderCalls.incrementAndGet();
              return "computed";
            });
    String second =
        cacheService.getOrCompute(
            cacheName,
            "key-1",
            Duration.ofHours(1),
            new TypeReference<>() {},
            () -> {
              loaderCalls.incrementAndGet();
              return "computed";
            });

    assertEquals("computed", first);
    assertEquals("computed", second);
    assertEquals(1, loaderCalls.get());
  }

  /**
   * The core L1-miss/L2-hit case this whole service exists for: a value written by one call is
   * readable by a second call whose loader is never invoked — the same property a Caffeine-eviction
   * (or a restart, which this test can't simulate directly) relies on.
   */
  @Test
  void loaderNeverRunsAgainOnceCacheEntryRowExists() {
    String cacheName = "test-" + System.nanoTime();
    AtomicInteger loaderCalls = new AtomicInteger();
    java.util.function.Supplier<String> loader =
        () -> {
          loaderCalls.incrementAndGet();
          return "value-" + loaderCalls.get();
        };

    String first =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<>() {}, loader);
    String second =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<>() {}, loader);
    String third =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<>() {}, loader);

    assertEquals("value-1", first);
    assertEquals("value-1", second);
    assertEquals("value-1", third);
    assertEquals(1, loaderCalls.get());
  }

  @Test
  void expiredEntryRunsLoaderAgain() {
    String cacheName = "test-" + System.nanoTime();
    cacheService.getOrCompute(
        cacheName, "k", Duration.ofSeconds(-1), new TypeReference<>() {}, () -> "first");

    AtomicInteger loaderCalls = new AtomicInteger();
    String result =
        cacheService.getOrCompute(
            cacheName,
            "k",
            Duration.ofHours(1),
            new TypeReference<>() {},
            () -> {
              loaderCalls.incrementAndGet();
              return "second";
            });

    assertEquals("second", result);
    assertEquals(1, loaderCalls.get());
  }

  /**
   * A loader legitimately returning null (how most metadata-provider methods represent "not found")
   * must itself be cached — not treated as a miss on every subsequent call.
   */
  @Test
  void nullLoaderResultIsCachedAsARealHit() {
    String cacheName = "test-" + System.nanoTime();
    AtomicInteger loaderCalls = new AtomicInteger();
    java.util.function.Supplier<String> loader =
        () -> {
          loaderCalls.incrementAndGet();
          return null;
        };

    String first =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<>() {}, loader);
    String second =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<>() {}, loader);

    assertNull(first);
    assertNull(second);
    assertEquals(1, loaderCalls.get());
  }

  @Test
  void roundTripsGenericCollectionTypes() {
    String cacheName = "test-" + System.nanoTime();
    List<String> value = List.of("a", "b", "c");

    List<String> result =
        cacheService.getOrCompute(
            cacheName, "k", Duration.ofHours(1), new TypeReference<List<String>>() {}, () -> value);

    assertEquals(value, result);
  }

  /** {@code Map<Integer, FribbEntry>} shape — FribbMappingProvider/TheXemMappingProvider's own. */
  @Test
  void roundTripsGenericMapTypes() {
    String cacheName = "test-" + System.nanoTime();
    java.util.Map<Integer, String> value = java.util.Map.of(1, "one", 2, "two");

    java.util.Map<Integer, String> result =
        cacheService.getOrCompute(
            cacheName,
            "k",
            Duration.ofHours(1),
            new TypeReference<java.util.Map<Integer, String>>() {},
            () -> value);

    assertEquals(value, result);
  }

  @Test
  void deleteExpiredRemovesOnlyExpiredRows() {
    String cacheName = "test-" + System.nanoTime();
    cacheService.getOrCompute(
        cacheName, "expired", Duration.ofSeconds(-1), new TypeReference<>() {}, () -> "x");
    cacheService.getOrCompute(
        cacheName, "fresh", Duration.ofHours(1), new TypeReference<>() {}, () -> "y");

    cacheService.deleteExpired();

    assertFalse(rowExists(cacheName, "expired"));
    assertTrue(rowExists(cacheName, "fresh"));
  }

  @Transactional
  boolean rowExists(String cacheName, String key) {
    return CacheEntry.count("cacheName = ?1 and cacheKey = ?2", cacheName, key) > 0;
  }
}
