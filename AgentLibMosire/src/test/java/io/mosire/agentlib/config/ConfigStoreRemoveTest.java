package io.mosire.agentlib.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigStore#remove} 的闭环测试（A4 的「删」）：对接方要做 provider 配置页，能增能改却<b>不能删</b>， 等于逼用户去手改 JSON
 * 文件——所以删除必须是一等能力，而且必须与写入同一条安全路径。
 *
 * <p>判别性约定（与 {@link ConfigStoreTest} 同一套，R10）：每条断言都按"错误但结构相似的实现能否蒙混过关"设计——
 * 越权/校验失败断言<b>磁盘逐字节未变</b>（抓"先写坏再抛"）；摘空壳断言<b>兄弟键仍在</b>（抓"把整个父对象删了"）；
 * 幂等断言<b>文件字节未变且无回调</b>（抓"每次删都重写一遍文件"）。
 */
class ConfigStoreRemoveTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 全放行 schema（值任意对象）——多数用例的"存在校验"占位。 */
  private static final String PERMISSIVE_SCHEMA = "{\"type\":\"object\"}";

  /** 要求 {@code runtime.model} 必填——用于验证"删掉必填键必须被拦下"。 */
  private static final String MODEL_REQUIRED_SCHEMA =
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{\"runtime\":{\"type\":\"object\",\"required\":[\"model\"],"
          + "\"properties\":{\"model\":{\"type\":\"string\"}}}}}";

  @TempDir Path tempDir;

  // ---------- 工具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private static AgentPermissionSet system() {
    return AgentPermissionSet.system();
  }

  private static AgentPermissionSet asAgent() {
    return AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
  }

  private static JsonNode text(String value) {
    return JSON.getNodeFactory().textNode(value);
  }

  private static JsonNode object(String json) throws IOException {
    return JSON.readTree(json);
  }

  private static JsonNode permissiveSchema() throws IOException {
    return JSON.readTree(PERMISSIVE_SCHEMA);
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

  private byte[] bytesOf(Path file) throws IOException {
    return Files.readAllBytes(file);
  }

  /** 一次成功落盘的变更（写入与删除共用回调，故旧值/新值都可能为空节点）。 */
  private record Change(String key, JsonNode oldValue, JsonNode newValue) {}

  private static final class RecordingListener implements ConfigListener {
    private final List<Change> changes = new ArrayList<>();

    @Override
    public void onChanged(String key, JsonNode oldValue, JsonNode newValue) {
      changes.add(new Change(key, oldValue, newValue));
    }
  }

  // ---------- 基本删除 ----------

  @Test
  void 删除已有键后配置里不再有它() throws IOException {
    FileConfigStore store = store();
    store.put(
        "llm.routes.deepseek", "model", text("deepseek-chat"), system(), null, permissiveSchema());

    store.remove("llm.routes.deepseek", "model", system(), null, permissiveSchema());

    assertThat(store.get("llm.routes.deepseek", "model")).as("删掉之后读不到").isEmpty();
    assertThat(readJson(globalFile()).has("llm")).as("父对象被删空后一并摘掉，不留空壳").isFalse();
  }

  @Test
  void 删掉一个键不会动同级的兄弟键() throws IOException {
    FileConfigStore store = store();
    store.put("llm.routes.a", "model", text("m-a"), system(), null, permissiveSchema());
    store.put("llm.routes.b", "model", text("m-b"), system(), null, permissiveSchema());

    store.remove("llm.routes.a", "model", system(), null, permissiveSchema());

    assertThat(store.get("llm.routes.b", "model")).as("兄弟键必须原样保留").hasValue(text("m-b"));
    assertThat(readJson(globalFile()).path("llm").path("routes").has("a"))
        .as("被删的那条不留空壳")
        .isFalse();
  }

  @Test
  void 沿途清空的中间层被逐级摘掉() throws IOException {
    FileConfigStore store = store();
    store.put("llm.routes.a", "model", text("m-a"), system(), null, permissiveSchema());
    store.put("runtime", "logLevel", text("info"), system(), null, permissiveSchema());

    store.remove("llm.routes.a", "model", system(), null, permissiveSchema());

    JsonNode root = readJson(globalFile());
    assertThat(root.has("llm")).as("llm → routes → a 三级都空了，应当整支摘掉").isFalse();
    assertThat(root.path("runtime").path("logLevel").asText()).as("无关分支不受影响").isEqualTo("info");
  }

  @Test
  void 删除不存在的键是无操作_不写盘不回调不抛() throws IOException {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("info"), system(), null, permissiveSchema());
    byte[] before = bytesOf(globalFile());

    RecordingListener listener = new RecordingListener();
    FileConfigStore observed =
        new FileConfigStore(tempDir, tempDir.resolve("schemas"), java.util.Map.of(), listener);

    observed.remove("runtime", "nope", system(), null, permissiveSchema());
    observed.remove("runtime.logLevel.deep", "deeper", system(), null, permissiveSchema());

    assertThat(bytesOf(globalFile())).as("幂等：文件逐字节未变").isEqualTo(before);
    assertThat(listener.changes).as("没有变更就没有回调").isEmpty();
  }

  @Test
  void 删掉本就不存在的文件里的键不会把文件创建出来() throws IOException {
    FileConfigStore store = store();

    store.remove("runtime", "logLevel", system(), null, permissiveSchema());

    assertThat(Files.exists(globalFile())).as("无操作不该凭空造出配置文件").isFalse();
  }

  // ---------- 回调 ----------

  @Test
  void 删除照样走变更回调_新值是空节点() throws IOException {
    FileConfigStore seed = store();
    seed.put("llm.routes.deepseek", "model", text("m"), system(), null, permissiveSchema());

    RecordingListener listener = new RecordingListener();
    FileConfigStore store =
        new FileConfigStore(tempDir, tempDir.resolve("schemas"), java.util.Map.of(), listener);

    store.remove("llm.routes.deepseek", "model", system(), null, permissiveSchema());

    assertThat(listener.changes).as("删除必须产生一次回调（provider 页靠它刷新）").hasSize(1);
    Change change = listener.changes.get(0);
    assertThat(change.key()).isEqualTo("llm.routes.deepseek.model");
    assertThat(change.oldValue()).isEqualTo(text("m"));
    assertThat(change.newValue().isNull()).as("删除用空节点表达，而不是 Java null").isTrue();
  }

  // ---------- 授权 ----------

  @Test
  void 越权删除被拒且文件逐字节未变() throws IOException {
    FileConfigStore store = store();
    store.put("llm.routes.deepseek", "model", text("m"), system(), null, permissiveSchema());
    byte[] before = bytesOf(globalFile());

    ConfigException denied =
        catchThrowableOfType(
            () ->
                store.remove("llm.routes.deepseek", "model", asAgent(), "bob", permissiveSchema()),
            ConfigException.class);

    assertThat(denied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(bytesOf(globalFile())).isEqualTo(before);
  }

  @Test
  void 无身份与低身份都删不动() throws IOException {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("info"), system(), null, permissiveSchema());
    byte[] before = bytesOf(globalFile());

    ConfigException noIdentity =
        catchThrowableOfType(
            () -> store.remove("runtime", "logLevel", null, null, permissiveSchema()),
            ConfigException.class);
    ConfigException guest =
        catchThrowableOfType(
            () ->
                store.remove(
                    "runtime",
                    "logLevel",
                    AgentPermissionSet.unrestricted(AccessToken.GUEST),
                    null,
                    permissiveSchema()),
            ConfigException.class);

    assertThat(noIdentity.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(guest.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(bytesOf(globalFile())).isEqualTo(before);
  }

  @Test
  void agent_只能删自己的键() throws IOException {
    FileConfigStore store = store();
    store.put("agents.bob", "temperature", text("7"), asAgent(), "bob", permissiveSchema());
    store.put("agents.eve", "temperature", text("3"), asAgent(), "eve", permissiveSchema());

    ConfigException denied =
        catchThrowableOfType(
            () -> store.remove("agents.eve", "temperature", asAgent(), "bob", permissiveSchema()),
            ConfigException.class);
    assertThat(denied.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(store.get("agents.eve", "temperature")).as("别人的键原样还在").hasValue(text("3"));

    store.remove("agents.bob", "temperature", asAgent(), "bob", permissiveSchema());
    assertThat(store.get("agents.bob", "temperature")).isEmpty();
    assertThat(readJson(agentFile("bob")).has("temperature")).isFalse();
    assertThat(readJson(agentFile("eve")).path("temperature").asText()).isEqualTo("3");
  }

  // ---------- 删不掉根 ----------

  @Test
  void 空键与空段一律按越权拒绝() throws IOException {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("info"), system(), null, permissiveSchema());
    byte[] before = bytesOf(globalFile());

    ConfigException emptyKey =
        catchThrowableOfType(
            () -> store.remove("llm", "", system(), null, permissiveSchema()),
            ConfigException.class);
    ConfigException emptyPrefix =
        catchThrowableOfType(
            () -> store.remove("", "llm.routes", system(), null, permissiveSchema()),
            ConfigException.class);
    ConfigException dotSegment =
        catchThrowableOfType(
            () -> store.remove("runtime", ".", system(), null, permissiveSchema()),
            ConfigException.class);

    assertThat(emptyKey.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(emptyPrefix.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(dotSegment.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(bytesOf(globalFile())).as("「根」删不掉，而且不该有任何副作用").isEqualTo(before);
  }

  // ---------- schema ----------

  @Test
  void 删除会让文档违反_schema_时被拦下且磁盘未变() throws IOException {
    FileConfigStore store = store();
    JsonNode schema = object(MODEL_REQUIRED_SCHEMA);
    store.put("runtime", "model", text("m"), system(), null, schema);
    store.put("runtime", "temperature", text("1"), system(), null, schema);
    byte[] before = bytesOf(globalFile());

    ConfigException rejected =
        catchThrowableOfType(
            () -> store.remove("runtime", "model", system(), null, schema), ConfigException.class);

    assertThat(rejected.code()).isEqualTo(ConfigException.E_SCHEMA_INVALID);
    assertThat(bytesOf(globalFile())).as("写前校验失败 = 磁盘逐字节未变").isEqualTo(before);
    assertThat(store.get("runtime", "model")).as("值还在").hasValue(text("m"));
  }

  @Test
  void 删除非必填键照常成功() throws IOException {
    FileConfigStore store = store();
    JsonNode schema = object(MODEL_REQUIRED_SCHEMA);
    store.put("runtime", "model", text("m"), system(), null, schema);
    store.put("runtime", "temperature", text("1"), system(), null, schema);

    store.remove("runtime", "temperature", system(), null, schema);

    assertThat(store.get("runtime", "temperature")).isEmpty();
    assertThat(store.get("runtime", "model")).hasValue(text("m"));
  }

  // ---------- 与写入同族的边界 ----------

  @Test
  void 手工写进去的_json_null_也能删掉() throws IOException {
    Files.createDirectories(tempDir);
    Files.writeString(globalFile(), "{\"runtime\":{\"logLevel\":null}}\n", StandardCharsets.UTF_8);
    FileConfigStore store = store();

    store.remove("runtime", "logLevel", system(), null, permissiveSchema());

    assertThat(store.get("runtime", "logLevel")).as("null 值也算「存在」，删得掉").isEmpty();
    assertThat(readJson(globalFile()).has("runtime")).isFalse();
  }

  @Test
  void 删除后文件仍是合法_json() throws IOException {
    FileConfigStore store = store();
    store.put("runtime", "logLevel", text("info"), system(), null, permissiveSchema());

    store.remove("runtime", "logLevel", system(), null, permissiveSchema());

    JsonNode root = readJson(globalFile());
    assertThat(root.isObject()).as("整份文档被删空时留下的是空对象，不是空文件").isTrue();
    assertThat(root.isEmpty()).isTrue();
  }
}
