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
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), randomTime(time), unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
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

        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
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
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }

    private Long randomTime(Long time) {
        return time + ThreadLocalRandom.current().nextLong(1, 10);
    }
}
