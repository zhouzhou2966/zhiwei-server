package com.zhiwei.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiwei.message.domain.po.ChatUserAck;
import org.apache.ibatis.annotations.Param;

public interface ChatUserAckMapper extends BaseMapper<ChatUserAck> {

    /**
     * 已读位点前推（只前进不后退）。
     * <p>用 SQL 原子 upsert 而不是「先查后改」：ack 可能从多端（多条 WebSocket 连接）
     * 并发到达，先查后改在竞态下会把位点回退。ON DUPLICATE KEY UPDATE 里用 IF
     * 比较，保证并发下位点单调前进。
     *
     * @return 影响行数
     */
    int upsertAck(@Param("ack") ChatUserAck ack);
}
