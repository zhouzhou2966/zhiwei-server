package com.zhiwei.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天群组。
 */
@Data
@TableName("chat_group")
public class ChatGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 群 ID，雪花算法生成
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 群名称
     */
    private String name;

    /**
     * 群主用户 ID
     */
    private Long ownerId;

    /**
     * 建群时间
     */
    private LocalDateTime createTime;
}
