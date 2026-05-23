package io.github.hyshmily.hotkey.broadcast;

import com.github.benmanes.caffeine.cache.Cache;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;

public class BroadcastListener {

  private static final Logger log = LoggerFactory.getLogger(BroadcastListener.class);

  private final Cache<String, Object> caffeineCache;
  private final RedisTemplate<String, Object> redisTemplate;

  public BroadcastListener(
    Cache<String, Object> caffeineCache,
    RedisTemplate<String, Object> redisTemplate
  ) {
    this.caffeineCache = caffeineCache;
    this.redisTemplate = redisTemplate;
  }

  @RabbitListener(queues = "#{@broadcastProperties.queueName}", ackMode = "MANUAL")
  public void handleHotKeyMessage(Channel channel, Message msg) throws IOException {
    long tag = msg.getMessageProperties().getDeliveryTag();
    try {
      processBroadcast(msg);
      channel.basicAck(tag, false);
    } catch (Exception e) {
      log.error("HotKey broadcast processing failed: body={}", new String(msg.getBody()), e);
      channel.basicNack(tag, false, false);
    }
  }

  private void processBroadcast(Message msg) {
    String redisHashKey = msg.getMessageProperties().getHeader("hk");
    String fieldKey = msg.getMessageProperties().getHeader("fk");
    String cacheKey = redisHashKey + ":" + fieldKey;

    Optional.ofNullable(caffeineCache.getIfPresent(cacheKey)).ifPresentOrElse(
      _ -> log.debug("HotKey broadcast message skipped: local caffeine cache already exists: {}", cacheKey),
      () -> {
        Object value = redisTemplate.opsForHash().get(redisHashKey, fieldKey);
        if (value != null) {
          caffeineCache.put(cacheKey, value);
          log.debug("HotKey broadcast loaded into local caffeine cache: {}", cacheKey);
        } else {
          log.warn("The broadcast HotKey does not exist in Redis: {}", cacheKey);
        }
      }
    );
  }
}
