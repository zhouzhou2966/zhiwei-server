package com.zhiwei.promotion.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于回溯算法的全排列工具类
 */
public class PermuteUtil {
    /**
     * 将[0~n)的所有数字重组，生成不重复的所有排列方案
     *
     * @param n 数字n
     * @return 排列组合
     */
    public static List<List<Byte>> permute(int n) {
        List<List<Byte>> res = new ArrayList<>();

        List<Byte> input = new ArrayList<>(n);
        for (byte i = 0; i < n; i++) {
            input.add(i);
        }

        backtrack(n, input, res, 0);
        return res;
    }

    /**
     * 将指定集合中的元素重组，生成所有的排列组合方案
     *
     * @param input 输入的集合
     * @param <T>   集合类型
     * @return 重组后的集合方案
     */
    public static <T> List<List<T>> permute(List<T> input) {
        List<List<T>> res = new ArrayList<>();
        backtrack(input.size(), input, res, 0);
        return res;
    }

    /**
     * 将指定集合中的元素重组，生成长度不超过 maxLen 的所有排列方案（包含 1~maxLen 的所有排列）
     *
     * @param input  输入的集合
     * @param maxLen 排列最大长度
     * @param <T>    集合类型
     * @return 长度 1~maxLen 的所有排列方案
     */
    public static <T> List<List<T>> permute(List<T> input, int maxLen) {
        List<List<T>> res = new ArrayList<>();
        boolean[] used = new boolean[input.size()];
        List<T> current = new ArrayList<>();
        selectBacktrack(input, maxLen, res, current, used);
        return res;
    }

    private static <T> void selectBacktrack(List<T> input, int maxLen, List<List<T>> res, List<T> current, boolean[] used) {
        if (!current.isEmpty()) {
            res.add(new ArrayList<>(current));
        }
        if (current.size() == maxLen) {
            return;
        }
        for (int i = 0; i < input.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            current.add(input.get(i));
            selectBacktrack(input, maxLen, res, current, used);
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    private static <T> void backtrack(int n, List<T> input, List<List<T>> res, int first) {
        // 所有数都填完了
        if (first == n) {
            res.add(new ArrayList<>(input));
        }
        for (int i = first; i < n; i++) {
            // 动态维护数组
            Collections.swap(input, first, i);
            // 继续递归填下一个数
            backtrack(n, input, res, first + 1);
            // 撤销操作
            Collections.swap(input, first, i);
        }
    }
}