package de.oppahansi.kosmos.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * L2 cache behind the Caffeine {@code @CacheResult} L1 every metadata-provider method already
 * carries — see {@link PersistentCacheInterceptor} for how a method actually gets covered (just its
 * own {@code @PersistentCache} marker alongside the {@code @CacheResult} it already has; no method
 * body needs to know this class exists). A routine restart drops Caffeine's whole JVM-heap cache,
 * so a fresh instance re-fetches everything popular from TMDB/AniList/etc. all over again — wasted
 * latency and real pressure against those APIs' own rate limits right when a fresh instance is
 * already doing the most work. {@code cache_entry} (plain Postgres, the datastore Kosmos already
 * runs) fixes that without a new service or storage engine — see the roadmap's own Phase 19 note
 * for why Redis/SQLite/etc. were ruled out.
 *
 * <p>Deliberately consulted only on a Caffeine miss ({@code @CacheResult} still sits above every
 * call site) — a hot key never pays a Postgres round trip, this only changes what happens the first
 * time a key is asked for after a restart. Each {@code getOrCompute*} keeps its own DB read/write
 * in its own short transaction, with the loader itself (an HTTP call, potentially slow) called with
 * no transaction open — a cache write is not on the critical path a slow upstream API should be
 * allowed to hold a Postgres connection hostage for.
 *
 * <p>Uses the CDI-managed {@link ObjectMapper} (unlike every metadata provider's own {@code new
 * ObjectMapper()}) — this class's whole job is generic (de)serialization of whatever type a caller
 * hands it, including {@code Optional}/{@code LocalDate} shapes the providers' own bare mappers
 * can't handle without a manual module registration Quarkus's own bean already carries.
 */
@ApplicationScoped
public class PersistentCacheService {

  @Inject ObjectMapper objectMapper;

  /**
   * Returns the persisted value for {@code cacheName}/{@code key} if a still-valid row exists,
   * otherwise runs {@code loader} and persists its result with the given {@code ttl}. {@code
   * loader} legitimately returning {@code null} (the not-found case most of these methods already
   * represent as {@code Optional.empty()}/{@code null} rather than throwing) is itself a cacheable
   * result — a row's mere existence is what "cache hit" means here, not whether its value is null.
   */
  public <T> T getOrCompute(
      String cacheName, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
    Optional<String> cachedJson = readValid(cacheName, key);
    if (cachedJson.isPresent()) {
      return deserialize(cachedJson.get(), objectMapper.getTypeFactory().constructType(type));
    }
    T value = loader.get();
    store(cacheName, key, serialize(value), Instant.now().plus(ttl));
    return value;
  }

  /**
   * Reflective counterpart used by {@link PersistentCacheInterceptor}, which only has a {@link
   * java.lang.reflect.Method}'s generic return type — not a compile-time {@link TypeReference} —
   * and whose {@code loader} ({@code ctx::proceed}) declares a checked {@code throws Exception},
   * unlike {@link Supplier}.
   */
  Object getOrCompute(
      String cacheName, String key, Duration ttl, JavaType type, Callable<Object> loader)
      throws Exception {
    Optional<String> cachedJson = readValid(cacheName, key);
    if (cachedJson.isPresent()) {
      return deserialize(cachedJson.get(), type);
    }
    Object value = loader.call();
    store(cacheName, key, serialize(value), Instant.now().plus(ttl));
    return value;
  }

  @Transactional
  Optional<String> readValid(String cacheName, String key) {
    CacheEntry entry =
        CacheEntry.find("cacheName = ?1 and cacheKey = ?2", cacheName, key).firstResult();
    if (entry == null || entry.expiresAt.isBefore(Instant.now())) {
      return Optional.empty();
    }
    return Optional.of(entry.value);
  }

  /**
   * A bulk UPDATE first, INSERT only on a miss — not a find-then-persist on a managed entity.
   * {@link CacheCleanupJob} deletes expired rows concurrently with ordinary traffic; a find-then-
   * persist on an entity that gets deleted out from under it between the find and the flush throws
   * {@code OptimisticLockException} (a real failure this project's own test suite caught once
   * per-test runtime grew past the scheduler's tick interval). A bulk update can't hit that — it
   * either matches a row or it doesn't, with no managed-entity flush to race.
   */
  @Transactional
  void store(String cacheName, String key, String json, Instant expiresAt) {
    long updated =
        CacheEntry.update(
            "value = ?1, expiresAt = ?2 where cacheName = ?3 and cacheKey = ?4",
            json,
            expiresAt,
            cacheName,
            key);
    if (updated == 0) {
      CacheEntry entry = new CacheEntry();
      entry.cacheName = cacheName;
      entry.cacheKey = key;
      entry.value = json;
      entry.expiresAt = expiresAt;
      entry.persist();
    }
  }

  /** {@link de.oppahansi.kosmos.scheduler.JobHandler}-driven housekeeping — see CacheCleanupJob. */
  @Transactional
  public long deleteExpired() {
    return CacheEntry.delete("expiresAt < ?1", Instant.now());
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize cache value", e);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T deserialize(String json, JavaType type) {
    try {
      return (T) objectMapper.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize cache value", e);
    }
  }
}
