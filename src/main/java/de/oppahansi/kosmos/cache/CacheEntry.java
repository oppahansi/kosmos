package de.oppahansi.kosmos.cache;

import de.oppahansi.kosmos.common.KosmosEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry of the L2 cache behind Caffeine — see {@link PersistentCacheService}'s own doc. {@code
 * value} is stored as-is, whatever {@link PersistentCacheService}'s Jackson {@code ObjectMapper}
 * serialized the loader's result to (including the literal JSON {@code "null"} for a loader that
 * legitimately returned null) — the row's mere existence, not the content of {@code value}, is what
 * "cache hit" means.
 */
@Entity
@Table(name = "cache_entry")
public class CacheEntry extends KosmosEntity {

  @Column(name = "cache_name", nullable = false, length = 100)
  public String cacheName;

  @Column(name = "cache_key", nullable = false, length = 2000)
  public String cacheKey;

  @Column(nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  public String value;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;
}
