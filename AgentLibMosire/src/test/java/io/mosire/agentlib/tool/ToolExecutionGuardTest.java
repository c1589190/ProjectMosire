package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ToolExecutionGuardTest {

  private final ToolExecutionGuard guard = new ToolExecutionGuard();

  @Test
  void executesWhenPermitted() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    ToolResult result = guard.execute(registry, "echo", context);
    assertThat(result.success()).isTrue();
  }

  @Test
  void deniesWhenNotInWhitelist() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allow("other").build());
    ToolResult result = guard.execute(registry, "echo", context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolExecutionGuard.DENIED);
  }

  @Test
  void unknownToolGivesNotFound() {
    ToolRegistry registry = new ToolRegistry();
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    ToolResult result = guard.execute(registry, "nope", context);
    assertThat(result.code()).isEqualTo("TOOL_NOT_FOUND");
  }

  @Test
  void declaresDestructiveRequiresPermission() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new AgentTool() {
          @Override
          public String name() {
            return "rm";
          }

          @Override
          public ToolSpec spec() {
            return ToolSpec.level(AccessToken.GUEST, false, true);
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return ToolResult.ok("deleted");
          }
        });
    ToolContext denied =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    assertThat(guard.execute(registry, "rm", denied).code()).isEqualTo(ToolExecutionGuard.DENIED);
    ToolContext allowed =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .build());
    assertThat(guard.execute(registry, "rm", allowed).success()).isTrue();
  }

  // ---- A-2：不声明角色的工具（= 今天全部既有工具）经真入口判定逐字不变 ----

  /**
   * A-2 回归钉（真入口）：{@code roles()} 未覆写 ⇒ 声明空集 ⇒ 第 8 维不表态。对任意调用者，{@link
   * ToolExecutionGuard#denial(AgentTool, ToolContext)} 的结论（含拒因文案）与三参 {@link
   * PermissionChecker#denialReason} 逐字相同——即“本维引入前的判定”。
   *
   * <p>覆盖调用者侧的三种取值：⊥（builder 缺省）、显式 {@code none()}、非空角色——⊥ 不得把不表态的工具打死（那正是本维 引入时最容易犯的错）。
   */
  @Test
  void noRoleDeclarationKeepsRealEntryVerdictsByteIdentical() {
    List<AgentTool> undeclared =
        List.of(
            echoTool(),
            guestLevelTool(),
            new AgentTool() {
              @Override
              public String name() {
                return "rm";
              }

              @Override
              public ToolSpec spec() {
                return ToolSpec.level(AccessToken.GUEST, false, true);
              }

              @Override
              public ToolResult execute(ToolContext context) {
                return ToolResult.ok("deleted");
              }
            });
    List<AgentPermissionSet> callers =
        List.of(
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .build()
                .withRoles(RoleSet.none()),
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .build()
                .withRoles(RoleSet.of("GM")),
            AgentPermissionSet.builder(AccessToken.GUEST).allowAll().build(),
            AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build(),
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("echo").build(),
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().readOnly(true).build(),
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .build());
    for (AgentTool tool : undeclared) {
      assertThat(tool.roles()).as("不覆写 roles() ⇒ 缺省空集（声明侧 = 不表态）").isEmpty();
      for (AgentPermissionSet caller : callers) {
        ToolContext context = ToolContext.of(caller.grantedToken(), caller);
        Optional<ToolResult> viaGuard = guard.denial(tool, context);
        Optional<String> viaThreeArg =
            PermissionChecker.denialReason(caller, tool.name(), tool.spec());
        assertThat(viaGuard.map(ToolResult::message))
            .as("工具 %s × 调用者 roles=%s：真入口与三参路逐字一致", tool.name(), caller.roles().summary())
            .isEqualTo(viaThreeArg);
      }
    }
    // ⊥ 调用者 + 不表态工具 + 默认三要素 ⇒ 放行（本维不参与判定）
    AgentPermissionSet bottom = callers.get(0);
    assertThat(bottom.roles()).isEqualTo(RoleSet.none());
    assertThat(guard.denial(echoTool(), ToolContext.of(AccessToken.DEFAULT, bottom))).isEmpty();
    assertThat(guard.denial(guestLevelTool(), ToolContext.of(AccessToken.DEFAULT, bottom)))
        .isEmpty();
  }

  // ---- A-3：角色门工具（真入口） ----

  /**
   * A-3 负向（★ 必须经真入口）：stub 声明 {@code roles()={GM}}。⊥ 与 {DM} ⇒ {@code PERMISSION_DENIED}；{GM} ⇒
   * 放行且工具体真的执行。被拒的调用绝不进工具体。
   */
  @Test
  void roleDeclaredToolIsDeniedForBottomAndNonMatchingCallersThroughTheRealEntry() {
    AtomicBoolean executed = new AtomicBoolean();
    AgentTool gmOnly = gmOnlyTool(executed);
    assertThat(gmOnly.roles()).as("声明侧非空 ⇒ 参与判定").containsExactly("GM");
    ToolRegistry registry = new ToolRegistry();
    registry.register(gmOnly);
    AgentTool resolved = registry.find("gm_tool").orElseThrow();

    AgentPermissionSet bottom = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    Optional<ToolResult> deniedBottom =
        guard.denial(resolved, ToolContext.of(AccessToken.DEFAULT, bottom));
    assertThat(deniedBottom).as("调用者 ⊥ ⇒ 拒").isPresent();
    assertThat(deniedBottom.orElseThrow().code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(deniedBottom.orElseThrow().message()).contains("角色不相交");
    assertThat(
            guard
                .execute(registry, "gm_tool", ToolContext.of(AccessToken.DEFAULT, bottom))
                .success())
        .isFalse();
    assertThat(executed).as("被拒的调用不得进工具体").isFalse();

    Optional<ToolResult> deniedDm =
        guard.denial(resolved, ToolContext.of(AccessToken.DEFAULT, unrestrictedWithRoles("DM")));
    assertThat(deniedDm).as("调用者 {DM} 与 {GM} 不相交 ⇒ 拒").isPresent();
    assertThat(deniedDm.orElseThrow().code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(executed).isFalse();

    assertThat(
            guard.denial(
                resolved, ToolContext.of(AccessToken.DEFAULT, unrestrictedWithRoles("GM"))))
        .as("调用者 {GM} ⇒ 放行")
        .isEmpty();
    assertThat(
            guard
                .execute(
                    registry,
                    "gm_tool",
                    ToolContext.of(AccessToken.DEFAULT, unrestrictedWithRoles("GM")))
                .success())
        .isTrue();
    assertThat(executed).as("放行的调用确实执行了工具体").isTrue();

    assertThat(
            guard.denial(
                resolved, ToolContext.of(AccessToken.DEFAULT, unrestrictedWithRoles("gm"))))
        .as("角色名大小写敏感：{gm} 不等于 {GM} ⇒ 拒")
        .isPresent();
  }

  /** 顺序（真入口）：角色排在白名单之后、敏感之前。 */
  @Test
  void realEntryOrdersRoleCheckAfterWhitelistAndBeforeSensitive() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(gmOnlyTool(new AtomicBoolean()));
    AgentTool resolved = registry.find("gm_tool").orElseThrow();
    AgentTool sensitiveGm = sensitiveGmTool();

    AgentPermissionSet gmOutsideWhitelist =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("other")
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(
            guard
                .denial(resolved, ToolContext.of(AccessToken.DEFAULT, gmOutsideWhitelist))
                .orElseThrow()
                .message())
        .as("白名单先于角色")
        .contains("白名单");

    AgentPermissionSet gmWithoutSensitiveAllowance =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(
            guard
                .denial(
                    sensitiveGm, ToolContext.of(AccessToken.DEFAULT, gmWithoutSensitiveAllowance))
                .orElseThrow()
                .message())
        .as("角色相交 ⇒ 让路给敏感维")
        .contains("敏感");

    AgentPermissionSet dmWithSensitiveAllowance =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .sensitiveAllowed(true)
            .build()
            .withRoles(RoleSet.of("DM"));
    assertThat(
            guard
                .denial(sensitiveGm, ToolContext.of(AccessToken.DEFAULT, dmWithSensitiveAllowance))
                .orElseThrow()
                .message())
        .as("角色不相交 ⇒ 先于敏感拒（敏感已放行也拦得住）")
        .contains("角色不相交");
  }

  private static AgentPermissionSet unrestrictedWithRoles(String... roles) {
    AgentPermissionSet set = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
    return roles.length == 0 ? set : set.withRoles(RoleSet.of(roles));
  }

  private static AgentTool gmOnlyTool(AtomicBoolean executed) {
    return new AgentTool() {
      @Override
      public String name() {
        return "gm_tool";
      }

      @Override
      public Set<String> roles() {
        return Set.of("GM");
      }

      @Override
      public ToolResult execute(ToolContext context) {
        executed.set(true);
        return ToolResult.ok("gm only");
      }
    };
  }

  private static AgentTool sensitiveGmTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "gm_sensitive";
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.GUEST, true, false);
      }

      @Override
      public Set<String> roles() {
        return Set.of("GM");
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("sensitive");
      }
    };
  }

  private static AgentTool guestLevelTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "guest_level";
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.GUEST);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("guest level");
      }
    };
  }

  private static AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo: " + context.arguments().getOrDefault("text", ""));
      }
    };
  }
}
