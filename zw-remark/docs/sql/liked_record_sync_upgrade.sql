-- =============================================================
-- 点赞记录持久化方案二改造（事件驱动行级同步）配套 DDL
-- 作用：为幂等落库提供唯一索引地基
-- =============================================================

-- 1. 清理历史重复数据（每组保留 id 最小的一条），否则唯一索引创建会失败
DELETE r1 FROM liked_record r1
INNER JOIN liked_record r2
    ON r1.biz_id = r2.biz_id
   AND r1.biz_type = r2.biz_type
   AND r1.user_id = r2.user_id
   AND r1.id > r2.id;

-- 2. 唯一索引：biz + user 维度唯一，配合 insert ignore 实现幂等写入
--    （消费端重复消息、同步任务与事件并发写入，均不会产生重复行）
ALTER TABLE liked_record
    ADD UNIQUE INDEX uk_biz_type_biz_id_user_id (biz_id, biz_type, user_id);
