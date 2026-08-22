package de.oppahansi.kosmos.cache;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CacheResult}-annotated method as also persisted to the L2 {@code cache_entry}
 * table — see {@link PersistentCacheService}'s own doc comment for why. Deliberately takes no
 * attributes of its own: {@link PersistentCacheInterceptor} derives the cache name from the
 * co-located {@link io.quarkus.cache.CacheResult}, the TTL from that same cache name's existing
 * {@code quarkus.cache.caffeine."<name>".expire-after-write} config (one TTL source of truth, not a
 * second number to keep in sync by hand), and the key/value shape from the method's own
 * parameters/generic return type via reflection — so covering a method is exactly "add this one
 * annotation," nothing about the method body changes.
 */
@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersistentCache {}
