package com.zhiwei.pay.controller;


import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.pay.domain.po.PayChannel;
import com.zhiwei.pay.sdk.dto.PayChannelDTO;
import com.zhiwei.pay.service.IPayChannelService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * <p>
 * 支付渠道 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
@Tag(name = "支付相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/pay-channels")
public class PayChannelController {

    private final IPayChannelService channelService;

    @Operation(summary = "查询支付渠道列表")
    @GetMapping("/list")
    public List<PayChannelDTO> listAllPayChannels(){
        // 只返回使用中（status=1）的渠道：停用的渠道调第三方必然失败，
        // 继续下发给前端只会让用户在支付页点到必然报错的选项
        return BeanUtils.copyList(
                channelService.lambdaQuery().eq(PayChannel::getStatus, 1).list(),
                PayChannelDTO.class);
    }

    @Operation(summary = "添加支付渠道")
    @PostMapping
    public Long addPayChannel(@Valid @RequestBody PayChannelDTO channelDTO){
        return channelService.addPayChannel(channelDTO);
    }

    @Operation(summary = "修改支付渠道")
    @PutMapping("/{id}")
    public void updatePayChannel(
            @Parameter(description = "支付渠道id") @PathVariable("id") Long id,
            @RequestBody PayChannelDTO channelDTO){
        channelDTO.setId(id);
        channelService.updatePayChannel(channelDTO);
    }
}
