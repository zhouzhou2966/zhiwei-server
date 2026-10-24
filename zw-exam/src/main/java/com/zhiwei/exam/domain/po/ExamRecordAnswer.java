package com.zhiwei.exam.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 考试答题记录
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("exam_record_answer")
public class ExamRecordAnswer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 考试记录id
     */
    private Long examRecordId;

    /**
     * 题目id
     */
    private Long questionId;

    /**
     * 用户答案（客观题选项，如"A"或"A,B,C"）
     */
    private String answer;

    /**
     * 是否正确，0-错误，1-正确
     */
    private Integer isCorrect;

    /**
     * 得分
     */
    private Integer score;

    /**
     * 老师评语（主观题人工批阅时填写）
     */
    private String comment;
}