package com.zhiwei.message.domain.query;

import com.zhiwei.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Schema(description = "通知模板查询对象")
@Data
public class MessageTemplatePageQuery extends PageQuery {

    /**
     * 第三方平台编码，如 {@code aliYun}、{@code tencent}。
     * <p>
     * <b>为什么是 String</b>：它最终落到 {@code message_template.platform_code}（{@code varchar(50)}），
     * 库里存的是平台标识而不是数字 id。原来声明成 {@code Long}，前端传 {@code aliYun} 会在参数绑定阶段
     * 直接 400，传数字则永远查不到数据。字段名保留 {@code thirdPlatformId} 是为了不破坏已发布的接口契约。
     * </p>
     */
    @Schema(description = "第三方平台编码", example = "aliYun")
    private String thirdPlatformId;

    @Schema(description = "模板状态: 0-草稿，1-使用中，2-停用")
    private Integer status;

    @Schema(description = "模板名称关键字")
    private String keyword;
}
