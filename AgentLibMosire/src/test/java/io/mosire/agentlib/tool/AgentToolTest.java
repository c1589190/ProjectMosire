package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import org.junit.jupiter.api.Test;

/**
 * 声明面契约（设计 {@code 设计-角色与统一决策包.md} §4.2）：{@link AgentTool#roles()} 缺省 = 空集 = <b>本维不表态</b> （=
 * 本维引入前行为），且必须非 null。
 *
 * <p>这是 A-2 的上游一半：判定点把“声明空集”翻译成“本维不参与判定”；这里钉住“既有工具不覆写 ⇒ 拿到空集”这个事实， 判定面的逐字不变在 {@code
 * ToolExecutionGuardTest} / {@code PermissionCheckerTest}。
 *
 * <p>★ 声明侧空集读作 ⊤、调用者侧空集读作 ⊥（fail-closed）——两侧读法相反是设计的有意选择，不在这里判（那是判定点的判据）。
 */
class AgentToolTest {

  @Test
  void rolesDefaultsToEmptyDeclaration() {
    assertThat(bareTool().roles()).as("缺省 = 空集 = 不表态，且非 null").isNotNull().isEmpty();
  }

  @Test
  void declaringOtherSpiDimensionsDoesNotChangeTheRolesDefault() {
    AgentTool declared =
        new AgentTool() {
          @Override
          public String name() {
            return "declared";
          }

          @Override
          public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, true);
          }

          @Override
          public ToolGate gate(ToolContext context) {
            return new ToolGate.Block("test", "测试用硬拒");
          }

          @Override
          public ResourceManifest resources() {
            return ResourceManifest.NONE;
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return ToolResult.ok("declared");
          }
        };
    assertThat(declared.roles())
        .as("声明了 spec/gate/resources ≠ 声明了角色；第 8 维仍是不表态")
        .isNotNull()
        .isEmpty();
  }

  private static AgentTool bareTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "bare";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("bare");
      }
    };
  }
}
