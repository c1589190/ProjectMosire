package io.mosire.main;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三期 S1-A1（R6）的装配门禁：{@code Main run} 的 LLM 选择（{@link Main#selectLlm}）与真模型装配（{@link
 * Main#realLlm(Path)}）——非 demo 且无离线开关 ⇒ 真客户端；缺配置/缺密钥响亮失败；绝不退化回假 LLM。
 *
 * <p><b>全程离线</b>：所有 {@code baseUrl} 一律指向本机不可达端口 {@code http://127.0.0.1:1/v1}，任何越过"取密钥"的路径最多撞到
 * {@code ECONNREFUSED}，不会碰到网络；真连通性不是本测试的验收项（归 S1-B 探索测试，D22）。
 *
 * <p>判据分层：<b>单元层</b>钉住选择规则（{@code selectLlm} 的四条分支）；<b>进程层</b>钉住端到端不变量（无配置的 {@code Main run}
 * 子进程必须非零退出且不打假回复）——后者是"曾经静默退化"的回归门禁，不能被重构悄悄抹掉。
 */
class MainRealLlmTest {

  private static final String CREDENTIAL = "sk-test-placeholder";

  /** 本机不可达端口：即便某条路径真的发起连接，也只会得到 ECONNREFUSED，不出网。 */
  private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1/v1";

  @TempDir Path tempDir;

  // ---------- F1 单元层：选择规则（哪条开关都不给 ⇒ 真模型） ----------

  /** 不变量：非 demo 且既无 {@code --fake} 也无 {@code --fake-script} ⇒ 真客户端（不是假 LLM、不是 null）。 */
  @Test
  void selectLlmPicksRealClientWhenNoOfflineSwitchIsGiven() throws IOException {
    writeConfig(configJson("keys.deepseek", keyEntry("deepseek", CREDENTIAL)));

    LlmClient selected = Main.selectLlm(bootConfig(false), false, null);

    assertThat(selected).isNotNull();
    assertThat(selected).as("默认路径必须是真模型（D24：不静默退化）").isInstanceOf(OpenAICompatibleLlmClient.class);
    assertThat(selected).isNotInstanceOf(FakeLlmClient.class);
  }

  /** {@code --demo} 仍交回 {@code App.scriptedLlm} 的骨架回复（{@code null} = 不覆盖），且不读配置。 */
  @Test
  void selectLlmLeavesDemoToAppFakeReply() {
    assertThat(Main.selectLlm(bootConfig(true), false, null)).isNull();
  }

  /** 两个离线开关的语义零变化：{@code --fake} 出骨架回复；{@code --fake-script} 优先且出脚本回复。 */
  @Test
  void selectLlmKeepsOfflineSwitchesWorking() {
    LlmClient fake = Main.selectLlm(bootConfig(false), true, null);
    assertThat(fake).isInstanceOf(FakeLlmClient.class);
    assertThat(replyOf(fake)).isEqualTo(App.DEFAULT_LLM_REPLY);

    LlmClient scripted = Main.selectLlm(bootConfig(false), true, "text:脚本回复");
    assertThat(scripted).isNotNull();
    assertThat(replyOf(scripted)).as("--fake-script 优先于 --fake：出脚本回复而非骨架回复").isEqualTo("脚本回复");
  }

  // ---------- F1 进程层：端到端不变量（缺配置 ⇒ 非零退出，绝不假回复） ----------

  /**
   * 回归门禁：spawn 一个<b>不带</b> {@code --demo/--fake*} 的 {@code Main run} 子进程，{@code --data-dir} 指向没有
   * {@code config.json} 的空目录。
   *
   * <p>断言三件事：进程非零退出、stderr 出现 {@code E_LLM_CONFIG_MISSING}、stderr 里<b>没有</b>假 LLM 的骨架回复文案。这条用例正是
   * {@code AppMcpLinkTest} 曾经踩中的"生产路径静默退化"的守卫——它在进程边界上证明"缺配置时宁可起不来，也不假装能说话"。
   */
  @Test
  void nonDemoRunWithoutConfigDiesLoudlyInsteadOfFakeReplies() throws Exception {
    Path dataDir = tempDir.resolve("empty-data-dir");
    Files.createDirectories(dataDir);
    // stderr 走文件而不是管道：无死锁风险，且进程被兜底 kill 后仍拿得到已写出的内容（否则断言会被 IO 异常遮住）
    Path stderrFile = tempDir.resolve("run-stderr.txt");

    Process child =
        new ProcessBuilder(
                "java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.mosire.main.Main",
                "run",
                "--data-dir",
                dataDir.toString(),
                "--port",
                "0")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(stderrFile.toFile())
            .start();
    boolean finished = child.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);

    assertThat(finished).as("缺配置在 App.start 之前就抛：进程必须立刻失败退出，不是起来当网关").isTrue();
    assertThat(child.exitValue()).as("非 demo 无配置 ⇒ 非零退出").isNotZero();
    assertThat(stderr).contains(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(stderr).as("绝不静默退化成假 LLM 的骨架回复").doesNotContain(App.DEFAULT_LLM_REPLY);
  }

  // ---------- ⑥ 真客户端成型 ----------

  /** ⑥：有 {@code llm.*} 与密钥 → 不抛，且返回的是真客户端（不是 FakeLlmClient 之类的退化物）。 */
  @Test
  void realLlmBuildsRealClientFromConfig() throws IOException {
    writeConfig(configJson("keys.deepseek", keyEntry("deepseek", CREDENTIAL)));

    LlmClient client = Main.realLlm(tempDir);

    assertThat(client).isInstanceOf(OpenAICompatibleLlmClient.class);
    assertThat(((OpenAICompatibleLlmClient) client).route().credentialsRef())
        .isEqualTo("keys.deepseek");
  }

  // ---------- ⑦ 缺配置响亮 ----------

  /** ⑦：空数据目录（无 {@code config.json}）→ 装配期响亮抛（非 demo 的默认路径不会退回假 LLM）。 */
  @Test
  void realLlmFailsLoudlyWhenConfigIsMissing() {
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> Main.realLlm(tempDir));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(failure.getMessage()).contains(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  // ---------- ⑧ 缺密钥响亮（构造期 vs 取密钥期） ----------

  /**
   * ⑧：{@code llm.*} 齐全但被引用的 {@code keys.deepseek} 缺失 → 装配<b>不</b>抛，真客户端首次 {@code chat} 响亮抛（{@code
   * E_KEY_MISSING}，且异常文本里点名缺失的引用）。
   *
   * <p>为什么这样选（简报 ⑧ 给了二选一）：本断言语义<b>更准确</b>——{@link ConfigApiKeySource} 构造期不读配置，取密钥发生在 {@code
   * apiKey()}，这正是 SPI 契约"每次 {@code chat} 重新取一次（便于轮换/过期感知）"的落地方式；把密钥在装配期固化反而会破坏该契约。
   *
   * <p>夹具里放了一条<b>存在但未被引用</b>的 {@code keys.other}（凭据样串）：<b>有东西可咬</b>，"未被引用的密钥不得回流"才是可判别的断言（空夹具下这类
   * {@code sk-} 断言恒真、任何实现都过）。本用例零 I/O：密钥取不到发生在建连之前。
   */
  @Test
  void missingKeyIsLoudAtKeyFetchTimeNotAtAssemblyTime() throws IOException {
    writeConfig(configJson("keys.deepseek", keyEntry("other", CREDENTIAL)));

    // 装配不抛：密钥不在构造期固化（否则轮换/过期感知失效）
    assertThatCode(() -> Main.realLlm(tempDir)).doesNotThrowAnyException();

    LlmClient client = Main.realLlm(tempDir);
    LlmException failure = chatFailure(client);

    assertThat(failure.getMessage()).as("客户端对取密钥失败只给固定非敏感文案").contains("取密钥失败");
    assertThat(allMessages(failure)).anyMatch(message -> message.contains("E_KEY_MISSING"));
    assertThat(allMessages(failure))
        .as("夹具里那条未被引用的凭据样串不得随异常回流")
        .noneMatch(message -> message.contains(CREDENTIAL));
  }

  // ---------- F2 接线钉住：route 的 ref + realLlm 收到的配置根 ----------

  /**
   * F2（接线一）：取密钥必须用 {@code route.credentialsRef()} 指名的那个引用——配置里恰巧存在另一个可用密钥也不行。
   *
   * <p>夹具：{@code credentialsRef = "keys.other"}（缺失），但 {@code keys.deepseek} 存在且有值。把 ref 接错成常量
   * "deepseek" 之类的实现会取到密钥、进而走去连接（不再有 {@code E_KEY_MISSING}）→ 本用例变红。
   */
  @Test
  void realClientUsesTheRefFromRouteEvenWhenAnotherKeyExists() throws IOException {
    writeConfig(configJson("keys.other", keyEntry("deepseek", CREDENTIAL)));

    LlmException failure = chatFailure(Main.realLlm(tempDir));

    assertThat(allMessages(failure)).anyMatch(message -> message.contains("E_KEY_MISSING"));
    assertThat(allMessages(failure))
        .as("必须点名 route 里的引用")
        .anyMatch(message -> message.contains("keys.other"));
    assertThat(allMessages(failure))
        .as("不得把另一个碰巧存在的密钥名报出来")
        .noneMatch(message -> message.contains("keys.deepseek"));
  }

  /**
   * F2（接线二）：取密钥必须读 {@code realLlm} 收到的那份配置根——换根之后密钥就不再解析得到。
   *
   * <p>夹具：{@code keys.local} 有值。正确接线时取密钥成功、失败发生在<b>连接阶段</b>（127.0.0.1:1 拒绝），异常链里没有 {@code
   * E_KEY_MISSING}；密钥源指向别处（如进程默认数据目录）的实现会在这里退化回 {@code E_KEY_MISSING} → 变红。
   */
  @Test
  void realClientResolvesTheKeyFromTheConfigRootItWasGiven() throws IOException {
    writeConfig(configJson("keys.local", keyEntry("local", CREDENTIAL)));

    LlmException failure = chatFailure(Main.realLlm(tempDir));

    assertThat(allMessages(failure))
        .as("密钥已从传入的配置根解析成功——失败只能发生在连接阶段")
        .noneMatch(message -> message.contains("E_KEY_MISSING"));
    assertThat(allMessages(failure))
        .as("夹具里的密钥值不得进入异常链")
        .noneMatch(message -> message.contains(CREDENTIAL));
  }

  // ---------- 工具 ----------

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  /** 一条 {@code keys} 段条目。 */
  private static String keyEntry(String name, String value) {
    return "\"" + name + "\":\"" + value + "\"";
  }

  /**
   * 夹具配置：{@code llm.*} 一律指向本机不可达端口，{@code credentialsRef} = {@code refName}；{@code keys} 段由 {@code
   * keysJson} 精确给出（调用方决定"哪个引用存在"——"存在但未被引用"是 F4 要求的可咬夹具）。
   */
  private static String configJson(String refName, String keysJson) {
    return "{\"keys\":{"
        + keysJson
        + "},\"llm\":{\"baseUrl\":\""
        + UNREACHABLE_BASE_URL
        + "\",\"model\":\"model-one\",\"credentialsRef\":\""
        + refName
        + "\"}}";
  }

  private BootConfig bootConfig(boolean demo) {
    return new BootConfig(0, tempDir, demo, "", null, false, "127.0.0.1", 0);
  }

  /** 一次往返的失败：{@code chat} 抛 {@link LlmException}（本文件所有用例都不该走到成功路径）。 */
  private static LlmException chatFailure(LlmClient client) {
    LlmException failure =
        catchThrowableOfType(
            LlmException.class,
            () -> client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("ping")))));
    assertThat(failure).as("期望 chat 响亮失败（不返回值、不退化成假回复）").isNotNull();
    return failure;
  }

  /** 假客户端的回复文本（用于钉住 {@code --fake}/{@code --fake-script} 的语义）。 */
  private static String replyOf(LlmClient client) {
    LlmResponse response = client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("ping"))));
    return response.textPart().orElse("");
  }

  /** 异常链全部 message（含逐层 cause）——凭据安全与错误码可见性断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
    }
    return messages;
  }
}
