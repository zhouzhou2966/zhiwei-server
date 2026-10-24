package com.zhiwei.learning.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.ReplyDTO;
import com.zhiwei.learning.domian.po.InteractionReply;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.query.ReplyPageQuery;
import com.zhiwei.learning.domian.vo.ReplyVO;

/**
 * <p>
 * 互动问题的回答或评论 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
public interface IInteractionReplyService extends IService<InteractionReply> {
    // 根据问题id删除回复
    void removeByQuestionId(Long id);

    // 保存回复
    void saveReply(ReplyDTO replyDTO);

    // 分页查询回复或评论
    PageDTO<ReplyVO> listReplies(ReplyPageQuery query);

    // 管理端分页查询回复或评论
    PageDTO<ReplyVO> listRepliesAdmin(ReplyPageQuery query);

    // 更新回复或评论的隐藏状态
    void updateReplyHidden(Long id, Boolean hidden);
}
