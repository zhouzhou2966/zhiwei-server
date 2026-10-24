package com.zhiwei.remark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiwei.remark.domian.po.LikedRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 点赞记录表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-13
 */
public interface LikedRecordMapper extends BaseMapper<LikedRecord> {

    /**
     * 插入一条点赞记录，唯一索引 (biz_id, biz_type, user_id) 冲突时忽略
     * <p>
     * 用于 MQ 消费端幂等落库：重复消费同一事件不会产生重复行
     */
    @Insert("insert ignore into liked_record(user_id, biz_id, biz_type) " +
            "values(#{userId}, #{bizId}, #{bizType})")
    int insertIgnore(LikedRecord record);

    /**
     * 批量插入点赞记录，唯一索引冲突时忽略
     * <p>
     * 用于同步任务覆盖式写入：多值 INSERT 比逐条插入更快，且并发重复写入不报错
     */
    @Insert("<script>insert ignore into liked_record(user_id, biz_id, biz_type) values " +
            "<foreach collection='records' item='r' separator=','>(#{r.userId}, #{r.bizId}, #{r.bizType})</foreach>" +
            "</script>")
    int insertIgnoreBatch(@Param("records") List<LikedRecord> records);
}
