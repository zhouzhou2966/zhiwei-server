package com.zhiwei.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 群成员关系。
 * <p>跨实例推送群消息时，需要用「群 → 成员列表」反查每个成员所在的实例
 * （Redis 路由表 HMGET），本表是该反查的数据源。
 */
@Data
@TableName("chat_group_member")
public class ChatGroupMember implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键，雪花算法生成
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 群 ID
     */
    private Long groupId;

    /**
     * 成员用户 ID
     */
    private Long userId;

    /**
     * 入群时间
     */
    private LocalDateTime joinTime;
}
