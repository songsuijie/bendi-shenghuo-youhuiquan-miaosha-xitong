-- 先比较锁中的持有人标识，再删除锁；整个过程在 Lua 内原子执行。
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end

return 0
