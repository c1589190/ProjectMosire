package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.decision.DecisionPackages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DecisionWindows} 的离线闭环：{@code decision.*} → 两个等待窗（用户 2026-10-17 指令「这玩意他妈不是可配置项吗？」）。
 *
 * <p><b>判别性约定</b>（与 {@code SubagentLimitsLoaderTest} 同取法）：
 *
 * <ul>
 *   <li><b>整项缺失 / 显式 null ⇒ 缺省</b>（30 分钟 / 30 秒，行为逐字不变）：没写过这一段的部署照常起来；
 *   <li><b>值真的来自配置</b>：改配置 → 两个数随之改变（抓"返回常量"的实现——那种实现让配置形同虚设，且不会有任何用例变红）；
 *   <li><b>存在但非法 ⇒ 响亮失败</b>（类型错 / 0 / 负数）且点名键与坏值：静默回退缺省会得到一个"看起来配了、其实没生效"的部署
 *       ——而这两个窗决定的正是"一次敏感调用要等多久才被拒绝"（本键的存在理由就是这个）。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不起进程、不读真配置目录。
 */
class DecisionWindowsTest {

  @TempDir Path tempDir;

  @Test
  void missingSectionFallsBackToBothDefaults() throws IOException {
    writeConfig("{}");
    DecisionWindows windows = DecisionWindows.load(store());
    assertThat(windows).isEqualTo(DecisionWindows.defaults());
    assertThat(windows.deadlineMillis())
        .as("★ 缺省 = 裁定 10 的原值（30 分钟），不是别的数")
        .isEqualTo(DecisionPackages.DEFAULT_DEADLINE_MILLIS);
    assertThat(windows.commitWaitMillis())
        .as("★ 缺省 = 网关上那个独立的短窗（30 秒），**不继承** 30 分钟")
        .isEqualTo(CrossProcessDecisionGateway.DEFAULT_COMMIT_WAIT_MILLIS);
  }

  @Test
  void explicitNullsAreTreatedAsMissing() throws IOException {
    writeConfig("{\"decision\":{\"package\":{\"deadlineMs\":null},\"commit\":{\"waitMs\":null}}}");
    assertThat(DecisionWindows.load(store())).isEqualTo(DecisionWindows.defaults());
  }

  /** 判别性：两个数都真的来自配置（否则"配置项存在"这件事在实现里根本没接线）。 */
  @Test
  void bothWindowsComeFromTheConfigKeys() throws IOException {
    writeConfig("{\"decision\":{\"package\":{\"deadlineMs\":250},\"commit\":{\"waitMs\":75}}}");
    DecisionWindows windows = DecisionWindows.load(store());
    assertThat(windows.deadlineMillis()).isEqualTo(250L);
    assertThat(windows.commitWaitMillis()).isEqualTo(75L);

    // 逐项独立：只写一个键时另一个取缺省（不是"有一个键就整段覆盖"）
    writeConfig("{\"decision\":{\"commit\":{\"waitMs\":75}}}");
    DecisionWindows partial = DecisionWindows.load(store());
    assertThat(partial.deadlineMillis()).isEqualTo(DecisionPackages.DEFAULT_DEADLINE_MILLIS);
    assertThat(partial.commitWaitMillis()).isEqualTo(75L);

    // 毫秒级是真的毫秒级（用例注入的就是这种量级）：一个数就够，不需要"秒"这种单位换算
    writeConfig("{\"decision\":{\"package\":{\"deadlineMs\":1}}}");
    assertThat(DecisionWindows.load(store()).deadlineMillis()).isEqualTo(1L);
  }

  /** 非法值一律**响亮失败**（不是静默回退）：类型错（字符串/小数）+ 取值错（0/负数）。 */
  @Test
  void nonIntegralOrNonPositiveValuesFailLoudly() throws IOException {
    for (String bad :
        new String[] {
          "{\"decision\":{\"package\":{\"deadlineMs\":\"600000\"}}}",
          "{\"decision\":{\"package\":{\"deadlineMs\":1.5}}}",
          "{\"decision\":{\"package\":{\"deadlineMs\":0}}}",
          "{\"decision\":{\"package\":{\"deadlineMs\":-1}}}",
          "{\"decision\":{\"commit\":{\"waitMs\":\"400\"}}}",
          "{\"decision\":{\"commit\":{\"waitMs\":0}}}",
          "{\"decision\":{\"commit\":{\"waitMs\":-5}}}",
        }) {
      writeConfig(bad);
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> DecisionWindows.load(store()));
      assertThat(failure).as("坏值必须响亮失败: %s", bad).isNotNull();
      assertThat(failure.code()).isEqualTo(DecisionWindows.E_DECISION_WINDOWS);
      assertThat(failure.getMessage()).as("消息点名键（人要知道改哪一行）").contains("decision.");
    }
  }

  /** 记录级守卫：两个窗在记录构造期就必须为正（配置层之外的第二道，与 {@code PendingDecisionQueue} 同口径）。 */
  @Test
  void theRecordItselfRejectsNonPositiveWindows() {
    assertThat(
            catchThrowableOfType(
                IllegalArgumentException.class, () -> new DecisionWindows(0L, 30_000L)))
        .isNotNull();
    assertThat(
            catchThrowableOfType(
                IllegalArgumentException.class, () -> new DecisionWindows(1_000L, -1L)))
        .isNotNull();
  }

  private void writeConfig(String json) throws IOException {
    Files.writeString(tempDir.resolve("config.json"), json, StandardCharsets.UTF_8);
  }

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }
}
