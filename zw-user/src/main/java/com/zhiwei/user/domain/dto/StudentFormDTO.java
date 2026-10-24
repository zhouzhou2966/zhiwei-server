package com.zhiwei.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "学生注册和修改密码的表单实体")
public class StudentFormDTO {

    @Schema(description = "手机号", example = "13800010004")
    private String cellPhone;

    @Schema(description = "密码", example = "123456")
    private String password;

    @Schema(description = "验证码", example = "645632")
    private String code;

    // 以下三个字段只用于「修改当前登录学员的基本信息」（PUT /students），
    // 注册与改密码流程不传它们，MyBatis-Plus 的 updateById 也只更新非 null 字段，互不影响。
    @Schema(description = "昵称")
    private String name;

    @Schema(description = "头像路径", example = "/img-ali/xxx.png")
    private String icon;

    @Schema(description = "性别，0-男，1-女", example = "0")
    private Integer gender;
}
