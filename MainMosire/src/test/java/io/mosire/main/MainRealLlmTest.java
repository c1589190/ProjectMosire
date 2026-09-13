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
   *
   * <p><b>正向判据（G2）</b>：下面原有的两条都是<b>否定式</b>（"没有 E_KEY_MISSING""没有密钥值"），一个从不取密钥的实现
   * （永远匿名、直接发请求）也能满足；故追加一条<b>正向</b>断言，要求失败文案属于连接阶段——把"取密钥确实成功、请求确实发出去了" 钉成必需条件，而不是"没观察到坏现象"。
   *
   * <p>（残余，如实登记）："永远匿名"的实现同样会走到连接阶段、同样满足本判据——真正闭合它要观察请求上的 {@code Authorization} 头，那需要环回桩服务器（D22
   * 不进默认门禁，见 S1-A2 简报 §二·五 G2 的裁决）。
   */
  @Test
  void realClientResolvesTheKeyFromTheConfigRootItWasGiven() throws IOException {
    writeConfig(configJson("keys.local", keyEntry("local", CREDENTIAL)));

    LlmException failure = chatFailure(Main.realLlm(tempDir));

    assertThat(failure.getMessage())
        .as("正向：失败必须发生在连接阶段（取密钥已成功，不是取密钥阶段失败）")
        .startsWith("LLM 调用失败（")
        .contains("ConnectException");
    assertThat(allMessages(failure))
        .as("密钥已从传入的配置根解析成功——失败只能发生在连接阶段")
        .noneMatch(message -> message.contains("E_KEY_MISSING"));
    assertThat(allMessages(failure))
        .as("夹具里的密钥值不得进入异常链")
        .noneMatch(message -> message.contains(CREDENTIAL));
  }

  // ---------- F3 多 provider 路由接线（2026-09-14） ----------

  /**
   * F3（选路接线）：{@code Main.realLlm} 必须走 {@code llm.route} 点名的那条路由。
   *
   * <p><b>为什么补这条</b>：这条接线此前<b>零自动化判据</b>——把选名整个忽略掉（退回 {@code load(store)} 单参形态）后， 本文件与子体那批用例 20/20
   * 全绿（评审 M5 实测）。
   *
   * <p>夹具把两条路由的<b>可观测后果</b>拉开（不是"断言看起来对"）：扁平那条的密钥引用 {@code keys.flat} <b>缺失</b>，被点名那条的 {@code
   * keys.second} <b>在场</b>。于是两种实现互斥——忽略选名的实现落到扁平路由上，{@code chat} 在<b>取密钥阶段</b>就失败（{@code
   * E_KEY_MISSING}）；正确接线则在<b>连接阶段</b>失败（127.0.0.1:2 拒绝，无 {@code E_KEY_MISSING}）。
   */
  @Test
  void realLlmFollowsTheSelectedRouteNotTheFlatForm() throws IOException {
    writeConfig(
        "{\"keys\":{\"second\":\""
            + CREDENTIAL
            + "\"},\"llm\":{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"flat-model\","
            + "\"credentialsRef\":\"keys.flat\",\"route\":\"second\",\"routes\":{\"second\":"
            + "{\"baseUrl\":\"http://127.0.0.1:2/v1\",\"model\":\"second-model\","
            + "\"credentialsRef\":\"keys.second\"}}}}");

    LlmClient client = Main.realLlm(tempDir);
    assertThat(((OpenAICompatibleLlmClient) client).route().name()).isEqualTo("second");
    assertThat(((OpenAICompatibleLlmClient) client).route().model()).isEqualTo("second-model");

    LlmException failure = chatFailure(client);
    assertThat(failure.getMessage())
        .as("正向：被点名那条的密钥在场 ⇒ 必须走到连接阶段（落到扁平路由的实现止步于取密钥）")
        .startsWith("LLM 调用失败（")
        .contains("ConnectException");
    assertThat(allMessages(failure))
        .as("不得报出扁平路由那条缺失的引用")
        .noneMatch(message -> message.contains("E_KEY_MISSING"))
        .noneMatch(message -> message.contains("keys.flat"));
  }

  /**
   * F3 反向：{@code llm.route} 点名了一条<b>不存在</b>的路由 ⇒ 装配期响亮，且<b>绝不</b>改用扁平那条（它明明可用）。
   *
   * <p>这正是"未知名静默回落"最容易被放过的形态：配置里有一条完好可用的扁平路由，拼错的路由名若被忽略，进程会照常起来。
   */
  @Test
  void realLlmRefusesToFallBackToFlatFormWhenTheNamedRouteIsUnknown() throws IOException {
    writeConfig(
        "{\"keys\":{\"deepseek\":\""
            + CREDENTIAL
            + "\"},\"llm\":{\"baseUrl\":\""
            + UNREACHABLE_BASE_URL
            + "\",\"model\":\"flat-model\",\"credentialsRef\":\"keys.deepseek\",\"route\":\"glmm\","
            + "\"routes\":{\"glm\":{\"baseUrl\":\"https://glm.example.test/v1\",\"model\":"
            + "\"glm-5.3-flash\",\"credentialsRef\":\"keys.deepseek\"}}}}");

    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> Main.realLlm(tempDir));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN);
    assertThat(failure.getMessage()).contains("glmm").contains("glm");
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
