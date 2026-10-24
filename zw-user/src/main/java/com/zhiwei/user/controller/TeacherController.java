package com.zhiwei.user.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.TeacherPageVO;
import com.zhiwei.user.service.ITeacherService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 教师详情表 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/teachers")
@Tag(name = "用户管理接口")
public class TeacherController {

    @Autowired
    private ITeacherService teacherService;

    @GetMapping("/page")
    @Operation(summary = "分页查询教师信息")
    public PageDTO<TeacherPageVO> queryTeacherPage(UserPageQuery pageQuery){
        return teacherService.queryTeacherPage(pageQuery);
    }
}
