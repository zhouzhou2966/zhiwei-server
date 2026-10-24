package com.zhiwei.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.CouponScope;
import com.zhiwei.promotion.domian.po.UserCoupon;
import com.zhiwei.promotion.enums.UserCouponStatus;
import com.zhiwei.promotion.mapper.UserCouponMapper;
import com.zhiwei.promotion.service.ICouponScopeService;
import com.zhiwei.promotion.service.ICouponService;
import com.zhiwei.promotion.service.IDiscountService;
import com.zhiwei.promotion.strategy.discount.Discount;
import com.zhiwei.promotion.strategy.discount.DiscountStrategy;
import com.zhiwei.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiscountServiceImpl implements IDiscountService {
    private final UserCouponMapper userCouponMapper;
    private final ICouponScopeService couponScopeService;
    private final Executor discountSolutionExecutor;
    private final ICouponService couponService;

    /**
     * 查找当前用户最优的优惠券折扣方案
     * <p>
     * 处理流程：
     * <ol>
     *   <li>查询当前用户所有可用的优惠券</li>
     *   <li>过滤出满足订单总金额门槛的优惠券</li>
     *   <li>筛选每张券的可用课程，构建券 → 可用课程映射</li>
     *   <li>生成所有排列方案（最多 3 张券），线程池并发计算</li>
     *   <li>等待完成（最多 2 秒），加锁迭代选最优解</li>
     * </ol>
     *
     * @param orderCourses 订单中的课程列表
     * @return 各用券数量级的最优方案列表，按用券数升序排列
     */
    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
        // 1. 查询用户所有可用优惠券
        List<Coupon> coupons = userCouponMapper.queryMyCoupons(UserContext.getUserId());
        if (CollUtils.isEmpty(coupons)) return CollUtils.emptyList();

        // 2. 过滤出满足订单总金额门槛的优惠券
        int totalAmount = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        List<Coupon> availableCoupons = coupons
                .stream()
                .filter(c -> {
                    Discount discount = DiscountStrategy.getDiscount(c.getDiscountType());
                    return discount != null && discount.canUse(totalAmount, c);
                })
                .collect(Collectors.toList());

        // 3. 进一步过滤出每张优惠券可用范围的课程，构建优惠券 → 可用课程列表的映射
        Map<Coupon, List<OrderCourseDTO>> availableCouponsMap = findAvailableCouponsMap(availableCoupons, orderCourses);
        if (CollUtils.isEmpty(availableCouponsMap)) return CollUtils.emptyList();

        // 4. 生成所有排列方案 + 线程池并发计算（最多 3 张券）
        List<Coupon> couponList = new ArrayList<>(availableCouponsMap.keySet());
        List<List<Coupon>> solutions = PermuteUtil.permute(couponList, 3);
        List<CouponDiscountDTO> list = Collections.synchronizedList(new ArrayList<>(solutions.size()));
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture
                    .supplyAsync(() -> calculateSolutionDiscount(solution, orderCourses, availableCouponsMap), discountSolutionExecutor)
                    .whenComplete((dto, throwable) -> {
                        if (throwable != null) {
                            log.error("优惠方案计算异常", throwable);
                        } else if (CollUtils.isNotEmpty(dto.getIds())) {
                            list.add(dto);
                        }
                        latch.countDown();
                    });
        }
        try {
            boolean finished = latch.await(2, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("优惠方案计算超时，已计算 {}/{} 个方案", list.size(), solutions.size());
            }
        } catch (InterruptedException e) {
            log.error("优惠方案计算被中断", e);
        }

        // 6. 加锁迭代，避免超时后仍有 worker 写入导致 CME
        synchronized (list) {
            return findBestSolution(list);
        }
    }

    /**
     * 从所有优惠券组合方案中双维度筛选最优解
     * <p>
     * 维度一：相同券组合（id 集合相同），只保留折扣金额最大的版本<br>
     * 维度二：相同折扣金额，只保留用券数量最少的版本<br>
     * 取两轮交集后按折扣金额降序返回，用户优先看到省钱最多的方案。
     *
     * @param list 所有优惠券组合方案的计算结果列表
     * @return 双维度筛选后的最优方案列表，按折扣金额降序排列
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> list) {
        // 维度一：相同券组合只保留折扣金额最大的版本
        Map<String, CouponDiscountDTO> bestPerCombo = new HashMap<>();
        for (CouponDiscountDTO solution : list) {
            String key = solution.getIds().stream().sorted(Long::compare).map(String::valueOf).collect(Collectors.joining(","));
            CouponDiscountDTO existing = bestPerCombo.get(key);
            if (existing == null || solution.getDiscountAmount() > existing.getDiscountAmount()) {
                bestPerCombo.put(key, solution);
            }
        }

        // 维度二：相同折扣金额只保留用券数量最少的版本
        Map<Integer, CouponDiscountDTO> bestPerDiscount = new HashMap<>();
        for (CouponDiscountDTO solution : bestPerCombo.values()) {
            int amount = solution.getDiscountAmount();
            CouponDiscountDTO existing = bestPerDiscount.get(amount);
            if (existing == null || solution.getIds().size() < existing.getIds().size()) {
                bestPerDiscount.put(amount, solution);
            }
        }

        // 取交集结果，按折扣金额降序排列，最多返回 3 个方案
        return bestPerDiscount.values().stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .limit(3)
                .collect(Collectors.toList());
    }

    /**
     * 计算一种优惠券组合方案的折扣结果
     * <p>
     * 对于方案中的多张优惠券，按顺序逐张计算折扣，支持优惠券叠加使用。
     * 后一张优惠券基于前一张优惠券折扣后的课程剩余金额进行计算，
     * 即每门课程的已优惠金额会从总金额中扣除，作为后续优惠券的计算基数。
     * 最终返回该方案的优惠券 id 列表、规则描述及累计折扣总金额。
     *
     * @param solution            优惠券组合方案（有序列表）
     * @param courses             订单中的所有课程列表
     * @param availableCouponsMap 每张优惠券对应的可用课程映射
     * @return 该方案的折扣结果 DTO，包含使用的优惠券 id、规则描述和折扣总金额
     */
    private CouponDiscountDTO calculateSolutionDiscount(
            List<Coupon> solution, List<OrderCourseDTO> courses, Map<Coupon, List<OrderCourseDTO>> availableCouponsMap) {
        CouponDiscountDTO dto = new CouponDiscountDTO();
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, oc -> 0));

        for (Coupon coupon : solution) {
            // 防御：券不在可用映射中（无可用课程 / 不满足门槛），跳过
            List<OrderCourseDTO> availableCourses = availableCouponsMap.get(coupon);
            if (CollUtils.isEmpty(availableCourses)) continue;

            int totalAmount = availableCourses.stream()
                    .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId())).sum();

            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount == null || !discount.canUse(totalAmount, coupon)) continue;

            int discountAmount = discount.calculateDiscount(totalAmount, coupon);
            calculateDiscountDetail(availableCourses, discountAmount, totalAmount, detailMap);

            dto.getIds().add(coupon.getId());
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());
            dto.setDiscountDetail(detailMap);
        }
        return dto;
    }

    /**
     * 将优惠券的折扣金额按比例分摊到各门可用课程上，并累加每门课程的已优惠明细
     * <p>
     * 分摊策略：每门课程按其剩余金额（原价 - 已优惠金额）占总剩余金额的比例分配折扣。
     * 由于整数除法会产生舍入误差，最后一门课程采用兜底方式，
     * 取剩余未分配的全部折扣金额，确保总折扣金额与入参一致。
     *
     * @param courses        当前优惠券可用的课程列表
     * @param discountAmount 当前优惠券计算出的折扣总金额
     * @param totalAmount    可用课程的剩余总金额（分摊计算的分母）
     * @param detailMap      课程已优惠金额的累计明细映射（key: 课程id, value: 已优惠金额）
     */
    private void calculateDiscountDetail(List<OrderCourseDTO> courses, int discountAmount, int totalAmount, Map<Long, Integer> detailMap) {
        int times = 0;
        int remainDiscount = discountAmount;
        for (OrderCourseDTO course : courses) {
            times++;
            int discount = 0;
            if (times == courses.size()) {
                discount = remainDiscount;
            } else {
                // 按课程剩余金额（原价 - 已优惠金额）占总剩余金额的比例分配
                int remainingPrice = course.getPrice() - detailMap.get(course.getId());
                discount = remainingPrice * discountAmount / totalAmount;
                remainDiscount -= discount;
            }
            detailMap.put(course.getId(), discount + detailMap.get(course.getId()));
        }
    }

    /**
     * 为每张优惠券筛选出可用的课程列表，构建优惠券 → 可用课程的映射关系
     * <p>
     * 处理逻辑：
     * <ul>
     *   <li>如果优惠券指定了适用分类范围（{@link Coupon#getSpecific()} 为 true），
     *      则查询其适用分类范围，仅保留匹配分类的课程</li>
     *   <li>如果某张优惠券无可用课程，则跳过该优惠券</li>
     *   <li>进一步校验可用课程的总金额是否满足优惠券的使用门槛，
     *      不满足的优惠券不会被放入结果映射中</li>
     * </ul>
     *
     * @param coupons 用户拥有的所有可用优惠券列表
     * @param courses 订单中的课程列表
     * @return 优惠券与其可用课程列表的映射关系，仅包含满足使用门槛的优惠券
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCouponsMap(List<Coupon> coupons, List<OrderCourseDTO> courses) {
        // 批量查询所有 scope 限定券的适用范围，避免 N+1 查询
        List<Long> specificCouponIds = coupons.stream()
                .filter(c -> Boolean.TRUE.equals(c.getSpecific()))
                .map(Coupon::getId)
                .collect(Collectors.toList());
        Map<Long, Set<Long>> scopeMap = Collections.emptyMap();
        if (!specificCouponIds.isEmpty()) {
            List<CouponScope> allScopes = couponScopeService.lambdaQuery()
                    .in(CouponScope::getCouponId, specificCouponIds)
                    .list();
            scopeMap = allScopes.stream()
                    .collect(Collectors.groupingBy(
                            CouponScope::getCouponId,
                            Collectors.mapping(CouponScope::getBizId, Collectors.toSet())));
        }

        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>(coupons.size());
        for (Coupon coupon : coupons) {
            List<OrderCourseDTO> availableCourses = courses;
            if (Boolean.TRUE.equals(coupon.getSpecific())) {
                Set<Long> scopeIds = scopeMap.getOrDefault(coupon.getId(), Collections.emptySet());
                availableCourses = courses.stream()
                        .filter(c -> scopeIds.contains(c.getCateId()))
                        .collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) continue;
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount != null && discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }
        }
        return map;
    }


    @Override
    public CouponDiscountDTO queryDiscountDetailByOrder(OrderCouponDTO orderCouponDTO) {
        List<Long> ids = orderCouponDTO.getUserCouponIds();
        List<OrderCourseDTO> courses = orderCouponDTO.getCourseList();
        if (CollUtils.isEmpty(courses) || CollUtils.isEmpty(ids)) return null;
        // 1.查询用户券，校验有效期
        List<UserCoupon> userCoupons = userCouponMapper.selectList(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, UserContext.getUserId())
                        .in(UserCoupon::getCouponId, ids)
                        .eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                        .le(UserCoupon::getTermBeginTime, LocalDateTime.now())
                        .gt(UserCoupon::getTermEndTime, LocalDateTime.now())
        );
        if (CollUtils.isEmpty(userCoupons)) return null;

        // 1.1 关键校验：只允许「用户真正持有」的券参与计算。
        //     入参是前端提交的 couponIds，可以直接构造。原来只判断 userCoupons 非空，
        //     随后却按 ids 从 coupon 模板表取券 —— 用户传一张自己没领过的 couponId
        //     也能算进优惠，属于越权用券（下单时 writeOffCoupon 只核销真实持有的那张，
        //     差额等于白送）。
        Set<Long> ownedCouponIds = userCoupons.stream()
                .map(UserCoupon::getCouponId)
                .collect(Collectors.toSet());
        List<Long> validIds = ids.stream()
                .filter(ownedCouponIds::contains)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtils.isEmpty(validIds)) return null;

        // 2.查询优惠券模板，按用户提交的顺序重排（保证叠券顺序与确认页一致）
        Map<Long, Coupon> couponByTemplateId = couponService.lambdaQuery()
                .in(Coupon::getId, validIds).list().stream()
                .collect(Collectors.toMap(Coupon::getId, c -> c, (a, b) -> a));
        List<Coupon> orderedCoupons = new ArrayList<>(validIds.size());
        for (Long id : validIds) {
            Coupon c = couponByTemplateId.get(id);
            if (c != null) orderedCoupons.add(c);
        }
        if (CollUtils.isEmpty(orderedCoupons)) return null;
        Map<Coupon, List<OrderCourseDTO>> availableCouponsMap = findAvailableCouponsMap(orderedCoupons, courses);
        return calculateSolutionDiscount(orderedCoupons, courses, availableCouponsMap);
    }
}