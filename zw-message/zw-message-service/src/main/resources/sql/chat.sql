-- ============================================================
-- 知微群聊模块建表脚本（zw_message 库）
-- 幂等：重复执行不报错（IF NOT EXISTS / IGNORE）
--
-- ⚠️ 执行方式必须显式指定字符集，否则中文演示数据会被双重编码写坏：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p<密码> zw_message < chat.sql
-- ⚠️ SENSITIVE 是 MySQL 8.0.13+ 的保留字，表名一律用反引号包裹。
-- ============================================================
SET NAMES utf8mb4;

-- 聊天消息表：私聊/群聊共用。
-- 索引说明：
--   idx_target_type   按会话拉历史 / 算未读数（where target_id=? and message_type=?）
--   idx_sender        离线补拉私聊「我收到的」方向（where sender_id=? and target_id=?）
--   idx_sent          时间范围查询兜底
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`           BIGINT       NOT NULL COMMENT '雪花ID',
  `sender_id`    BIGINT       NOT NULL COMMENT '发送者用户ID',
  `content`      TEXT         NOT NULL COMMENT '消息内容（DFA过滤后）',
  `message_type` TINYINT      NOT NULL COMMENT '1:私聊 2:群聊',
  `target_id`    BIGINT       NOT NULL COMMENT '接收者用户ID或群ID',
  `sent_at`      DATETIME(3)  NOT NULL COMMENT '发送时间（毫秒精度）',
  `recalled`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已撤回 0:否 1:是（软删，content 不再下发）',
  PRIMARY KEY (`id`),
  KEY `idx_target_type` (`target_id`, `message_type`),
  KEY `idx_sender` (`sender_id`),
  KEY `idx_sent` (`sent_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';

-- 群组表
CREATE TABLE IF NOT EXISTS `chat_group` (
  `id`          BIGINT      NOT NULL COMMENT '雪花ID',
  `name`        VARCHAR(64) NOT NULL COMMENT '群名称',
  `owner_id`    BIGINT      NOT NULL COMMENT '群主用户ID',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建群时间',
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天群组';

-- 群成员表：跨实例推送时按群反查成员，再用 Redis 路由表定位实例
CREATE TABLE IF NOT EXISTS `chat_group_member` (
  `id`        BIGINT   NOT NULL COMMENT '雪花ID',
  `group_id`  BIGINT   NOT NULL COMMENT '群ID',
  `user_id`   BIGINT   NOT NULL COMMENT '成员用户ID',
  `join_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入群时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_user` (`group_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群成员表';

-- 已读位点表：离线消息方案的游标。
--   uk_user_target 保证每用户每会话一行，upsertAck 的 ON DUPLICATE KEY 依赖它
CREATE TABLE IF NOT EXISTS `chat_user_ack` (
  `id`            BIGINT      NOT NULL COMMENT '雪花ID',
  `user_id`       BIGINT      NOT NULL COMMENT '用户ID',
  `message_type`  TINYINT     NOT NULL COMMENT '1:私聊 2:群聊',
  `target_id`     BIGINT      NOT NULL COMMENT '群ID或私聊对端用户ID',
  `last_acked_id` BIGINT      NOT NULL DEFAULT 0 COMMENT '已读到的最大消息ID',
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`, `message_type`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户已读位点';

-- 违禁词表：DFA 字典树词源
CREATE TABLE IF NOT EXISTS `sensitive` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sensitives`  VARCHAR(20)     NOT NULL COMMENT '违禁词',
  `create_time` DATETIME        NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`sensitives`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '违禁词表';

-- 演示数据：违禁词样例（覆盖「带空格/中英混合」场景便于验证 DFA 替换）
INSERT IGNORE INTO `sensitive` (`sensitives`) VALUES
  ('代考'), ('刷单'), ('赌博'), ('代写论文'), ('外挂');

-- 演示数据：公开学习交流群 + 全体既有用户入群
INSERT INTO `chat_group` (`id`, `name`, `owner_id`)
SELECT 1, '知微学习交流群', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `chat_group` WHERE `id` = 1);

INSERT IGNORE INTO `chat_group_member` (`id`, `group_id`, `user_id`, `join_time`)
SELECT `id`, 1, `id`, NOW() FROM `zw_user`.`user` WHERE `status` = 1 LIMIT 100;

-- ============================================================
-- 增量升级：老库补「消息撤回」列（新库不用管，上面的 CREATE 已含）
-- MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 判断保持幂等，
-- 这样整个脚本重复执行仍然不报错。
-- ============================================================
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'chat_message'
                      AND COLUMN_NAME = 'recalled');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `chat_message` ADD COLUMN `recalled` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否已撤回 0:否 1:是（软删，content 不再下发）'' AFTER `sent_at`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
