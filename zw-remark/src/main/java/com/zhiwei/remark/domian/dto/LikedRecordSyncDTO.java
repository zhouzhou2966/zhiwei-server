package com.zhiwei.remark.domian.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞记录持久化事件
 * <p>
 * 由点赞接口在 Redis 变更成功后发送到 MQ，
 * 由 {@code LikedRecordSyncListener} 消费并行级落库（点赞=插入一行，取消=删除一行）。
 */
@Data
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class LikedRecordSyncDTO {
    /** 点赞用户id */
    private Long userId;
    /** 点赞业务id */
    private Long bizId;
    /** 点赞业务类型 */
    private String bizType;
    /** 是否点赞，true=插入一行；false=删除一行 */
    private Boolean liked;
}
