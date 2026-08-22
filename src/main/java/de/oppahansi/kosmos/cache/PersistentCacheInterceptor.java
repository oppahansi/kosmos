package de.oppahansi.kosmos.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Backs {@link PersistentCache}. Runs outside {@code @CacheResult}'s own interceptor (a Caffeine
 * hit never reaches here at all — see {@link PersistentCacheService}'s doc) and, on a Caffeine
 * miss, checks the L2 {@link CacheEntry} table before letting {@code ctx.proceed()} make the real
 * HTTP call.
 */
@PersistentCache
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class PersistentCacheInterceptor {

  @Inject PersistentCacheService persistentCache;
  @Inject ObjectMapper objectMapper;

  @AroundInvoke
  public Object around(InvocationContext ctx) throws Exception {
    Method method = ctx.getMethod();
    CacheResult cacheResult = method.getAnnotation(CacheResult.class);
    if (cacheResult == null) {
      throw new IllegalStateException(
          "@PersistentCache requires a co-located @CacheResult on " + method);
    }
    String cacheName = cacheResult.cacheName();
    String key = buildKey(ctx.getParameters());
    Duration ttl = readTtl(cacheName);
    JavaType type = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
    return persistentCache.getOrCompute(cacheName, key, ttl, type, ctx::proceed);
  }

  private String buildKey(Object[] params) {
    if (params == null || params.length == 0) {
      return "";
    }
    return Arrays.stream(params).map(String::valueOf).collect(Collectors.joining("|"));
  }

  /**
   * Quarkus's cache extension accepts a bare unit suffix ({@code "7D"}, {@code "24H"}) for {@code
   * expire-after-write} rather than {@code java.time.Duration}'s own ISO-8601 syntax, so this
   * parses that shape directly rather than trying to reuse the generic config {@code Duration}
   * converter.
   */
  private Duration readTtl(String cacheName) {
    String raw =
        ConfigProvider.getConfig()
            .getValue(
                "quarkus.cache.caffeine.\"" + cacheName + "\".expire-after-write", String.class);
    char unit = Character.toUpperCase(raw.charAt(raw.length() - 1));
    long amount = Long.parseLong(raw.substring(0, raw.length() - 1));
    return switch (unit) {
      case 'D' -> Duration.ofDays(amount);
      case 'H' -> Duration.ofHours(amount);
      case 'M' -> Duration.ofMinutes(amount);
      case 'S' -> Duration.ofSeconds(amount);
      default ->
          throw new IllegalStateException(
              "Unsupported TTL unit in \"" + raw + "\" for " + cacheName);
    };
  }
}
