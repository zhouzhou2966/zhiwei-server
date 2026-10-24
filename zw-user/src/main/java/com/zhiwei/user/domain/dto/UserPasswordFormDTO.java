package com.zhiwei.user.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 修改「当前登录用户」登录密码的表单。
 * <p>
 * 为什么单独建一个 DTO，而不是复用 {@link UserFormDTO}：
 * UserFormDTO 继承自 zw-api 的 UserDTO，而 UserDTO 上的 {@code type} 带 {@code @NotNull}
 * （Bean Validation 的字段约束会被子类继承，无法在子类里取消）。
 * 门户改密码压根用不到 type，却被迫在请求体里塞一个假的 {@code type: 2}，
 * 前端注释还得解释「这个字段没意义但必须传」——属于典型的设计泄漏。
 * 这里只收两个语义明确的字段，接口自解释。
 */
@Data
@ApiModel(description = "修改当前登录用户登录密码的表单")
public class UserPasswordFormDTO {

    @ApiModelProperty(value = "原密码", example = "123321")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @ApiModelProperty(value = "新密码", example = "123321")
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
