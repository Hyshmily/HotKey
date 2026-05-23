package io.github.hyshmily.hotkey;

@FunctionalInterface
public interface HotKeyBroadcaster {

  void broadcastHotKey(String redisHashKey, String fieldKey);
}
