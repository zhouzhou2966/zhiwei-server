package com.zhiwei.remark.service;

import com.zhiwei.remark.domian.dto.LikeRecordFormDTO;
import com.zhiwei.remark.domian.po.LikedRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-13
 */
public interface ILikedRecordService extends IService<LikedRecord> {
    // 添加点赞记录
    void likeOrUnlike(@Valid LikeRecordFormDTO likeRecordFormDTO);

    // 批量查询业务是否被点赞
    Set<Long> isBizLike(List<Long> bizId, String bizType);

}
