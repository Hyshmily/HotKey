package io.github.hyshmily.hotkey;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.hyshmily.hotkey.algorithm.AddResult;
import io.github.hyshmily.hotkey.algorithm.TopK;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class HotKeyCache {

  private static final Logger log = LoggerFactory.getLogger(HotKeyCache.class);

  private final TopK hotKeyDetector;
  private final Cache<String, Object> caffeineCache;
  private final RedisTemplate<String, Object> redisTemplate;
  private final Optional<HotKeyBroadcaster> broadcaster;

  public HotKeyCache(
    TopK hotKeyDetector,
    Cache<String, Object> caffeineCache,
    RedisTemplate<String, Object> redisTemplate,
    Optional<HotKeyBroadcaster> broadcaster
  ) {
    this.hotKeyDetector = hotKeyDetector;
    this.caffeineCache = caffeineCache;
    this.redisTemplate = redisTemplate;
    this.broadcaster = broadcaster;
  }

  private String cacheKey(String redisHashKey, String fieldKey) {
    return redisHashKey + ":" + fieldKey;
  }

  public Object get(String redisHashKey, String fieldKey) {
    String cacheKey = cacheKey(redisHashKey, fieldKey);

    return Optional.ofNullable(caffeineCache.getIfPresent(cacheKey))
      .map(value -> {
        log.debug("Caffeine L1 hit: key={}", cacheKey);
        AddResult result = hotKeyDetector.add(cacheKey, 1);
        if (result.isHotKey()) {
          caffeineCache.put(cacheKey, value);
          log.debug("HotKey access, refresh local caffeine cache expiration time: {}", cacheKey);
        }
        return value;
      })
      .orElseGet(() -> {
        Object redisValue = redisTemplate.opsForHash().get(redisHashKey, fieldKey);

        return Optional.ofNullable(redisValue)
          .map(value -> {
            AddResult addResult = hotKeyDetector.add(cacheKey, 1);
            if (addResult.isHotKey()) {
              caffeineCache.put(cacheKey, value);
              broadcaster.ifPresent(p -> p.broadcastHotKey(redisHashKey, fieldKey));
              log.debug("HotKey detected and added to local caffeine cache: {}", cacheKey);
            }
            return redisValue;
          })
          .orElseGet(() -> {
            log.warn("Caffeine L1 miss,Redis L2 miss: key={}", cacheKey);
            return null;
          });
      });
  }

  public void updateCaffeineIfPresent(String redisHashKey, String fieldKey, Object value) {
    String cacheKey = cacheKey(redisHashKey, fieldKey);

    try {
      Optional.ofNullable(caffeineCache.getIfPresent(cacheKey)).ifPresentOrElse(
        existing -> {
          caffeineCache.put(cacheKey, value);
          log.debug("refresh local caffeine cache expiration time: {}", cacheKey);
        },
        () -> log.debug("Key not found in local caffeine cache: {}", cacheKey)
      );
    } catch (Exception e) {
      caffeineCache.invalidate(cacheKey);
      log.error("update caffeine cache failed , invalidated: {}", cacheKey, e);
    }
  }

  @Scheduled(fixedDelayString = "${hotkey.decay-period:20}", timeUnit = TimeUnit.SECONDS)
  public void cleanHotKeys() {
    hotKeyDetector.fading();
    log.debug("HeavyKeeper count has decayed");
  }
}
