package com.minicc.cli.command;

import com.minicc.cli.CardPrompter;
import com.minicc.cli.Renderer;
import com.minicc.memory.AgentMemory;
import com.minicc.memory.AutoMemory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /memory}（M5）：查看与编辑记忆文件——人写的 {@code AGENT.md}、模型维护的 {@code MEMORY.md}
 * 索引及各主题文件。
 *
 * <p>最小闭环：列出可编辑的记忆文件 → 编号选一个 → 用外部编辑器（{@code $VISUAL}/{@code $EDITOR}）打开；
 * 文件不存在则先建一个空文件再打开（如首次编辑 {@code AGENT.md}）。没设编辑器就打印路径让用户自己打开。
 *
 * <p>相比完整实现，去掉 React 对话框、文件选择器、缓存失效等 UI 细节，
 * 只保留「列出 → 选 → 用外部编辑器打开」。
 */
public final class MemoryCommand implements SlashCommand {

    private final Path projectRoot;   // 项目根：项目级 AGENT.md 落在其 .mcc/ 下
    private final Path stateDir;      // ~/.mcc：可选的全局 AGENT.md
    private final AutoMemory autoMemory;  // 记忆目录：MEMORY.md 及主题文件
    private final CardPrompter prompter;  // 复用编号选择卡片
    private final Renderer renderer;

    public MemoryCommand(Path projectRoot, Path stateDir, AutoMemory autoMemory,
                         CardPrompter prompter, Renderer renderer) {
        this.projectRoot = projectRoot;
        this.stateDir = stateDir;
        this.autoMemory = autoMemory;
        this.prompter = prompter;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "查看或编辑记忆（AGENT.md、MEMORY.md 及主题文件）";
    }

    @Override
    public boolean run(String args) {
        List<Entry> files = memoryFiles();
        if (files.isEmpty()) {
            renderer.info("暂无记忆文件。");
            return false;
        }
        List<String> options = new ArrayList<>();
        for (Entry e : files) {
            options.add(e.label + (Files.exists(e.path) ? "" : "（尚未创建）"));
        }
        int choice = prompter.select(null, "选择要查看/编辑的记忆文件：", options);
        if (choice < 0) {
            renderer.info("已取消。");
            return false;
        }
        openInEditor(files.get(choice).path);
        return false;
    }

    /** 可编辑的记忆文件清单：项目 AGENT.md、（存在的）全局 AGENT.md、MEMORY.md、各主题文件。 */
    private List<Entry> memoryFiles() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("项目约定 .mcc/AGENT.md",
                projectRoot.resolve(AgentMemory.PROJECT_DIR).resolve(AgentMemory.AGENT_FILE)));
        Path globalAgent = stateDir.resolve(AgentMemory.AGENT_FILE);
        if (Files.exists(globalAgent)) {
            entries.add(new Entry("全局约定 ~/.mcc/AGENT.md", globalAgent));
        }
        Path memoryDir = autoMemory.dir();
        entries.add(new Entry("记忆索引 MEMORY.md", memoryDir.resolve(AutoMemory.INDEX_NAME)));
        // 各主题文件（memory/*.md，排除 MEMORY.md 本身）。
        if (Files.isDirectory(memoryDir)) {
            try {
                Files.list(memoryDir)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().equals(AutoMemory.INDEX_NAME))
                        .sorted()
                        .forEach(p -> entries.add(new Entry("主题 " + p.getFileName(), p)));
            } catch (IOException e) {
                // 列不出主题文件无妨，至少还能编辑 AGENT.md / MEMORY.md。
            }
        }
        return entries;
    }

    /** 用外部编辑器打开文件（不存在则先建空文件）；没设 $VISUAL/$EDITOR 就打印路径。 */
    private void openInEditor(Path target) {
        try {
            if (!Files.exists(target)) {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, "", StandardCharsets.UTF_8);   // 首次编辑则建空文件
            }
        } catch (IOException e) {
            renderer.error("创建文件失败：" + e.getMessage());
            return;
        }

        String editor = System.getenv("VISUAL");
        if (editor == null || editor.isBlank()) {
            editor = System.getenv("EDITOR");
        }
        if (editor == null || editor.isBlank()) {
            renderer.info("未设置 $VISUAL / $EDITOR，请手动打开：" + target);
            return;
        }
        try {
            // 继承当前终端的输入输出，让 vim/nano 这类全屏编辑器正常工作；等用户编辑完再返回。
            Process process = new ProcessBuilder(editor, target.toString()).inheritIO().start();
            int code = process.waitFor();
            if (code == 0) {
                renderer.info("已用 " + editor + " 编辑：" + target);
            } else {
                renderer.info("编辑器退出码 " + code + "：" + target);
            }
        } catch (IOException e) {
            renderer.error("无法启动编辑器 " + editor + "：" + e.getMessage() + "。文件路径：" + target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            renderer.error("等待编辑器时被中断：" + target);
        }
    }

    /** 清单里的一项：展示用标签 + 实际路径。 */
    private record Entry(String label, Path path) {
    }
}
