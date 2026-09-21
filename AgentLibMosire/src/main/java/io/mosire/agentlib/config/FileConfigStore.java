package io.mosire.agentlib.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.Specification;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.Dialect;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ConfigAuth;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ConfigStore} 的默认实现：分层 JSON 文件 + networknt schema 校验 + 原子写回滚 + 前缀授权。
 *
 * <p><b>磁盘布局</b>（配置根为显式构造参数，不读 cwd/环境变量/全局状态；测试一律 {@code @TempDir}）：
 *
 * <ul>
 *   <li>{@code config.json}：全局配置（{@code runtime.*}/{@code keys.*} 子树，以及 {@code agents.<id>.*}
 *       的全局缺省），键为全量点分路径的嵌套对象；
 *   <li>{@code agents/<id>.json}：该 Agent 自己的配置子树，键为后缀（{@code agents.<id>.temperature} 在文件里就是 {@code
 *       {"temperature": …}}）；
 *   <li>{@code schemas/}：schema 目录（可注入，缺省 = 配置根下 {@code schemas/}；计划两处文档 {@code configs/schemas/} 与
 *       {@code config-schema/} 冲突，取前者——控制者裁决）。
 * </ul>
 *
 * <p>计划 §六 的 {@code configs/keys/} 目录形态本任务<b>不采纳</b>：{@code keys.*} 是引用式键值， 存入 {@code config.json}
 * 的 {@code keys} 子树（本任务只做引用存储与授权，真实解析归 T23）。
 *
 * <p><b>分层合并</b>（读时，低 → 高）：{@code config.json} ← {@code agents/<id>.json} ← env 覆盖层；
 * 同键高层覆盖低层，低层独有的键保留（合并 ≠ 替换）。env 覆盖层<b>只做读时叠加，永不落盘、不产生 变更回调</b>，且<b>排除 {@code keys.*}</b>——密钥只能经
 * ConfigStore 的授权路径进入。这是对计划 「config.json ← agents/&lt;id&gt;.json ← 环境变量覆盖」的<b>有意偏离</b>（自证来源：T18 R2
 * 裁决 "不得直接读环境变量取密钥、不得绕过 ConfigStore" + 开发计划-中期「keys.* 仅 SYSTEM 可写」；若 env 覆盖 keys.* 则"谁设了 env
 * 谁就改了密钥"，绕过授权路径）。另外本实现<b>不读进程环境</b>（无 {@code System.getenv}），env 覆盖层是构造注入的 {@code
 * Map<String,String>}——测试离线可测、不随 环境漂移。
 *
 * <p><b>原子写协议</b>（顺序硬性，每步原因见下）：
 *
 * <ol>
 *   <li>写<b>同目录</b>临时文件（同目录才能 {@code ATOMIC_MOVE}）+ {@code FileChannel.force(true)} fsync（不只是流
 *       flush——掉电/崩溃窗口内 tmp 必须已是完整字节）；
 *   <li>备份原文件为 {@code .bak}（同样整份 fsync；<b>覆盖旧 .bak</b>：只保留最近一个前值）；
 *   <li>{@code Files.move(tmp, target, ATOMIC_MOVE)}（<b>绝不边写边改原文件</b>——先截断再写 =
 *       中途崩溃即损坏；目标文件在任意时刻要么完整旧内容、要么完整新内容）；
 *   <li>读回校验（重新解析 + schema 再校验——防止"写坏但没察觉"）；失败 → 用 {@code .bak} 恢复 （无 .bak = 此前无原文件 →
 *       删除新文件），并把失败<b>响亮抛出</b>（消息注明已回滚）。
 * </ol>
 *
 * <p><b>{@code .bak} 保留策略</b>：每次成功写入覆盖旧 {@code .bak}（保留最近一个前值）；首次写入 （无原文件）成功后无 {@code .bak}；回滚消耗
 * {@code .bak}（恢复后即消失）。失败路径<b>不留下 {@code .tmp} 残留</b>（有用例）。<b>目录 fsync 不做</b>（只保证"目标文件任意时刻完整旧/新内容"，
 * 不保证 rename 本身的掉电持久性——该窗口的半截文件风险已由"tmp+move"消除，已知局限如实声明）。
 *
 * <p><b>{@code ATOMIC_MOVE} 不受支持时的行为</b>（明确声明）：抛 {@link ConfigException} （{@code
 * E_CONFIG_IO}），<b>不降级</b>为非原子替换（降级 = 放弃本任务的原子性契约），原文件不动、 临时文件已清理（有用例，经 {@link #moveAtomically}
 * 测试缝注入）。
 *
 * <p><b>校验语义</b>：schema 由调用方每次写入传入（文件名或 JsonNode），校验<b>合并后的整个目标文件</b>。 ①非法值 →
 * 写前校验即拒绝，磁盘逐字节未变（有用例）；②既有旧配置 JSON 合法但违反 schema：<b>读照常返回 </b>（schema
 * 是写侧契约），但<b>任何写都会失败</b>（整文件校验）——修法是把违例键写合法；③既有文件 不是合法 JSON / 顶层不是对象：读与写都抛 {@code
 * E_CONFIG_CORRUPT}，写不会触碰损坏文件（修复语义不在 本任务）。schema 校验错误消息<b>只含关键字名 + 错误数 + schema
 * 名</b>，不含库消息原文、不含键路径、 不含任何值——凭据安全契约（{@link ConfigException} 的硬性要求；networknt 的 {@code
 * Error#getInstanceLocation} 会嵌入键名、{@code getMessage} 可能嵌入值，故二者都不进消息）。
 *
 * <p><b>并发</b>：每次调用自身原子（内部锁；put 的"读-改-写"整体在锁内，否则并发 put 丢更新），
 * <b>不宣称</b>跨调用事务、<b>不宣称</b>跨进程并发（文件层无锁——多进程写同一配置根不在保证范围）。 无内存缓存：每次调用都从磁盘读（回滚后再读到的必然是回滚后的内容，有用例）。
 */
public class FileConfigStore implements ConfigStore {

  private static final Logger LOG = LoggerFactory.getLogger(FileConfigStore.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path configRoot;
  private final Path schemaDir;
  private final Map<String, JsonNode> envOverlay;
  private final ConfigListener listener;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * 便捷构造：schema 目录 = {@code configRoot/schemas}，无 env 覆盖层，不监听。
   *
   * <p>{@code CT_CONSTRUCTOR_THROW} 抑制理由：构造器抛异常仅发生在参数校验（{@code requireNonNull}，
   * 调用方传参错误）路径；本类与仓库其余类都不定义 {@code finalize()}，JDK 21 已弃用终结机制， 无 Finalizer 攻击面——该告警是 SpotBugs 对非
   * final 类构造器抛异常路径的保守启发式。
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public FileConfigStore(Path configRoot) {
    this(configRoot, configRoot.resolve("schemas"), Map.of(), ConfigListener.none());
  }

  /**
   * @param configRoot 配置根（{@code config.json} 与 {@code agents/} 的父目录；显式注入，不读 cwd）
   * @param schemaDir schema 目录（可注入；put 的 schema 文件名相对它解析）
   * @param envOverlay env 覆盖层（原始字符串映射，键为全量点分键；构造时快照：{@code keys.*} 条目 丢弃、null 值丢弃、值按 JSON
   *     解析、解析失败按文本）。<b>不是</b> {@code System.getenv}
   * @param listener 变更监听（落盘成功后回调；{@link ConfigListener#none()} 表示不监听）
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW") // 理由同便捷构造的注解说明
  public FileConfigStore(
      Path configRoot, Path schemaDir, Map<String, String> envOverlay, ConfigListener listener) {
    this.configRoot = Objects.requireNonNull(configRoot, "configRoot");
    this.schemaDir = Objects.requireNonNull(schemaDir, "schemaDir");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.envOverlay = snapshotEnv(envOverlay);
  }

  @Override
  public Optional<JsonNode> get(String prefix, String key) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(key, "key");
    String fullKey = prefix + "." + key;
    lock.lock();
    try {
      // env 覆盖层最高优先；keys.* 永远不从 env 读出（R5-2）
      if (!fullKey.startsWith("keys.")) {
        JsonNode envValue = envOverlay.get(fullKey);
        if (envValue != null) {
          return Optional.of(envValue);
        }
      }
      Optional<String> agentId = ConfigAuth.agentIdOf(fullKey);
      if (agentId.isPresent()) {
        ObjectNode agentConfig = readObjectIfExists(agentFile(agentId.get()));
        JsonNode value = agentConfig == null ? null : nodeAt(agentConfig, splitSegments(key));
        if (value != null) {
          return Optional.of(value);
        }
      }
      ObjectNode global = readObjectIfExists(globalFile());
      JsonNode value = global == null ? null : nodeAt(global, splitSegments(fullKey));
      if (value != null) {
        return Optional.of(value);
      }
      return Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void put(
      String prefix,
      String key,
      JsonNode value,
      AgentPermissionSet permissions,
      String ownerId,
      String schemaFile) {
    putInternal(
        prefix,
        key,
        value,
        permissions,
        ownerId,
        Objects.requireNonNull(schemaFile, "schemaFile"),
        null);
  }

  @Override
  public void put(
      String prefix,
      String key,
      JsonNode value,
      AgentPermissionSet permissions,
      String ownerId,
      JsonNode schemaNode) {
    putInternal(
        prefix,
        key,
        value,
        permissions,
        ownerId,
        null,
        Objects.requireNonNull(schemaNode, "schemaNode"));
  }

  @Override
  public void remove(
      String prefix,
      String key,
      AgentPermissionSet permissions,
      String ownerId,
      String schemaFile) {
    removeInternal(
        prefix, key, permissions, ownerId, Objects.requireNonNull(schemaFile, "schemaFile"), null);
  }

  @Override
  public void remove(
      String prefix,
      String key,
      AgentPermissionSet permissions,
      String ownerId,
      JsonNode schemaNode) {
    removeInternal(
        prefix, key, permissions, ownerId, null, Objects.requireNonNull(schemaNode, "schemaNode"));
  }

  private void removeInternal(
      String prefix,
      String key,
      AgentPermissionSet permissions,
      String ownerId,
      String schemaFile,
      JsonNode schemaNode) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(key, "key");
    AccessToken token = permissions == null ? null : permissions.grantedToken();
    String fullKey = prefix + "." + key;

    // 前缀授权（与 put 同一判定，含"删不掉根"：空键/空段在这里被拒）；拒绝时文件不被触碰
    Optional<String> denial = ConfigAuth.writeDenialReason(token, ownerId, fullKey);
    if (denial.isPresent()) {
      throw new ConfigException(ConfigException.E_PREFIX_DENIED, denial.get());
    }

    // 落盘位置与授权判定用同一解析（与 putInternal 同源）
    Path target;
    List<String> segments;
    Optional<String> agentId = ConfigAuth.agentIdOf(fullKey);
    if (agentId.isPresent()) {
      target = agentFile(agentId.get());
      segments = splitSegments(key);
    } else {
      target = globalFile();
      segments = splitSegments(fullKey);
    }

    Schema schema =
        schemaNode != null ? compileSchemaNode(schemaNode, null) : compileSchemaFile(schemaFile);

    lock.lock();
    try {
      ObjectNode current = readObjectOrNew(target);
      JsonNode oldValue = nodeAt(current, segments); // 文件层旧值（env 不参与）
      if (oldValue == null) {
        // 键本就不存在 = 无操作：不写盘、不回调、不抛（DELETE 的语义是"确保它不在"，见接口 Javadoc）
        return;
      }

      ObjectNode merged = (ObjectNode) current.deepCopy();
      deleteAtPath(merged, segments);

      validatePreWrite(schema, merged, schemaFile, schemaNode);
      commit(
          target,
          merged,
          schema,
          schemaFile,
          schemaNode,
          fullKey,
          oldValue,
          NullNode.getInstance()); // 删除：监听器拿到空节点（与写入共用同一个回调）
    } finally {
      lock.unlock();
    }
  }

  private void putInternal(
      String prefix,
      String key,
      JsonNode value,
      AgentPermissionSet permissions,
      String ownerId,
      String schemaFile,
      JsonNode schemaNode) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    if (value.isNull() || value.isMissingNode()) {
      throw new IllegalArgumentException("value 不得为 null/MissingNode（删除请用 remove，不借 null 表达删除）");
    }
    AccessToken token = permissions == null ? null : permissions.grantedToken();
    String fullKey = prefix + "." + key;

    // 前缀授权（红线 1 的第二个实现点）：拒绝时文件不被触碰
    Optional<String> denial = ConfigAuth.writeDenialReason(token, ownerId, fullKey);
    if (denial.isPresent()) {
      throw new ConfigException(ConfigException.E_PREFIX_DENIED, denial.get());
    }

    // 落盘位置与授权判定用同一解析（fullKey 派生，prefix 形状不影响落盘位置）
    Path target;
    List<String> segments;
    Optional<String> agentId = ConfigAuth.agentIdOf(fullKey);
    if (agentId.isPresent()) {
      target = agentFile(agentId.get());
      segments = splitSegments(key);
    } else {
      target = globalFile();
      segments = splitSegments(fullKey);
    }

    Schema schema =
        schemaNode != null ? compileSchemaNode(schemaNode, null) : compileSchemaFile(schemaFile);

    lock.lock();
    try {
      ObjectNode current = readObjectOrNew(target);
      JsonNode oldValue = nodeAt(current, segments); // 文件层旧值（env 不参与）

      // 合并 ≠ 替换：深拷贝现状后只动本次键，同文件其他键保留
      ObjectNode merged = (ObjectNode) current.deepCopy();
      setAtPath(merged, segments, value.deepCopy());

      // 写前校验失败：磁盘未被触碰（R6-1 的逐字节未变由此保证——先校验后动盘）
      validatePreWrite(schema, merged, schemaFile, schemaNode);

      commit(target, merged, schema, schemaFile, schemaNode, fullKey, oldValue, value);
    } finally {
      lock.unlock();
    }
  }

  /** 写前校验：不通过即抛 {@code E_SCHEMA_INVALID}，此时<b>磁盘逐字节未变</b>（先校验后动盘）。put 与 remove 共用。 */
  private static void validatePreWrite(
      Schema schema, ObjectNode merged, String schemaFile, JsonNode schemaNode) {
    List<Error> preWriteErrors = validationErrors(schema, merged);
    if (!preWriteErrors.isEmpty()) {
      throw new ConfigException(
          ConfigException.E_SCHEMA_INVALID,
          "写入前校验未通过（未落盘）: " + summarizeErrors(preWriteErrors, schemaFile, schemaNode));
    }
  }

  /**
   * 落盘 + 确认（put 与 remove <b>共用的同一段尾巴</b>，逐字同源）：原子写 → 读回重解析 + schema 再校验 → 失败回滚并响亮抛出 → 成功后回调监听器。
   *
   * <p>抽成一段是因为"删除"与"写入"在这条路径上<b>没有任何理由不同</b>：两者都是把一份完整文档原子替换到盘上，
   * 都要防"写坏了却没察觉"。两份拷贝迟早会分叉（改了一处忘了另一处），而那正是配置损坏最容易钻进来的缝。
   *
   * @param newValue 给监听器的"变更后是什么"：put 传调用方给的值；删除传 JSON 空节点（见 {@link ConfigListener#onChanged}）
   */
  private void commit(
      Path target,
      ObjectNode merged,
      Schema schema,
      String schemaFile,
      JsonNode schemaNode,
      String fullKey,
      JsonNode oldValue,
      JsonNode newValue) {
    atomicWrite(target, serialize(merged));

    // 读回校验：重新解析 + schema 再校验；失败 → 回滚 + 响亮抛出
    byte[] onDisk;
    try {
      onDisk = Files.readAllBytes(target);
    } catch (IOException e) {
      rollback(target);
      throw new ConfigException(ConfigException.E_CONFIG_IO, "写入后读回失败（写入已回滚）: " + target, e);
    }
    ObjectNode readBack;
    try {
      readBack = parseObject(onDisk, target);
    } catch (ConfigException e) {
      rollback(target);
      throw new ConfigException(e.code(), e.getMessage() + "（写入已回滚）", e);
    }
    List<Error> readBackErrors;
    try {
      readBackErrors = validationErrors(schema, readBack);
    } catch (RuntimeException e) {
      rollback(target);
      throw new ConfigException(
          ConfigException.E_SCHEMA_UNAVAILABLE, "读回校验失败（写入已回滚）: " + target, e);
    }
    if (!readBackErrors.isEmpty()) {
      rollback(target);
      throw new ConfigException(
          ConfigException.E_SCHEMA_INVALID,
          "读回校验未通过（写入已回滚）: " + summarizeErrors(readBackErrors, schemaFile, schemaNode));
    }

    // 落盘成功后回调（先写后播）；监听器异常只记日志、不回滚
    try {
      listener.onChanged(fullKey, oldValue, newValue);
    } catch (RuntimeException e) {
      LOG.warn("配置变更监听器异常（忽略，写入不回滚）: key={}", fullKey, e);
    }
  }

  /** 原子写协议（R4 顺序硬性）；读回校验在调用方（需要 schema）。 */
  private void atomicWrite(Path target, byte[] content) {
    Path dir = parentOrDie(target);
    String name = fileNameOrDie(target);
    Path tmp = dir.resolve(name + ".tmp");
    Path bak = dir.resolve(name + ".bak");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new ConfigException(ConfigException.E_CONFIG_IO, "无法创建配置目录: " + dir, e);
    }
    boolean hadOriginal = Files.exists(target);
    try {
      // ① 同目录临时文件 + fsync（force(true) 强制落盘，不只是流 flush）
      try (FileChannel channel =
          FileChannel.open(
              tmp,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          if (channel.write(buffer) <= 0) {
            throw new IOException("写入临时文件零字节: " + tmp);
          }
        }
        channel.force(true);
      }
      // ② 备份原文件（覆盖旧 .bak：只保留最近一个前值；整份 fsync 后再动目标）
      if (hadOriginal) {
        Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
        fsync(bak);
      }
      // ③ 原子替换（测试缝：可覆写模拟"不支持 ATOMIC_MOVE"/"替换后损坏"）
      try {
        moveAtomically(tmp, target);
      } catch (AtomicMoveNotSupportedException e) {
        // 明确声明：不降级为非原子替换；原文件未动，清理临时文件后响亮失败
        deleteQuietly(tmp);
        throw new ConfigException(
            ConfigException.E_CONFIG_IO,
            "文件系统不支持 ATOMIC_MOVE（已放弃写入并清理临时文件；本实现不降级为非原子替换）: " + name,
            e);
      }
    } catch (ConfigException e) {
      throw e;
    } catch (IOException e) {
      deleteQuietly(tmp);
      throw new ConfigException(ConfigException.E_CONFIG_IO, "原子写失败（临时文件已清理，原文件未动）: " + name, e);
    }
  }

  /** 回滚：用 {@code .bak} 恢复原文件；无 {@code .bak} = 此前无原文件 → 删除新文件。 */
  private void rollback(Path target) {
    Path dir = parentOrDie(target);
    String name = fileNameOrDie(target);
    Path bak = dir.resolve(name + ".bak");
    try {
      if (Files.exists(bak)) {
        Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.deleteIfExists(target);
      }
    } catch (IOException e) {
      throw new ConfigException(
          ConfigException.E_CONFIG_IO, "写入失败且回滚失败（.bak 未能恢复为原文件）: " + name, e);
    }
  }

  /**
   * 原子替换（测试缝）：生产实现 = {@code Files.move(tmp, target, ATOMIC_MOVE)}；测试可覆写以注入 "文件系统不支持
   * ATOMIC_MOVE"或"替换成功后内容损坏"两种失败形态（回滚路径与不支持路径的用例）。
   */
  protected void moveAtomically(Path tmp, Path target) throws IOException {
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
  }

  /** 目标的文件名（防御：{@code getFileName()} 对根路径返回 null——本类目标恒为具名文件，null 属不可能态）。 */
  private static String fileNameOrDie(Path target) {
    Path fileName = target.getFileName();
    if (fileName == null) {
      throw new ConfigException(ConfigException.E_CONFIG_IO, "目标路径没有文件名: " + target);
    }
    return fileName.toString();
  }

  /** 目标的父目录（防御：{@code getParent()} 对单段相对路径返回 null——tmp/.bak 必须与目标同目录）。 */
  private static Path parentOrDie(Path target) {
    Path dir = target.getParent();
    if (dir == null) {
      throw new ConfigException(ConfigException.E_CONFIG_IO, "目标路径没有父目录: " + target);
    }
    return dir;
  }

  private Path globalFile() {
    return configRoot.resolve("config.json");
  }

  private Path agentFile(String agentId) {
    return configRoot.resolve("agents").resolve(agentId + ".json");
  }

  /** 读并解析；文件不存在 → null；存在但损坏 → {@code E_CONFIG_CORRUPT}（读侧契约见类 Javadoc）。 */
  private static ObjectNode readObjectIfExists(Path file) {
    if (!Files.exists(file)) {
      return null;
    }
    return readObjectOrNew(file);
  }

  /** 读并解析；文件不存在或为空 → 空对象；损坏 → {@code E_CONFIG_CORRUPT}。 */
  private static ObjectNode readObjectOrNew(Path file) {
    if (!Files.exists(file)) {
      return JSON.createObjectNode();
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new ConfigException(ConfigException.E_CONFIG_IO, "读取配置文件失败: " + file, e);
    }
    if (bytes.length == 0) {
      return JSON.createObjectNode(); // 空文件 = 空配置（文档化：视为合法空态）
    }
    return parseObject(bytes, file);
  }

  private static ObjectNode parseObject(byte[] bytes, Path file) {
    JsonNode node;
    try {
      node = JSON.readTree(bytes);
    } catch (IOException e) {
      throw new ConfigException(ConfigException.E_CONFIG_CORRUPT, "配置文件不是合法 JSON: " + file, e);
    }
    if (!node.isObject()) {
      throw new ConfigException(ConfigException.E_CONFIG_CORRUPT, "配置文件顶层必须是 JSON 对象: " + file);
    }
    return (ObjectNode) node;
  }

  private Schema compileSchemaFile(String schemaFile) {
    Path schemaPath = Paths.get(schemaFile);
    if (!schemaPath.isAbsolute()) {
      schemaPath = schemaDir.resolve(schemaFile);
    }
    if (!Files.isRegularFile(schemaPath)) {
      throw new ConfigException(
          ConfigException.E_SCHEMA_UNAVAILABLE, "schema 文件不可读: " + schemaPath);
    }
    JsonNode schemaNode;
    try {
      schemaNode = JSON.readTree(Files.readAllBytes(schemaPath));
    } catch (IOException e) {
      throw new ConfigException(
          ConfigException.E_SCHEMA_UNAVAILABLE, "schema 文件解析失败: " + schemaPath, e);
    }
    Path fileName = schemaPath.getFileName();
    return compileSchemaNode(
        schemaNode, fileName == null ? schemaPath.toString() : fileName.toString());
  }

  private static Schema compileSchemaNode(JsonNode schemaNode, String schemaName) {
    try {
      // networknt 2.0.4（重写后的 API）：dialect 从 $schema 探测，缺省 draft-2020-12
      SpecificationVersion version =
          SpecificationVersion.fromSchemaNode(schemaNode)
              .orElse(SpecificationVersion.DRAFT_2020_12);
      Dialect dialect = Specification.getDialect(version);
      SchemaRegistry registry = SchemaRegistry.withDefaultDialect(version);
      SchemaContext context = new SchemaContext(dialect, registry);
      return context.newSchema(SchemaLocation.of("mem://mosire-config-schema"), schemaNode, null);
    } catch (RuntimeException e) {
      throw new ConfigException(
          ConfigException.E_SCHEMA_UNAVAILABLE,
          "schema 不可用（不是合法 JSON Schema）" + (schemaName == null ? "" : ": " + schemaName),
          e);
    }
  }

  private static List<Error> validationErrors(Schema schema, ObjectNode node) {
    return schema.validate(node);
  }

  /**
   * 校验失败的<b>非敏感</b>摘要：只含未通过关键字名 + 错误数 + schema 名。<b>有意不含</b>库消息原文 与实例路径——networknt 的 {@code
   * Error#getMessage} 可能嵌入值、{@code getInstanceLocation} 会嵌入 键名（实测），键/值都可能被调用方放成凭据形态（T18 评审 Minor 3
   * 升级条件的同源约束）。
   */
  private static String summarizeErrors(
      List<Error> errors, String schemaFile, JsonNode schemaNode) {
    LinkedHashSet<String> keywords = new LinkedHashSet<>();
    for (Error error : errors) {
      keywords.add(String.valueOf(error.getKeyword()));
    }
    StringBuilder summary = new StringBuilder("未通过关键字: ").append(String.join("、", keywords));
    summary.append("，共 ").append(errors.size()).append(" 处");
    if (schemaFile != null) {
      summary.append("（schema 文件: ").append(schemaFile).append("）");
    } else if (schemaNode != null) {
      summary.append("（schema: 内联 JsonNode）");
    }
    return summary.toString();
  }

  private static byte[] serialize(ObjectNode node) {
    try {
      byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
      byte[] withNewline = Arrays.copyOf(bytes, bytes.length + 1);
      withNewline[bytes.length] = '\n';
      return withNewline;
    } catch (JsonProcessingException e) {
      throw new ConfigException(ConfigException.E_CONFIG_IO, "序列化配置失败", e);
    }
  }

  private static void fsync(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.warn("清理临时文件失败（不掩盖主异常）: {}", path, e);
    }
  }

  /** env 覆盖层构造期快照：{@code keys.*} 丢弃（R5-2）、null 丢弃、值按 JSON 解析、失败按文本。 */
  private static Map<String, JsonNode> snapshotEnv(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, JsonNode> snapshot = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.startsWith("keys.") || value == null) {
        continue;
      }
      JsonNode node;
      try {
        node = JSON.readTree(value);
      } catch (IOException e) {
        node = TextNode.valueOf(value);
      }
      if (node != null) {
        snapshot.put(key, node);
      }
    }
    return Collections.unmodifiableMap(snapshot);
  }

  /** 按点分键段取节点；任一段为空/{@code .}/{@code ..} 或中途非对象 → null（读侧不抛）。 */
  private static JsonNode nodeAt(ObjectNode root, List<String> segments) {
    JsonNode current = root;
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return null;
      }
      if (!(current instanceof ObjectNode)) {
        return null;
      }
      JsonNode next = ((ObjectNode) current).get(segment);
      if (next == null) {
        return null;
      }
      current = next;
    }
    return current;
  }

  /** 按点分键段写入节点（沿途缺对象则创建、非对象则覆盖为对象）；段合法性已由写前授权保证。 */
  private static void setAtPath(ObjectNode root, List<String> segments, JsonNode value) {
    ObjectNode current = root;
    for (int i = 0; i < segments.size() - 1; i++) {
      JsonNode next = current.get(segments.get(i));
      if (!(next instanceof ObjectNode)) {
        next = JSON.createObjectNode();
        current.set(segments.get(i), next);
      }
      current = (ObjectNode) next;
    }
    current.set(segments.get(segments.size() - 1), value);
  }

  /**
   * 按点分键段摘除节点，并<b>顺路摘掉被删空的对象</b>（返回是否真的删掉了东西）。
   *
   * <p><b>为什么顺路摘空壳</b>：删 {@code llm.routes.glm} 之后若留下 {@code "routes": {}}，配置页渲染原始 JSON
   * 时会显示一个已经不存在的分组——用户删了却看见它还在，只能再删一次，而那一删是"键不存在"的无操作，界面上 表现为删除按钮时灵时不灵。空对象在这里一律按"这个分组不存在"处理。
   *
   * <p>中途遇到非对象（{@code "a"} 是字符串却要删 {@code "a.b"}）→ 视为该键不存在，不做任何改动（与 {@link #nodeAt}
   * 的读侧口径一致：读不到的东西，删也删不动）。
   */
  private static boolean deleteAtPath(ObjectNode root, List<String> segments) {
    return deleteAt(root, segments, 0);
  }

  private static boolean deleteAt(ObjectNode current, List<String> segments, int index) {
    String segment = segments.get(index);
    if (index == segments.size() - 1) {
      return current.remove(segment) != null;
    }
    JsonNode next = current.get(segment);
    if (!(next instanceof ObjectNode)) {
      return false;
    }
    ObjectNode child = (ObjectNode) next;
    boolean removed = deleteAt(child, segments, index + 1);
    if (removed && child.size() == 0) {
      current.remove(segment); // 空壳一并摘掉：文件里留下的都该是"确实存在的东西"
    }
    return removed;
  }

  /** 点分键分段；limit -1 保留尾随空段（"x." → ["x",""]），读侧 nodeAt 对空段一律视为不存在。 */
  private static List<String> splitSegments(String dotted) {
    return new ArrayList<>(Arrays.asList(dotted.split("\\.", -1)));
  }
}
