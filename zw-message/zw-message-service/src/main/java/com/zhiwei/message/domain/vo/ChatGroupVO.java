package com.zhiwei.message.domain.vo;

import lombok.Data;

/**
 * 群概要（会话侧栏用）
 */
@Data
public class ChatGroupVO {

    private Long id;

    private String name;

    /**
     * 群成员数
     */
    private Long memberCount;
}
