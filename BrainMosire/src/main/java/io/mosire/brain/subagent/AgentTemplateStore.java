package io.mosire.brain.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 模板库：扫描目录内 {@code *.json} 装载全部 {@link AgentTemplate}，提供只读快照访问。
 *
 * <p>为什么坏文件要炸掉整个 load 而不是跳过：模板是权限边界，静默跳过一个坏模板等于子 Agent 静默缺失，调用方无从察觉——宁可进程启动失败（fail-fast），也不带病运行。
 *
 * <p>快照语义：{@link #load()} 一次成型，内部替换为不可变 Map；运行中读旧快照、reload 后读新快照， 读写不互锁。
 */
public final class AgentTemplateStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path dir;

  /** 不可变快照（volatile 保证 reload 后的可见性）。 */
  private volatile Map<String, AgentTemplate> snapshot = Map.of();

  /**
   * @param dir 模板目录（含 {@code *.json} 文件）
   */
  public AgentTemplateStore(Path dir) {
    this.dir = Objects.requireNonNull(dir, "dir");
  }

  /**
   * 扫描目录装载全部模板（重复调用即刷新快照，同 {@link #reload()}）。
   *
   * <p>约束：文件名 stem 必须 ≡ 模板 id；JSON 非法 / 字段校验失败 / 非法枚举值一律抛 {@link
   * IllegalArgumentException}（消息带文件路径），绝不静默跳过。
   */
  public void load() {
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("模板目录不存在或不是目录: " + dir);
    }
    Map<String, AgentTemplate> loaded = new TreeMap<>();
    try (Stream<Path> files = Files.list(dir)) {
      files
          .filter(p -> Objects.requireNonNullElse(p.getFileName(), p).toString().endsWith(".json"))
          .sorted()
          .forEach(p -> loadOne(p, loaded));
    } catch (IOException e) {
      throw new IllegalArgumentException("模板目录无法扫描: " + dir, e);
    }
    snapshot = Map.copyOf(loaded);
  }

  /** 重新扫描并替换快照（模板文件被 Agent 管理工具改写后调用）。 */
  public void reload() {
    load();
  }

  /**
   * @param id 模板 id
   * @return 命中的模板；装载前或无此 id 时为 empty
   */
  public Optional<AgentTemplate> get(String id) {
    return Optional.ofNullable(snapshot.get(id));
  }

  /** 全量只读快照（按 id 排序；返回的 Map 不可变）。 */
  public Map<String, AgentTemplate> all() {
    return snapshot;
  }

  private static void loadOne(Path file, Map<String, AgentTemplate> into) {
    String name = Objects.requireNonNullElse(file.getFileName(), file).toString();
    String stem = name.substring(0, name.length() - ".json".length());
    AgentTemplate template;
    try {
      template = JSON.readValue(file.toFile(), AgentTemplate.class);
    } catch (IOException e) {
      // Jackson 的解析错误/未知枚举/构造校验失败都走 IOException 家族——统一带上路径再抛
      throw new IllegalArgumentException("模板文件装载失败: " + file + "（" + e.getMessage() + "）", e);
    }
    if (!stem.equals(template.id())) {
      throw new IllegalArgumentException(
          "文件名 stem 与模板 id 不一致: 文件=" + file + " stem=" + stem + " id=" + template.id());
    }
    into.put(template.id(), template);
  }
}
