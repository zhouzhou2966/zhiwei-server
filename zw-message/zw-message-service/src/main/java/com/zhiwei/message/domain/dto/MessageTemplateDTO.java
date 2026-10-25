package com.zhiwei.message.domain.dto;

import com.zhiwei.common.domain.dto.BaseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 第三方短信平台
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "短信模板")
public class MessageTemplateDTO extends BaseDTO {

    @Schema(description = "信发送模板id，新增时无需填写")
    private Long id;

    /**
     * 第三方短信推送渠道编码，如 {@code aliYun}、{@code tencent}。
     * <p>
     * <b>为什么是 String 而不是 Long</b>：库表 {@code message_template.platform_code} 是
     * {@code varchar(50)}，存的就是平台标识字符串。这里原来声明成 {@code Long}，
     * 导致 {@code PageDTO.of(page, MessageTemplateDTO.class)} 走 hutool 的字段类型转换时
     * 对 {@code "aliYun"} 抛 {@code NumberFormatException}，整个分页接口 500。
     * 与 {@code MessageTemplateFormDTO}、{@code MessageTemplate} 保持一致。
     * </p>
     */
    @Schema(description = "第三方短信推送渠道编码", example = "aliYun")
    private String platformCode;

    @Schema(description = "第三方短信推送渠道名称")
    private String platformName;

    @Schema(description = "短信模板名称")
    private String name;

    @Schema(description = "短信模板预览内容")
    private String content;

    @Schema(description = "第三方平台短信签名")
    private String signName;

    @Schema(description = "第三方平台短信模板code")
    private String thirdTemplateCode;

    @Schema(description = "模板状态:  0-草稿，1-使用中，2-停用")
    private Integer status;
}
