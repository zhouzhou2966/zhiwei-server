package com.zhiwei.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.constants.PromotionConstants;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.ExchangeCode;
import com.zhiwei.promotion.domian.query.CodeQuery;
import com.zhiwei.promotion.domian.vo.CouponScopePageVO;
import com.zhiwei.promotion.mapper.ExchangeCodeMapper;
import com.zhiwei.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.promotion.utils.CodeUtil;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.zhiwei.promotion.constants.PromotionConstants.COUPON_CODE_MAP_KEY;
import static com.zhiwei.promotion.constants.PromotionConstants.COUPON_CODE_SERIAL_KEY;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
@Service
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {
    private final BoundValueOperations<String, String> serialOps;
    private final StringRedisTemplate redisTemplate;

    public ExchangeCodeServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.serialOps = redisTemplate.boundValueOps(COUPON_CODE_SERIAL_KEY);
    }

    @Override
    @Async("generateCouponExecutor")   // 使用自定义线程池异步生成兑换码，不阻塞主线程
    @Transactional
    public void asyncGenerateCode(Coupon coupon) {
        // 1. 批量原子分配兑换码序列号
        Integer totalNum = coupon.getTotalNum();
        // 原子递增需要生成的兑换码总数，得到当前最大序列号
        Long result = serialOps.increment(totalNum);
        if (result == null) return;
        int maxSerialNum = result.intValue();
        // 2. 循环生成所有兑换码
        List<ExchangeCode> codes = new ArrayList<>(totalNum);
        // 序列号范围：[maxSerialNum - totalNum + 1, maxSerialNum]
        for (int serialNum = maxSerialNum - totalNum + 1; serialNum <= maxSerialNum; serialNum++) {
            // 根据序列号和优惠券ID生成兑换码
            String code = CodeUtil.generateCode(serialNum, coupon.getId());
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setCode(code);
            exchangeCode.setId(serialNum);
            exchangeCode.setExchangeTargetId(coupon.getId());
            exchangeCode.setExpiredTime(coupon.getIssueEndTime());
            codes.add(exchangeCode);
        }
        // 3. 批量保存兑换码到数据库
        saveBatch(codes);
        // 4. 记录该优惠券最大序列号到ZSet，供Lua脚本快速范围查找
        redisTemplate.opsForZSet().add(PromotionConstants.COUPON_CODE_RANGE_KEY, String.valueOf(coupon.getId()), maxSerialNum);
    }


    @Override
    public PageDTO<CouponScopePageVO> queryCodesPage(CodeQuery query) {
        Long couponId = query.getCouponId();
        Integer status = query.getStatus();
        if (couponId == null) return PageDTO.empty(0L, 0L);
        Page<ExchangeCode> page = lambdaQuery()
                .select(ExchangeCode::getId, ExchangeCode::getCode)
                .eq(ExchangeCode::getExchangeTargetId, couponId)
                .eq(status != null, ExchangeCode::getStatus, status)
                .page(query.toMpPage("id", true));
        List<ExchangeCode> codeList = page.getRecords();
        if (codeList.isEmpty()) return PageDTO.empty(0L, 0L);
        List<CouponScopePageVO> voList = codeList
                .stream()
                .map(code -> new CouponScopePageVO(code.getId(), code.getCode()))
                .collect(Collectors.toList());
        return PageDTO.of(page, voList);
    }


    @Override
    public boolean updateExchangeMark(long serialNum, boolean mark) {
        Boolean b = redisTemplate.opsForValue().setBit(COUPON_CODE_MAP_KEY, serialNum, mark); // 设置位为兑换状态
        return b != null && b;    //返回的是设置前的值, 为true ，原来有值，已兑换，false ，原来无值，未兑换，设置成功
    }
}