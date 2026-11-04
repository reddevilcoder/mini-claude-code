package com.minicc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConfigLoader 的单元测试。 */
class ConfigLoaderTest {

    @Test
    void usesDefaultsWhenNoFile(@TempDir Path dir) {
        // 用一个不存在的全局配置路径，隔离真实的 ~/.mcc/mcc.yml，保证测试与环境无关。
        MccConfig config = ConfigLoader.load(dir, dir.resolve("no-global.yml"));
        assertEquals(MccConfig.DEFAULT_MODEL, config.model());
        assertEquals(MccConfig.DEFAULT_MAX_STEPS, config.maxSteps());
        assertEquals(MccConfig.DEFAULT_CONTEXT_LIMIT, config.contextLimit());
        assertEquals(MccConfig.DEFAULT_SANDBOX, config.sandbox());
        assertEquals(MccConfig.DEFAULT_BASH_TIMEOUT_MS, config.bashTimeoutMs());
        assertEquals(MccConfig.DEFAULT_STATE_DIR, config.stateDir());
        // M7：Skills 默认开启、清单预算 8000。
        assertTrue(config.skills().enabled());
        assertEquals(MccConfig.DEFAULT_SKILLS_LISTING_MAX_CHARS, config.skills().listingMaxChars());
    }

    @Test
    void readsSkillsOverridesFromYaml(@TempDir Path dir) throws IOException {
        Path projectConfig = ConfigLoader.projectConfigPath(dir);
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, """
                skills:
                  enabled: false
                  listingMaxChars: 1234
                """);
        MccConfig config = ConfigLoader.load(dir, dir.resolve("no-global.yml"));
        assertFalse(config.skills().enabled(), "skills.enabled 应被覆盖为 false");
        assertEquals(1234, config.skills().listingMaxChars());
    }

    @Test
    void skillsPartialOverrideKeepsOtherDefault(@TempDir Path dir) throws IOException {
        Path projectConfig = ConfigLoader.projectConfigPath(dir);
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, """
                skills:
                  enabled: false
                """);
        MccConfig config = ConfigLoader.load(dir, dir.resolve("no-global.yml"));
        assertFalse(config.skills().enabled());
        // listingMaxChars 未给出，应回落默认值。
        assertEquals(MccConfig.DEFAULT_SKILLS_LISTING_MAX_CHARS, config.skills().listingMaxChars());
    }

    @Test
    void readsStateDirOverrideFromYaml(@TempDir Path dir) throws IOException {
        Path projectConfig = ConfigLoader.projectConfigPath(dir);
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, """
                stateDir: /tmp/mcc-state
                """);
        MccConfig config = ConfigLoader.load(dir, dir.resolve("no-global.yml"));
        assertEquals("/tmp/mcc-state", config.stateDir());
    }

    @Test
    void ensureConfigFileCreatesDefaultWhenMissing(@TempDir Path dir) {
        Path target = dir.resolve("sub/mcc.yml");
        Path created = ConfigLoader.ensureConfigFile(target);
        assertEquals(target, created, "首次应生成默认配置文件");
        assertTrue(Files.exists(target));
    }

    @Test
    void ensureConfigFileDoesNotOverwriteExisting(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("mcc.yml");
        Files.writeString(target, "model: my-model\n");
        Path created = ConfigLoader.ensureConfigFile(target);
        assertNull(created, "已存在时不应再生成");
        assertEquals("model: my-model\n", Files.readString(target), "已存在的文件不应被覆盖");
    }

    @Test
    void readsOverridesFromYaml(@TempDir Path dir) throws IOException {
        Path projectConfig = ConfigLoader.projectConfigPath(dir);
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, """
                model: qwen3.8-max
                maxSteps: 10
                contextLimit: 123456
                sandbox: false
                bashTimeoutMs: 5000
                """);
        MccConfig config = ConfigLoader.load(dir, dir.resolve("no-global.yml"));
        assertEquals("qwen3.8-max", config.model());
        assertEquals(10, config.maxSteps());
        assertEquals(123456, config.contextLimit());
        assertFalse(config.sandbox());
        assertEquals(5000L, config.bashTimeoutMs());
    }
}
