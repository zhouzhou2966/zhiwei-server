package com.zhiwei.message.controller;

import com.zhiwei.common.utils.UserContext;
import com.zhiwei.message.domain.dto.ChatAckDTO;
import com.zhiwei.message.domain.vo.ChatGroupVO;
import com.zhiwei.message.domain.vo.ChatMessageVO;
import com.zhiwei.message.domain.vo.ChatSessionVO;
import com.zhiwei.message.domain.vo.ChatUserVO;
import com.zhiwei.message.service.IChatService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天模块 REST 接口：拉历史、拉未读、看在线——都是「请求-响应」型的操作。
 * 发送/ack 走 WebSocket 帧（见 ChatWebSocketHandler），不用 REST 再发一份。
 *
 * <p>路径映射 /chats（不带 /sms 前缀）：网关路由 Path=/sms/** 带 StripPrefix，
 * /sms/chats/** 到达本服务时前缀已被剥掉；前端统一以 /sms/chats/** 访问。
 */
@Api(tags = "聊天接口")
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;

    @ApiOperation("我的群列表")
    @GetMapping("/groups")
    public List<ChatGroupVO> myGroups() {
        return chatService.myGroups(UserContext.getUserId());
    }

    @ApiOperation("历史消息（按 ID 倒序向前翻，返回时间正序）")
    @GetMapping("/history")
    public List<ChatMessageVO> history(
            @RequestParam("messageType") Integer messageType,
            @RequestParam("targetId") Long targetId,
            @RequestParam(value = "beforeId", defaultValue = "0") Long beforeId,
            @RequestParam(value = "size", defaultValue = "30") Integer size) {
        return chatService.history(UserContext.getUserId(), messageType, targetId, beforeId, size);
    }

    @ApiOperation("会话列表 + 未读数（离线消息补拉入口）")
    @GetMapping("/sessions")
    public List<ChatSessionVO> offlineSessions() {
        return chatService.offlineSessions(UserContext.getUserId());
    }

    @ApiOperation("在线用户列表（带昵称头像，排除自己）")
    @GetMapping("/online")
    public List<ChatUserVO> onlineUsers() {
        return chatService.onlineUsers(UserContext.getUserId());
    }

    @ApiOperation("已读确认（REST 备用通道，与 WS ACK 帧等价）")
    @PostMapping("/ack")
    public void ack(@RequestBody ChatAckDTO dto) {
        chatService.ack(UserContext.getUserId(), dto);
    }
}
