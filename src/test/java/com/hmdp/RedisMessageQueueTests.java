package com.hmdp;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Ignore("Redis MQ learning examples. Run manually when Redis is ready.")
@RunWith(SpringRunner.class)
@SpringBootTest
public class RedisMessageQueueTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testListQueue() {
        String queue = "queue:list:demo";
        stringRedisTemplate.opsForList().leftPush(queue, "hello-list");
        String message = stringRedisTemplate.opsForList().rightPop(queue, 5, TimeUnit.SECONDS);
        System.out.println("list message = " + message);
        // Simple and easy, but no durable ack or pending recovery.
    }

    @Test
    public void testPubSubPublish() {
        stringRedisTemplate.convertAndSend("channel:demo", "hello-pubsub");
        // Real-time fanout, but messages are not persisted when consumers are offline.
    }

    @Test
    public void testStreamSingleConsumer() {
        String stream = "stream.demo.single";
        Map<Object, Object> body = new HashMap<>();
        body.put("id", "1");
        body.put("value", "hello-stream");
        RecordId recordId = stringRedisTemplate.opsForStream().add(stream, body);
        System.out.println("xadd id = " + recordId);

        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(stream, ReadOffset.from("0"))
        );
        System.out.println("xread records = " + records);
    }

    @Test
    public void testStreamConsumerGroup() {
        String stream = "stream.demo.group";
        String group = "g1";
        String consumer = "c1";
        try {
            stringRedisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
        } catch (Exception e) {
            System.out.println("group may already exist: " + e.getMessage());
        }

        Map<Object, Object> body = new HashMap<>();
        body.put("id", "1");
        body.put("value", "hello-group");
        stringRedisTemplate.opsForStream().add(stream, body);

        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return;
        }

        MapRecord<String, Object, Object> record = records.get(0);
        System.out.println("xreadgroup record = " + record);
        stringRedisTemplate.opsForStream().acknowledge(stream, group, record.getId());

        List<MapRecord<String, Object, Object>> pendingRecords = stringRedisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.from("0"))
        );
        System.out.println("pending records = " + pendingRecords);
        // Consumer groups support multiple consumers, ack, pending-list and failure recovery.
    }
}
