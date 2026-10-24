package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.QuestionFormDTO;
import com.zhiwei.learning.domian.query.QuestionPageQuery;
import com.zhiwei.learning.domian.vo.QuestionVO;
import com.zhiwei.learning.service.IInteractionQuestionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
@Tag(name = "互动问题")
public class InteractionQuestionController {
    private final IInteractionQuestionService interactionQuestionService;

    @Operation(summary = "添加互动问题")
    @PostMapping
    public void addQuestion(@Valid @RequestBody QuestionFormDTO questionFormDTO) {
        interactionQuestionService.addQuestion(questionFormDTO);
    }

    @Operation(summary = "分页查询互动问题")
    @GetMapping("/page")
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        return interactionQuestionService.queryQuestionPage(query);
    }

    @Operation(summary = "根据id查询互动问题")
    @GetMapping("/{id}")
    public QuestionVO queryQuestionById(@PathVariable Long id) {
        return interactionQuestionService.queryQuestionById(id);
    }

    @Operation(summary = "修改互动问题")
    @PutMapping("/{id}")
    public void updateQuestion(@RequestBody QuestionFormDTO questionFormDTO, @PathVariable Long id) {
        interactionQuestionService.updateQuestion(questionFormDTO, id);
    }

    @Operation(summary = "删除我的问题")
    @DeleteMapping("/{id}")
    public void deleteQuestion(@PathVariable Long id) {
        interactionQuestionService.removeMyQuestion(id);
    }
}
