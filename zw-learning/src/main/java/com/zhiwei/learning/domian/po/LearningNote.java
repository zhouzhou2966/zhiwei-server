package com.zhiwei.learning.domian.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 学习笔记表
 * </p>
 *
 * @author author
 * @since 2026-07-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("learning_note")
public class LearningNote implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 笔记id
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 笔记所属用户id
     */
    private Long userId;

    /**
     * 所属课程id
     */
    private Long courseId;

    /**
     * 所属课程章id
     */
    private Long chapterId;

    /**
     * 所属课程节id
     */
    private Long sectionId;

    /**
     * 笔记内容
     */
    private String content;

    /**
     * 是否是隐私笔记，0-公开，1-隐私
     */
    private Boolean isPrivate;

    /**
     * 是否被隐藏，默认false
     */
    private Boolean hidden;

    /**
     * 被采集的原始笔记id，非采集笔记则为null
     */
    private Long gatheredNoteId;

    /**
     * 记录笔记时视频播放的时间点，单位：秒
     */
    private Integer noteMoment;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}