-- 点赞/取消点赞 + 点赞数更新 + 脏标记，原子执行
-- KEYS[1]: likes:set:{bizType}:{bizId}          (点赞用户集合)
-- KEYS[2]: likes:times:type:{bizType}           (点赞数 ZSet)
-- KEYS[3]: likes:changed:times:{bizType}        (点赞数变更脏标记集合)
-- ARGV[1]: userId
-- ARGV[2]: 是否点赞，1=点赞，0=取消点赞
-- ARGV[3]: bizId
-- 返回: 1=集合发生变化，0=重复操作(幂等)
local changed
if ARGV[2] == '1' then
    changed = redis.call('SADD', KEYS[1], ARGV[1])
else
    changed = redis.call('SREM', KEYS[1], ARGV[1])
end
if changed == 1 then
    local count = redis.call('SCARD', KEYS[1])
    redis.call('ZADD', KEYS[2], count, ARGV[3])-- 更新点赞数 ZSet
    redis.call('SADD', KEYS[3], ARGV[3])-- 标记脏数据
    return 1
end
return 0