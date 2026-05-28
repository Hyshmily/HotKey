# HotKey

[![JitPack](https://jitpack.io/v/Hyshmily/HotKey.svg)](https://jitpack.io/#Hyshmily/HotKey) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0) [![Java](https://img.shields.io/badge/Java-25-orange)](https://openjdk.java.net/) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen)](https://spring.io/projects/spring-boot)

[**中文版**](README.zh.md)

HotKey — HeavyKeeper Top-K hot key detection + multi-level cache auto-warming + distributed broadcast Spring Boot Starter

HotKey is not a general-purpose local cache — it's a lightweight hot key auto-detection & multi-level cache warming framework with optional distributed broadcast.

Most local cache solutions store every accessed key in Caffeine. This works fine with small data, but under millions of keys:

- **Memory waste** — most keys are read once and never accessed again
- **Broadcast storm** — full cache invalidation requires full broadcast at scale

HotKey takes a different approach — **cache only the hot keys.**

It uses [HeavyKeeper](https://github.com/go-kratos/aegis) (a Count-Min Sketch variant) to probabilistically detect access frequency. Only keys that enter the Top-K set are promoted into the local Caffeine L1, and optionally synchronized across instances via RabbitMQ fanout. Non-hot key reads are delegated back to the caller via `Supplier<T>` — the framework makes no assumption about your data source.

### When to use

| Suitable                                            | Not suitable                                     |
| --------------------------------------------------- | ------------------------------------------------ |
| Read-heavy workloads (String / List / Set / ZSet)   | Write-heavy / atomic operations (seckill, Lua)   |
| Large key space with Pareto distribution            | Small key space (< 200), manual Caffeine is fine |
| Read-many-write-few, eventually consistent          | Strong read-after-write consistency required     |
| Spring Boot 3.x + Java 17+                          | Non-Spring-Boot projects                         |
| Optional Redis + optional RabbitMQ (multi-instance) |                                                  |

> [!Important]
> This is an experience module summarized by the author during development. Reliability and stability in production cannot be guaranteed. For a complete production-ready hot key auto-detection and higher-precision version, please refer to [hotkey](https://gitee.com/jd-platform-opensource/hotkey)

> See [CHANGELOG.md](CHANGELOG.md) for version history.

## Features

- **HeavyKeeper Algorithm** — probabilistic top-k detection with Count-Min Sketch + exponential conflict decay
- **Three-Level Cache** — Caffeine (L1) → Redis (L2, optional) → DB fallback, with automatic hot-key promotion
- **In-Flight Dedup** — concurrent L1 miss requests share a single Redis read via `Caffeine<key, CompletableFuture>`

  > **Note:** Ensure `hotkey.inflight-ttl-seconds` exceeds the slowest Redis response time for your workload, or the cache entry may expire before the future completes, causing duplicate Redis reads.
  > Also ensure `hotkey.inflight-timeout-seconds` < `hotkey.inflight-ttl-seconds`. On timeout, `loadSingleflight` returns `Optional.empty()` — the caller should handle via DB fallback.

- **Soft Expire (Logical Expiration)** — return stale L1 value immediately while asynchronously refreshing in the background; lower p99 at the cost of short-lived staleness. **Fully replaces traditional Redis-side logical expiration** (`RedisData{data, expireTime}` wrapper pattern) — Redis stores raw values, HotKey manages staleness at the L1 Caffeine level
- **Redis Collections** — `putBeforeInvalidate` for List/Set/ZSet incremental writes; no `putThrough` needed
- **Hot Key Broadcast** — optional RabbitMQ fanout to synchronize hot keys across instances
- **Configurable Thread Pool** — dedicated `TaskExecutor` with bounded queue
- **Spring Boot Auto-Configuration** — drop-in dependency, zero boilerplate

## Architecture

```
┌──────────────┐   L1 hit + add(key,1) ┌──────────────┐
│   Request    │ ────────────────────→ │  Caffeine L1 │
│              │ ←──────────────────── │  (local)     │
└──────┬───────┘   Optional.of(value)  └──────┬───────┘
       │ L1 miss           (auto unwrap       │ isHotKey()?
       ↓ (inflight dedup)  CacheEntry)        ↓
┌──────────────┐   redisReader     ┌───────────────┐
│  L2 Storage  │ ←───────────────  │     TopK      │
│  (pluggable) │ ───────────────→  │  (interface)  │
└──┬───────┬───┘  add(key,1)       ├───────────────┤
   │ hit   │ null                  │ add()→Result  │
   ↓       ↓                       │ list()        │
Optional   Optional.empty()        │ total()       │
.of(value)   r.isEmpty() → DB      │ contains()    │
                                   │ expelled()    │
                                   │ fading()      │
                                   └───────┬───────┘
                                           │ isHotKey()
                                           ↓
                              Caffeine.put(key,
                                CacheEntry(value, version=0L, expireAtMs))
                              + broadcastHotKey with version header
```

Write path (user-initiated):
`putThrough(cacheKey, value, writer)`
├─ `writer.run()` — L2 write (caller-supplied Runnable)
├─ `nextVersion(cacheKey)` — Redis INCR → monotonic version
├─ Caffeine.put(cacheKey, CacheEntry(value, version, expireAtMs))
└─ RabbitMQ fanout with version header (if enabled)

For incremental collection mutations (LPUSH, SADD, ZADD):
`putBeforeInvalidate(cacheKey, mutation)`
├─ `mutation.run()` — L2 write (caller-supplied Runnable)
├─ `nextVersion(cacheKey)` — Redis INCR → monotonic version
├─ Caffeine local cache **invalidate**
└─ RabbitMQ fanout with version header (if enabled)

> **Note:** Between `mutation.run()` and L1 cache invalidation there is a ~1ms window where a concurrent `get()` may hit the L1 stale value. This is a deliberate trade-off — invalidating before the mutation would cause a worse race where `get()` re-populates L1 with old Redis data. The window is bounded to a single Redis round-trip (`nextVersion` call).

Soft Expire Read Path (`getWithSoftExpire`):

```
         ┌──────────────┐   L1 hit ┌───────────────┐
         │   Request    │ ───────→ │ softExpireAt  │
         │              │ ←─────── │  time check   │
         └──────┬───────┘  stale   └───────┬───────┘
                │ soft expired?            │ expired?
                ↓ true                     ↓ yes
           Return stale          triggerAsyncRefresh
           value +                     ├─ refreshLimiter.tryAcquire()
           check TopK                  └─ Async L2 → Caffeine.put
                │                            + update softExpireAt
                 │ L1 miss (falls through to normal path)
                 ↓
            loadSingleflight(cacheKey, redisReader)
            (see Normal Read Path above)
            Caffeine.put(key, CacheEntry(value, 0L, keepExpireAt))
```

## Degradation

HotKey forms a three-level degradation chain through the `supplier` callback:

```
hotKey.get(key, supplier)
  ├─ L1(Caffeine) HIT → return directly
  ├─ L1 MISS → supplier()
  │    ├─ Returns data → hot key? → write L1 + return
  │    ├─ Returns null → Optional.empty() → caller's orElseGet/orElseThrow
  │    └─ Throws → loadSingleflight catches → Optional.empty() → caller's fallback
  └─ HotKey itself throws → skip L1, supplier called directly
```

Component failure behavior:

| Failed component           | Impact                                                     | Recovery                            |
| -------------------------- | ---------------------------------------------------------- | ----------------------------------- |
| HotKey itself              | Skips L1, falls through to supplier directly               | Restart app                         |
| L2 backend (Redis/DB/API)  | Every request hits caller's fallback                       | Auto-recover on backend restoration |
| L1 Caffeine OOM / eviction | Individual keys evicted, next read re-fetches via supplier | Automatic (Caffeine internal)       |

> The caller is always responsible for handling `Optional.empty()` — HotKey never hides backend failures.

## Quick Start

### 1. Add dependency (JitPack)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.github.hyshmily</groupId>
    <artifactId>hotkey</artifactId>
    <version>1.0.8-SNAPSHOT</version>
</dependency>
```

Use a Git tag as the version (e.g. `1.0.8-SNAPSHOT`). Redis and RabbitMQ dependencies are optional — include them only if you need the corresponding features.

### 2. Configure

```yaml
hotkey:
  top-k: 100
  width: 100000
  depth: 5
  decay: 0.92
  min-count: 10
  local-cache-max-size: 1000
  local-cache-ttl-minutes: 5
  broadcast:
    enabled: false
    exchange-name: hotkey.broadcast.exchange
    queue-prefix: hotkey.broadcast
```

### 3. Use

> **Note:** From v1.0.2 includes a **breaking change** — `get(hk, fk)` and `putAndBroadcast(hk, fk, val)` are removed. The library is now decoupled from `RedisTemplate`; callers supply their own read/write callbacks via `Supplier<T>` / `Runnable`.

**A. Pure local cache (no L2)**

```java
@Autowired
private HotKey hotKey;

Optional<String> r = hotKey.peek("user:123"); // Caffeine L1 + hot key detection only
```

Calls `peek(cacheKey)` — returns `Optional.empty()` if L1 miss, skips secondary storage entirely.

**B. Two-level cache (Redis or any backend)**

```java
@Autowired
private HotKey hotKey;
@Autowired
private RedisTemplate<String, Object> redisTemplate;

Optional<String> r = hotKey.get("user:123", () -> redisTemplate.opsForValue().get("user:123"));

hotKey.putThrough("user:123", newValue, () -> redisTemplate.opsForValue().set("user:123", newValue));
```

**C. Database fallback**

```java
Optional<String> r = hotKey.get("user:123", () -> redisTemplate.opsForValue().get("user:123"));
if (r.isEmpty()) {
    String value = userService.getById(123);   // DB fallback
    redisTemplate.opsForValue().set("user:123", value);
}
```

**D. Helper bean to avoid repetitive lambdas**

```java
@Component
public class RedisHotKeyHelper {

  @Autowired
  private HotKey hotKey;

  @Autowired
  private RedisTemplate<String, Object> redisTemplate;

  public <T> Optional<T> get(String key) {
    return hotKey.get(key, () -> redisTemplate.opsForValue().get(key));
  }

  public void set(String key, Object value) {
    hotKey.putThrough(key, value, () -> redisTemplate.opsForValue().set(key, value));
  }
}
```

**E. Custom L2 (non-Redis)**

```java
// Use MySQL, remote API, or any data source as L2
Optional<User> r = hotKey.get("user:123", () -> userMapper.selectById(123));

User user = r.orElseGet(() -> createDefaultUser());
```

**F. Redis collections (List, Set, ZSet)**

`putThrough` requires the full new value for L1 update, but collection incremental operations (LPUSH, SADD, ZADD) modify only a single element — the caller cannot know the full collection state. Use `putBeforeInvalidate` to invalidate L1 after the mutation; the next `get()` re-fetches from Redis, ensuring consistency.

```java
@Component
public class CollectionHotKeyCache {

  @Autowired
  private HotKey hotKey;

  @Autowired
  private RedisTemplate<String, Object> redisTemplate;

  public Boolean sIsMember(String key, Object member) {
    return hotKey.get(key + "::member::" + member, () -> redisTemplate.opsForSet().isMember(key, member));
  }

  @SuppressWarnings("unchecked")
  public Set<Object> sMembers(String key) {
    return hotKey.get(key, () -> redisTemplate.opsForSet().members(key));
  }

  public void sAdd(String key, Object... members) {
    hotKey.putBeforeInvalidate(key, () -> redisTemplate.opsForSet().add(key, members));
  }

  public List<Object> lRange(String key, long start, long end) {
    String cacheKey = key + "::range::" + start + "::" + end;
    return hotKey.get(cacheKey, () -> redisTemplate.opsForList().range(key, start, end));
  }

  public Double zScore(String key, Object member) {
    return hotKey.get(key + "::score::" + member, () -> redisTemplate.opsForZSet().score(key, member));
  }
}
```

**G. Soft expire — replaces traditional logical expiration**

Soft expire returns the stale L1 value immediately while asynchronously refreshing (stale-while-revalidate). Unlike traditional logical expiration (which embeds `expireTime` in Redis values), HotKey manages staleness purely at the L1 Caffeine level — **Redis stores raw values, no wrappers needed**.

| Aspect | Traditional Logical Expiration | HotKey Soft Expire |
|---|---|---|
| Expiry storage | Embedded in Redis value (`RedisData{data, expireTime}`) | L1 Caffeine metadata (`softExpireAt`) |
| Stale delivery | Returns old data | Returns old L1 value |
| Async rebuild | Redis distributed lock + custom thread pool | Singleflight (local) + `hotKeyExecutor` + `refreshLimiter` |
| Redis format | Wrapped JSON | Raw value (clean, no wrapper) |
| DB fallback | Manual locking logic | Native `orElseGet` / `orElseThrow` |

```java
// Traditional approach (no longer needed):
//   redisData.setExpireTime(LocalDateTime.now().plusSeconds(30L));
//   stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

// HotKey: Redis stores raw value, soft expire managed at L1
Optional<String> r = hotKey.getWithSoftExpire("user:123",
    () -> redisTemplate.opsForValue().get("user:123"));
// L1 hit + soft expired → returns stale value + triggers async refresh
// L1 miss → singleflight load (same as get())

// Per-call custom soft TTL (overrides global hotkey.soft-ttl-ms)
Optional<String> r2 = hotKey.getWithSoftExpire("user:456",
    () -> redisTemplate.opsForValue().get("user:456"), 3000);
```

Database fallback (no distributed lock required):

```java
User user = hotKey.getWithSoftExpire("shop:" + shopId,
        () -> redisTemplate.opsForValue().get("shop:" + shopId))
    .orElseGet(() -> {
        User u = userMapper.selectById(shopId);
        if (u != null) {
            redisTemplate.opsForValue().set("shop:" + shopId, JSONUtil.toJsonStr(u));
        }
        return u;
    });
```

Configuration:

```yaml
hotkey:
  soft-ttl-ms: 5000               # enable soft expire with 5s soft TTL (default 0 = disabled)
  refresh-concurrency: 50         # limit concurrent async refreshes
```

**H. Per-entry hard TTL**

By default, all entries share the global `hotkey.local-cache-ttl-minutes`. Use `get(key, reader, ttlMs)` or `putThrough(key, value, writer, ttlMs)` to set a per-entry Caffeine hard TTL. Entries without a custom TTL remain governed by the global setting.

```java
// 5-minute hard TTL for this key
Optional<Shop> shop = hotKey.get("shop:" + shopId,
    () -> redisTemplate.opsForValue().get("shop:" + shopId),
    TimeUnit.MINUTES.toMillis(5));

// 30-second hard TTL via putThrough
hotKey.putThrough("weather:" + city, weatherData,
    () -> redisTemplate.opsForValue().set("weather:" + city, weatherData),
    TimeUnit.SECONDS.toMillis(30));
```

> **Note:** When combined with `getWithSoftExpire`, the per-entry hard TTL is preserved across background refreshes. If a key was loaded with a custom `ttlMs`, subsequent soft-expire refreshes will keep the original hard expiry time rather than resetting to the global default.

## HotKey API Reference

The recommended entry point is the `HotKey` facade (auto-configured as a Spring bean). Beyond the `get`/`peek`/`putThrough`/`putBeforeInvalidate` shown above, it exposes:

| Method                                      | Description                                                                                                 |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `get(key, reader, ttlMs)`                   | Read with per-entry Caffeine hard TTL (ms)                                                                  |
| `putThrough(key, value, writer, ttlMs)`     | Write-through with per-entry Caffeine hard TTL (ms)                                                         |
| `putBeforeInvalidate(key, mutation)`        | Write-then-invalidate for collection ops (LPUSH, SADD, ZADD)                                                |
| `getWithSoftExpire(key, reader, softTtlMs)` | Soft expire with per-call soft TTL (ms)                                                                     |
| `isHotKey(cacheKey)`                        | Check if a key is in the current Top-K hot set (O(1))                                                       |
| `invalidate(cacheKey)`                      | Invalidate a single key from all cache layers                                                               |
| `invalidateAll(cacheKeys...)`               | Varargs overload — invalidate multiple keys at once                                                         |
| `invalidateAll(Collection)`                 | Collection overload                                                                                         |
| `returnHotKeys()`                           | Snapshot of current Top-K entries (key + count)                                                             |
| `returnExpelledHotKeys()`                   | Access the expelled hot key queue (recently evicted from Top-K); drained periodically by internal scheduler |
| `returnTotalDataStreams()`                  | Total number of reads passed through HeavyKeeper                                                            |

> **Note:** `invalidate()` generates a monotonic version via Redis `INCR` and broadcasts with it, allowing version-aware peers to skip stale updates. `invalidateAll()` does **not** call `INCR` — it broadcasts with version `0L`, so all peers apply it regardless of their current version.

## TTL Reference

| Method | TTL means | Default |
|---|---|---|
| `get(key, reader, ttlMs)` | Caffeine hard TTL — entry evicted after this time | 3rd param required |
| `getWithSoftExpire(key, reader)` | Caffeine soft TTL — stale value returned, async refresh triggered | global `hotkey.soft-ttl-ms` |
| `getWithSoftExpire(key, reader, softTtlMs)` | Same as above, per-call override | caller supplied |
| `putThrough(key, value, writer, ttlMs)` | Caffeine hard TTL for written entry | 4th param optional, falls back to global `local-cache-ttl-minutes` |
| Global `hotkey.local-cache-ttl-minutes` | Default hard TTL for all entries without per-call TTL | `5` minutes |
| Global `hotkey.soft-ttl-ms` | Default soft TTL when no per-call value given | `0` (disabled) |
| Global `hotkey.local-cache-access-ttl-minutes` | Access-based hard TTL (resets on every read), supplements `local-cache-ttl-minutes` | `0` (disabled) |

## Broadcast

```yaml
hotkey:
  broadcast:
    enabled: true
```

Each instance declares its own queue (`hotkey.broadcast:<pod-id>`) bound to a fanout exchange. When a hot key is promoted, the instance broadcasts the key. Peers load the value from Redis on first broadcast miss. Invalidations remove the local cache entry immediately.

## Monitoring

When `spring-boot-starter-actuator` is on the classpath, the HotKey endpoint is automatically registered at `/actuator/hotkey`.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,hotkey
```

```json
{
  "topK": [{ "key": "cache:shop:17", "count": 1523 }],
  "topKCount": 1,
  "totalRequests": 158392,
  "l1CacheSize": 87,
  "l1MaxSize": 1000,
  "inflightSize": 3,
  "recentlyExpelled": ["cache:shop:5", "cache:shop:99"]
}
```

| Field                       | Description                                    |
| --------------------------- | ---------------------------------------------- |
| `topK`                      | Current Top-K hot keys (descending by count)   |
| `topKCount`                 | Number of hot keys in Top-K set                |
| `totalRequests`             | Total requests passed through HotKey detection |
| `l1CacheSize` / `l1MaxSize` | L1 Caffeine current size / max limit           |
| `inflightSize`              | Current in-flight dedup requests               |
| `recentlyExpelled`          | Recently evicted keys from Top-K (up to 10)    |

## Configuration Reference

| Property                                | Default                     | Description                                                                                                                                                   |
| --------------------------------------- | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `hotkey.top-k`                          | `100`                       | Top-K set size                                                                                                                                                |
| `hotkey.width`                          | `50000`                     | Count-Min Sketch width                                                                                                                                        |
| `hotkey.depth`                          | `5`                         | Count-Min Sketch depth (rows)                                                                                                                                 |
| `hotkey.decay`                          | `0.92`                      | Conflict decay factor                                                                                                                                         |
| `hotkey.min-count`                      | `10`                        | Minimum count threshold for hot key                                                                                                                           |
| `hotkey.local-cache-max-size`           | `1000`                      | Caffeine L1 max entries                                                                                                                                       |
| `hotkey.local-cache-ttl-minutes`        | `5`                         | Caffeine L1 TTL in minutes                                                                                                                                    |
| `hotkey.inflight-max-size`              | `50000`                     | In-flight dedup max entries                                                                                                                                   |
| `hotkey.inflight-ttl-seconds`           | `5`                         | In-flight dedup entry TTL (must exceed slowest Redis response)                                                                                                |
| `hotkey.inflight-timeout-seconds`       | `3`                         | Inflight load timeout (must be < inflight-ttl-seconds). On timeout returns `Optional.empty()` — caller should fallback to DB                                  |
| `hotkey.executor-core-pool-size`        | `8`                         | Thread pool core size                                                                                                                                         |
| `hotkey.executor-max-pool-size`         | `32`                        | Thread pool max size                                                                                                                                          |
| `hotkey.executor-queue-capacity`        | `2000`                      | Thread pool queue capacity                                                                                                                                    |
| `hotkey.broadcast.enabled`              | `false`                     | Enable RabbitMQ broadcast                                                                                                                                     |
| `hotkey.broadcast.exchange-name`        | `hotkey.broadcast.exchange` | Fanout exchange name                                                                                                                                          |
| `hotkey.broadcast.queue-prefix`         | `hotkey.broadcast`          | Queue name prefix                                                                                                                                             |
| `hotkey.broadcast.dedup-window-seconds` | `10`                        | Broadcast dedup window (seconds)                                                                                                                              |
| `hotkey.broadcast.dedup-max-size`       | `10000`                     | Broadcast dedup max entries                                                                                                                                   |
| `hotkey.decay-period`                   | `20`                        | (Deprecated) Decay period in seconds, backward compatibility only                                                                                             |
| `hotkey.broadcast.instance-id`          | `-`                         | Auto-generated from `server.port` + hostname/UUID (not configurable via YAML)                                                                                 |
| `hotkey.soft-ttl-ms`                    | `0`                         | Soft expire TTL (ms), 0 = disabled                                                                                                                            |
| `hotkey.soft-expire-max-size`           | `50000`                     | Soft expire timestamp cache max entries                                                                                                                       |
| `hotkey.soft-expire-ttl-minutes`        | `60`                        | Soft expire timestamp cache internal entry TTL (minutes)                                                                                                      |
| `hotkey.refresh-concurrency`            | `100`                       | Max concurrent async refreshes for soft expire                                                                                                                |
| `hotkey.version-key-ttl-minutes`        | `60`                        | Redis version key TTL (minutes), 0 = no expire                                                                                                                |
| `hotkey.local-cache-access-ttl-minutes` | `0`                         | Caffeine L1 access-based TTL (minutes), 0 = disabled. Supplements write-based TTL                                                                             |
| `hotkey.broadcast.concurrent-consumers` | `3`                         | Number of concurrent RabbitMQ consumers for broadcast queue                                                                                                   |
| `hotkey.broadcast.scheduler-pool-size`  | `4`                         | Thread pool size for async broadcast jitter delay scheduling                                                                                                  |
| `hotkey.broadcast.warmup-jitter-ms`     | `100`                       | Random jitter (ms) before processing broadcast messages to prevent thundering herd                                                                            |
| `hotkey.scheduling.enabled`             | `true`                      | Enable internal scheduler for HeavyKeeper decay and expelled queue drain. Set to `false` if you use your own `@EnableScheduling` or don't need periodic decay |

## Modules

| Module                 | Dependency                                                    | Auto-Config                      |
| ---------------------- | ------------------------------------------------------------- | -------------------------------- |
| `algorithm`            | none                                                          | always                           |
| `cache` (Redis)        | `spring-boot-starter-data-redis`                              | `@ConditionalOnClass`            |
| `broadcast` (RabbitMQ) | `spring-boot-starter-amqp` + `spring-boot-starter-data-redis` | `@ConditionalOnClass` + property |
| `actuator`             | `spring-boot-starter-actuator`                                | `@ConditionalOnClass`            |

## License

Apache License 2.0
