package com.zhiwei.exam.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 考试记录
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("exam_record")
public class ExamRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 课表id
     */
    private Long lessonId;

    /**
     * 课程id
     */
    private Long courseId;

    /**
     * 小节id（考试对应的目录id）
     */
    private Long sectionId;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 题目总数
     */
    private Integer questionCount;

    /**
     * 总分
     */
    private Integer totalScore;

    /**
     * 得分
     */
    private Integer score;

    /**
     * 正确率，保留2位小数，例如8550表示85.5%
     */
    private Integer correctRate;

    /**
     * 考试类型，1-练习，2-考试
     */
    private Integer type;

    /**
     * 考试用时，单位：秒
     */
    private Integer duration;

    /**
     * 考试状态，0-未完成，1-已完成
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 完成时间
     */
    private LocalDateTime finishTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}