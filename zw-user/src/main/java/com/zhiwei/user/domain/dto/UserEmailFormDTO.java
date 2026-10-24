package com.zhiwei.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;

/**
 * 修改 / 解绑邮箱的表单。
 *
 * <p>为什么不用 {@code UserDTO}：那个 DTO 上的 {@code type} 是 {@code @NotNull}，
 * 改邮箱这种只需要一个字段的场景强行复用它，调用方就得凭空塞一个无意义的用户类型进去。
 */
@Data
@Schema(description = "修改/解绑邮箱的表单")
public class UserEmailFormDTO {

    /**
     * 邮箱。传空字符串或 null 表示解绑。
     * {@code @Email} 对空值放行，所以「解绑」和「格式校验」不冲突。
     */
    @Schema(description = "邮箱，留空表示解绑", example = "anthony@zhiwei.com")
    @Email(message = "邮箱格式不正确")
    private String email;
}
