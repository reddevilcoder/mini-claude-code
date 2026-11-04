package com.minicc.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SkillLoader 的单元测试：扫描两层目录、解析 frontmatter、项目级覆盖全局、容错。 */
class SkillLoaderTest {

    /** 写一个 skill-name/SKILL.md，frontmatter + 正文。 */
    private static void writeSkill(Path skillsDir, String name, String fm, String body) throws IOException {
        Path dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\n" + fm + "\n---\n\n" + body);
    }

    @Test
    void parsesFrontmatterFields(@TempDir Path global, @TempDir Path project) throws IOException {
        writeSkill(global, "commit-helper",
                "name: commit-helper\ndescription: 规范化生成 git 提交信息\nwhen_to_use: 当用户要创建 git 提交时",
                "# 正文\n按步骤操作……");

        List<Skill> skills = new SkillLoader(global, project).loadAll();
        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("commit-helper", s.name());
        assertEquals("规范化生成 git 提交信息", s.description());
        assertEquals("当用户要创建 git 提交时", s.whenToUse());
        assertTrue(s.skillFile().endsWith("commit-helper/SKILL.md"));
    }

    @Test
    void projectLevelOverridesGlobalSameName(@TempDir Path global, @TempDir Path project) throws IOException {
        writeSkill(global, "helper", "name: helper\ndescription: 全局版", "g");
        writeSkill(project, "helper", "name: helper\ndescription: 项目版", "p");

        List<Skill> skills = new SkillLoader(global, project).loadAll();
        assertEquals(1, skills.size(), "同名应去重");
        assertEquals("项目版", skills.get(0).description(), "项目级应覆盖全局");
        assertTrue(skills.get(0).skillFile().startsWith(project));
    }

    @Test
    void nameDefaultsToDirectoryName(@TempDir Path global, @TempDir Path project) throws IOException {
        // frontmatter 里没有 name，应回落目录名。
        writeSkill(global, "release-checklist", "description: 发布前检查", "body");
        List<Skill> skills = new SkillLoader(global, project).loadAll();
        assertEquals("release-checklist", skills.get(0).name());
    }

    @Test
    void emptyDescriptionFallsBackToName(@TempDir Path global, @TempDir Path project) throws IOException {
        writeSkill(global, "foo", "name: foo", "body");  // 无 description
        List<Skill> skills = new SkillLoader(global, project).loadAll();
        assertEquals("foo", skills.get(0).description(), "空描述应兜底为名字");
    }

    @Test
    void skipsDirectoryWithoutSkillFile(@TempDir Path global, @TempDir Path project) throws IOException {
        Files.createDirectories(global.resolve("not-a-skill"));  // 目录里没有 SKILL.md
        writeSkill(global, "real", "name: real\ndescription: d", "b");
        List<Skill> skills = new SkillLoader(global, project).loadAll();
        assertEquals(1, skills.size());
        assertEquals("real", skills.get(0).name());
    }

    @Test
    void tolerantToBadFrontmatter(@TempDir Path global, @TempDir Path project) throws IOException {
        // 没有 frontmatter 的 SKILL.md：不应抛异常，name 回落目录名、description 回落名字。
        Path dir = global.resolve("plain");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# 只有正文，没有 frontmatter\n");
        writeSkill(global, "good", "name: good\ndescription: d", "b");

        List<Skill> skills = new SkillLoader(global, project).loadAll();
        Map<String, Skill> byName = skills.stream().collect(Collectors.toMap(Skill::name, s -> s));
        assertTrue(byName.containsKey("good"), "好技能应被加载");
        assertTrue(byName.containsKey("plain"), "无 frontmatter 技能应回落目录名、不致失败");
    }

    @Test
    void missingDirsReturnEmpty(@TempDir Path base) {
        Path nope1 = base.resolve("no-global");
        Path nope2 = base.resolve("no-project");
        List<Skill> skills = new SkillLoader(nope1, nope2).loadAll();
        assertTrue(skills.isEmpty(), "目录不存在应返回空列表、不抛异常");
    }

    @Test
    void ensureGlobalDirCreatesIt(@TempDir Path base) {
        Path global = base.resolve("skills");
        assertFalse(Files.isDirectory(global));
        new SkillLoader(global, base.resolve("proj")).ensureGlobalDirExists();
        assertTrue(Files.isDirectory(global), "应创建全局技能目录");
    }
}
