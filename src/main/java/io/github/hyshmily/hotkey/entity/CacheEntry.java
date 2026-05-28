package io.github.hyshmily.hotkey.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CacheEntry {

  private final Object value;
  private final long version;
  private final long expireAtMs;
}
