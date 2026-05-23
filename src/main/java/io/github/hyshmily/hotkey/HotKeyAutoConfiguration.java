package io.github.hyshmily.hotkey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.hyshmily.hotkey.algorithm.HeavyKeeper;
import io.github.hyshmily.hotkey.algorithm.TopK;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableConfigurationProperties(HotKeyProperties.class)
@EnableScheduling
public class HotKeyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public TopK hotKeyDetector(HotKeyProperties properties) {
    return new HeavyKeeper(
      properties.getTopK(),
      properties.getWidth(),
      properties.getDepth(),
      properties.getDecay(),
      properties.getMinCount()
    );
  }

  @Bean
  @ConditionalOnMissingBean
  public Cache<String, Object> hotLocalCache(HotKeyProperties properties) {
    return Caffeine.newBuilder()
      .maximumSize(properties.getLocalCacheMaxSize())
      .expireAfterWrite(properties.getLocalCacheTtlMinutes(), TimeUnit.MINUTES)
      .build();
  }

  @AutoConfiguration(after = HotKeyAutoConfiguration.class)
  @ConditionalOnClass(RedisTemplate.class)
  @EnableConfigurationProperties(HotKeyProperties.class)
  public static class HotKeyRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HotKeyCache hotKeyCache(
      TopK hotKeyDetector,
      Cache<String, Object> hotLocalCache,
      RedisTemplate<String, Object> redisTemplate,
      Optional<HotKeyBroadcaster> broadcaster
    ) {
      return new HotKeyCache(hotKeyDetector, hotLocalCache, redisTemplate, broadcaster);
    }
  }
}
