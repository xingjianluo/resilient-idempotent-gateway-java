package com.github.xingjianluo.resilientidempotentgatewayjava.tests.support;

/**
 * 单元测试统一日志：明确打印【输入】【判定依据】【状态迁移】，
 * 便于在 mvn 输出中复盘每个断言的推理过程。
 */
public final class TestLog {

    private TestLog() {
    }

    public static void input(String format, Object... args) {
        System.out.println("  [输入] " + fmt(format, args));
    }

    public static void basis(String format, Object... args) {
        System.out.println("  [判定依据] " + fmt(format, args));
    }

    public static void transition(String format, Object... args) {
        System.out.println("  [状态迁移] " + fmt(format, args));
    }

    public static void section(String name) {
        System.out.println("== " + name + " ==");
    }

    private static String fmt(String format, Object... args) {
        return args.length == 0 ? format : format.formatted(args);
    }
}
