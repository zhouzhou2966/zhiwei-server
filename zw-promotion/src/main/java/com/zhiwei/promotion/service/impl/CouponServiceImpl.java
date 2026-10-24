package com.zhiwei.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.*;
import com.zhiwei.promotion.constants.PromotionConstants;
import com.zhiwei.promotion.domian.dto.CouponFormDTO;
import com.zhiwei.promotion.domian.dto.CouponIssueFormDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.CouponScope;
import com.zhiwei.promotion.domian.po.UserCoupon;
import com.zhiwei.promotion.domian.query.CouponQuery;
import com.zhiwei.promotion.domian.vo.CouponDetailVO;
import com.zhiwei.promotion.domian.vo.CouponPageVO;
import com.zhiwei.promotion.domian.vo.CouponScopeVO;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.zhiwei.promotion.enums.CouponStatus;
import com.zhiwei.promotion.enums.ObtainType;
import com.zhiwei.promotion.enums.UserCouponStatus;
import com.zhiwei.promotion.mapper.CouponMapper;
import com.zhiwei.promotion.service.ICouponScopeService;
import com.zhiwei.promotion.service.ICouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.promotion.service.IExchangeCodeService;
import com.zhiwei.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zhiwei.promotion.constants.PromotionConstants.COUPON_CACHE_KEY_PREFIX;
import static com.zhiwei.promotion.constants.PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {
    private final ICouponScopeService couponScopeService;
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        save(coupon);

        if (!dto.getSpecific()) return;
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) throw new BadRequestException("请选择优惠券作用范围");
        List<CouponScope> list = scopes.stream()
                .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(coupon.getId()))
                .collect(Collectors.toList());

        couponScopeService.saveBatch(list);
    }


    @Override
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        String name = query.getName();
        Integer type = query.getType();
        Integer status = query.getStatus();
        Page<Coupon> page = lambdaQuery()
                .eq(type != null, Coupon::getDiscountType, type)
                .eq(status != null, Coupon::getStatus, status)
                .like(StringUtils.isNotBlank(name), Coupon::getName, name)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) return PageDTO.empty(page);
        List<CouponPageVO> voList = BeanUtils.copyList(records, CouponPageVO.class);
        return PageDTO.of(page, voList);
    }


    @Transactional
    @Override
    public void issueCoupon(CouponIssueFormDTO dto) {
        Coupon coupon = getById(dto.getId());
        if (coupon == null) throw new BadRequestException("优惠券不存在");
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BizIllegalException("优惠券状态有误");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean isBegin = dto.getIssueBeginTime() == null || now.isAfter(dto.getIssueBeginTime());
        Coupon c = BeanUtils.copyBean(dto, Coupon.class);
        if (isBegin) {
            c.setStatus(CouponStatus.ISSUING);
            c.setIssueBeginTime(now);
        } else {
            c.setStatus(CouponStatus.UN_ISSUE);
        }
        updateById(c);
        if (isBegin) {
            coupon.setIssueBeginTime(c.getIssueBeginTime());
            coupon.setIssueEndTime(c.getIssueEndTime());
            preheatCouponCache(coupon);
        }
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            coupon.setIssueEndTime(c.getIssueEndTime());
            exchangeCodeService.asyncGenerateCode(coupon);
        }


    }

    /**
     * 预热优惠券缓存（领券 Lua 的唯一数据源）
     * <p>两个关键点：</p>
     * <ul>
     *   <li>库存写的是<b>剩余可领数量</b>（totalNum - issueNum），不是总量。
     *       否则「暂停后重新发放」时 Redis 会多放出已发出去的那部分，DB 侧
     *       {@code issue_num < total_num} 会拦住，用户看到的是「已发放完毕」而不是成功。</li>
     *   <li>写入 obtainWay，让 Lua 能拒绝「兑换码专属券走手动领取接口」。</li>
     * </ul>
     */
    @Override
    public void preheatCouponCache(Coupon coupon) {
        if (coupon == null || coupon.getId() == null) return;
        int totalNum = NumberUtils.null2Zero(coupon.getTotalNum());
        int issueNum = NumberUtils.null2Zero(coupon.getIssueNum());
        int remain = Math.max(totalNum - issueNum, 0);
        int userLimit = NumberUtils.null2Zero(coupon.getUserLimit());
        int obtainWay = coupon.getObtainWay() == null
                ? ObtainType.PUBLIC.getValue() : coupon.getObtainWay().getValue();

        Map<String, String> map = new HashMap<>(8);
        map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueBeginTime())));
        map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueEndTime())));
        map.put("totalNum", String.valueOf(remain));
        map.put("userLimit", String.valueOf(userLimit));
        map.put("obtainWay", String.valueOf(obtainWay));

        String key = COUPON_CACHE_KEY_PREFIX + coupon.getId();
        redisTemplate.opsForHash().putAll(key, map);
        // TTL 取「发放结束时间 + 7 天」：既保证发放期内计数不会提前过期，
        // 又能在活动结束后自动回收，避免 key 永久驻留
        if (coupon.getIssueEndTime() != null) {
            long seconds = Duration.between(LocalDateTime.now(), coupon.getIssueEndTime().plusDays(7)).getSeconds();
            if (seconds > 0) {
                redisTemplate.expire(key, Duration.ofSeconds(seconds));
            }
        }
    }

    @Override
    public void removeCouponCache(Long couponId) {
        if (couponId == null) return;
        redisTemplate.delete(COUPON_CACHE_KEY_PREFIX + couponId);
        redisTemplate.delete(USER_COUPON_CACHE_KEY_PREFIX + couponId);
    }


    @Override
    public void updateCouponInfo(CouponFormDTO dto) {
        Coupon coupon = getById(dto.getId());
        if (coupon == null) throw new BadRequestException("优惠券不存在");
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BizIllegalException("优惠券状态有误");
        }
        Coupon c = BeanUtils.copyBean(dto, Coupon.class);
        updateById(c);

    }


    @Override
    public CouponDetailVO getCouponById(Long id) {
        if (id == null) return null;
        Coupon coupon = getById(id);
        if (coupon == null) return null;
        CouponDetailVO vo = BeanUtils.copyBean(coupon, CouponDetailVO.class);
        List<CouponScope> list = couponScopeService.lambdaQuery()
                .eq(CouponScope::getCouponId, id)
                .list();
        if (CollUtils.isEmpty(list)) return vo;
        List<CouponScopeVO> scopeList = new ArrayList<>(list.size());
        for (CouponScope scope : list) {
            CouponScopeVO scopeVO = new CouponScopeVO();
            scopeVO.setId(scope.getBizId());
            scopeList.add(scopeVO);
        }
        vo.setScopes(scopeList);
        return vo;
    }


    @Override
    @Transactional
    public void deleteCouponById(Long id) {
        if (id == null) return;
        Coupon coupon = getById(id);
        if (coupon == null) return;
        if (coupon.getStatus() != CouponStatus.DRAFT) throw new BizIllegalException("优惠券状态有误");
        couponScopeService
                .lambdaUpdate()
                .eq(CouponScope::getCouponId, id)
                .remove();
        removeById(id);
    }

    @Override
    public List<Coupon> queryCouponForIssue() {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> list = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.DRAFT)
                .lt(Coupon::getIssueBeginTime, now)
                .list();
        if (CollUtils.isEmpty(list)) return CollUtils.emptyList();
        return list;
    }


    @Override
    @Transactional
    public void pauseCoupon(Long id) {
        if (id == null) throw new BadRequestException("优惠卷id不能为空!");
        Coupon coupon = getById(id);
        if (coupon == null || coupon.getStatus() != CouponStatus.ISSUING) return;
        boolean result = lambdaUpdate().set(Coupon::getStatus, CouponStatus.PAUSE).eq(Coupon::getId, id).eq(Coupon::getStatus, CouponStatus.ISSUING).update();
        if (!result) {
            log.error("优惠券暂停失败:{}", coupon);
        }
        log.info("优惠券暂停成功,id:{}", id);
        // 删除缓存，防止缓存数据与数据库数据不一致
        redisTemplate.delete(COUPON_CACHE_KEY_PREFIX + id);
    }


    @Override
    public List<CouponVO> queryIssuingCoupons() {
        List<Coupon> list = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(list)) return CollUtils.emptyList();
        List<Long> couponIds = list.stream().map(Coupon::getId).collect(Collectors.toList());
        List<UserCoupon> userCoupons = userCouponService
                .lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUserId())
                .in(UserCoupon::getCouponId, couponIds)
                .list();
        // 该用户对每张模板「总共领过几张」（含已使用、已过期）
        Map<Long, Long> totalMap = userCoupons
                .stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        // 其中「还没用掉」的张数，用来决定是否展示「去使用」
        Map<Long, Long> unusedMap = userCoupons
                .stream()
                .filter(userCoupon -> userCoupon.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        List<CouponVO> voList = new ArrayList<>(list.size());
        for (Coupon c : list) {
            CouponVO vo = BeanUtils.copyBean(c, CouponVO.class);
            // 限领额度必须按「累计领取数」算：已用掉的券也算领过，
            // 否则 userLimit=1 的券在用掉之后又能再领一张
            vo.setAvailable(
                    c.getIssueNum() < c.getTotalNum()
                            && totalMap.getOrDefault(c.getId(), 0L) < c.getUserLimit()
            );
            // 只有手里还有未使用的券，才引导用户「去使用」
            vo.setReceived(unusedMap.getOrDefault(c.getId(), 0L) > 0);
            voList.add(vo);
        }
        return voList;
    }
}
