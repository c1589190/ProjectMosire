package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LlmRouteLoader} 的闭环测试（三期 S1-A1 R2）：{@code llm.*} → {@link ModelRoute}。
 *
 * <p><b>判别性约定</b>：
 *
 * <ul>
 *   <li>缺 {@code baseUrl} 与缺 {@code model} 的失败必须<b>可分辨</b>（消息点名各自的键——抓"同一条通用文案"的实现）；
 *   <li>{@code credentialsRef} 缺失<b>不报错</b>（抓"把可选当必填"的实现），但存在却不是文本时<b>响亮</b>（抓"把配错当匿名"的实现）；
 *   <li>三个字段必须真的来自配置（改写配置 → 结果随之改变，抓硬编码实现）；
 *   <li>失败消息不回流任何配置值（含凭据样串的 URL 型 baseUrl 也不例外）。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不触任何网络。
 */
class LlmRouteLoaderTest {

  /** 含凭据的 URL（凭据安全断言用的大小写敏感标记串）。 */
  private static final String CREDENTIAL_URL = "https://user:sk-live-SECRET123@vault.local/v1";

  @TempDir Path tempDir;

  // ---------- 正向：三字段来自配置 ----------

  /** R2 判别性：字段确实来自配置（不是硬编码/默认值）；{@code name} 取稳定的固定值。 */
  @Test
  void loadsRouteFieldsFromConfigAndFollowsConfigChanges() throws IOException {
    writeConfig(config(CREDENTIAL_URL, "model-one", "keys.one"));
    ModelRoute first = LlmRouteLoader.load(store());
    assertThat(first.baseUrl()).isEqualTo(CREDENTIAL_URL);
    assertThat(first.model()).isEqualTo("model-one");
    assertThat(first.credentialsRef()).isEqualTo("keys.one");
    assertThat(first.name()).as("配置里没有路由名这一项：固定 'default'").isEqualTo("default");

    writeConfig(config("https://api.two.example.test/v1", "model-two", "keys.two"));
    ModelRoute second = LlmRouteLoader.load(store());
    assertThat(List.of(second.baseUrl(), second.model(), second.credentialsRef()))
        .as("改写配置必须改变结果（抓硬编码）")
        .containsExactly("https://api.two.example.test/v1", "model-two", "keys.two");
  }

  // ---------- 必填缺失：响亮且可分辨 ----------

  /** R2 判别性：缺 {@code baseUrl} 与缺 {@code model} 的文案必须互相可分辨，且共用同一非敏感码。 */
  @Test
  void missingBaseUrlAndMissingModelAreDistinguishable() throws IOException {
    writeConfig("{\"llm\":{\"model\":\"model-one\"}}");
    ConfigException noBaseUrl =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    writeConfig("{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\"}}");
    ConfigException noModel =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    assertThat(noBaseUrl).isNotNull();
    assertThat(noModel).isNotNull();
    assertThat(noBaseUrl.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(noModel.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);

    assertThat(noBaseUrl.getMessage()).contains("llm.baseUrl").doesNotContain("llm.model");
    assertThat(noModel.getMessage()).contains("llm.model").doesNotContain("llm.baseUrl");
  }

  /** R2：值存在但不可用（空白 / 非文本）以及整段缺失，一律响亮——不许当成"用了默认值"。 */
  @Test
  void unusableRequiredValuesAreLoud() throws IOException {
    List<String> unusable =
        List.of(
            "{}",
            "{\"llm\":{}}",
            "{\"llm\":{\"baseUrl\":\"   \",\"model\":\"model-one\"}}",
            "{\"llm\":{\"baseUrl\":12,\"model\":\"model-one\"}}",
            "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":null}}");

    for (String json : unusable) {
      writeConfig(json);
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));
      assertThat(failure).as("config=%s", json).isNotNull();
      assertThat(failure.code())
          .as("config=%s", json)
          .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    }
  }

  // ---------- 可选：credentialsRef ----------

  /** R2 判别性：缺失（或显式 null）= 匿名，<b>不</b>报错（抓"把可选当必填"的实现）。 */
  @Test
  void missingCredentialsRefIsAnonymousNotAnError() throws IOException {
    writeConfig("{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\"}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();

    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":null}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();

    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":\"  \"}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();
  }

  /**
   * R2 判别性：{@code credentialsRef} <b>存在但不是文本</b>时响亮（抓"把配错当匿名"的实现）。
   *
   * <p>与"缺失 = 匿名"刻意分开：缺失是用户明说了"不要密钥"，非文本是用户以为配了密钥——后者静默变匿名后， 故障会以"鉴权失败/匿名被拒"的形态在很远的地方爆出来。
   */
  @Test
  void nonTextualCredentialsRefIsLoudNotAnonymous() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":{\"env\":\"MY_API_KEY\"}}}");
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("MY_API_KEY"));
  }

  // ---------- 凭据安全 ----------

  /** 失败消息不回流任何配置值：配置里放含凭据的 URL（作为 baseUrl/credentialsRef）也不能出现在异常链里。 */
  @Test
  void messagesNeverEchoConfiguredValues() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\""
            + CREDENTIAL_URL
            + "\",\"credentialsRef\":\""
            + CREDENTIAL_URL
            + "\"}}");
    ConfigException noModel =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    writeConfig(
        "{\"llm\":{\"baseUrl\":\""
            + CREDENTIAL_URL
            + "\",\"model\":\"model-one\","
            + "\"credentialsRef\":[1,2]}}");
    ConfigException badRef =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    for (ConfigException failure : List.of(noModel, badRef)) {
      assertThat(failure).isNotNull();
      assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-live-SECRET123"));
    }
  }

  // ---------- 多 provider：按名选路由（2026-09-14） ----------

  /** 两条路由 + 扁平形态共存的完整夹具。 */
  private static final String MULTI_ROUTE_CONFIG =
      "{\"llm\":{\"baseUrl\":\"https://flat.example.test/v1\",\"model\":\"flat-model\","
          + "\"credentialsRef\":\"keys.flat\",\"route\":\"glm\",\"routes\":{"
          + "\"glm\":{\"baseUrl\":\"https://glm.example.test/v1\",\"model\":\"glm-5.3-flash\","
          + "\"credentialsRef\":\"keys.glm\"},"
          + "\"deepseek\":{\"baseUrl\":\"https://ds.example.test/v1\",\"model\":\"deepseek-flash\"}}}}";

  /** 判别性：路由表按名各给各的字段（抓"读表但永远取第一条"的实现）；扁平形态同时在场时仍是 {@code default}（兼容层）。 */
  @Test
  void namedRoutesAreSelectedByNameWhileFlatFormStaysDefault() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);

    ModelRoute glm = LlmRouteLoader.load(store(), "glm");
    ModelRoute deepseek = LlmRouteLoader.load(store(), "deepseek");
    ModelRoute dflt = LlmRouteLoader.load(store());

    assertThat(List.of(glm.name(), glm.baseUrl(), glm.model(), glm.credentialsRef()))
        .containsExactly("glm", "https://glm.example.test/v1", "glm-5.3-flash", "keys.glm");
    assertThat(
            List.of(
                deepseek.name(), deepseek.baseUrl(), deepseek.model(), deepseek.credentialsRef()))
        .as("第二条路由缺 credentialsRef = 匿名（与扁平形态同口径）")
        .containsExactly("deepseek", "https://ds.example.test/v1", "deepseek-flash", "");
    assertThat(List.of(dflt.name(), dflt.baseUrl(), dflt.model(), dflt.credentialsRef()))
        .as("扁平形态 = 名为 default 的路由：老配置一字不改照跑")
        .containsExactly("default", "https://flat.example.test/v1", "flat-model", "keys.flat");
  }

  /**
   * 判别性：名字拼错 ⇒ {@code E_LLM_ROUTE_UNKNOWN}，<b>绝不</b>静默改用另一条路由——<b>即使扁平形态明明可用</b>
   * （这正是"错误的名字静默回落"最容易被放过的形态）。码与"配置缺失"刻意分开：拼错与没配是两种修法。
   */
  @Test
  void unknownRouteNameIsLoudDistinguishableAndNeverFallsBack() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);

    ConfigException typo =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glmm"));

    assertThat(typo).isNotNull();
    assertThat(typo.code())
        .isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN)
        .isNotEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(typo.getMessage())
        .as("带请求名与可用名，一眼看出是拼错还是没配")
        .contains("glmm")
        .contains("glm")
        .contains("deepseek")
        .contains("default");
  }

  /**
   * 名字是往配置树里<b>寻址的段</b>：带点/空白/怪字符的名字不许被当成路径（{@code a.b} 会指到别的节点上）。
   *
   * <p><b>判别力的边界（如实登记）</b>：本用例的样本名在夹具表里<b>都不存在</b>，所以"拦名字"与"把名字当路径去查" 两种实现都落到"未知名 ⇒
   * UNKNOWN"——它钉的是<b>码</b>与"不静默回落"，不钉名字校验本身。 名字校验的判别性归下一条用例（表里真放字面键）。
   */
  @Test
  void illegalRouteNamesAreRejectedNotTreatedAsPaths() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);

    for (String bad : List.of("a.b", "-a", "a b", "a/b", "glm.")) {
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), bad));
      assertThat(failure).as("name=%s", bad).isNotNull();
      assertThat(failure.code()).as("name=%s", bad).isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN);
    }
  }

  /**
   * 判别性：表里<b>真有一个</b>字面含点/空白的键时，同名请求仍必须 UNKNOWN。
   *
   * <p>为什么必须有这一条：上一条用例的名字全部落空，删掉 {@code SAFE_NAME} 校验后它<b>仍然全绿</b>（判据零判别力，已实测）。
   * 这里让字面键<b>命中</b>，两种走样实现各有一个可观测后果：①按字面命中 ⇒ 直接返回一条"名叫 {@code a.b} 的路由" （不抛）；②把名字当路径段下钻 ⇒ 报 {@code
   * E_LLM_CONFIG_MISSING} 并点名 {@code llm.routes.a.b.baseUrl}
   * 这种<b>误导性键名</b>（用户照着改怎么也改不好）。正确实现两条都拦在名字校验里。
   */
  @Test
  void illegalNamesAreStillUnknownEvenWhenSuchALiteralKeyExists() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"a.b\":{\"baseUrl\":\"https://dotted.example.test/v1\","
            + "\"model\":\"dotted-model\"},\"a b\":{\"baseUrl\":\"https://spaced.example.test/v1\","
            + "\"model\":\"spaced-model\"}}}}");

    for (String bad : List.of("a.b", "a b")) {
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), bad));
      assertThat(failure).as("name=%s：字面键在场也不许被当成一条可用路由", bad).isNotNull();
      assertThat(failure.code())
          .as("name=%s：拼错/非法名字 ⇒ 路由不存在，不是配置缺失", bad)
          .isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN)
          .isNotEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
      assertThat(failure.getMessage())
          .as("name=%s：不许把点/空白当路径段下钻——也就不得报出这样的键名", bad)
          .doesNotContain("llm.routes." + bad + ".");
    }
  }

  /** 只有路由表、没有扁平形态时，{@code default} 不在表里也必须响亮——答案只能是"没有"，不是"随便挑一条"。 */
  @Test
  void routesOnlyConfigWithoutDefaultEntryIsLoud() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"glm\":{\"baseUrl\":\"https://glm.example.test/v1\","
            + "\"model\":\"glm-5.3-flash\"}}}}");

    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN);
    assertThat(failure.getMessage()).contains("default").contains("glm");
  }

  /** 没有路由表却点了非默认名：不许静默忽略这个名字（用户以为配了第二条路由，实际在跑第一条）。 */
  @Test
  void nonDefaultNameWithoutRoutesTableIsUnknownNotIgnored() throws IOException {
    writeConfig(config("https://api.example.test/v1", "model-one", "keys.one"));

    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glm"));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_ROUTE_UNKNOWN);
    assertThat(failure.getMessage()).contains("glm").contains("default");
  }

  /** 路由表 / 条目的形态配错 ⇒ 响亮且点名具体键（含路由名）——不是"当成没配"、更不是"当成匿名"。 */
  @Test
  void malformedRoutesTableIsLoudWithRouteScopedKeyNames() throws IOException {
    writeConfig("{\"llm\":{\"routes\":\"glm\"}}");
    ConfigException tableNotObject =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glm"));
    assertThat(tableNotObject).isNotNull();
    assertThat(tableNotObject.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(tableNotObject.getMessage()).contains("llm.routes");

    writeConfig("{\"llm\":{\"routes\":{\"glm\":5}}}");
    ConfigException entryNotObject =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glm"));
    assertThat(entryNotObject).isNotNull();
    assertThat(entryNotObject.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(entryNotObject.getMessage()).contains("llm.routes.glm");

    writeConfig("{\"llm\":{\"routes\":{\"glm\":{\"baseUrl\":\"https://glm.example.test/v1\"}}}}");
    ConfigException noModel =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glm"));
    assertThat(noModel).isNotNull();
    assertThat(noModel.getMessage())
        .as("两类缺配置必须可分辨，且键名带路由名")
        .contains("llm.routes.glm.model")
        .doesNotContain("llm.routes.glm.baseUrl");
  }

  /** 路由表里的凭据引用同样不许回流值（含凭据样串的 URL 型 baseUrl）。 */
  @Test
  void namedRouteMessagesNeverEchoConfiguredValues() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"glm\":{\"baseUrl\":\""
            + CREDENTIAL_URL
            + "\",\"model\":\"glm-5.3-flash\",\"credentialsRef\":"
            + "\""
            + CREDENTIAL_URL
            + "\"}}}}");

    ModelRoute route = LlmRouteLoader.load(store(), "glm");
    assertThat(route.baseUrl()).isEqualTo(CREDENTIAL_URL);

    writeConfig("{\"llm\":{\"routes\":{\"glm\":{\"baseUrl\":\"" + CREDENTIAL_URL + "\"}}}}");
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store(), "glm"));
    assertThat(failure).isNotNull();
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-live-SECRET123"));
  }

  // ---------- 多 provider：按 agent 选名字（routeName） ----------

  /**
   * 三级回退：{@code agents/<id>.llm.route} → {@code llm.route} → {@code
   * "default"}（配置层不做跨前缀回退，回退是我们自己写的）。
   */
  @Test
  void routeNameFallsBackFromAgentOverrideToGlobalToDefault() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);
    assertThat(LlmRouteLoader.routeName(store(), "alice")).isEqualTo("glm");

    writeAgentConfig("bob", "{\"llm\":{\"route\":\"deepseek\"}}");
    assertThat(LlmRouteLoader.routeName(store(), "bob")).isEqualTo("deepseek");
    assertThat(LlmRouteLoader.routeName(store(), "alice")).as("覆盖只对该 agent 生效").isEqualTo("glm");

    writeConfig(
        "{\"llm\":{\"routes\":{\"glm\":{\"baseUrl\":\"https://glm.example.test/v1\","
            + "\"model\":\"glm-5.3-flash\"}}}}");
    assertThat(LlmRouteLoader.routeName(store(), "alice"))
        .as("全局也没写 ⇒ default")
        .isEqualTo("default");
  }

  /** 端到端合成：子体按 <b>templateId</b> 选名 → 加载器取到那条路由（两个函数接起来的唯一语义）。 */
  @Test
  void templateScopedSelectionComposesWithLoad() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);
    writeAgentConfig("orch-probe", "{\"llm\":{\"route\":\"deepseek\"}}");

    ModelRoute route =
        LlmRouteLoader.load(store(), LlmRouteLoader.routeName(store(), "orch-probe"));

    assertThat(List.of(route.name(), route.model())).containsExactly("deepseek", "deepseek-flash");
  }

  /** 覆盖值配错（非文本/空白）⇒ 响亮，不许当成"没覆盖"——后者会静默走另一条路由，正是 D24 要防的形态。 */
  @Test
  void malformedOverrideIsLoudNotIgnored() throws IOException {
    writeConfig(MULTI_ROUTE_CONFIG);

    writeAgentConfig("bob", "{\"llm\":{\"route\":5}}");
    ConfigException nonTextual =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.routeName(store(), "bob"));
    assertThat(nonTextual).isNotNull();
    assertThat(nonTextual.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(nonTextual.getMessage()).contains("agents.bob.llm.route");

    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"m\",\"route\":\"  \"}}");
    ConfigException blankGlobal =
        catchThrowableOfType(
            ConfigException.class, () -> LlmRouteLoader.routeName(store(), "alice"));
    assertThat(blankGlobal).isNotNull();
    assertThat(blankGlobal.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  /** 可用名清单：升序、含路由表里的名字与（在场时的）扁平 {@code default}——错误消息据此自解释。 */
  @Test
  void availableNamesListsSortedRouteNamesIncludingFlatDefault() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://flat.example.test/v1\",\"model\":\"flat-model\","
            + "\"routes\":{\"zeta\":{\"baseUrl\":\"https://z.example.test/v1\",\"model\":\"z\"},"
            + "\"alpha\":{\"baseUrl\":\"https://a.example.test/v1\",\"model\":\"a\"}}}}");
    assertThat(LlmRouteLoader.availableNames(store())).containsExactly("alpha", "default", "zeta");

    writeConfig(
        "{\"llm\":{\"routes\":{\"zeta\":{\"baseUrl\":\"https://z.example.test/v1\",\"model\":\"z\"}}}}");
    assertThat(LlmRouteLoader.availableNames(store()))
        .as("没有扁平形态就没有 default 这个名字")
        .containsExactly("zeta");
  }

  // ---------- 工具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeAgentConfig(String agentId, String json) throws IOException {
    Path dir = Files.createDirectories(tempDir.resolve("agents"));
    Files.write(dir.resolve(agentId + ".json"), json.getBytes(StandardCharsets.UTF_8));
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  private static String config(String baseUrl, String model, String credentialsRef) {
    return "{\"llm\":{\"baseUrl\":\""
        + baseUrl
        + "\",\"model\":\""
        + model
        + "\",\"credentialsRef\":\""
        + credentialsRef
        + "\"}}";
  }

  /** 异常链全部 message（含逐层 cause）——凭据安全断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
    }
    return messages;
  }
}
