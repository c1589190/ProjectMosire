package io.mosire.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S5-D 的三层围栏里<b>能被确定性测到的那两层</b>（L1 参数面硬拒 / L2 起点固定）+ “沙箱不可用 ⇒ 响亮失败”的构造用例。
 *
 * <p><b>判别性重点</b>（每条都对应"改坏哪一行会转红"）：
 *
 * <ul>
 *   <li><b>越界就是拒</b>：把 L1 判定改成恒真（或去掉）⇒ {@link
 *       #absolutePathOutsideTheScopeIsDeniedBeforeAnyExecution} 等三条转红；
 *   <li><b>拒在执行之前</b>：把 {@code cwd} 判定挪到进程启动之后 ⇒ 副作用断言（标记文件不得出现）转红；
 *   <li><b>L1 先于 L3</b>：把 cwd 判定排到沙箱装配之后 ⇒ 越界用例会拿到 {@code SANDBOX_UNAVAILABLE} 而不是 {@code
 *       DIR_NOT_ALLOWED}（本类的沙箱刻意恒不可用，顺序判别点因此变得可观测）；
 *   <li><b>沙箱不可用不退化</b>：把"沙箱不可用"改成静默跑原命令 ⇒ {@link
 *       #unavailableSandboxFailsLoudlyWithoutRunningAnything} 转红（标记文件会出现、码会变成成功）；
 *   <li><b>相对路径的基准是可达面首根</b>（L2）：把基准换回 {@code baseWorkingDirectory} ⇒ {@link
 *       #relativeCwdResolvesUnderTheFirstRootOfTheScope} 转红。
 * </ul>
 *
 * <p><b>L3 的真实围栏效果</b>不在这里测（它需要 root/CAP_SYS_ADMIN，且测的是"宿主上真跑一次"）：见 {@link BashSandboxTest}——那里用
 * {@code assumeTrue} 只在沙箱真可用时断言"隐藏面读不到 / 逃逸口关闭"。
 */
class ShellToolFenceTest {

  private static final String MARKER = "executed.marker";

  /** 沙箱刻意"不可用"：L1/L2 与顺序、失败语义都能在<b>不起容器</b>的前提下确定性测到。 */
  private static ShellTool toolWithUnavailableSandbox(Path base) {
    return new ShellTool(
        base,
        ShellTool.OutputSink.none(),
        ShellOutputTruncator.Mode.NORMAL,
        1 << 20,
        4000,
        BashSandbox.unavailable("构造用：探针恒否"));
  }

  /** 调用者：白名单通配（SYSTEM）+ fs 可达面 = 给定根——被挡住只可能来自围栏这一维。 */
  private static ToolContext callerLimitedTo(Path root, Map<String, Object> args) {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.SYSTEM)
            .allowAll()
            .sensitiveAllowed(true)
            .destructiveAllowed(true)
            .resourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.ofDir(root)))
            .build();
    return new ToolContext(AccessToken.SYSTEM, permissions, Map.of(), args);
  }

  /** 不限可达面的调用者（主 Agent 的缺省形态）。 */
  private static ToolContext callerUnrestricted(Map<String, Object> args) {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.SYSTEM)
            .allowAll()
            .sensitiveAllowed(true)
            .destructiveAllowed(true)
            .build();
    return new ToolContext(AccessToken.SYSTEM, permissions, Map.of(), args);
  }

  // ── L1：参数面硬拒（纯函数层，不起进程） ───────────────────────────────────────────────

  @Test
  void absolutePathOutsideTheScopeIsDenied(@TempDir Path allowed, @TempDir Path outside)
      throws Exception {
    ResourceScope fence = ResourceScope.ofDir(allowed);
    assertThatThrownBy(() -> ShellTool.resolveWorkingDirectory(allowed, outside.toString(), fence))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("越出调用者的可达面")
        .hasMessageContaining(allowed.toRealPath().toString());
    // 面内照常（否则"全拒"无法与"判定点坏了"区分）
    assertThat(ShellTool.resolveWorkingDirectory(allowed, allowed.toString(), fence))
        .isEqualTo(allowed.toAbsolutePath().normalize());
  }

  @Test
  void relativeSegmentsCannotEscapeTheScope(@TempDir Path allowed) throws Exception {
    ResourceScope fence = ResourceScope.ofDir(allowed);
    // 词法越界：/allowed/../elsewhere 归一后落到面外（段边界判定的意义就在这一跳）
    assertThatThrownBy(
            () ->
                ShellTool.resolveWorkingDirectory(
                    allowed, allowed.resolve("..").resolve("elsewhere").toString(), fence))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("越出调用者的可达面");
    // 留在面内的相对段照常
    Path inside = Files.createDirectories(allowed.resolve("sub"));
    assertThat(ShellTool.resolveWorkingDirectory(allowed, inside.toString(), fence))
        .isEqualTo(inside);
    assertThatThrownBy(
            () ->
                ShellTool.resolveWorkingDirectory(
                    allowed, allowed.resolve("../..").toString(), fence))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void symlinkPointingOutsideTheScopeIsDenied(@TempDir Path allowed, @TempDir Path outside)
      throws Exception {
    Path link = allowed.resolve("link-out");
    Files.createSymbolicLink(link, outside);
    // 词法上"在面内"，realpath 一跳把它看穿（L1 的加严；完备的链接防线归 L3）
    assertThatThrownBy(
            () ->
                ShellTool.resolveWorkingDirectory(
                    allowed, link.toString(), ResourceScope.ofDir(allowed)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("符号链接");
  }

  @Test
  void emptyScopeHasNoWorkingDirectoryAtAll(@TempDir Path allowed) {
    assertThatThrownBy(() -> ShellTool.resolveWorkingDirectory(allowed, null, ResourceScope.none()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("可达面为空");
  }

  @Test
  void unrestrictedCallerKeepsTheOldResolutionRules(@TempDir Path base, @TempDir Path anywhere) {
    // fence == null（不限 scope + sandbox=off）：逐字保留 S4-C 的行为——绝对路径原样、相对路径按基准目录
    assertThat(ShellTool.resolveWorkingDirectory(base, null, null)).isEqualTo(base);
    assertThat(ShellTool.resolveWorkingDirectory(base, anywhere.toString(), null))
        .isEqualTo(anywhere);
    assertThat(ShellTool.resolveWorkingDirectory(base, "sub", null)).isEqualTo(base.resolve("sub"));
  }

  // ── L2 + 执行面：拒必须发生在执行之前；沙箱不可用必须响亮 ─────────────────────────────────

  @Test
  void absolutePathOutsideTheScopeIsDeniedBeforeAnyExecution(
      @TempDir Path allowed, @TempDir Path outside) {
    ShellTool tool = toolWithUnavailableSandbox(allowed);
    Path marker = outside.resolve(MARKER);
    ToolResult result =
        tool.execute(
            callerLimitedTo(
                allowed, Map.of("command", "touch " + marker, "cwd", outside.toString())));

    assertThat(result.code()).isEqualTo(ShellTool.DIR_NOT_ALLOWED);
    assertThat(result.message()).contains("越出调用者的可达面");
    // ★ 判别性：命令根本没跑（真跑了就会在面外留下标记文件）
    assertThat(marker).doesNotExist();
    // ★ 顺序判别点：L1 先于 L3——沙箱恒不可用，但越界这一条拿到的是 DIR_NOT_ALLOWED 而不是 SANDBOX_UNAVAILABLE
    assertThat(result.code()).isNotEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
  }

  @Test
  void unavailableSandboxFailsLoudlyWithoutRunningAnything(@TempDir Path allowed) {
    ShellTool tool = toolWithUnavailableSandbox(allowed);
    Path marker = allowed.resolve(MARKER); // 面内：L1 会放行，能不能跑全看 L3
    ToolResult result =
        tool.execute(callerLimitedTo(allowed, Map.of("command", "touch " + marker)));

    assertThat(result.code()).isEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
    assertThat(result.message()).contains("需要 OS 沙箱而它不可用").contains("构造用：探针恒否");
    // ★ 判别性：不静默退化——命令一次都没执行（退化实现会把标记文件留下，且返回 null code = 成功）
    assertThat(marker).doesNotExist();
  }

  @Test
  void relativeCwdResolvesUnderTheFirstRootOfTheScope(@TempDir Path allowed, @TempDir Path base) {
    // base 与可达面刻意不同：相对路径若按 base 解析，就会落到面外 ⇒ 必须转红
    ShellTool tool = toolWithUnavailableSandbox(base);
    ToolResult result =
        tool.execute(callerLimitedTo(allowed, Map.of("command", "pwd", "cwd", "sub")));

    // 沙箱不可用 ⇒ 到不了执行；但 L1/L2 的解析结果已经写进拒因（"解析="是该跳的观测面）
    assertThat(result.code()).isEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
    // 相对路径解析到面内 ⇒ L1 放行（若按 base 解析，这里会是 DIR_NOT_ALLOWED）
    assertThat(result.code()).isNotEqualTo(ShellTool.DIR_NOT_ALLOWED);
  }

  @Test
  void unrestrictedCallerWithoutRequireIsNotFencedAtAll(@TempDir Path base) throws Exception {
    // 主 Agent 缺省形态（不限 scope + sandbox=off）：三层围栏一层都不参与，bash 照常跑
    ShellTool tool = new ShellTool(base);
    ToolResult result = tool.execute(callerUnrestricted(Map.of("command", "echo third-layer-off")));

    assumeTrue(result.success(), "环境里没有可用的 bash: " + result.message());
    assertThat(result.message()).contains("third-layer-off");
  }

  @Test
  void requireSandboxTakesTheBaseWorkingDirectoryAsTheFence(@TempDir Path base) {
    // sandbox=require 且调用者不限 scope：没有可达面可绑 ⇒ 围栏面取工具基准工作目录（本用例钉住这个语义）
    ShellTool tool =
        new ShellTool(
            base,
            ShellTool.OutputSink.none(),
            ShellOutputTruncator.Mode.NORMAL,
            1 << 20,
            4000,
            BashSandbox.unavailable("构造用：探针恒否"));
    Map<String, Object> config =
        new BashToolConfig(
                List.of(),
                List.of(),
                BashToolConfig.CommandLog.DIGEST,
                BashToolConfig.Sandbox.REQUIRE,
                null)
            .toToolConfig();
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.SYSTEM)
            .allowAll()
            .sensitiveAllowed(true)
            .destructiveAllowed(true)
            .build();
    ToolContext context =
        new ToolContext(AccessToken.SYSTEM, permissions, config, Map.of("command", "pwd"));

    ToolResult result = tool.execute(context);
    assertThat(result.code()).isEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
    // 基准目录之外 ⇒ DIR_NOT_ALLOWED（require 把它当成了可达面）
    ToolResult outside =
        tool.execute(
            new ToolContext(
                AccessToken.SYSTEM, permissions, config, Map.of("command", "pwd", "cwd", "/")));
    assertThat(outside.code()).isEqualTo(ShellTool.DIR_NOT_ALLOWED);
  }
}
