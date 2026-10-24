package com.zhiwei.message.domain.vo;

import lombok.Data;

/**
 * 可私聊对象（群成员去重后的用户简要信息），用于前端发起私聊时选择。
 */
@Data
public class ChatUserVO {

    private Long id;

    private String name;

    private String icon;
}
