package com.zhiwei.user.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.dto.StudentFormDTO;
import com.zhiwei.user.domain.dto.UserEmailFormDTO;
import com.zhiwei.user.domain.dto.UserPasswordFormDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.StudentPageVo;
import com.zhiwei.user.service.IStudentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 学员详情表 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/students")
@Api(tags = "用户管理接口")
public class StudentController {

    @Autowired
    private IStudentService studentService;

    @ApiOperation("分页查询学生信息")
    @GetMapping("/page")
    public PageDTO<StudentPageVo> queryStudentPage(UserPageQuery pageQuery){
        return studentService.queryStudentPage(pageQuery);
    }

    @ApiOperation("学员注册")
    @PostMapping("/register")
    public void registerStudent(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.saveStudent(studentFormDTO);
    }

    @ApiOperation("修改学员密码")
    @PutMapping("/password")
    public void updateMyPassword(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.updateMyPassword(studentFormDTO);
    }

    @ApiOperation("修改当前登录学员的登录密码（原密码 + 新密码）")
    @PutMapping("/login-password")
    public void updateMyLoginPassword(@Valid @RequestBody UserPasswordFormDTO formDTO) {
        studentService.updateMyLoginPassword(formDTO);
    }

    @ApiOperation("修改当前登录学员的基本信息（昵称、头像、性别）")
    @PutMapping
    public void updateMyInfo(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.updateMyInfo(studentFormDTO);
    }

    @ApiOperation("修改当前登录学员的手机号，需要新手机号的短信验证码")
    @PutMapping("/phone")
    public void updateMyPhone(@RequestBody StudentFormDTO studentFormDTO) {
        studentService.updateMyPhone(studentFormDTO);
    }

    @ApiOperation("修改/解绑当前登录学员的邮箱，email 传空表示解绑")
    @PutMapping("/email")
    public void updateMyEmail(@Valid @RequestBody UserEmailFormDTO formDTO) {
        studentService.updateMyEmail(formDTO.getEmail());
    }
}
