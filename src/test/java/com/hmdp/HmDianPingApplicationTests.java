package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Ignore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static com.hmdp.utils.RedisConstants.UV_KEY;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testRedissonQuickStart() throws InterruptedException {
        RLock lock = redissonClient.getLock("lock:redisson:quickstart");
        boolean locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
        if (!locked) {
            System.out.println("get lock failed");
            return;
        }
        try {
            System.out.println("get lock success");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Test
    public void testRedissonReentrantLock() {
        RLock lock = redissonClient.getLock("lock:redisson:reentrant");
        lock.lock();
        try {
            System.out.println("outer lock acquired");
            redissonReentrantMethod(lock);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void redissonReentrantMethod(RLock lock) {
        lock.lock();
        try {
            System.out.println("inner lock acquired");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Test
    public void testRedissonWatchDog() throws InterruptedException {
        RLock lock = redissonClient.getLock("lock:redisson:watchdog");
        boolean locked = lock.tryLock(1, TimeUnit.SECONDS);
        if (!locked) {
            System.out.println("get lock failed");
            return;
        }
        try {
            // No explicit lease time is set, so Redisson WatchDog keeps renewing the lock.
            Thread.sleep(1000);
            System.out.println("watchdog lock still held: " + lock.isHeldByCurrentThread());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Test
    public void testRedissonMultiLock() throws InterruptedException {
        RLock lock1 = redissonClient.getLock("lock:multi:1");
        RLock lock2 = redissonClient.getLock("lock:multi:2");
        // In real multiLock usage, these locks should come from independent Redis nodes.
        RLock multiLock = redissonClient.getMultiLock(lock1, lock2);
        boolean locked = multiLock.tryLock(1, 10, TimeUnit.SECONDS);
        if (!locked) {
            System.out.println("get multiLock failed");
            return;
        }
        try {
            System.out.println("get multiLock success");
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task =()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = "+(end-begin));
    }

    @Test
    public void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    public void loadShopData() {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    public void testGeoSearch() {
        System.out.println(stringRedisTemplate.opsForGeo().radius(
                SHOP_GEO_KEY + 1,
                new Circle(new Point(120.149192, 30.316078), new Distance(5, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(10)
        ));
    }

    @Test
    public void testBitMap() {
        String key = "sign:test";
        stringRedisTemplate.opsForValue().setBit(key, 0, true);
        stringRedisTemplate.opsForValue().setBit(key, 1, true);
        stringRedisTemplate.opsForValue().setBit(key, 3, true);
        System.out.println("day1 = " + stringRedisTemplate.opsForValue().getBit(key, 0));
        System.out.println(stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(4)).valueAt(0)
        ));
    }

    @Test
    public void testHyperLogLog() {
        String key = UV_KEY + "demo";
        stringRedisTemplate.opsForHyperLogLog().add(key, "u1", "u2", "u3", "u1");
        System.out.println("uv = " + stringRedisTemplate.opsForHyperLogLog().size(key));
    }

    @Test
    public void testMillionUV() {
        String key = UV_KEY + "test";
        stringRedisTemplate.delete(key);
        for (int i = 0; i < 1000; i++) {
            String[] values = new String[1000];
            int start = i * 1000;
            for (int j = 0; j < 1000; j++) {
                values[j] = "user_" + (start + j);
            }
            stringRedisTemplate.opsForHyperLogLog().add(key, values);
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size(key);
        System.out.println("expected = 1000000, actual = " + count + ", error = " + Math.abs(1000000 - count));
    }

}
