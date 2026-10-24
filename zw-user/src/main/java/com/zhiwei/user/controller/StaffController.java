package com.zhiwei.user.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.StaffVO;
import com.zhiwei.user.service.IStaffService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 员工详情表 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
@RestController
@RequestMapping("/staffs")
@Api(tags = "用户管理接口")
public class StaffController {

    private final IStaffService staffService;

    public StaffController(IStaffService staffService) {
        this.staffService = staffService;
    }

    @ApiOperation("分页查询员工信息")
    @GetMapping("page")
    public PageDTO<StaffVO> queryStaffPage(UserPageQuery pageQuery){
        return staffService.queryStaffPage(pageQuery);
    }
}
