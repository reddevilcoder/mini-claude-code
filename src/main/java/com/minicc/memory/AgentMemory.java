package com.minicc.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 读取人写的项目约定 {@code AGENT.md}。
 *
 * <p>这是「两路长期记忆」里由<b>人</b>维护、<b>随仓库提交</b>的那一路：构建命令、代码风格、接口约定
 * 等写在项目级 {@code <项目>/.mcc/AGENT.md} 里，会话开始时整份读进上下文。它和项目级配置
 * {@code <项目>/.mcc/mcc.yml}、项目级 skills {@code <项目>/.mcc/skills} 一起挂在项目的 {@code .mcc/}
 * 目录下（随仓库走）。另一路（模型自己写的 auto memory）由 {@link AutoMemory} 负责——别把两者搞混。
 *
 * <p>加载顺序：先读可选的全局 {@code ~/.mcc/AGENT.md}（对所有项目通用的个人约定），
 * 再读项目级 {@code <项目>/.mcc/AGENT.md}（主路径，更贴近当前项目，排在后面）。都没有则返回空。
 *
 * <p>本类只负责「读成文本」，不解析、不校验——{@code AGENT.md} 是给模型看的自然语言约定。
 * 这里只取「项目根一个文件（+ 可选全局）」这条主线，省掉 managed/user/rules/add-dir 等层级。
 */
public class AgentMemory {

    /** 约定文件名。 */
    public static final String AGENT_FILE = "AGENT.md";

    /** 项目级 {@code .mcc} 目录名：项目约定、配置、skills 都挂在这里（随仓库走）。 */
    public static final String PROJECT_DIR = ".mcc";

    private final Path projectRoot;   // 项目根，项目级 AGENT.md 落在其 .mcc/ 下
    private final Path stateDir;      // ~/.mcc，用于定位可选的全局 AGENT.md

    public AgentMemory(Path projectRoot, Path stateDir) {
        this.projectRoot = projectRoot;
        this.stateDir = stateDir;
    }

    /**
     * 返回拼好的项目约定文本（全局在前、项目在后）；两个文件都没有时返回 {@link Optional#empty()}。
     */
    public Optional<String> load() {
        StringBuilder sb = new StringBuilder();
        readIfExists(stateDir.resolve(AGENT_FILE))        // 可选的全局约定（~/.mcc/AGENT.md）
                .ifPresent(text -> sb.append(text).append("\n\n"));
        readIfExists(projectRoot.resolve(PROJECT_DIR).resolve(AGENT_FILE))  // 项目级约定（<项目>/.mcc/AGENT.md，主路径）
                .ifPresent(sb::append);
        String merged = sb.toString().strip();
        return merged.isEmpty() ? Optional.empty() : Optional.of(merged);
    }

    /** 文件存在且非空则读成文本，否则返回空。 */
    private static Optional<String> readIfExists(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
