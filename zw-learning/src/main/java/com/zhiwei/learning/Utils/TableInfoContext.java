package com.zhiwei.learning.Utils;

public class TableInfoContext {
    public static final ThreadLocal<String> TL = new ThreadLocal<>();

    public static void setInfo(String info) {
        TL.set(info);
    }

    public static String getInfo() {
        return TL.get();
    }

    public static void clear() {
        TL.remove();
    }
}
