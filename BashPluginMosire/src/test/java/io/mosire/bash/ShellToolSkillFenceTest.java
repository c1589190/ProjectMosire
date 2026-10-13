package io.mosire.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.SandboxMask;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.skills.SkillNamespaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能树围栏在 bash 工具上的接线契约（本能力新增；沙箱本身的能力见 {@link BashSandboxTest}）：
 *
 * <ul>
 *   <li><b>没接线遮蔽面的部署行为逐字不变</b>：{@code ToolContext.config} 里没有 {@link SandboxMask#CONFIG_KEY}
 *       时，一切照旧（含“不限可达面 ⇒ 不沙箱”）；
 *   <li><b>该遮蔽却没有沙箱 ⇒ 响亮拒绝且不发命令</b>：调用者看不见全部技能 + 不限可达面 + {@code sandbox=off} 的组合下， {@code
 *       SKILL_SCOPE_UNFENCED}，标记文件不得出现（把这条改成静默放行，本类第一条与第三条断言转红）；
 *   <li><b>可见面覆盖整棵树 ⇒ 不需要遮蔽</b>：只读暴露是沙箱<i>内</i>的事，不影响“要不要沙箱”的判定；
 *   <li><b>受限可达面 ⇒ 照常走沙箱</b>：沙箱不可用时仍是既有的 {@code SANDBOX_UNAVAILABLE}（不是新码）。
 * </ul>
 *
 * <p>L3 里“遮蔽/只读 bind 真的生效”需要 root/CAP_SYS_ADMIN，本机不可用（真实验证见实现说明的“未完成项”）；此处钉的是 <b>判定与失败方向</b>：判定表在
 * {@code SandboxMaskTest}，落点在 {@link BashSandbox#wrap(Path, List, String, List, List)}。
 */
class ShellToolSkillFenceTest {

  private static final String MARKER = "executed.marker";

  @TempDir Path tempDir;

  @Test
  void withoutMaskConfigBehaviorIsUnchanged() throws IOException {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    ShellTool tool = tool(work, BashSandbox.system());
    ToolContext caller =
        caller(AgentPermissionSet.builder(AccessToken.SYSTEM).allowAll().build(), Map.of());

    ToolResult result = tool.execute(withArgs(caller, "touch " + MARKER));

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(Files.exists(work.resolve(MARKER))).isTrue();
  }

  @Test
  void maskRequiredButUnfencedIsRejectedBeforeRunningAnything() throws IOException {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    Path skills = Files.createDirectories(tempDir.resolve("skills"));
    ShellTool tool = tool(work, BashSandbox.system());
    // 调用者没有任何技能表态（= 技能工具面里它一个技能都看不见），可达面不限 ⇒ 命令若跑起来就能读技能树
    ToolContext caller =
        caller(
            AgentPermissionSet.builder(AccessToken.SYSTEM).allowAll().build(),
            Map.of(SandboxMask.CONFIG_KEY, List.of(skills.toString())));

    ToolResult result = tool.execute(withArgs(caller, "touch " + MARKER));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ShellTool.SKILL_SCOPE_UNFENCED);
    assertThat(Files.exists(work.resolve(MARKER))).as("命令根本不该执行").isFalse();
  }

  @Test
  void visibleCoverageNeedsNoMaskSoUnfencedRunIsAllowed() throws IOException {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    Path skills = Files.createDirectories(tempDir.resolve("skills"));
    ShellTool tool = tool(work, BashSandbox.system());
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.SYSTEM)
            .allowAll()
            .resourceScopes(
                ResourceScopeMap.of(SkillNamespaces.SKILLS, ResourceScope.ofDir(skills)))
            .build();
    ToolContext caller =
        caller(permissions, Map.of(SandboxMask.CONFIG_KEY, List.of(skills.toString())));

    ToolResult result = tool.execute(withArgs(caller, "touch " + MARKER));

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(Files.exists(work.resolve(MARKER))).isTrue();
  }

  /** 受限可达面本来就要沙箱：遮蔽面在场不改变失败方向（仍是既有的 SANDBOX_UNAVAILABLE，不是新码）。 */
  @Test
  void restrictedCallerStillFailsLoudlyWhenSandboxUnavailable() throws IOException {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    Path skills = Files.createDirectories(tempDir.resolve("skills"));
    ShellTool tool = tool(work, BashSandbox.unavailable("构造用：探针恒否"));
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.SYSTEM)
            .allowAll()
            .resourceScopes(
                ResourceScopeMap.of(
                    Map.of(
                        ResourceScopeMap.FS, ResourceScope.ofDir(work),
                        SkillNamespaces.SKILLS, ResourceScope.none())))
            .build();
    ToolContext caller =
        caller(permissions, Map.of(SandboxMask.CONFIG_KEY, List.of(skills.toString())));

    ToolResult result = tool.execute(withArgs(caller, "touch " + MARKER));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ShellTool.SANDBOX_UNAVAILABLE);
    assertThat(Files.exists(work.resolve(MARKER))).isFalse();
  }

  // ---------- 夹具 ----------

  private static ShellTool tool(Path base, BashSandbox sandbox) {
    return new ShellTool(
        base,
        ShellTool.OutputSink.none(),
        ShellOutputTruncator.Mode.NORMAL,
        1 << 20,
        4000,
        sandbox);
  }

  private static ToolContext caller(AgentPermissionSet permissions, Map<String, Object> config) {
    return new ToolContext(AccessToken.SYSTEM, permissions, config, Map.of());
  }

  private static ToolContext withArgs(ToolContext caller, String command) {
    return new ToolContext(
        caller.caller(), caller.permissions(), caller.config(), Map.of("command", command));
  }
}
