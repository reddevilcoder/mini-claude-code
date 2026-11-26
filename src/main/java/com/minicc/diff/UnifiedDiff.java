package com.minicc.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写的行级 unified diff（M10）：审批卡片里展示“将要发生的改动”。
 *
 * <p>算法：先剥掉公共前缀/后缀（Edit 通常只改中间几行，这一步就把问题规模降到很小），
 * 再对剩下的中间段做 LCS 动态规划回溯出 -/+ 行，最后按上下文行数切成 {@code @@} hunk。
 *
 * <p><b>面试关注：为什么不直接上 Myers？</b>
 * Myers 是 O((N+M)·D)，更适合大文件；这里先剥前后缀，中间段通常只有几十行，O(N·M) 的 LCS 足够且易读。
 * 中间段过大（超过 {@link #MAX_CELLS} 个格子）时不做 LCS，退化为“整段删除 + 整段新增”，
 * 用可读性换取有界的时间和内存——审批卡片不能因为一次大文件重写而卡住。
 */
public final class UnifiedDiff {

    /** LCS 表格的格子上限（约 4M 个 int ≈ 16MB）。 */
    static final long MAX_CELLS = 4_000_000L;

    private UnifiedDiff() {
    }

    /** 一行 diff：' ' 上下文、'-' 删除、'+' 新增。 */
    public record Line(char op, String text) {
    }

    /**
     * 生成 unified diff 文本行（不含 ---/+++ 头）。before 为 null 视为空文件（新建）。
     *
     * @param context 每个 hunk 前后保留的上下文行数
     */
    public static List<String> diff(String before, String after, int context) {
        List<Line> ops = lines(before, after);
        return hunks(ops, context);
    }

    /** 统计新增 / 删除行数。 */
    public static int[] stat(String before, String after) {
        int add = 0;
        int del = 0;
        for (Line l : lines(before, after)) {
            if (l.op() == '+') {
                add++;
            } else if (l.op() == '-') {
                del++;
            }
        }
        return new int[]{add, del};
    }

    static List<Line> lines(String before, String after) {
        String[] a = split(before);
        String[] b = split(after);
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
            pre++;
        }
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
            suf++;
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) {
            out.add(new Line(' ', a[i]));
        }
        int aEnd = a.length - suf;
        int bEnd = b.length - suf;
        int n = aEnd - pre;
        int m = bEnd - pre;
        if ((long) n * m > MAX_CELLS) {
            for (int i = pre; i < aEnd; i++) {
                out.add(new Line('-', a[i]));
            }
            for (int j = pre; j < bEnd; j++) {
                out.add(new Line('+', b[j]));
            }
        } else {
            // dp[i][j] = a[pre+i..aEnd) 与 b[pre+j..bEnd) 的 LCS 长度（从后往前填，便于正向回溯）
            int[][] dp = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    dp[i][j] = a[pre + i].equals(b[pre + j]) ? dp[i + 1][j + 1] + 1
                            : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n && j < m) {
                if (a[pre + i].equals(b[pre + j])) {
                    out.add(new Line(' ', a[pre + i]));
                    i++;
                    j++;
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    out.add(new Line('-', a[pre + i++]));
                } else {
                    out.add(new Line('+', b[pre + j++]));
                }
            }
            while (i < n) {
                out.add(new Line('-', a[pre + i++]));
            }
            while (j < m) {
                out.add(new Line('+', b[pre + j++]));
            }
        }
        for (int i = aEnd; i < a.length; i++) {
            out.add(new Line(' ', a[i]));
        }
        return out;
    }

    private static List<String> hunks(List<Line> ops, int context) {
        List<String> out = new ArrayList<>();
        int k = 0;
        int size = ops.size();
        while (k < size) {
            // 找下一处改动
            int first = k;
            while (first < size && ops.get(first).op() == ' ') {
                first++;
            }
            if (first == size) {
                break;
            }
            int start = Math.max(k, first - context);
            // 向后延伸：两处改动之间的上下文 ≤ 2*context 时并入同一 hunk
            int end = first;
            int lastChange = first;
            while (end < size) {
                if (ops.get(end).op() != ' ') {
                    lastChange = end;
                } else if (end - lastChange > 2 * context) {
                    break;
                }
                end++;
            }
            end = Math.min(size, lastChange + context + 1);
            // 计算 hunk 头的行号
            int aLine = 1;
            int bLine = 1;
            for (int t = 0; t < start; t++) {
                char op = ops.get(t).op();
                if (op != '+') {
                    aLine++;
                }
                if (op != '-') {
                    bLine++;
                }
            }
            int aCount = 0;
            int bCount = 0;
            for (int t = start; t < end; t++) {
                char op = ops.get(t).op();
                if (op != '+') {
                    aCount++;
                }
                if (op != '-') {
                    bCount++;
                }
            }
            out.add("@@ -" + aLine + "," + aCount + " +" + bLine + "," + bCount + " @@");
            for (int t = start; t < end; t++) {
                out.add(ops.get(t).op() + ops.get(t).text());
            }
            k = end;
        }
        return out;
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        String body = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        return body.split("\n", -1);
    }
}
