package com.zhiwei.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.*;
import com.zhiwei.promotion.cache.CouponCacheManager;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final CouponCacheManager couponCacheManager;

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
     * <p>实现已下沉到 {@link com.zhiwei.promotion.cache.CouponCacheManager}：
     * 领券链路在缓存缺失时也要能回源重建，而它不能反向依赖本类（构造器循环依赖），
     * 所以写入逻辑必须放在双方都能注入的组件里。这里只保留接口签名。</p>
     */
    @Override
    public void preheatCouponCache(Coupon coupon) {
        couponCacheManager.preheat(coupon);
    }

    @Override
    public void removeCouponCache(Long couponId) {
        couponCacheManager.evict(couponId);
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
        // 删除券缓存，防止缓存数据与数据库数据不一致。
        // 只删券缓存、保留用户领取计数：恢复发放后限领额度必须还在，否则用户会多出一次领取机会
        couponCacheManager.evictCouponOnly(id);
    }


    @Override
    public List<CouponVO> queryIssuingCoupons() {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> list = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                // 只按 status 过滤是不够的：状态由定时任务推进，任务没跑或被人为改过时，
                // 未开始/已结束的券会顶着 ISSUING 留在这里，用户点「立即领取」才被 Lua 拦住
                // （错误码 6「活动尚未开始」、3「优惠券已过期」）。这里补上时间判定，
                // 让列表的可见性和 Lua 的准入判定用同一套规则。
                // 时间字段允许为空，为空表示不限 —— 直接写 le/ge 会因 NULL 比较不成立而漏掉这些券
                .and(w -> w.isNull(Coupon::getIssueBeginTime).or().le(Coupon::getIssueBeginTime, now))
                .and(w -> w.isNull(Coupon::getIssueEndTime).or().ge(Coupon::getIssueEndTime, now))
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
        // 限定了使用范围的券：一次查出分类id，前端据此展示「限 Java进阶、产品运营」。
        // 不这么做的话，页面只能把所有限定券都写成「指定课程」，用户看不出能用在哪儿
        List<Long> specificIds = list.stream()
                .filter(c -> Boolean.TRUE.equals(c.getSpecific()))
                .map(Coupon::getId)
                .collect(Collectors.toList());
        Map<Long, List<Long>> scopeMap = couponScopeService.mapCategoryIdsByCouponIds(specificIds);
        for (Coupon c : list) {
            CouponVO vo = BeanUtils.copyBean(c, CouponVO.class);
            if (Boolean.TRUE.equals(c.getSpecific())) {
                vo.setScopeCategoryIds(scopeMap.getOrDefault(c.getId(), Collections.emptyList()));
            }
            // 限领额度必须按「累计领取数」算：已用掉的券也算领过，
            // 否则 userLimit=1 的券在用掉之后又能再领一张
            // 三个字段都是包装类型，@Range 不拦 null，DB 里为空时直接比较会 NPE，
            // 而 NPE 会让整个列表接口 500（一张脏数据打翻整页），这里按 0 兜底
            long owned = totalMap.getOrDefault(c.getId(), 0L);
            int userLimit = NumberUtils.null2Zero(c.getUserLimit());
            // 与 CouponCacheManager#preheat 保持一致：0/null 一律按 1 处理，
            // 否则列表会显示「已领完」而 Lua 里其实永远领不了（curCount >= 0 恒真），两边口径对不上
            if (userLimit <= 0) userLimit = 1;
            boolean soldOut = NumberUtils.null2Zero(c.getIssueNum()) >= NumberUtils.null2Zero(c.getTotalNum());
            // 还能领几张：前端要据此显示「还可领 2 张」，光给一个 available 布尔值
            // 会让 userLimit>1 的券在领到第一张后看着像「已经领过了」
            long remain = Math.max(userLimit - owned, 0);
            vo.setUserLimit(userLimit);
            vo.setRemainCount((int) remain);
            vo.setSoldOut(soldOut);
            vo.setAvailable(!soldOut && remain > 0);
            // 只有手里还有未使用的券，才引导用户「去使用」（和「还能不能再领」是两件事）
            vo.setReceived(unusedMap.getOrDefault(c.getId(), 0L) > 0);
            voList.add(vo);
        }
        return voList;
    }
}
