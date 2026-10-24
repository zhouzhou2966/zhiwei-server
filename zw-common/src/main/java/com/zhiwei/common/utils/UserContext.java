package com.zhiwei.common.utils;

public class UserContext {
    private static final ThreadLocal<Long> TL = new ThreadLocal<>();

    /**
     * 保存用户信息
     * @param userId 用户id
     */
    public static void setUserId(Long userId){
        TL.set(userId);
    }

    /**
     * 获取用户
     * @return 用户id
     */
    public static Long getUserId(){
        return TL.get();
    }

    /**
     * 移除用户信息
     */
    public static void removeUserId(){
        TL.remove();
    }
}
