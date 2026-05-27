package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_BLOCKING_QUEUE_SCRIPT;

    private static final String ORDER_STREAM_KEY = "stream.orders";
    private static final String ORDER_GROUP = "g1";
    private static final String ORDER_CONSUMER = "c1";
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_BLOCKING_QUEUE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_BLOCKING_QUEUE_SCRIPT.setLocation(new ClassPathResource("seckill_blocking_queue.lua"));
        SECKILL_BLOCKING_QUEUE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ApplicationContext applicationContext;

    @PostConstruct
    private void init() {
        // 应用启动时确保 Stream 消费组存在，再启动单线程消费者顺序处理异步订单。
        createStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 秒杀入口只做必要前置校验；库存扣减、一人一单和入队交给 Lua 保证原子性。
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已经结束");
        }

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // Lua 成功后会写入 Redis Stream，后台消费者再异步落库，接口先返回订单号。
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        int r = result == null ? 1 : result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }
        if (r != 0) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        return Result.ok(orderId);
    }

    /**
     * 本地 BlockingQueue 版本仅保留为演进对照；当前公开秒杀链路使用 Redis Stream，支持重启后的 pending-list 补偿。
     */
    private Result seckillVoucherWithBlockingQueue(Long voucherId) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_BLOCKING_QUEUE_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        int r = result == null ? 1 : result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        boolean success = orderTasks.offer(voucherOrder);
        if (!success) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        return Result.ok(orderId);
    }

    private class BlockingQueueVoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理阻塞队列秒杀订单异常", e);
                }
            }
        }
    }

    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 同步下单入口使用用户维度分布式锁，避免同一用户并发创建多笔同券订单。
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }

        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不能重复下单");
        }

        try {
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(redisIdWorker.nextId("order"));
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherOrder);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        // 数据库事务是最终一致性兜底：再次校验一人一单，并用 stock > 0 条件防止超卖。
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.warn("重复下单，userId={}, voucherId={}", userId, voucherId);
            return Result.fail("不能重复下单");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("库存扣减失败，voucherId={}", voucherId);
            return Result.fail("库存不足");
        }

        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }

    private void createStreamGroup() {
        try {
            // MKSTREAM 允许 stream 不存在时自动创建；BUSYGROUP 表示消费组已存在，可安全忽略。
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                connection.execute(
                        "XGROUP",
                        "CREATE".getBytes(StandardCharsets.UTF_8),
                        ORDER_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                        ORDER_GROUP.getBytes(StandardCharsets.UTF_8),
                        "0".getBytes(StandardCharsets.UTF_8),
                        "MKSTREAM".getBytes(StandardCharsets.UTF_8)
                );
                return null;
            });
        } catch (DataAccessException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private boolean handleVoucherOrder(VoucherOrder voucherOrder) {
        // 异步消费者再加一层用户锁，兜住重复消息或极端并发导致的重复处理。
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.warn("用户正在下单，userId={}", voucherOrder.getUserId());
            return false;
        }

        try {
            IVoucherOrderService proxy = applicationContext.getBean(IVoucherOrderService.class);
            Result result = proxy.createVoucherOrder(voucherOrder);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                return true;
            }
            // 如果数据库已经存在订单，说明之前处理成功但 ACK 失败，按幂等成功确认消息。
            int count = query()
                    .eq("user_id", voucherOrder.getUserId())
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .count();
            if (count > 0) {
                log.warn("订单已存在，按幂等成功处理，userId={}, voucherId={}",
                        voucherOrder.getUserId(), voucherOrder.getVoucherId());
                return true;
            }
            log.warn("秒杀订单处理失败，不确认 Stream 消息，orderId={}, error={}",
                    voucherOrder.getId(), result.getErrorMsg());
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private VoucherOrder toVoucherOrder(Map<Object, Object> valueMap) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(Long.valueOf(valueMap.get("id").toString()));
        voucherOrder.setUserId(Long.valueOf(valueMap.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(valueMap.get("voucherId").toString()));
        return voucherOrder;
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 正常消费只读取该消费者上次 ACK 之后的新消息。
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        // 没有新消息时顺手扫描 pending-list，补偿之前未 ACK 的订单。
                        handlePendingList();
                        continue;
                    }

                    MapRecord<String, Object, Object> record = list.get(0);
                    handleRecord(record);
                } catch (Exception e) {
                    log.error("处理秒杀订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 从 pending-list 最早的消息开始重试，直到没有遗留消息。
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        return;
                    }

                    MapRecord<String, Object, Object> record = list.get(0);
                    handleRecord(record);
                } catch (Exception e) {
                    log.error("处理 pending-list 秒杀订单异常", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void handleRecord(MapRecord<String, Object, Object> record) {
            VoucherOrder voucherOrder = toVoucherOrder(record.getValue());
            boolean success = handleVoucherOrder(voucherOrder);
            if (!success) {
                return;
            }
            // 只有数据库落单成功或幂等确认成功后才 ACK；失败消息留在 pending-list 继续补偿。
            RecordId recordId = record.getId();
            stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM_KEY, ORDER_GROUP, recordId);
        }
    }
}
