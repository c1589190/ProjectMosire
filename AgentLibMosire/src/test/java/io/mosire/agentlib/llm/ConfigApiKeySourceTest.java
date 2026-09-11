package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigApiKeySource} 的闭环测试（三期 S1-A1 R1/R1-0）：形态判定、匿名语义、身份门禁、凭据安全。
 *
 * <p><b>判别性约定</b>（每条断言都针对一种"结构相似的错误实现"设计）：
 *
 * <ul>
 *   <li>形态不合法的引用必须<b>抛</b>（抓"偷偷读环境变量"与"一律静默返回空"两种实现——前者会成功返回 env 值，后者会把配错伪装成匿名）；
 *   <li>{@code keys.<name>} 命中必须<b>真的返回配置里的值</b>（抓"一律拒绝"的空转实现：空转骗得过形态断言，骗不过正向控制）；
 *   <li>身份不足必须<b>构造即抛且零次读配置</b>（用读计数替身，抓"检查了 token 但顺手先读了配置"或"根本没检查"）；
 *   <li>所有失败消息（含逐层 cause）不得含凭据样串或含凭据的 URL（照 {@code ConfigStoreTest} 的凭据安全形态）。
 * </ul>
 *
 * <p>测试全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，密钥值是假串，不触任何网络。
 */
class ConfigApiKeySourceTest {

  private static final String CREDENTIAL = "sk-live-SECRET123";

  private static final String CREDENTIAL_URL = "https://user:sk-live-SECRET123@vault.local/v1";

  @TempDir Path tempDir;

  // ---------- 语义：空白 = 有意匿名 ----------

  /** R1-1：空白引用是<b>正常路径</b>（本地部署匿名调用），不是错误；且不需要任何配置存在。 */
  @Test
  void blankRefMeansAnonymousNotAnError() {
    assertThat(source("", AccessToken.SYSTEM).apiKey()).isEmpty();
    assertThat(source("   ", AccessToken.SYSTEM).apiKey()).isEmpty();
    assertThat(source(null, AccessToken.SYSTEM).apiKey()).isEmpty();
  }

  // ---------- 语义：keys.<name> 命中 / 未命中 ----------

  /** R1-② 正向控制：命中时返回的必须是配置里的真值（抓"一律拒绝/永远空"的空转实现）。 */
  @Test
  void keysRefReturnsTheConfiguredValue() throws IOException {
    writeConfig("{\"keys\":{\"deepseek\":\"sk-test-placeholder\"}}");
    assertThat(source("keys.deepseek", AccessToken.SYSTEM).apiKey())
        .contains("sk-test-placeholder");
  }

  /** R1-③/SPI 契约：每次调用都重读配置（构造时取一次并永久缓存会让轮换/过期感知失效）。 */
  @Test
  void keyIsReReadOnEveryCall() throws IOException {
    writeConfig("{\"keys\":{\"deepseek\":\"sk-test-first\"}}");
    ConfigApiKeySource source = source("keys.deepseek", AccessToken.SYSTEM);
    assertThat(source.apiKey()).contains("sk-test-first");

    writeConfig("{\"keys\":{\"deepseek\":\"sk-test-second\"}}");
    assertThat(source.apiKey()).as("每次 apiKey() 现读，不得构造时取一次并永久缓存").contains("sk-test-second");
  }

  /** R1-④ 判别性：缺键与形态不支持是<b>两个不同</b>的非敏感码（"取密钥失败"只有一种文案会让事件库里的失败不可分辨）。 */
  @Test
  void missingKeyAndUnsupportedFormHaveDistinctCodes() throws IOException {
    writeConfig("{\"keys\":{\"present\":\"sk-test-placeholder\"}}");
    String missing = codeOf(() -> source("keys.absent", AccessToken.SYSTEM).apiKey());
    String unsupported = codeOf(() -> source("MY_API_KEY", AccessToken.SYSTEM).apiKey());

    assertThat(missing).isEqualTo(ConfigApiKeySource.E_KEY_MISSING);
    assertThat(unsupported).isEqualTo(ConfigApiKeySource.E_REF_FORM_UNSUPPORTED);
    assertThat(missing).isNotEqualTo(unsupported);
  }

  /** R1 "绝不静默返回空"：配了但取不到值的五种形态都必须响亮，不许退化成"看起来像匿名部署"。 */
  @Test
  void unusableConfiguredValueIsLoudNeverSilentlyAnonymous() throws IOException {
    writeConfig(
        "{\"keys\":{\"empty\":\"\",\"blank\":\"   \",\"nothing\":null,\"obj\":{\"k\":\"v\"},\"num\":7}}");

    for (String ref : List.of("keys.empty", "keys.blank", "keys.nothing", "keys.obj", "keys.")) {
      ConfigException failure =
          catchThrowableOfType(
              ConfigException.class, () -> source(ref, AccessToken.SYSTEM).apiKey());
      assertThat(failure).as("ref=%s", ref).isNotNull();
      assertThat(failure.code()).as("ref=%s", ref).isEqualTo(ConfigApiKeySource.E_KEY_MISSING);
    }
    // 非文本但可取文本形态的值（数字）不算"取不到"：照常返回（抓"非文本一律拒绝"的过度收窄）
    assertThat(source("keys.num", AccessToken.SYSTEM).apiKey()).contains("7");
  }

  // ---------- 语义：形态收窄（R1-3） ----------

  /**
   * R1-① 环境变量判别性：用进程内<b>必然存在</b>的环境变量名作引用 → 必须被拒。
   *
   * <p>专抓"偷偷从进程环境读同名变量"的实现：那种实现会成功返回 {@code PATH} 的值而不是抛。
   *
   * <p>此处<b>不</b>调进程环境 API 去比对该值：全仓 Java 代码 0 处读环境变量取密钥是硬性约定（测试也不开这个口子，故此断言只写"必抛"不写 env
   * 值比对）；"必抛"本身已足以证伪该实现。额外断言连引用名都不回显（引用名本身可能就是含凭据的 URL）。
   */
  @Test
  void envVarNameRefIsRejectedAndNeverEchoed() {
    ConfigException failure =
        catchThrowableOfType(
            ConfigException.class, () -> source("PATH", AccessToken.SYSTEM).apiKey());

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(ConfigApiKeySource.E_REF_FORM_UNSUPPORTED);
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("PATH"));
  }

  // ---------- 语义：身份门禁（R1-0） ----------

  /** R1-⑤：GUEST/DEFAULT 构造即抛（fail-fast，不是等到 apiKey() 才失败）；SYSTEM 正常放行。 */
  @Test
  void guestAndDefaultTokensAreRejectedAtConstruction() {
    for (AccessToken token : List.of(AccessToken.GUEST, AccessToken.DEFAULT)) {
      ConfigException failure =
          catchThrowableOfType(
              ConfigException.class, () -> new ConfigApiKeySource(store(), "keys.deepseek", token));
      assertThat(failure).as("token=%s", token).isNotNull();
      assertThat(failure.code())
          .as("token=%s", token)
          .isEqualTo(ConfigApiKeySource.E_TOKEN_TOO_LOW);
    }
    assertThatCode(() -> new ConfigApiKeySource(store(), "keys.deepseek", AccessToken.SYSTEM))
        .doesNotThrowAnyException();
  }

  /**
   * R1-⑤ 判别性：配置里<b>确实有</b>密钥时，低身份也拿不到它——且用读计数替身证明"一次配置都不许读"。
   *
   * <p>专抓两种实现：①"根本没检查 token"（构造成功）；②"检查了 token，但先顺手读了配置"。两者都会让读计数 &gt; 0 或构造不抛。
   */
  @Test
  void lowTokenRejectsBeforeAnyConfigRead() throws IOException {
    writeConfig("{\"keys\":{\"deepseek\":\"" + CREDENTIAL + "\"}}");
    ReadCountingStore counting = new ReadCountingStore(tempDir);

    ConfigException failure =
        catchThrowableOfType(
            ConfigException.class,
            () -> new ConfigApiKeySource(counting, "keys.deepseek", AccessToken.GUEST));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(ConfigApiKeySource.E_TOKEN_TOO_LOW);
    assertThat(counting.reads()).as("身份不足时一次配置都不许读").isZero();
  }

  // ---------- 凭据安全（R1 硬约束） ----------

  /**
   * R1-③：配置里放凭据样串与含凭据的 URL，触发各类失败 → 异常链<b>逐层</b> message 均不含该串。
   *
   * <p>判别性：正向控制放在最后——密钥确实在配置里且能被取到，否则"不含"可以靠"根本没读到配置"蒙混过关。
   */
  @Test
  void credentialShapedStringsNeverEnterExceptionChain() throws IOException {
    writeConfig(
        "{\"keys\":{\"deepseek\":\"" + CREDENTIAL + "\",\"url\":\"" + CREDENTIAL_URL + "\"}}");

    List<ConfigException> failures = new ArrayList<>();
    // ① 引用指向不存在的键（配置里确实有密钥，只是引用写错了）
    failures.add(
        catchThrowableOfType(
            ConfigException.class, () -> source("keys.absent", AccessToken.SYSTEM).apiKey()));
    // ② 引用形态不受支持，且引用本身就是含凭据的 URL（把引用原样拼进消息就泄漏）
    failures.add(
        catchThrowableOfType(
            ConfigException.class, () -> source(CREDENTIAL_URL, AccessToken.SYSTEM).apiKey()));
    // ③ keys. 形态但键名是含凭据的 URL
    failures.add(
        catchThrowableOfType(
            ConfigException.class,
            () -> source("keys." + CREDENTIAL_URL, AccessToken.SYSTEM).apiKey()));
    // ④ 身份不足
    failures.add(
        catchThrowableOfType(
            ConfigException.class,
            () -> new ConfigApiKeySource(store(), "keys.deepseek", AccessToken.DEFAULT)));

    for (ConfigException failure : failures) {
      assertThat(failure).isNotNull();
      assertThat(allMessages(failure)).noneMatch(message -> message.contains(CREDENTIAL));
      assertThat(allMessages(failure)).noneMatch(message -> message.contains(CREDENTIAL_URL));
    }
    // 正向控制：密钥确实在（上文的"不含"不是因为什么都没读到）
    assertThat(source("keys.deepseek", AccessToken.SYSTEM).apiKey()).contains(CREDENTIAL);
    assertThat(source("keys.url", AccessToken.SYSTEM).apiKey()).contains(CREDENTIAL_URL);
  }

  // ---------- 工具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private ConfigApiKeySource source(String credentialsRef, AccessToken token) {
    return new ConfigApiKeySource(store(), credentialsRef, token);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  private static String codeOf(ThrowingCallable callable) {
    ConfigException failure = catchThrowableOfType(ConfigException.class, callable);
    assertThat(failure).isNotNull();
    return failure.code();
  }

  /** 异常链全部 message（含逐层 cause）——凭据安全断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
    }
    return messages;
  }

  /** 读计数替身：专抓"token 检查通过/被跳过时顺手读了配置"的实现。 */
  private static final class ReadCountingStore extends FileConfigStore {

    private final AtomicInteger reads = new AtomicInteger();

    ReadCountingStore(Path configRoot) {
      super(configRoot);
    }

    @Override
    public Optional<JsonNode> get(String prefix, String key) {
      reads.incrementAndGet();
      return super.get(prefix, key);
    }

    int reads() {
      return reads.get();
    }
  }
}
