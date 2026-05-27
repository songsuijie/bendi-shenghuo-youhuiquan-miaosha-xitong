package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 给缓存 TTL 加少量随机值，降低大量 key 同时过期导致的缓存雪崩风险。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), randomTime(time), unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 逻辑过期数据不依赖 Redis 删除 key，而是把过期时间写进 value，便于过期后先返回旧值再异步重建。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(randomTime(time))));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 命中正常 JSON 直接返回；命中空字符串说明数据库也没有该数据，是缓存穿透保护。
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            // 空对象短 TTL 缓存，拦截对不存在 ID 的重复访问。
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)) {
            if (json != null) {
                return null;
            }
            // 冷数据首次访问直接回源并写入逻辑过期缓存。
            R fresh = dbFallback.apply(id);
            if (fresh == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.setWithLogicalExpire(key, fresh, time, unit);
            return fresh;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData.getExpireTime() == null || redisData.getData() == null) {
            stringRedisTemplate.delete(key);
            R fresh = dbFallback.apply(id);
            if (fresh == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.setWithLogicalExpire(key, fresh, time, unit);
            return fresh;
        }

        R r = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 数据逻辑过期后，只有抢到互斥锁的线程负责重建缓存，其余线程继续返回旧值，保护数据库。
        String lockKey = lockKeyPrefix + id;
        String lockValue = UUID.randomUUID() + "-" + Thread.currentThread().getId();
        boolean isLock = tryLock(lockKey, lockValue);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    if (fresh == null) {
                        stringRedisTemplate.delete(key);
                        return;
                    }
                    this.setWithLogicalExpire(key, fresh, time, unit);
                } catch (Exception e) {
                    log.error("cache rebuild failed, key={}", key, e);
                } finally {
                    unlock(lockKey, lockValue);
                }
            });
        }

        return r;
    }

    private boolean tryLock(String key, String value) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key, String value) {
        // Lua 保证“判断锁持有人 + 删除锁”是原子操作，避免误删其他线程的锁。
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }

    private Long randomTime(Long time) {
        return time + ThreadLocalRandom.current().nextLong(1, 10);
    }
}
