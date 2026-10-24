package com.zhiwei.remark.constants;

public interface RedisConstants {

    /** 点赞用户集合 key 前缀，完整格式：likes:set:{bizType}:{bizId}（bizType 参与 key，避免不同业务 id 冲突） */
    String LIKES_BIZ_KEY_PREFIX = "likes:set:";
    /** 点赞数 ZSet key 前缀，完整格式：likes:times:type:{bizType}，member=bizId，score=点赞数 */
    String LIKES_TIMES_KEY_PREFIX = "likes:times:type:";
    /** 点赞数变更脏标记集合 key 前缀，完整格式：likes:changed:times:{bizType}，定时任务消费后 SREM 清除 */
    String LIKES_CHANGED_TIMES_KEY_PREFIX = "likes:changed:times:";
    /** 持久化失败降级集合 key 前缀，完整格式：likes:sync:failed:{bizType}:{bizId}，member=userId，定时任务每日消费 */
    String LIKES_SYNC_FAILED_KEY_PREFIX = "likes:sync:failed:";

}