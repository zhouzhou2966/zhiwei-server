package com.zhiwei.message.controller;

import com.zhiwei.message.domain.po.Sensitive;
import com.zhiwei.message.mapper.SensitiveMapper;
import com.zhiwei.message.service.IChatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 违禁词管理：增删后立即刷新本机 DFA 并广播其他实例重建词库。
 */
@Tag(name = "违禁词管理接口")
@RestController
@RequestMapping("/chats/sensitive")
@RequiredArgsConstructor
public class SensitiveController {

    private final SensitiveMapper sensitiveMapper;
    private final IChatService chatService;

    @Operation(summary = "违禁词列表")
    @GetMapping
    public List<Sensitive> list() {
        return sensitiveMapper.selectList(null);
    }

    @Operation(summary = "新增违禁词")
    @PostMapping
    public Long add(@RequestBody Sensitive sensitive) {
        sensitive.setId(null);
        sensitive.setCreateTime(LocalDateTime.now());
        sensitiveMapper.insert(sensitive);
        chatService.refreshSensitiveWords();
        return sensitive.getId();
    }

    @Operation(summary = "删除违禁词")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        sensitiveMapper.deleteById(id);
        chatService.refreshSensitiveWords();
    }
}
