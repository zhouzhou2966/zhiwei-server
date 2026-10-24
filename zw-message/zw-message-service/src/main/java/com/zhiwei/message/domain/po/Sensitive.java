package com.zhiwei.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 违禁词表，DFA 字典树的词源。
 * <p>表名必须用反引号包裹：SENSITIVE 是 MySQL 8.0.13+ 的保留字
 * （用于表空间加密语法），不加引号生成的 SQL 会直接语法报错。
 */
@Data
@TableName("`sensitive`")
public class Sensitive implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 违禁词
     */
    private String sensitives;

    /**
     * 添加时间
     */
    private LocalDateTime createTime;
}
