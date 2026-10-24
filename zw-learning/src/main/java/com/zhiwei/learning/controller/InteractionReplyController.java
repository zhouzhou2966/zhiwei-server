package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.ReplyDTO;
import com.zhiwei.learning.domian.query.ReplyPageQuery;
import com.zhiwei.learning.domian.vo.ReplyVO;
import com.zhiwei.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@RestController
@RequestMapping("/replies")
@RequiredArgsConstructor
@Api(tags = "互动回答或评论接口")
public class InteractionReplyController {
    private final IInteractionReplyService replyService;

    @ApiOperation("保存互动回答或评论")
    @PostMapping
    public void saveReply(@Valid @RequestBody ReplyDTO replyDTO) {
        replyService.saveReply(replyDTO);
    }


    @ApiOperation("分页查询互动回答或评论")
    @GetMapping("/page")
    public PageDTO<ReplyVO> listReplies(ReplyPageQuery query) {
        return replyService.listReplies(query);
    }

}
