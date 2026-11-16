-- 修「同一用户领第二张同款券被静默丢弃」
--
-- 背景：user_coupon 上有一条 uk_user_coupon(user_id, coupon_id) 唯一索引，
-- 意味着「一人一张模板只能有一条记录」，与 coupon.user_limit 这个「每人限领数量」
-- 字段直接冲突：user_limit=2 的券，第二张永远落不了库。
--
-- 更糟的是失败方式：Lua 已经把 Redis 库存扣了、用户计数加了、兑换码也 SETBIT 标记了，
-- 而 MQ 消费端因为「用户已持有该券」把这条消息当成重复投递直接 return，
-- 既没有异常也没有回滚 —— 表现是用户看到「兑换成功」，列表里却没有券，三条链路各自看都正常。
--
-- 改法：幂等粒度从「用户 + 券」换成「一次领取请求」。
--   · request_id 记录这一次领取请求（普通领取用 UUID，兑换码用 exchange:{序列号}）
--   · 唯一索引挂在 request_id 上，仍然能挡住「同一条消息被消费两次」
--   · 去掉 (user_id, coupon_id) 的唯一约束，让 user_limit 说了算
--
-- 代码侧对应：UserCouponServiceImpl#checkAndCreateUserCoupon（去重键与回查）、
--            receiveCoupon / exchangeCoupon（生成 requestId）、saveUserCoupon（落库 requestId）

ALTER TABLE user_coupon
    ADD COLUMN request_id varchar(64) NULL COMMENT '领取请求唯一标识，用于 MQ 消费幂等' AFTER coupon_id;

-- 老数据 request_id 为 NULL，MySQL 唯一索引允许多个 NULL，不影响历史记录
ALTER TABLE user_coupon
    ADD UNIQUE KEY uk_user_coupon_request (request_id);

ALTER TABLE user_coupon
    DROP INDEX uk_user_coupon;

-- 保留 (user_id, coupon_id) 的普通索引，按用户+券查列表仍然走索引
-- （idx_user_coupon 已存在，此处无需新建；若库里没有则补上）
-- ALTER TABLE user_coupon ADD INDEX idx_user_coupon (user_id, coupon_id);
