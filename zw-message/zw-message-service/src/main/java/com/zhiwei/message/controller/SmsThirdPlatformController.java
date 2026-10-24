package com.zhiwei.message.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.message.domain.dto.SmsThirdPlatformDTO;
import com.zhiwei.message.domain.dto.SmsThirdPlatformFormDTO;
import com.zhiwei.message.domain.query.SmsThirdPlatformPageQuery;
import com.zhiwei.message.service.ISmsThirdPlatformService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 第三方云通讯平台 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
@Tag(name = "短信平台管理接口")
@RestController
@RequestMapping("/sms-platforms")
@RequiredArgsConstructor
public class SmsThirdPlatformController {

    private final ISmsThirdPlatformService smsThirdPlatformService;

    @PostMapping
    @Operation(summary = "新增短信平台信息")
    public Long saveSmsThirdPlatform(@RequestBody SmsThirdPlatformFormDTO smsThirdPlatformDTO){
        return smsThirdPlatformService.saveSmsThirdPlatform(smsThirdPlatformDTO);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新短信平台信息")
    public void updateSmsThirdPlatform(
            @RequestBody SmsThirdPlatformFormDTO smsThirdPlatformDTO,
            @Parameter(description = "短信平台id", example = "1") @PathVariable("id") Long id){
        smsThirdPlatformDTO.setId(id);
        smsThirdPlatformService.updateSmsThirdPlatform(smsThirdPlatformDTO);
    }

    @GetMapping
    @Operation(summary = "分页查询短信平台信息")
    public PageDTO<SmsThirdPlatformDTO> querySmsThirdPlatforms(SmsThirdPlatformPageQuery pageQuery){
        return smsThirdPlatformService.querySmsThirdPlatforms(pageQuery);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据id查询短信平台信息")
    public SmsThirdPlatformDTO querySmsThirdPlatform(
            @Parameter(description = "短信平台id", example = "1") @PathVariable("id") Long id){
        return smsThirdPlatformService.querySmsThirdPlatform(id);
    }
}
