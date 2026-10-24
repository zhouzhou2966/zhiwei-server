package com.zhiwei.trade.service;

import com.zhiwei.trade.domain.dto.OrderDelayQueryDTO;
import com.zhiwei.trade.domain.dto.PayApplyFormDTO;
import com.zhiwei.trade.domain.vo.PayChannelVO;

import java.util.List;

public interface IPayService {
    List<PayChannelVO> queryPayChannels();

    String applyPayOrder(PayApplyFormDTO payApply);

    void queryPayResult(OrderDelayQueryDTO message);
}
