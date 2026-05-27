package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    // 自定义纪元：2022-01-01 00:00:00 UTC，用较短的时间戳减少 ID 位数占用。
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 低 32 位作为每日递增序列号，高位保存相对时间戳。
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        // 1. 生成相对时间戳，保证整体趋势递增。
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 每个业务前缀每天一个 Redis 自增序列，避免长期累加导致低位溢出。
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 时间戳左移后拼接序列号，得到适合订单等业务使用的全局唯一 ID。
        return timestamp << COUNT_BITS | count;
    }
}
