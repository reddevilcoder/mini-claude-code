package com.minicc.tool.bash;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 沙箱：把 Bash 命令包进操作系统级沙箱后再执行，限制它能写入的目录。
 *
 * <p>macOS 用 seatbelt（{@code sandbox-exec} + 一段 profile）、Linux 用
 * bubblewrap（{@code bwrap}）。mini-cc 做一个可运行、可讲清楚的简化版：
 * <ul>
 *   <li>macOS：{@code sandbox-exec -p "<profile>"}，允许读、把写入限制在项目目录与临时目录；</li>
 *   <li>Linux：若有 {@code bwrap}，把根只读绑定、项目目录可写绑定；</li>
 *   <li>降级：检测不到可用沙箱时打印一次提示，退化为不受沙箱限制地执行，保证"下载即可运行"。</li>
 * </ul>
 *
 * <p>M2 不实现细粒度文件/网络规则、命令豁免、裸库防护等。
 *
 * <p><b>面试关注：审批与沙箱各防什么？</b>审批决定是否授权某项操作，沙箱在命令运行期间限制可访问的资源，
 * 防止已批准的命令意外越界。本类只包装 Bash 子进程，Java 进程内直接实现的 Read/Edit/Write 不会自动受到
 * 这层沙箱约束。两个平台都允许广泛读取，也没有在此实现网络隔离，不能把“只限制写目录”说成全面隔离。
 *
 * <p><b>面试追问：启用 sandbox=true 就一定受保护吗？</b>不会。探测只检查可执行文件是否在 PATH 中，
 * 缺失时按本项目的易用性取舍降级为裸执行（fail-open）；文件存在但沙箱启动失败时不会自动重试裸执行，
 * 而会由命令结果报告失败。更严格的部署可选择“不可用即拒绝执行”。这里的 WARNED 只用 CAS 保证并发下
 * 降级提示最多输出一次，不代表记录了一次成功的隔离环境自检。
 */
public final class Sandbox {

    /** 降级提示只打一次，避免刷屏。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private Sandbox() {
    }

    /**
     * 把命令包装成实际要执行的 argv。
     *
     * @param command    原始 shell 命令
     * @param projectDir 允许写入的项目目录
     * @return 交给 ProcessBuilder 的参数列表
     */
    public static List<String> wrap(String command, Path projectDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") && hasExecutable("sandbox-exec")) {
            return List.of("sandbox-exec", "-p", seatbeltProfile(projectDir), "/bin/sh", "-c", command);
        }
        if (os.contains("linux") && hasExecutable("bwrap")) {
            return bubblewrapArgv(command, projectDir);
        }
        if (strict) {
            // M0：无人值守（--permission auto）时不允许降级为裸执行。
            throw new IllegalStateException("未检测到可用沙箱（sandbox-exec / bwrap），严格模式下拒绝执行 Bash 命令。");
        }
        warnOnce();
        return List.of("/bin/sh", "-c", command);
    }

    /**
     * 严格模式（fail-closed）：沙箱不可用时拒绝执行，而不是降级裸跑。
     * 交互模式保持原来的"易用性优先"降级；Headless + 自动批准时必须开启。
     */
    private static volatile boolean strict = false;

    public static void setStrict(boolean value) {
        strict = value;
    }

    /** 不加沙箱时的直接执行形式。 */
    public static List<String> plain(String command) {
        return List.of("/bin/sh", "-c", command);
    }

    /** macOS seatbelt profile：默认允许，禁止一切写，再放开项目目录与临时目录的写。 */
    private static String seatbeltProfile(Path projectDir) {
        String dir = projectDir.toAbsolutePath().normalize().toString();
        return "(version 1)\n"
                + "(allow default)\n"
                + "(deny file-write*)\n"
                + "(allow file-write* (subpath \"" + dir + "\")"
                + " (subpath \"/tmp\") (subpath \"/private/tmp\") (subpath \"/dev\"))\n";
    }

    /** Linux bubblewrap：根只读、项目目录可写、保留 /dev /proc /tmp。 */
    private static List<String> bubblewrapArgv(String command, Path projectDir) {
        String dir = projectDir.toAbsolutePath().normalize().toString();
        return List.of(
                "bwrap",
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--proc", "/proc",
                "--tmpfs", "/tmp",
                "--bind", dir, dir,
                "/bin/sh", "-c", command
        );
    }

    /** 检测某个可执行文件是否可用（执行 `which`）。 */
    private static boolean hasExecutable(String name) {
        try {
            Process p = new ProcessBuilder("which", name).redirectErrorStream(true).start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void warnOnce() {
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("⚠ 未检测到可用沙箱（sandbox-exec / bwrap），Bash 命令将不受沙箱限制地执行。");
        }
    }
}
