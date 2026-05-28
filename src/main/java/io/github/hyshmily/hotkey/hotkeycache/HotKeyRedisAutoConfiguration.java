package io.github.hyshmily.hotkey.hotkeycache;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.hyshmily.hotkey.HotKey;
import io.github.hyshmily.hotkey.algorithm.TopK;
import io.github.hyshmily.hotkey.broadcast.BroadcastPublisher;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(after = HotKeyAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
@ConditionalOnBean(RedisTemplate.class)
@EnableConfigurationProperties(HotKeyProperties.class)
public class HotKeyRedisAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public HotKeyCache hotKeyCache(
    TopK hotKeyDetector,
    Cache<String, Object> hotLocalCache,
    Cache<String, CompletableFuture<Object>> inflightLoads,
    Optional<BroadcastPublisher> broadcastPublisher,
    @Qualifier("hotKeyExecutor") Executor hotKeyExecutor,
    ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
    HotKeyProperties properties
  ) {
    Optional<StringRedisTemplate> redisTemplate =
      Optional.ofNullable(stringRedisTemplateProvider.getIfAvailable());
    return new HotKeyCache(
      hotKeyDetector,
      hotLocalCache,
      inflightLoads,
      broadcastPublisher,
      hotKeyExecutor,
      redisTemplate,
      properties.getInflightTimeoutSeconds(),
      properties.getSoftTtlMs(),
      properties.getRefreshConcurrency(),
      properties.getSoftExpireMaxSize(),
      properties.getSoftExpireTtlMinutes(),
      properties.getVersionKeyTtlMinutes()
    );
  }

}
