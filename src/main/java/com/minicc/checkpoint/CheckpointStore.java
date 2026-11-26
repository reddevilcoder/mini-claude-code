package com.minicc.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件检查点（M10）：影子备份，按“会话 / 轮次”存放被 Edit/Write 改动前的原文件。
 *
 * <pre>
 * &lt;stateDir&gt;/projects/&lt;proj&gt;/checkpoints/&lt;sessionId&gt;/
 *   turns.json                 轮次顺序（回退“从某轮起”需要知道先后）
 *   &lt;turnId&gt;/manifest.json     本轮改过哪些文件：原来是否存在、备份文件名、改后哈希、是否跑过 Bash
 *   &lt;turnId&gt;/files/0, 1, ...   原文件内容（每个文件每轮只在第一次改动前备份一次）
 * </pre>
 *
 * <p><b>面试关注：为什么不用 git stash / git commit 做检查点？</b>
 * 用户项目不一定是 git 仓库；即使是，往用户的仓库里塞提交或 stash 会污染他的历史、与他自己的操作互相干扰。
 * 影子备份放在 ~/.mcc 下，只记录 Agent 自己动过的文件，和用户的版本控制完全正交。
 *
 * <p><b>面试关注：为什么要记录“改后哈希”？</b>
 * 回退是“把文件改回 Agent 动手之前”。如果 Agent 改完之后用户自己又编辑过这个文件，直接覆盖会悄悄丢掉用户的修改。
 * 记下 Agent 最后一次写入后的哈希，回退前与磁盘现状比对，不一致就是冲突，交给用户决定。
 *
 * <p><b>面试追问：有什么做不到？</b>
 * 只追踪 Edit/Write。Bash 命令（sed -i、rm、构建产物）造成的改动无法追踪，所以本轮跑过 Bash 时会记一笔，
 * 回退时明确告诉用户“文件回退可能不完整”，而不是假装能完全复原。
 */
public class CheckpointStore {

    /** 本轮里一个文件的记录。 */
    public static final class FileEntry {
        public String path;          // 绝对路径
        public boolean existed;      // 本轮第一次改动前文件是否存在（不存在 → 回退时删除）
        public String backup;        // files/ 下的备份文件名；existed=false 时为 null
        public String afterHash;     // Agent 最后一次写入后的 sha256

        public FileEntry() {
        }
    }

    /** 一轮的清单。 */
    public static final class Manifest {
        public Map<String, FileEntry> files = new LinkedHashMap<>();
        public List<String> bashCommands = new ArrayList<>();
    }

    /** 回退结果。 */
    public record RestoreReport(List<Path> restored, List<Path> deleted, List<Path> conflicts,
                                List<String> bashCommands) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;   // null → 禁用

    public CheckpointStore(Path sessionCheckpointDir) {
        this.dir = sessionCheckpointDir;
    }

    public static CheckpointStore disabled() {
        return new CheckpointStore(null);
    }

    public boolean enabled() {
        return dir != null;
    }

    /**
     * 写入前调用：若这是本轮第一次动这个文件，把原内容备份下来（文件不存在则只记“原本不存在”）。
     * 备份失败抛 IOException——调用方应当<b>放弃写入</b>：没有备份的改动将无法回退。
     */
    public synchronized void beforeWrite(String turnId, Path file) throws IOException {
        if (dir == null || turnId == null) {
            return;
        }
        Path abs = file.toAbsolutePath().normalize();
        Manifest m = manifest(turnId);
        if (m.files.containsKey(abs.toString())) {
            return;   // 本轮已备份过最早的版本，后续改动不覆盖它
        }
        FileEntry e = new FileEntry();
        e.path = abs.toString();
        e.existed = Files.isRegularFile(abs);
        if (e.existed) {
            e.backup = String.valueOf(m.files.size());
            Path target = turnDir(turnId).resolve("files").resolve(e.backup);
            Files.createDirectories(target.getParent());
            Files.copy(abs, target, StandardCopyOption.REPLACE_EXISTING);
        }
        m.files.put(e.path, e);
        save(turnId, m);
    }

    /** 写入成功后调用：记录改后哈希，供回退时检测“用户事后又改过”。 */
    public synchronized void afterWrite(String turnId, Path file) {
        if (dir == null || turnId == null) {
            return;
        }
        try {
            Path abs = file.toAbsolutePath().normalize();
            Manifest m = manifest(turnId);
            FileEntry e = m.files.get(abs.toString());
            if (e != null) {
                e.afterHash = hash(abs);
                save(turnId, m);
            }
        } catch (IOException ignore) {
            // 拿不到哈希只会让回退时把它当冲突处理，不影响本次写入。
        }
    }

    /**
     * 每轮开始时登记轮次（即使本轮没有改文件）：回退“从某轮起”需要该轮在时间线上的位置。
     * 仅“回退对话”时这些轮次的记录保留——文件仍是改过的状态，之后再回退文件时要把它们一并还原。
     */
    public synchronized void registerTurn(String turnId) {
        if (dir == null || turnId == null) {
            return;
        }
        try {
            List<String> order = order();
            if (!order.contains(turnId)) {
                order.add(turnId);
                writeOrder(order);
            }
        } catch (IOException e) {
            System.err.println("登记检查点轮次失败：" + e.getMessage());
        }
    }

    /** 本轮执行过 Bash：记一笔，回退时提示文件回退可能不完整。 */
    public synchronized void noteBash(String turnId, String command) {
        if (dir == null || turnId == null) {
            return;
        }
        try {
            Manifest m = manifest(turnId);
            m.bashCommands.add(command);
            save(turnId, m);
        } catch (IOException ignore) {
            // 只是提示信息，丢了不影响正确性。
        }
    }

    /** 从 fromTurn（含）到最新的各轮，合计改过哪些文件、跑过哪些 Bash。用于回退前的预览。 */
    public synchronized Manifest summarizeFrom(String fromTurn) throws IOException {
        Manifest total = new Manifest();
        for (String t : turnsFrom(fromTurn)) {
            Manifest m = manifest(t);
            m.files.forEach(total.files::putIfAbsent);   // 同一文件取最早一轮的备份
            total.bashCommands.addAll(m.bashCommands);
        }
        return total;
    }

    /**
     * 把文件回退到 fromTurn 开始之前。
     *
     * <p>对每个文件：用“最早触及它的那一轮”的备份还原（那才是 fromTurn 之前的状态）；
     * 用“最晚触及它的那一轮”的改后哈希检测冲突。冲突文件在 {@code overwriteConflicts=false} 时跳过。
     */
    public synchronized RestoreReport restoreFrom(String fromTurn, boolean overwriteConflicts) throws IOException {
        List<String> turns = turnsFrom(fromTurn);
        Map<String, FileEntry> earliest = new LinkedHashMap<>();
        Map<String, String> earliestTurn = new LinkedHashMap<>();
        Map<String, String> latestHash = new LinkedHashMap<>();
        List<String> bash = new ArrayList<>();
        for (String t : turns) {
            Manifest m = manifest(t);
            for (FileEntry e : m.files.values()) {
                if (!earliest.containsKey(e.path)) {
                    earliest.put(e.path, e);
                    earliestTurn.put(e.path, t);
                }
                latestHash.put(e.path, e.afterHash);
            }
            bash.addAll(m.bashCommands);
        }
        List<Path> restored = new ArrayList<>();
        List<Path> deleted = new ArrayList<>();
        List<Path> conflicts = new ArrayList<>();
        for (FileEntry e : earliest.values()) {
            Path file = Path.of(e.path);
            String now = Files.isRegularFile(file) ? hash(file) : null;
            String expected = latestHash.get(e.path);
            boolean conflict = expected == null ? now != null : !expected.equals(now);
            if (conflict) {
                conflicts.add(file);
                if (!overwriteConflicts) {
                    continue;
                }
            }
            if (e.existed) {
                Path backup = turnDir(earliestTurn.get(e.path)).resolve("files").resolve(e.backup);
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                restored.add(file);
            } else if (Files.deleteIfExists(file)) {
                deleted.add(file);
            }
        }
        return new RestoreReport(restored, deleted, conflicts, bash);
    }

    /**
     * 回退之后丢弃 fromTurn 及之后各轮的检查点（这些轮次在对话里也已不存在或已被还原）。
     */
    public synchronized void dropFrom(String fromTurn) throws IOException {
        List<String> order = order();
        int idx = order.indexOf(fromTurn);
        if (idx < 0) {
            return;
        }
        for (String t : new ArrayList<>(order.subList(idx, order.size()))) {
            deleteTree(turnDir(t));
        }
        writeOrder(new ArrayList<>(order.subList(0, idx)));
    }

    /** 某一轮是否有文件改动记录。 */
    public synchronized boolean hasChanges(String turnId) {
        try {
            return dir != null && order().contains(turnId) && !manifest(turnId).files.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    // ---- 内部 ----

    private List<String> turnsFrom(String fromTurn) throws IOException {
        if (dir == null) {
            return List.of();
        }
        List<String> order = order();
        int idx = order.indexOf(fromTurn);
        if (idx < 0) {
            return List.of();   // 该轮不在时间线上（例如检查点功能启用前的旧会话）
        }
        return order.subList(idx, order.size());
    }

    /** 判断某轮在顺序表中的位置；不存在返回 -1。 */
    public synchronized int indexOf(String turnId) {
        try {
            return dir == null ? -1 : order().indexOf(turnId);
        } catch (IOException e) {
            return -1;
        }
    }

    /** 全部有记录的轮次（按先后）。 */
    public synchronized List<String> turns() {
        try {
            return dir == null ? List.of() : List.copyOf(order());
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path turnDir(String turnId) {
        return dir.resolve(turnId);
    }

    private Manifest manifest(String turnId) throws IOException {
        Path f = turnDir(turnId).resolve("manifest.json");
        if (!Files.exists(f)) {
            return new Manifest();
        }
        return JSON.readValue(f.toFile(), Manifest.class);
    }

    private void save(String turnId, Manifest m) throws IOException {
        Path f = turnDir(turnId).resolve("manifest.json");
        Files.createDirectories(f.getParent());
        // 先写临时文件再原子改名：进程中途崩溃也不会留下半截 manifest。
        Path tmp = f.resolveSibling("manifest.json.tmp");
        JSON.writeValue(tmp.toFile(), m);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        List<String> order = order();
        if (!order.contains(turnId)) {
            order.add(turnId);
            writeOrder(order);
        }
    }

    private List<String> order() throws IOException {
        Path f = dir.resolve("turns.json");
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(JSON.readValue(f.toFile(), new TypeReference<List<String>>() {
        }));
    }

    private void writeOrder(List<String> order) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("turns.json");
        Path tmp = dir.resolve("turns.json.tmp");
        JSON.writeValue(tmp.toFile(), order);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
