package io.github.hyshmily.hotkey.broadcast;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.hyshmily.hotkey.HotKeyBroadcaster;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class BroadcastPublisher implements HotKeyBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(BroadcastPublisher.class);

  private final RabbitTemplate rabbitTemplate;
  private final BroadcastProperties properties;
  private final Cache<String, Object> caffeineCache;
  private final RedisTemplate<String, Object> redisTemplate;

  private Cache<String, Boolean> recentBroadcasts;

  public BroadcastPublisher(
    RabbitTemplate rabbitTemplate,
    BroadcastProperties properties,
    Cache<String, Object> caffeineCache,
    RedisTemplate<String, Object> redisTemplate
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
    this.caffeineCache = caffeineCache;
    this.redisTemplate = redisTemplate;
  }

  @PostConstruct
  public void init() {
    this.recentBroadcasts = Caffeine.newBuilder()
      .expireAfterWrite(properties.getDedupWindowSeconds(), TimeUnit.SECONDS)
      .maximumSize(500)
      .build();
  }

  private Optional<String> validateCacheKey(String redisHashKey, String fieldKey) {
    if (redisHashKey == null || redisHashKey.isBlank() || fieldKey == null || fieldKey.isBlank()) {
      log.warn("Invalid args: hk={}, fk={}", redisHashKey, fieldKey);
      return Optional.empty();
    }
    return Optional.of(redisHashKey + ":" + fieldKey);
  }

  @Override
  public void broadcastHotKey(String redisHashKey, String fieldKey) {
    validateCacheKey(redisHashKey, fieldKey).ifPresent(this::publishHotKey);
  }

  public void putAndBroadcast(String redisHashKey, String fieldKey, Object value) {
    validateCacheKey(redisHashKey, fieldKey).ifPresent(cacheKey ->
      runAfterCommit(() -> {
        redisTemplate.opsForHash().put(redisHashKey, fieldKey, value);
        caffeineCache.put(cacheKey, value);
        publishHotKey(cacheKey);
      })
    );
  }

  private void publishHotKey(String cacheKey) {
    Optional.ofNullable(recentBroadcasts.getIfPresent(cacheKey)).ifPresentOrElse(
      existing -> log.debug("Skip duplicate broadcast: {}", cacheKey),
      () -> {
        recentBroadcasts.put(cacheKey, Boolean.TRUE);
        rabbitTemplate.convertAndSend(properties.getExchangeName(), "", cacheKey);
        log.debug("HotKey broadcast: {}", cacheKey);
      }
    );
  }

  private void runAfterCommit(Runnable task) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            task.run();
          }
        }
      );
      return;
    }
    task.run();
  }
}
