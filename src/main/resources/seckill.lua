local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local streamKey = 'stream.orders'

-- 1. 库存不存在或库存不足，直接失败。
local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

-- 2. 用户 ID 已在订单集合中，说明已经抢过同一张券。
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 3. Redis 内原子完成库存预扣、一人一单标记、订单消息入 Stream。
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', streamKey, '*',
        'id', orderId,
        'userId', userId,
        'voucherId', voucherId)

return 0
