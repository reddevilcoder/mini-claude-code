package com.minicc.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 技能加载器（M7）：会话开始时扫描「全局 + 项目级」两层技能目录，解析每个
 * {@code skill-name/SKILL.md} 的 frontmatter（{@code ---} 之间的 YAML），返回技能元数据列表。
 *
 * <p><b>只保留 frontmatter 元数据、不把正文注入上下文</b>——当前解析时通过 Files.readString
 * 临时读取整份文件，但返回的 Skill 不保存正文，等模型判断相关时再用 Read 工具按需取回。
 * 这节省的是常驻元数据与提示词成本，并不意味着加载器从未读取正文。和 M5 的
 * {@code AutoMemory} 只加载 {@code MEMORY.md} 索引、主题文件按需 Read 是镜像的两件事。
 *
 * <p>只取主干：
 * <ul>
 *   <li>目录格式 {@code skill-name/SKILL.md}（只支持目录格式，不支持散装的单个 {@code .md}）；</li>
 *   <li>从 frontmatter 取 {@code name} / {@code description} / {@code when_to_use}；</li>
 *   <li>用户级（全局）与项目级多个目录来源。</li>
 * </ul>
 * 去掉 plugin / MCP / 远程 / 条件触发（{@code paths}）/ 动态发现 / 技能 hooks 等。
 *
 * <p><b>面试关注：发现、选择、执行三件事如何分工？</b>本类只负责启动时发现和解析元数据，
 * SkillStage 负责把清单放入提示词，由模型判断是否读取正文；真正执行仍通过普通工具。
 * “匹配技能必须先加载”属于模型指令约束，没有独立的强制匹配器或读取状态机。
 *
 * <p>稳定排序与 LinkedHashMap 去重使清单顺序可重复，有利于提示词前缀保持稳定；
 * 覆盖已有键只替换值，不会把该键移到列表末尾。当前没有文件监听或自动重新扫描，
 * 清单也没有可信来源验证，不能把来自项目目录的自然语言指令当成新的系统权限。
 */
public class SkillLoader {

    /** 约定的技能文件名：每个技能目录里必须有它，否则跳过该目录。 */
    static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path globalSkillsDir;   // 全局 ~/.mcc/skills（所有项目通用）
    private final Path projectSkillsDir;  // 项目级 <项目>/.mcc/skills（随仓库走，像 AGENT.md）

    public SkillLoader(Path globalSkillsDir, Path projectSkillsDir) {
        this.globalSkillsDir = globalSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
    }

    /**
     * 启动时若全局技能目录不存在，顺手创建一个空目录，方便用户往里放技能（和 {@code AutoMemory}
     * 的 {@code ensureMemoryDirExists} 同一风格）。失败只打印，不致命。
     */
    public void ensureGlobalDirExists() {
        if (globalSkillsDir == null) {
            return;
        }
        try {
            Files.createDirectories(globalSkillsDir);
        } catch (IOException e) {
            System.err.println("创建技能目录失败（已忽略）：" + globalSkillsDir + " —— " + e.getMessage());
        }
    }

    /**
     * 加载全部技能：先全局、后项目级；同名时<b>项目级覆盖全局</b>（更靠近当前项目的优先，
     * 和配置、记忆的层叠约定一致）。
     *
     * <p>用 {@link LinkedHashMap} 保序去重，返回稳定顺序的列表——便于清单每轮一致、利于前缀缓存。
     */
    public List<Skill> loadAll() {
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (Skill s : scanDir(globalSkillsDir)) {   // 全局先放
            byName.put(s.name(), s);
        }
        for (Skill s : scanDir(projectSkillsDir)) {  // 项目级覆盖同名
            byName.put(s.name(), s);
        }
        return new ArrayList<>(byName.values());
    }

    /** 扫描一个技能目录下的所有 {@code skill-name/SKILL.md}；目录不存在或子项无 {@code SKILL.md} 则跳过。 */
    private List<Skill> scanDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            // 按目录名排序，保证清单顺序稳定。
            entries.filter(Files::isDirectory).sorted().forEach(skillDir -> {
                Path file = skillDir.resolve(SKILL_FILE_NAME);
                if (Files.isRegularFile(file)) {
                    parseSkill(skillDir, file).ifPresent(skills::add);
                }
            });
        } catch (IOException e) {
            System.err.println("扫描技能目录失败（已忽略）：" + dir + " —— " + e.getMessage());
        }
        return skills;
    }

    /**
     * 解析一个 {@code SKILL.md} 的 frontmatter，取 {@code name}/{@code description}/{@code when_to_use}。
     * 坏技能（frontmatter 不合法等）只跳过并打印一行，不影响其它技能、更不让启动失败——
     * 容错风格同配置/记忆加载。
     */
    private Optional<Skill> parseSkill(Path skillDir, Path file) {
        try {
            // 整份文件会临时进入堆内存；只提取元数据，不代表这里采用了按块/流式解析。
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> fm = parseFrontmatter(content);             // 复用 SnakeYAML
            String name = str(fm.get("name"), skillDir.getFileName().toString());  // 缺省用目录名
            String description = str(fm.get("description"), "");
            String whenToUse = str(fm.get("when_to_use"), "");
            // description 为空时给个兜底（用名字），避免清单里出现空描述。
            if (description.isBlank()) {
                description = name;
            }
            return Optional.of(new Skill(name, description, whenToUse, file, skillDir));
        } catch (Exception e) {
            System.err.println("解析技能失败（已跳过）：" + file + " —— " + e.getMessage());
            return Optional.empty();
        }
    }

    /** 取 {@code ---} 与 {@code ---} 之间的 YAML 块交给 SnakeYAML；没有 frontmatter 则返回空 Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontmatter(String content) {
        String s = content.stripLeading();
        if (!s.startsWith("---")) {
            return Map.of();
        }
        int end = s.indexOf("\n---", 3);                 // 找到闭合的 ---
        if (end < 0) {
            return Map.of();
        }
        String yaml = s.substring(3, end);
        Object parsed = new Yaml().load(yaml);
        return (parsed instanceof Map) ? (Map<String, Object>) parsed : Map.of();
    }

    /** 取字符串，null 回落默认值并去空白。 */
    private static String str(Object v, String dft) {
        return v == null ? dft : String.valueOf(v).strip();
    }
}
