package io.mosire.agentlib.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ConfigAuth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigStore}/{@link FileConfigStore}/{@link ConfigAuth} 的闭环测试：分层合并、schema 校验、
 * 原子写回滚、前缀授权、事件回调、凭据安全。
 *
 * <p>判别性约定（R10）：每条验收断言都按"错误但结构相似的实现能否蒙混过关"设计——越权被拒断言<b>文件未 被触碰</b>且<b>相邻合法前缀仍可写</b>（抓"一律拒"）；非法
 * schema 断言<b>磁盘逐字节未变</b>（抓"先写坏 再抛"）；分层合并断言<b>高层覆盖低层</b>且<b>低层独有键保留</b>（抓"高层整体替换低层"）。
 */
class ConfigStoreTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 全放行 schema（值任意对象）——多数用例的"存在校验"占位。 */
  private static final String PERMISSIVE_SCHEMA = "{\"type\":\"object\"}";

  /** temperature 为 0..2 的整数（不 required——允许同文件其他键并存）。 */
  private static final String TEMPERATURE_SCHEMA =
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{\"temperature\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2}}}";

  @TempDir Path tempDir;

  // ---------- 构造与工具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private FileConfigStore store(Map<String, String> env) {
    return new FileConfigStore(tempDir, tempDir.resolve("schemas"), env, ConfigListener.none());
  }

  private FileConfigStore store(ConfigListener listener) {
    return new FileConfigStore(tempDir, tempDir.resolve("schemas"), Map.of(), listener);
  }

  private static AgentPermissionSet system() {
    return AgentPermissionSet.system();
  }

  private static AgentPermissionSet asAgent() {
    return AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
  }

  private static AgentPermissionSet asGuest() {
    return AgentPermissionSet.unrestricted(AccessToken.GUEST);
  }

  private static JsonNode num(int value) {
    return JSON.getNodeFactory().numberNode(value);
  }

  private static JsonNode text(String value) {
    return JSON.getNodeFactory().textNode(value);
  }

  private static JsonNode permissiveSchema() throws IOException {
    return JSON.readTree(PERMISSIVE_SCHEMA);
  }

  private Path writeSchema(String name, String content) throws IOException {
    Path dir = tempDir.resolve("schemas");
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private Path globalFile() {
    return tempDir.resolve("config.json");
  }

  private Path agentFile(String id) {
    return tempDir.resolve("agents").resolve(id + ".json");
  }

  private JsonNode readJson(Path file) throws IOException {
    return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
  }

  private void assertNoTmpFiles() throws IOException {
    try (var stream = Files.walk(tempDir)) {
      assertThat(
              stream.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp")).toList())
          .as("失败路径不得留下 .tmp 残留")
          .isEmpty();
    }
  }

  /** 异常链全部 message（含 cause）——凭据安全断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable cur = throwable; cur != null; cur = cur.getCause()) {
      messages.add(String.valueOf(cur.getMessage()));
    }
    return messages;
  }

  /** 测试缝：原子替换成功后把目标写坏（模拟"写坏但没察觉"——读回校验必须抓住并回滚）。 */
  private static final class CorruptingMoveStore extends FileConfigStore {
    CorruptingMoveStore(Path root) {
      super(root);
    }

    @Override
    protected void moveAtomically(Path tmp, Path target) throws IOException {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      Files.writeString(target, "{\"temperature\":999}", StandardCharsets.UTF_8);
    }
  }

  /** 测试缝：文件系统不支持 ATOMIC_MOVE。 */
  private static final class NoAtomicMoveStore extends FileConfigStore {
    NoAtomicMoveStore(Path root) {
      super(root);
    }

    @Override
    protected void moveAtomically(Path tmp, Path target) throws IOException {
      throw new AtomicMoveNotSupportedException(tmp.toString(), target.toString(), "not supported");
    }
  }

  // ---------- ConfigAuth 纯函数 ----------

  /** 前缀规则全集：runtime/keys 仅 SYSTEM、agents.<id>.* 归属者写、其余 deny-by-default、不合规键拒绝。 */
  @Test
  void authEnforcesPrefixRules() {
    assertThat(ConfigAuth.writeDenialReason(null, null, "runtime.a")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.GUEST, null, "runtime.a")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, null, "runtime.a")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "runtime.a")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "keys.openai")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "keys.openai")).isPresent();

    assertThat(ConfigAuth.writeDenialReason(AccessToken.GUEST, "bob", "agents.bob.x")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents.bob.x")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "alice", "agents.bob.x"))
        .isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, null, "agents.bob.x")).isPresent();
    // SYSTEM 例外放行：运行时自身可管理任意 Agent 配置
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "agents.bob.x")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, "alice", "agents.bob.x")).isEmpty();

    // deny-by-default 与不合规键
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "other.x")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents.bob")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents..x")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents.bob.")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents.bob.x..y"))
        .isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "runtime.")).isPresent();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "runtime..x")).isPresent();
    assertThat(
            ConfigAuth.writeDenialReason(
                AccessToken.SYSTEM, null, "agents.https://user:sk-live-1@h/x"))
        .isPresent();

    assertThat(ConfigAuth.agentIdOf("agents.bob.x")).contains("bob");
    assertThat(ConfigAuth.agentIdOf("agents.bob.x.y")).contains("bob");
    assertThat(ConfigAuth.agentIdOf("agents.bob")).isEmpty();
    assertThat(ConfigAuth.agentIdOf("agents..x")).isEmpty();
    assertThat(ConfigAuth.agentIdOf("runtime.x")).isEmpty();
  }

  /** 拒绝原因不回显未经校验的调用方字符串（键/ownerId 可能是凭据形态——T18 Minor 3 同源约束）。 */
  @Test
  void authDenialReasonsNeverEchoUntrustedStrings() {
    String credentialUrlKey = "agents.https://user:sk-live-abc@vault.local/x";
    String reason =
        ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", credentialUrlKey).orElseThrow();
    assertThat(reason).doesNotContain("sk-live-abc").doesNotContain("vault.local");

    String credentialOwner = "https://user:sk-live-abc@vault.local";
    String ownerReason =
        ConfigAuth.writeDenialReason(AccessToken.DEFAULT, credentialOwner, "agents.bob.x")
            .orElseThrow();
    assertThat(ownerReason).doesNotContain("sk-live-abc").doesNotContain("vault.local");
    // 通过字符集校验的 id（构造不出 URL）可以被引用——可诊断性
    assertThat(ownerReason).contains("bob");
  }

  // ---------- 基础读写与分层合并 ----------

  @Test
  void putAndGetRoundTripBothPrefixKinds() throws Exception {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    assertThat(store.get("runtime", "logLevel"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("INFO"));
    assertThat(readJson(globalFile()).get("runtime").get("logLevel").asText()).isEqualTo("INFO");

    store.put("agents.bob", "temperature", num(1), asAgent(), "bob", permissiveSchema());
    assertThat(store.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(1));
    assertThat(readJson(agentFile("bob")).get("temperature").asInt()).isEqualTo(1);
    // Agent 写入落 agents/<id>.json，不进全局文件
    assertThat(readJson(globalFile()).has("agents")).isFalse();
  }

  @Test
  void putWritesNestedPathAndMergesSiblingKeys() throws Exception {
    FileConfigStore store = store();
    store.put("runtime", "a.b", num(1), system(), null, permissiveSchema());
    store.put("runtime", "a.c", num(2), system(), null, permissiveSchema());
    assertThat(store.get("runtime", "a.b"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(1));
    assertThat(store.get("runtime", "a.c"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(2));
    JsonNode global = readJson(globalFile());
    assertThat(global.get("runtime").get("a").get("b").asInt()).isEqualTo(1);
    assertThat(global.get("runtime").get("a").get("c").asInt()).isEqualTo(2);
  }

  /** 分层合并：高层覆盖低层（env > agents/<id>.json > config.json）。 */
  @Test
  void layeredMergeHigherLayerOverridesLower() throws Exception {
    Files.createDirectories(tempDir);
    Files.writeString(
        globalFile(), "{\"agents\":{\"bob\":{\"temperature\":0}}}", StandardCharsets.UTF_8);
    FileConfigStore fileOnly = store();
    fileOnly.put("agents.bob", "temperature", num(1), system(), null, permissiveSchema());
    assertThat(fileOnly.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(1));

    FileConfigStore withEnv = store(Map.of("agents.bob.temperature", "2"));
    assertThat(withEnv.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(2));
    // env 值按 JSON 解析；解析失败按文本
    FileConfigStore textEnv = store(Map.of("agents.bob.model", "gpt-4o"));
    assertThat(textEnv.get("agents.bob", "model"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("gpt-4o"));
  }

  /** 分层合并 ≠ 替换：低层独有的键不被高层抹掉。 */
  @Test
  void layeredMergeKeepsLowerLayerOnlyKeys() throws Exception {
    Files.writeString(
        globalFile(), "{\"agents\":{\"bob\":{\"alpha\":\"global-only\"}}}", StandardCharsets.UTF_8);
    FileConfigStore store = store();
    store.put("agents.bob", "beta", num(2), asAgent(), "bob", permissiveSchema());
    assertThat(store.get("agents.bob", "alpha"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("global-only"));
    assertThat(store.get("agents.bob", "beta"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(2));
  }

  /** env 层只读：永不落盘、构造期快照、读不产生回调；env 会遮蔽同名盘上值（文档化行为）。 */
  @Test
  void envOverlayIsReadOnlySnapshotAndNeverPersists() throws Exception {
    List<String> events = new ArrayList<>();
    Map<String, String> env = new HashMap<>();
    env.put("runtime.logLevel", "DEBUG");
    FileConfigStore store =
        new FileConfigStore(tempDir, tempDir.resolve("schemas"), env, (k, o, n) -> events.add(k));
    assertThat(store.get("runtime", "logLevel"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("DEBUG"));
    assertThat(events).isEmpty(); // 读不产生回调

    store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    assertThat(Files.readString(globalFile(), StandardCharsets.UTF_8))
        .contains("INFO")
        .doesNotContain("DEBUG");
    assertThat(store.get("runtime", "logLevel"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("DEBUG")); // env 优先级最高
    assertThat(events).containsExactly("runtime.logLevel"); // 落盘成功回调一次

    env.put("runtime.logLevel", "TRACE"); // 构造后改原始 map：快照不受影响
    assertThat(store.get("runtime", "logLevel"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("DEBUG"));
  }

  /** env 层排除 keys.*：密钥只能经 ConfigStore 授权路径进入（R5-2 的判别性用例）。 */
  @Test
  void envOverlayExcludesKeysPrefix() throws Exception {
    FileConfigStore store = store(Map.of("keys.openai", "sk-live-from-env"));
    assertThat(store.get("keys", "openai")).isEmpty();
    store.put("keys", "openai", text("ENV:OPENAI_KEY"), system(), null, permissiveSchema());
    assertThat(store.get("keys", "openai"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("ENV:OPENAI_KEY"));
  }

  // ---------- 前缀授权（端到端） ----------

  /** R10 判别性：越权被拒必须"文件未被触碰"+"相邻合法前缀仍可写"（抓"一律拒"的空转实现）。 */
  @Test
  void deniedPutLeavesFileUntouchedAndAdjacentPrefixStillWritable() throws Exception {
    Files.createDirectories(tempDir.resolve("agents"));
    byte[] before = "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8);
    Files.write(agentFile("bob"), before);

    FileConfigStore store = store();
    ConfigException denied =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "agents.bob", "temperature", num(2), asAgent(), "alice", permissiveSchema()));
    assertThat(denied).isNotNull();
    assertThat(denied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(before); // 文件未被触碰
    assertNoTmpFiles();

    // 相邻合法前缀（alice 自己的配置）仍可写——证明是"按前缀"拒，不是"一律拒"
    store.put("agents.alice", "temperature", num(2), asAgent(), "alice", permissiveSchema());
    assertThat(readJson(agentFile("alice")).get("temperature").asInt()).isEqualTo(2);
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(before);

    // 非 SYSTEM 写 runtime.*：拒绝且全局文件不存在（从未被创建）
    ConfigException runtimeDenied =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "runtime", "logLevel", text("INFO"), asAgent(), "bob", permissiveSchema()));
    assertThat(runtimeDenied).isNotNull();
    assertThat(runtimeDenied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(globalFile()).doesNotExist();
  }

  @Test
  void guestCannotWriteOwnConfig() throws Exception {
    ConfigException denied =
        catchThrowableOfType(
            ConfigException.class,
            () -> store().put("agents.bob", "x", num(1), asGuest(), "bob", permissiveSchema()));
    assertThat(denied).isNotNull();
    assertThat(denied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(agentFile("bob")).doesNotExist();
  }

  // ---------- schema 校验 ----------

  /** R6-1/R10 判别性：非法值 → 磁盘逐字节未变（抓"先写坏再抛"的实现）。 */
  @Test
  void schemaRejectionLeavesDiskByteIdentical() throws Exception {
    byte[] before = "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8);
    Files.createDirectories(tempDir.resolve("agents"));
    Files.write(agentFile("bob"), before);
    writeSchema("temp.json", TEMPERATURE_SCHEMA);

    List<String> events = new ArrayList<>();
    FileConfigStore store = store((k, o, n) -> events.add(k));
    ConfigException invalid =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("agents.bob", "temperature", num(99), asAgent(), "bob", "temp.json"));
    assertThat(invalid).isNotNull();
    assertThat(invalid.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(before);
    assertNoTmpFiles();
    assertThat(events).isEmpty(); // 校验失败不回调
  }

  @Test
  void schemaValidPutPersistsAndNotifiesAfterPersistence() throws Exception {
    writeSchema("temp.json", TEMPERATURE_SCHEMA);
    AtomicReference<Throwable> listenerAssertionFailure = new AtomicReference<>();
    FileConfigStore store =
        store(
            (key, oldValue, newValue) -> {
              try {
                // 回调时刻盘上必须已能读到本次写入的值（先落盘后广播）
                assertThat(readJson(agentFile("bob")).get("temperature")).isEqualTo(newValue);
              } catch (Throwable t) {
                listenerAssertionFailure.set(t);
                throw new AssertionError(t);
              }
            });
    store.put("agents.bob", "temperature", num(1), asAgent(), "bob", "temp.json");
    store.put("agents.bob", "temperature", num(2), asAgent(), "bob", "temp.json");
    assertThat(listenerAssertionFailure.get()).isNull();
  }

  @Test
  void listenerReceivesFileLevelOldAndNewValues() throws Exception {
    List<Object[]> calls = new ArrayList<>();
    FileConfigStore store = store((k, o, n) -> calls.add(new Object[] {k, o, n}));
    store.put("agents.bob", "temperature", num(1), asAgent(), "bob", permissiveSchema());
    store.put("agents.bob", "temperature", num(2), asAgent(), "bob", permissiveSchema());
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0)).containsExactly("agents.bob.temperature", null, num(1));
    assertThat(calls.get(1)).containsExactly("agents.bob.temperature", num(1), num(2));
  }

  /** R6-2：既有旧配置 JSON 合法但违反 schema → 读照常返回；写被整文件校验挡住；修违例键后放行。 */
  @Test
  void schemaInvalidOldStateIsReadableButBlocksWritesUntilFixed() throws Exception {
    Files.createDirectories(tempDir.resolve("agents"));
    byte[] invalid = "{\"temperature\":99,\"model\":\"gpt-4o\"}".getBytes(StandardCharsets.UTF_8);
    Files.write(agentFile("bob"), invalid);
    writeSchema("temp.json", TEMPERATURE_SCHEMA);

    FileConfigStore store = store();
    // 读照常返回（schema 是写侧契约）
    assertThat(store.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(99));

    // 写无关键：整文件校验失败，磁盘未动
    ConfigException blocked =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("agents.bob", "model", text("gpt-5"), asAgent(), "bob", "temp.json"));
    assertThat(blocked).isNotNull();
    assertThat(blocked.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(invalid);

    // 修违例键：放行
    store.put("agents.bob", "temperature", num(1), asAgent(), "bob", "temp.json");
    assertThat(store.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(1));
  }

  /** R6-2：损坏文件（非法 JSON）读抛、写抛且写不触碰损坏文件。 */
  @Test
  void corruptFileThrowsAndIsNotTouched() throws Exception {
    byte[] garbage = "not json{{{".getBytes(StandardCharsets.UTF_8);
    Files.write(globalFile(), garbage);

    FileConfigStore store = store();
    ConfigException onGet =
        catchThrowableOfType(ConfigException.class, () -> store.get("runtime", "logLevel"));
    assertThat(onGet).isNotNull();
    assertThat(onGet.code()).isEqualTo(ConfigException.E_CONFIG_CORRUPT);

    ConfigException onPut =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema()));
    assertThat(onPut).isNotNull();
    assertThat(onPut.code()).isEqualTo(ConfigException.E_CONFIG_CORRUPT);
    assertThat(Files.readAllBytes(globalFile())).isEqualTo(garbage); // 损坏文件未被破坏
    assertNoTmpFiles();
  }

  @Test
  void missingOrUnusableSchemaThrowsUnavailableWithoutTouchingDisk() throws Exception {
    FileConfigStore store = store();
    ConfigException missing =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("runtime", "logLevel", text("INFO"), system(), null, "nope.json"));
    assertThat(missing).isNotNull();
    assertThat(missing.code()).isEqualTo(ConfigException.E_SCHEMA_UNAVAILABLE);
    assertThat(globalFile()).doesNotExist();

    writeSchema("not-schema.json", "{not json");
    ConfigException unparsable =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put("runtime", "logLevel", text("INFO"), system(), null, "not-schema.json"));
    assertThat(unparsable).isNotNull();
    assertThat(unparsable.code()).isEqualTo(ConfigException.E_SCHEMA_UNAVAILABLE);
    assertThat(globalFile()).doesNotExist();

    writeSchema("bad-schema.json", "{\"minimum\":\"abc\"}");
    ConfigException invalidSchema =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put("runtime", "logLevel", text("INFO"), system(), null, "bad-schema.json"));
    assertThat(invalidSchema).isNotNull();
    assertThat(invalidSchema.code()).isEqualTo(ConfigException.E_SCHEMA_UNAVAILABLE);
    assertThat(globalFile()).doesNotExist();
    assertNoTmpFiles();
  }

  @Test
  void inlineSchemaNodeVariantValidates() throws Exception {
    byte[] before = "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8);
    Files.createDirectories(tempDir.resolve("agents"));
    Files.write(agentFile("bob"), before);
    JsonNode schemaNode = JSON.readTree(TEMPERATURE_SCHEMA);

    FileConfigStore store = store();
    ConfigException invalid =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("agents.bob", "temperature", num(-5), asAgent(), "bob", schemaNode));
    assertThat(invalid).isNotNull();
    assertThat(invalid.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(before);
  }

  // ---------- 原子写 / 回滚 ----------

  /** R4：首次写无 .bak；二次写 .bak = 前值；任意时刻无 .tmp 残留。 */
  @Test
  void atomicWriteBacksUpPreviousVersionAndLeavesNoTmp() throws Exception {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    assertThat(globalFile().resolveSibling("config.json.bak")).doesNotExist();
    assertNoTmpFiles();

    store.put("runtime", "logLevel", text("DEBUG"), system(), null, permissiveSchema());
    Path bak = globalFile().resolveSibling("config.json.bak");
    assertThat(bak).exists();
    assertThat(readJson(bak).get("runtime").get("logLevel").asText()).isEqualTo("INFO");
    assertNoTmpFiles();
  }

  /** R6-3/R10 判别性：读回校验抓住"替换后损坏"→ 回滚 → 盘上回到写前字节，get 与盘一致（非内存缓存）。 */
  @Test
  void readBackVerificationFailureRollsBackFromDisk() throws Exception {
    byte[] before = "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8);
    Files.createDirectories(tempDir.resolve("agents"));
    Files.write(agentFile("bob"), before);
    writeSchema("temp.json", TEMPERATURE_SCHEMA);

    FileConfigStore store = new CorruptingMoveStore(tempDir);
    ConfigException failed =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("agents.bob", "temperature", num(2), asAgent(), "bob", "temp.json"));
    assertThat(failed).isNotNull();
    assertThat(failed.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(failed.getMessage()).contains("已回滚");
    assertThat(Files.readAllBytes(agentFile("bob"))).isEqualTo(before); // 原文件内容等于写前
    assertThat(store.get("agents.bob", "temperature"))
        .hasValueSatisfying(v -> assertThat(v.asInt()).isEqualTo(1)); // 再读回来的是回滚后的内容
    assertNoTmpFiles();
  }

  /** 首次写入（无 .bak）读回失败 → 删除新文件，回到"无此文件"状态。 */
  @Test
  void rollbackWithoutPreviousFileDeletesNewFile() throws Exception {
    writeSchema("temp.json", TEMPERATURE_SCHEMA);
    FileConfigStore store = new CorruptingMoveStore(tempDir);
    ConfigException failed =
        catchThrowableOfType(
            ConfigException.class,
            () -> store.put("agents.bob", "temperature", num(2), asAgent(), "bob", "temp.json"));
    assertThat(failed).isNotNull();
    assertThat(failed.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(agentFile("bob")).doesNotExist();
    assertThat(store.get("agents.bob", "temperature")).isEmpty();
    assertNoTmpFiles();
  }

  /** R4-⑤：ATOMIC_MOVE 不受支持 → E_CONFIG_IO、不降级、原文件不动、无 .tmp 残留。 */
  @Test
  void atomicMoveUnsupportedThrowsWithoutResidueOrTouchingTarget() throws Exception {
    FileConfigStore seed = store();
    seed.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    byte[] before = Files.readAllBytes(globalFile());

    FileConfigStore store = new NoAtomicMoveStore(tempDir);
    ConfigException failed =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "runtime", "logLevel", text("DEBUG"), system(), null, permissiveSchema()));
    assertThat(failed).isNotNull();
    assertThat(failed.code()).isEqualTo(ConfigException.E_CONFIG_IO);
    assertThat(failed.getMessage()).contains("ATOMIC_MOVE").contains("不降级");
    assertThat(Files.readAllBytes(globalFile())).isEqualTo(before);
    assertThat(store.get("runtime", "logLevel"))
        .hasValueSatisfying(v -> assertThat(v.asText()).isEqualTo("INFO"));
    assertNoTmpFiles();
  }

  @Test
  void listenerExceptionDoesNotRollbackWrite() throws Exception {
    FileConfigStore store =
        store(
            (k, o, n) -> {
              throw new RuntimeException("listener boom");
            });
    store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    assertThat(readJson(globalFile()).get("runtime").get("logLevel").asText()).isEqualTo("INFO");
  }

  // ---------- 凭据安全与错误码 ----------

  /** R8-② 判别性：配置值含凭据样串 + 触发失败 → 异常链全部 message 不含该串；且带非敏感错误码。 */
  @Test
  void exceptionsNeverLeakCredentialShapedStrings() throws Exception {
    Files.createDirectories(tempDir.resolve("agents"));
    Files.write(agentFile("bob"), "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8));
    writeSchema("temp.json", TEMPERATURE_SCHEMA);

    String credential = "sk-live-SECRET123";
    String credentialUrl = "https://user:sk-live-SECRET123@vault.local/v1";

    FileConfigStore store = store();
    ConfigException byValue =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "agents.bob", "temperature", text(credential), asAgent(), "bob", "temp.json"));
    assertThat(byValue).isNotNull();
    assertThat(byValue.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(allMessages(byValue)).noneMatch(m -> m != null && m.contains(credential));

    ConfigException byUrlValue =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "agents.bob",
                    "temperature",
                    text(credentialUrl),
                    asAgent(),
                    "bob",
                    "temp.json"));
    assertThat(byUrlValue).isNotNull();
    assertThat(allMessages(byUrlValue)).noneMatch(m -> m != null && m.contains(credential));

    ConfigException denied =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "agents.bob",
                    "temperature",
                    text(credentialUrl),
                    asAgent(),
                    "alice",
                    "temp.json"));
    assertThat(denied).isNotNull();
    assertThat(denied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(allMessages(denied)).noneMatch(m -> m != null && m.contains(credential));
  }

  /** R8-①：五类失败各有可分辨的非敏感错误码（事件库只看得见 message，分类不能靠 cause）。 */
  @Test
  void failureCodesExposeDistinctCategories() throws Exception {
    Files.createDirectories(tempDir.resolve("agents"));
    Files.write(agentFile("bob"), "{\"temperature\":1}".getBytes(StandardCharsets.UTF_8));
    writeSchema("temp.json", TEMPERATURE_SCHEMA);
    Set<String> codes = new HashSet<>();

    codes.add(
        catchThrowableOfType(
                ConfigException.class,
                () ->
                    store()
                        .put("agents.bob", "temperature", num(9), asAgent(), "alice", "temp.json"))
            .code());
    codes.add(
        catchThrowableOfType(
                ConfigException.class,
                () ->
                    store().put("agents.bob", "temperature", num(9), asAgent(), "bob", "temp.json"))
            .code());
    codes.add(
        catchThrowableOfType(
                ConfigException.class,
                () ->
                    store()
                        .put("agents.bob", "temperature", num(9), asAgent(), "bob", "absent.json"))
            .code());
    Files.write(globalFile(), "garbage{{{".getBytes(StandardCharsets.UTF_8));
    codes.add(
        catchThrowableOfType(ConfigException.class, () -> store().get("runtime", "logLevel"))
            .code());
    Files.deleteIfExists(globalFile()); // 清掉损坏文件，让后续 seed 写入正常
    FileConfigStore seed = store();
    seed.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    codes.add(
        catchThrowableOfType(
                ConfigException.class,
                () ->
                    new NoAtomicMoveStore(tempDir)
                        .put(
                            "runtime",
                            "logLevel",
                            text("DEBUG"),
                            system(),
                            null,
                            permissiveSchema()))
            .code());

    assertThat(codes)
        .containsExactlyInAnyOrder(
            ConfigException.E_PREFIX_DENIED,
            ConfigException.E_SCHEMA_INVALID,
            ConfigException.E_SCHEMA_UNAVAILABLE,
            ConfigException.E_CONFIG_CORRUPT,
            ConfigException.E_CONFIG_IO);
  }

  // ---------- 并发与杂项 ----------

  /** R9：并发 put 同一文件不丢更新（读-改-写整体在锁内）。 */
  @Test
  void concurrentPutsDoNotLoseUpdates() throws Exception {
    FileConfigStore store = store();
    JsonNode schema = permissiveSchema();
    Runnable writer1 =
        () -> {
          for (int i = 0; i < 40; i++) {
            store.put("runtime", "k1_" + i, num(i), system(), null, schema);
          }
        };
    Runnable writer2 =
        () -> {
          for (int i = 0; i < 40; i++) {
            store.put("runtime", "k2_" + i, num(i), system(), null, schema);
          }
        };
    Thread t1 = new Thread(writer1, "writer-1");
    Thread t2 = new Thread(writer2, "writer-2");
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    for (int i = 0; i < 40; i++) {
      final int v = i;
      assertThat(store.get("runtime", "k1_" + i))
          .hasValueSatisfying(n -> assertThat(n.asInt()).isEqualTo(v));
      assertThat(store.get("runtime", "k2_" + i))
          .hasValueSatisfying(n -> assertThat(n.asInt()).isEqualTo(v));
    }
  }

  /** 读侧键格式不合规一律按"不存在"返回空（含路径穿越形态的 agent id——不会触碰文件系统）。 */
  @Test
  void getWithMalformedKeysReturnsEmpty() throws Exception {
    FileConfigStore store = store();
    assertThat(store.get("agents.bob", "x..y")).isEmpty();
    assertThat(store.get("agents.bob", "")).isEmpty();
    assertThat(store.get("", "x")).isEmpty();
    assertThat(store.get("agents../evil", "x")).isEmpty();
    assertThat(store.get("runtime", "a.")).isEmpty();
    assertThat(store.get("runtime", "..")).isEmpty();
    assertThat(agentFile("../evil")).doesNotExist();
  }

  /** get 对存在的键返回 Optional，对缺失键返回空。 */
  @Test
  void getReturnsEmptyForAbsentKeys() throws Exception {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("INFO"), system(), null, permissiveSchema());
    assertThat(store.get("runtime", "logLevel")).isPresent();
    assertThat(store.get("runtime", "absent")).isEmpty();
    assertThat(store.get("agents.nobody", "x")).isEmpty();
  }
}
