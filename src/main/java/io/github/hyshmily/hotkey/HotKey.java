package io.github.hyshmily.hotkey;

import io.github.hyshmily.hotkey.algorithm.Item;
import io.github.hyshmily.hotkey.algorithm.TopK;
import io.github.hyshmily.hotkey.hotkeycache.HotKeyCache;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class HotKey {

  private final HotKeyCache hotKeyCache;
  private final TopK topKAlgorithm;

  public boolean isHotKey(String cacheKey) {
    return hotKeyCache.isHotKey(cacheKey);
  }

  public List<Item> returnHotKeys() {
    return topKAlgorithm.list();
  }

  public BlockingQueue<Item> returnExpelledHotKeys() {
    return topKAlgorithm.expelled();
  }

  public long returnTotalDataStreams() {
    return topKAlgorithm.total();
  }

  public <T> Optional<T> peek(String cacheKey) {
    return hotKeyCache.peek(cacheKey);
  }

  public <T> Optional<T> get(String cacheKey, Supplier<T> reader) {
    return hotKeyCache.get(cacheKey, reader);
  }

  public <T> Optional<T> get(String cacheKey, Supplier<T> reader, long ttlMs) {
    return hotKeyCache.get(cacheKey, reader, ttlMs);
  }

  public <T> Optional<T> getWithSoftExpire(String cacheKey, Supplier<T> reader) {
    return hotKeyCache.getWithSoftExpire(cacheKey, reader);
  }

  public <T> Optional<T> getWithSoftExpire(String cacheKey, Supplier<T> reader, long softTtlMs) {
    return hotKeyCache.getWithSoftExpire(cacheKey, reader, softTtlMs);
  }

  public void invalidate(String cacheKey) {
    hotKeyCache.invalidate(cacheKey);
  }

  public void invalidateAll(String... cacheKeys) {
    invalidateAll(Arrays.asList(cacheKeys));
  }

  public void invalidateAll(Collection<String> cacheKeys) {
    hotKeyCache.invalidateAll(cacheKeys);
  }

  public <T> void putThrough(String cacheKey, T value, Runnable redisWriter) {
    hotKeyCache.putThrough(cacheKey, value, redisWriter);
  }

  public <T> void putThrough(String cacheKey, T value, Runnable redisWriter, long ttlMs) {
    hotKeyCache.putThrough(cacheKey, value, redisWriter, ttlMs);
  }

  public void putBeforeInvalidate(String cacheKey, Runnable redisMutation) {
    hotKeyCache.putInvalidate(cacheKey, redisMutation);
  }
}
