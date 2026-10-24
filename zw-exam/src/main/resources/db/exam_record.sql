-- =============================================
-- 知微 - 考试评测模块建表脚本
-- 数据库: zw_exam
-- 说明：本脚本与实际库结构保持一致（列顺序、默认值、索引均按 SHOW CREATE TABLE 核对过）
-- =============================================

-- 考试记录表：记录用户每次考试的基本信息
CREATE TABLE IF NOT EXISTS `exam_record` (
    `id`             BIGINT    NOT NULL   COMMENT '主键，考试记录id',
    `lesson_id`      BIGINT    NOT NULL   COMMENT '课表id',
    `course_id`      BIGINT    NOT NULL   COMMENT '课程id',
    `section_id`     BIGINT    NOT NULL   COMMENT '小节id（考试对应的目录id）',
    `user_id`        BIGINT    NOT NULL   COMMENT '用户id',
    `type`           TINYINT   NOT NULL DEFAULT 1 COMMENT '考试类型，1-练习，2-考试',
    `question_count` INT       NOT NULL DEFAULT 0 COMMENT '题目总数',
    `total_score`    INT       NOT NULL DEFAULT 0 COMMENT '总分',
    `score`          INT       NOT NULL DEFAULT 0 COMMENT '得分',
    `correct_rate`   INT       NOT NULL DEFAULT 0 COMMENT '正确率，保留2位小数，例如8550表示85.5%',
    `duration`       INT       NOT NULL DEFAULT 0 COMMENT '考试用时，单位秒',
    `status`         TINYINT   NOT NULL DEFAULT 0 COMMENT '考试状态，0-未完成，1-已完成',
    `create_time`    DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finish_time`    DATETIME  NULL     DEFAULT NULL COMMENT '完成时间',
    `update_time`    DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_user_section` (`user_id`, `section_id`) USING BTREE,
    INDEX `idx_lesson_id` (`lesson_id`) USING BTREE,
    INDEX `idx_user_id` (`user_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试记录表';

-- 考试答题记录表：记录用户每道题的作答和评分情况
CREATE TABLE IF NOT EXISTS `exam_record_answer` (
    `id`              BIGINT    NOT NULL   COMMENT '主键',
    `exam_record_id`  BIGINT    NOT NULL   COMMENT '考试记录id',
    `question_id`     BIGINT    NOT NULL   COMMENT '题目id',
    `answer`          VARCHAR(255) NULL    DEFAULT NULL COMMENT '用户答案（客观题选项，如"A"或"A,B,C"）',
    `is_correct`      TINYINT   NOT NULL DEFAULT 0 COMMENT '是否正确，0-错误，1-正确',
    `score`           INT       NOT NULL DEFAULT 0 COMMENT '该题得分',
    `comment`         VARCHAR(500) NULL    DEFAULT NULL COMMENT '老师评语',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_exam_record` (`exam_record_id`) USING BTREE,
    INDEX `idx_question` (`question_id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试答题记录表';
