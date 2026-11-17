package com.minicc.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端选择卡片（M3）：审批卡片和提问卡片共用这一套"打印选项 + 读一行编号"。
 *
 * <p>交互故意不用方向键。方向键在终端里是 {@code ESC [ A} 这样的转义序列，要自己解码、
 * 用 ANSI 把光标拉回去重绘，还得把终端切进 raw 模式。这些和"审批 / 提问"无关，学员读起来也费劲。
 * 改成输入编号后，卡片就是把选项打印出来，再 {@link #readLine(String)} 读一行。
 *
 * <p>单选输入 {@code 1}、{@code 2}…；多选输入 {@code 1,3}（逗号、空格或中文逗号都行）。
 * 编号不合法会再问一次。卡片等待输入时按 Ctrl-C（或 Ctrl-D）会中断这一行，{@link #readLine} 返回 null，
 * 调用方据此当成"取消整个任务"。
 *
 * <p>全程用 JLine 的 {@code LineReader} 按行读取，本类无需自行管理 raw 模式、
 * 解析方向键转义序列，也不需要和别的监听线程抢输入。底层终端模式由 JLine 管理。执行期间的取消由
 * {@code Repl} 装的 Ctrl-C 信号处理函数负责，与本类无关。
 *
 * <p><b>面试关注：人机交互为什么拆成单独组件？</b>
 * 卡片只处理“展示、读取、解析”，批准的权限范围由审批器决定，问题答案的含义由提问器处理。
 * parseSingle / parseMulti 是不依赖终端的解析函数，可以直接测试越界、超大数字、去重等边界。
 * select 的 -1、multiSelect/readLine 的 null 是取消信号，调用方必须与非法输入区分，
 * 不能把用户中断误当成默认批准。
 */
public class CardPrompter {

    private static final boolean COLOR = System.getenv("NO_COLOR") == null;
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private final Terminal terminal;
    private final LineReader reader;

    public CardPrompter(Terminal terminal) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    /**
     * 单选。返回选中项的下标（从 0 起）；用户取消时返回 -1。
     *
     * @param preview 预览行（diff 摘要、将执行的命令、问题文本），可为 null
     * @param title   卡片标题
     * @param options 选项文案，按 1、2、3… 显示
     */
    public int select(List<String> preview, String title, List<String> options) {
        printCard(preview, title, options);
        String prompt = "  请选择 [1-" + options.size() + "]：";
        while (true) {
            String line = readLine(prompt);
            if (line == null) {
                return -1;
            }
            Integer index = parseSingle(line, options.size());
            if (index != null) {
                return index;
            }
            say("  请输入 1 到 " + options.size() + " 之间的编号。");
        }
    }

    /**
     * 多选。返回选中项的下标（按输入顺序、去重）；用户取消时返回 null。
     * 一行里用逗号、空格或中文逗号分隔，例如 {@code 1,3}。
     */
    public List<Integer> multiSelect(List<String> preview, String title, List<String> options) {
        printCard(preview, title, options);
        String prompt = "  请选择（多选用逗号分隔，如 1,3）：";
        while (true) {
            String line = readLine(prompt);
            if (line == null) {
                return null;
            }
            List<Integer> indexes = parseMulti(line, options.size());
            if (indexes != null) {
                return indexes;
            }
            say("  请输入有效编号，多选用逗号或空格分隔。");
        }
    }

    /**
     * 读一行自由文本（拒绝原因、"其他"的补充说明）。
     * 用户按 Ctrl-C / Ctrl-D 时返回 null，调用方按取消任务处理。
     */
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    /** 解析单选编号，合法时返回从 0 起的下标，否则返回 null。 */
    static Integer parseSingle(String line, int optionCount) {
        if (line == null || optionCount <= 0) {
            return null;
        }
        String text = line.strip();
        if (!text.matches("\\d+")) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > optionCount) {
            return null;
        }
        return n - 1;
    }

    /**
     * 解析多选。合法时返回下标列表；有任何一个编号不合法，或一个都没选，返回 null（让上层再问一次）。
     */
    static List<Integer> parseMulti(String line, int optionCount) {
        if (line == null) {
            return null;
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return null;
        }
        List<Integer> indexes = new ArrayList<>();
        for (String part : text.split("[,，、\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            Integer one = parseSingle(part, optionCount);
            if (one == null) {
                return null;
            }
            if (!indexes.contains(one)) {
                indexes.add(one);
            }
        }
        return indexes.isEmpty() ? null : indexes;
    }

    private void printCard(List<String> preview, String title, List<String> options) {
        PrintWriter w = terminal.writer();
        w.println();
        if (preview != null) {
            for (String line : preview) {
                w.println(dim("  " + line));
            }
        }
        if (title != null && !title.isBlank()) {
            w.println("  " + bold(title));
        }
        for (int i = 0; i < options.size(); i++) {
            w.println("    " + (i + 1) + ". " + options.get(i));
        }
        w.flush();
    }

    private void say(String message) {
        PrintWriter w = terminal.writer();
        w.println(message);
        w.flush();
    }

    private static String bold(String s) {
        return COLOR ? BOLD + s + RESET : s;
    }

    private static String dim(String s) {
        return COLOR ? DIM + s + RESET : s;
    }
}
