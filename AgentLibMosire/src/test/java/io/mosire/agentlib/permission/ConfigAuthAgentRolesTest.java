package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ {@code agent.roles} <b>不得经配置写入面写入</b>（设计 §4.5 的明文落点，红线 1）——把这条从"偶然正确"钉成"故意正确"。
 *
 * <p><b>为什么它必须是一条负向用例</b>：角色是权限维。让运行中的 Agent 改自己的角色就等于"给自己提权"，正面撞红线 1。 而 {@code ConfigAuth} 的
 * deny-by-default 今天恰好把它挡住——"恰好"意味着任何人给 {@code ConfigAuth} 加一条"放开 agent.*"都会无声地开门， 没有任何用例会红。
 * 本类把"挡得住"变成断言。
 *
 * <p><b>为什么以 SYSTEM 身份试</b>：写入面最强的身份是运行时自身（HTTP 配置引导 {@code SetupHttpServer} 经 {@code
 * LlmConfigWriter} 以 {@link AgentPermissionSet#system()} 落盘）。若连 SYSTEM 都写不进 {@code
 * agent.roles}，则"HTTP 配置面 + Agent 身份 + 将来任何更弱的身份"都写不进——这是覆盖面最大的那一档。
 *
 * <p><b>对照面（同一条缝的另一半）</b>：{@code agents.<id>.*} 是可写前缀（宿主自己管理每 Agent 配置），本类用它做"同一条判定式在别处放行"的反向对照，
 * 证明上一条的拒绝来自<b>前缀</b>而不是"这个 store 根本写不进任何东西"。
 */
class ConfigAuthAgentRolesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 全通 schema（本类判的是授权，不是 schema；schema 阶段根本走不到）。 */
  private static JsonNode permissiveSchema() {
    try {
      return JSON.readTree("{\"type\":\"object\"}");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ────────────────────────── 判定层：ConfigAuth ──────────────────────────

  /**
   * 判定层：{@code agent.roles} 落在 deny-by-default 的"其余前缀"分支——<b>任何身份</b>都被拒（含 SYSTEM）。
   *
   * <p>判别性：给 {@code ConfigAuth} 加一条 {@code agent.*} 分支（哪怕只放 SYSTEM）⇒ 本用例转红。
   */
  @Test
  void configAuthDeniesAgentRolesForEveryIdentityIncludingSystem() {
    for (AccessToken token : AccessToken.values()) {
      Optional<String> denial = ConfigAuth.writeDenialReason(token, "main", "agent.roles");
      assertThat(denial).as("身份 %s 也不得写 agent.roles", token).asString().contains("键前缀不在可写范围");
    }
    assertThat(ConfigAuth.writeDenialReason(null, null, "agent.roles"))
        .asString()
        .contains("无身份令牌");
  }

  /** 反向对照（同一判定式）：{@code agents.<id>.*} 在归属匹配时是<b>可以</b>写的——上一条的拒绝来自前缀，不是"这条判定式恒拒"。 */
  @Test
  void theWritableAgentPrefixStillWorksAsTheControlCase() {
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "agents.main.roles"))
        .isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "main", "agents.main.roles"))
        .isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "bob", "agents.main.roles"))
        .as("归属不匹配仍拒——对照面自己也是 fail-closed 的")
        .isPresent();
  }

  // ────────────────────────── 写入口：FileConfigStore ──────────────────────────

  /**
   * ★ 主用例：经 {@code FileConfigStore.put} 以 SYSTEM 身份写 {@code agent.roles} ⇒ {@code E_PREFIX_DENIED}，
   * 且<b>文件一个字节都没动</b>（拒绝发生在任何磁盘操作之前）。
   *
   * <p>判别性：把 {@code putInternal} 里的前缀授权检查挪到落盘之后、或删掉 ⇒ 本用例转红（异常码变了 / 盘上多出文件）。
   */
  @Test
  void putOfAgentRolesIsDeniedAndLeavesNoTraceOnDisk(@TempDir Path configRoot) throws IOException {
    FileConfigStore store = new FileConfigStore(configRoot);
    Path configFile = configRoot.resolve("config.json");

    ConfigException failure =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.put(
                    "agent",
                    "roles",
                    JSON.readTree("[\"gm\"]"),
                    AgentPermissionSet.system(),
                    null,
                    permissiveSchema()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(failure.getMessage()).contains("键前缀不在可写范围");
    assertThat(Files.exists(configFile)).as("拒绝必须发生在触盘之前：连 config.json 都不该被建出来").isFalse();
    assertThat(store.get("agent", "roles")).as("读回来仍是缺省（⊥）").isEmpty();
  }

  /** 已有配置文件时同样不动它：拒绝前后逐字节相同（不许"写了再回滚"）。 */
  @Test
  void putOfAgentRolesLeavesAnExistingConfigFileByteIdentical(@TempDir Path configRoot)
      throws IOException {
    Files.createDirectories(configRoot);
    Path configFile = configRoot.resolve("config.json");
    String before = "{\n  \"runtime\": {\"logLevel\": \"INFO\"}\n}\n";
    Files.writeString(configFile, before, StandardCharsets.UTF_8);
    byte[] bytesBefore = Files.readAllBytes(configFile);
    FileConfigStore store = new FileConfigStore(configRoot);

    assertThatThrownBy(
            () ->
                store.put(
                    "agent",
                    "roles",
                    JSON.readTree("[\"gm\"]"),
                    AgentPermissionSet.system(),
                    null,
                    permissiveSchema()))
        .isInstanceOf(ConfigException.class);

    assertThat(Files.readAllBytes(configFile)).isEqualTo(bytesBefore);
    assertThat(Files.readString(configFile, StandardCharsets.UTF_8)).isEqualTo(before);
  }

  /**
   * ★ 判定层与写入口<b>成对</b>再钉一遍（三种 token × 真 {@code put}）：每一档都 `E_PREFIX_DENIED`、磁盘零痕迹。
   *
   * <p>判别性：只在 {@code ConfigAuth} 层拒、而 {@code FileConfigStore} 里"先落盘再判"（或忘了调判定）⇒
   * 本用例转红；`E_PREFIX_DENIED` 是唯一合法错误码（schema 错、IO 错都会让它红——那些都意味着"拒绝发生在触盘之后"）。
   */
  @Test
  void everyTokenIsDeniedAtTheWriteEntryPointWithoutTouchingDisk(@TempDir Path configRoot)
      throws IOException {
    Path configFile = configRoot.resolve("config.json");
    for (AccessToken token : AccessToken.values()) {
      FileConfigStore store = new FileConfigStore(configRoot);
      String owner = token == AccessToken.SYSTEM ? null : "main";
      ConfigException failure =
          catchThrowableOfType(
              ConfigException.class,
              () ->
                  store.put(
                      "agent",
                      "roles",
                      JSON.readTree("[\"gm\"]"),
                      AgentPermissionSet.builder(token).allowAll().build(),
                      owner,
                      permissiveSchema()));
      assertThat(failure).as("token=%s 必须被拒", token).isNotNull();
      assertThat(failure.code()).as("token=%s", token).isEqualTo(ConfigException.E_PREFIX_DENIED);
    }
    assertThat(Files.exists(configFile)).as("三次尝试都不许触盘").isFalse();
  }

  /** 删除面与写入面同一判定（"删掉它就不算越权"不是一条合法推理）：{@code remove} 同样 {@code E_PREFIX_DENIED}。 */
  @Test
  void removeOfAgentRolesIsDeniedByTheSamePredicate(@TempDir Path configRoot) throws IOException {
    Files.createDirectories(configRoot);
    Path configFile = configRoot.resolve("config.json");
    Files.writeString(configFile, "{\"agent\":{\"roles\":[\"gm\"]}}", StandardCharsets.UTF_8);
    byte[] bytesBefore = Files.readAllBytes(configFile);
    FileConfigStore store = new FileConfigStore(configRoot);

    ConfigException failure =
        catchThrowableOfType(
            ConfigException.class,
            () ->
                store.remove(
                    "agent", "roles", AgentPermissionSet.system(), null, permissiveSchema()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(ConfigException.E_PREFIX_DENIED);
    assertThat(Files.readAllBytes(configFile)).as("越权删除不许触碰文件").isEqualTo(bytesBefore);
  }

  /**
   * 对照面（写入口）：{@code agents.main.roles} <b>可以</b>写成功——证明上一条的拒绝来自前缀判定，而不是"这个 store 拒绝一切写入"。
   *
   * <p>★ 这个键<b>不是</b>角色维的读入口（{@code AgentRoles} 只读 {@code agent.roles}）；它在本类只作授权对照， "写进去也没用"由
   * {@code BrainMosire} 的 {@code AgentRolesTest#writableAgentsPrefixIsNotARoleSideDoor} 钉住。
   */
  @Test
  void writableAgentsPrefixStillWritesSuccessfullyAsTheControlCase(@TempDir Path configRoot)
      throws IOException {
    FileConfigStore store = new FileConfigStore(configRoot);

    store.put(
        "agents.main",
        "roles",
        JSON.readTree("[\"gm\"]"),
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        "main",
        permissiveSchema());

    assertThat(store.get("agents.main", "roles")).isPresent();
    assertThat(store.get("agent", "roles")).as("写的是 agents.*，读 agent.roles 仍是缺省").isEmpty();
  }
}
