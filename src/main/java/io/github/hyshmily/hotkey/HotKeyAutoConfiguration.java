package io.github.hyshmily.hotkey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.hyshmily.hotkey.algorithm.HeavyKeeper;
import io.github.hyshmily.hotkey.algorithm.TopK;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
        properties.getMinCount());
  }

  @Bean
  @ConditionalOnMissingBean
  public Cache<String, Object> hotLocalCache(HotKeyProperties properties) {
    return Caffeine.newBuilder()
        .maximumSize(properties.getLocalCacheMaxSize())
        .expireAfterWrite(properties.getLocalCacheTtlMinutes(), TimeUnit.MINUTES)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  public Cache<String, CompletableFuture<Object>> inflightLoads(HotKeyProperties properties) {
    return Caffeine.newBuilder()
        .maximumSize(properties.getInflightMaxSize())
        .expireAfterWrite(properties.getInflightTtlSeconds(), TimeUnit.SECONDS)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  public Executor hotKeyExecutor(HotKeyProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.getExecutorCorePoolSize());
    executor.setMaxPoolSize(properties.getExecutorMaxPoolSize());
    executor.setQueueCapacity(properties.getExecutorQueueCapacity());
    executor.setThreadNamePrefix("hotkey-");
    executor.initialize();
    return executor;
  }

  @Bean
  @ConditionalOnMissingBean
  public HotKeyCache hotKeyCache(
      TopK hotKeyDetector,
      Cache<String, Object> hotLocalCache,
      Cache<String, CompletableFuture<Object>> inflightLoads,
      Optional<io.github.hyshmily.hotkey.broadcast.BroadcastPublisher> broadcastPublisher,
      Executor hotKeyExecutor,
      HotKeyProperties properties) {
    return new HotKeyCache(
        hotKeyDetector, hotLocalCache, inflightLoads, broadcastPublisher, hotKeyExecutor,
        properties.getInflightTimeoutSeconds(),
        properties.getSoftTtlMs(),
        properties.getRefreshConcurrency(),
        properties.getSoftExpireMaxSize(),
        properties.getSoftExpireTtlMinutes());
  }

}
