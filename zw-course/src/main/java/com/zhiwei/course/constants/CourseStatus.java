package com.zhiwei.course.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

/**
 * @ClassName CourseStatus
 * @Author wusongsong
 * @Date 2022/7/18 16:07
 * @Version
 **/
@Getter
@AllArgsConstructor
public enum CourseStatus {
    NO_UP_SHELF(1, "待上架"),
    SHELF(2, "已上架"),
    DOWN_SHELF(3, "下架"),
    FINISHED(4, "已完结");
    private Integer status;
    private String desc;

    public static String desc(Integer status) {
        for (CourseStatus courseStatus : values()) {
            // 必须用 Objects.equals：getStatus() 与 status 都是 Integer，用 == 比较的是引用。
            // 1~4 恰好落在 Integer 缓存（-128~127）里所以现在不会出错，
            // 但这是「碰巧对」，枚举值一旦超出缓存范围就会静默返回 null。
            if (Objects.equals(courseStatus.getStatus(), status)) {
                return courseStatus.getDesc();
            }
        }
        return null;
    }

    /**
     * 按状态值查枚举，查不到返回 null。
     * <p>
     * 用于「先判断状态是否合法，再决定走哪个分支」的场景。
     * 原来的写法是 {@code CourseStatus.XXX.equals(status)}，只能回答「是不是某一个」，
     * 判断「是不是合法值之一」得把四个枚举都列一遍。
     * </p>
     */
    public static CourseStatus of(Integer status) {
        if (status == null) {
            return null;
        }
        for (CourseStatus courseStatus : values()) {
            if (courseStatus.status.intValue() == status.intValue()) {
                return courseStatus;
            }
        }
        return null;
    }

    /**
     * 判断当前枚举是否等于给定状态值。
     * <p>
     * <b>入参必须判空</b>：原来是 {@code this.status.intValue() == status}，直接对 Integer 拆箱，
     * 传 null 就抛 NullPointerException。而调用方（管理端课程搜索接口）的 status 是可以不传的，
     * 于是「不传状态」变成 500「服务器内部异常」—— 一个本该是参数错误的请求被报成了服务端故障，
     * 排查时只会去翻服务端日志，看不出是参数没传。
     * </p>
     * <p>
     * 现在 null 一律返回 false：状态未知就当作「不等于任何已知状态」，由调用方决定怎么处理
     * （例如参数校验阶段就拒绝掉），而不是在这里抛异常。
     * </p>
     */
    public boolean equals(Integer status){
        return status != null && this.status.intValue() == status.intValue();
    }
}
