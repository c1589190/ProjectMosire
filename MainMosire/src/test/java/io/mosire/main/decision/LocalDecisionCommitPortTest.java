package io.mosire.main.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 本机提交口的执行期判据链（设计 §5ter「裁定 13 补」；P2a 发现 A/C 的落点）：身份面 → 硬拒 → <b>资源面</b> → 执行（注入资源判定者 +
 * 工具配置），以及"执行身份按终局裁决者现取"。
 *
 * <p><b>判别性</b>：把资源前置闸那一段删掉 ⇒ {@link #toolThatAssertsItsResourceFaceCannotRunWithoutADecision()} 与
 * {@link #resourceBlindCallerIsRefusedBeforeTheBodyRuns()} 当场红（工具本体会跑/会拿到 {@code COMMITTED}）； 把
 * {@code ToolContext} 的 {@code config} 换回 {@code Map.of()} ⇒ {@link
 * #toolConfigIsTheSameOneAsThePipelineGets()} 当场红；把执行身份写死成主 Agent ⇒ {@link
 * #executingIdentityIsTakenPerPackageNotHardCoded()} 当场红。
 */
class LocalDecisionCommitPortTest {

  private static final String NAMESPACE = "docs";
  private static final String CONFIG_KEY = "test.marker";

  /** 有权执行 docs 面且持有工具配置的"主 Agent 级"身份。 */
  private static LocalDecisionCommitPort.ExecutingIdentity docsIdentity() {
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.SYSTEM)
            .withResourceScopes(ResourceScopeMap.of(NAMESPACE, ResourceScope.unlimited()))
            .withRoles(RoleSet.none());
    return new LocalDecisionCommitPort.ExecutingIdentity(
        permissions.grantedToken(), permissions, AgentIdentity.main(CommandMode.FULL));
  }

  /** 声明了 docs 面（READ_ONLY）的工具；体一执行就计数，并把它看到的 config / 资源判定结果交出来。 */
  private static AgentTool docsTool(
      AtomicInteger runs,
      AtomicReference<Object> seenConfig,
      AtomicReference<Boolean> seenResourceOk) {
    return new AgentTool() {
      @Override
      public String name() {
        return "write_doc";
      }

      @Override
      public ResourceManifest resources() {
        return ResourceManifest.of(NAMESPACE, ResourcePolicy.READ_ONLY);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        seenConfig.set(context.config().get(CONFIG_KEY));
        try {
          context.resources().require(Operation.READ, ResourceId.of(NAMESPACE, "a.md"));
          seenResourceOk.set(true);
        } catch (RuntimeException denied) {
          // require 判否会抛（ResourceDeniedException）；提交口把它记成 NOT_COMMITTED。这里如实记下来再上抛
          seenResourceOk.set(false);
          throw denied;
        }
        return ToolResult.ok("ran");
      }
    };
  }

  private static LocalDecisionCommitPort port(
      AgentTool tool, LocalDecisionCommitPort.ExecutingIdentity who) {
    ToolRegistry registry = new ToolRegistry();
    registry.register("test", tool);
    return new LocalDecisionCommitPort(
        registry, new ToolExecutionGuard(), Map.of(CONFIG_KEY, "same-as-pipeline"), id -> who);
  }

  @Test
  void toolConfigIsTheSameOneAsThePipelineGets() {
    AtomicInteger runs = new AtomicInteger();
    AtomicReference<Object> seenConfig = new AtomicReference<>();
    CommitReceipt receipt =
        port(docsTool(runs, seenConfig, new AtomicReference<>()), docsIdentity())
            .commit("dp-1", List.of(new ResolvedCall(0, "write_doc", Map.of("path", "a.md"))));

    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
    assertThat(runs).as("体跑了一次").hasValue(1);
    assertThat(seenConfig.get())
        .as("工具拿到的是装配层注入的那一份 config（不是 Map.of()）")
        .isEqualTo("same-as-pipeline");
  }

  @Test
  void toolThatAssertsItsResourceFaceCannotRunWithoutADecision() {
    AtomicInteger runs = new AtomicInteger();
    AtomicReference<Boolean> resourceOk = new AtomicReference<>();
    CommitReceipt receipt =
        port(docsTool(runs, new AtomicReference<>(), resourceOk), docsIdentity())
            .commit("dp-1", List.of(new ResolvedCall(0, "write_doc", Map.of("path", "a.md"))));

    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
    assertThat(resourceOk.get()).as("工具内 require 拿到了真正的资源判定者（withResources 注入生效）").isTrue();
  }

  @Test
  void resourceBlindCallerIsRefusedBeforeTheBodyRuns() {
    AtomicInteger runs = new AtomicInteger();
    // 声明了 docs 面、但调用者显式表态"哪里都不许"（ResourceScope.none）⇒ 声明式前置闸判否（资源面这一段的落点）。
    // ★ 必须是显式 none：资源面的口径是"未表态 = 取工具缺省策略"，不表态的权限集在 READ_ONLY 声明面前照样够得着
    AgentPermissionSet blind =
        new AgentPermissionSet(
            AccessToken.SYSTEM,
            Set.of("*"),
            Set.of(),
            true,
            true,
            false,
            ResourceScopeMap.of(NAMESPACE, ResourceScope.none()));
    LocalDecisionCommitPort.ExecutingIdentity who =
        new LocalDecisionCommitPort.ExecutingIdentity(
            blind.grantedToken(), blind, AgentIdentity.main(CommandMode.FULL));

    CommitReceipt receipt =
        port(docsTool(runs, new AtomicReference<>(), new AtomicReference<>()), who)
            .commit("dp-1", List.of(new ResolvedCall(0, "write_doc", Map.of("path", "a.md"))));

    assertThat(runs).as("够不着资源面 ⇒ 体一次都不许跑").hasValue(0);
    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
    assertThat(receipt.itemResults()).hasSize(1);
    assertThat(receipt.itemResults().get(0).outcome())
        .isEqualTo(CommitReceipt.ItemOutcome.NOT_COMMITTED);
    assertThat(receipt.itemResults().get(0).reason())
        .as("逐条理由要说得出是资源面拒的")
        .contains("RESOURCE_DENIED");
  }

  @Test
  void identityDenialIsReportedPerItem() {
    AtomicInteger runs = new AtomicInteger();
    // 白名单里没有这个工具 ⇒ 纯判定链第一段判否（PERMISSION_DENIED），体不跑
    AgentPermissionSet narrow =
        new AgentPermissionSet(
            AccessToken.SYSTEM,
            Set.of("other_tool"),
            Set.of(),
            true,
            true,
            false,
            ResourceScopeMap.of(NAMESPACE, ResourceScope.unlimited()));
    LocalDecisionCommitPort.ExecutingIdentity who =
        new LocalDecisionCommitPort.ExecutingIdentity(
            narrow.grantedToken(), narrow, AgentIdentity.main(CommandMode.FULL));

    CommitReceipt receipt =
        port(docsTool(runs, new AtomicReference<>(), new AtomicReference<>()), who)
            .commit("dp-1", List.of(new ResolvedCall(0, "write_doc", Map.of("path", "a.md"))));

    assertThat(runs).hasValue(0);
    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
    assertThat(receipt.itemResults().get(0).reason()).contains("PERMISSION_DENIED");
  }

  @Test
  void hardBlockIsRefusedAndReported() {
    AtomicInteger runs = new AtomicInteger();
    AgentTool blocking =
        new AgentTool() {
          @Override
          public String name() {
            return "write_doc";
          }

          @Override
          public ToolGate gate(ToolContext context) {
            return new ToolGate.Block("docs:block", "硬拒（本不该进包）");
          }

          @Override
          public ToolResult execute(ToolContext context) {
            runs.incrementAndGet();
            return ToolResult.ok("ran");
          }
        };

    CommitReceipt receipt =
        port(blocking, docsIdentity())
            .commit("dp-1", List.of(new ResolvedCall(0, "write_doc", Map.of())));

    assertThat(runs).hasValue(0);
    assertThat(receipt.itemResults().get(0).outcome())
        .isEqualTo(CommitReceipt.ItemOutcome.NOT_COMMITTED);
    assertThat(receipt.itemResults().get(0).reason()).contains("COMMAND_BLOCKED");
  }

  @Test
  void committedItemHasNoReasonAndFailureDoes() {
    AtomicInteger runs = new AtomicInteger();
    CommitReceipt receipt =
        port(docsTool(runs, new AtomicReference<>(), new AtomicReference<>()), docsIdentity())
            .commit(
                "dp-1",
                List.of(
                    new ResolvedCall(0, "write_doc", Map.of("path", "a.md")),
                    new ResolvedCall(1, "missing_tool", Map.of())));

    assertThat(receipt.outcomes())
        .as("冻结读口逐字不变：COMMITTED / NOT_COMMITTED")
        .containsExactly(
            CommitReceipt.ItemOutcome.COMMITTED, CommitReceipt.ItemOutcome.NOT_COMMITTED);
    assertThat(receipt.itemResults().get(0).reason()).as("成功条目没有理由可说 ⇒ null = 未说明理由（V14）").isNull();
    assertThat(receipt.itemResults().get(1).reason()).contains("工具已不在目录里");
  }

  @Test
  void executingIdentityIsTakenPerPackageNotHardCoded() {
    AtomicInteger runs = new AtomicInteger();
    AtomicReference<String> askedFor = new AtomicReference<>();
    ToolRegistry registry = new ToolRegistry();
    registry.register("test", docsTool(runs, new AtomicReference<>(), new AtomicReference<>()));
    // 一个"认不出裁决者"的包：装配层给 fail-closed 身份（无白名单的只读）⇒ 该条不执行
    LocalDecisionCommitPort port =
        new LocalDecisionCommitPort(
            registry,
            new ToolExecutionGuard(),
            Map.of(),
            packageId -> {
              askedFor.set(packageId);
              AgentPermissionSet none =
                  new AgentPermissionSet(AccessToken.GUEST, Set.of(), Set.of(), false, false, true);
              return new LocalDecisionCommitPort.ExecutingIdentity(
                  none.grantedToken(), none, AgentIdentity.UNKNOWN);
            });

    CommitReceipt receipt =
        port.commit("dp-42", List.of(new ResolvedCall(0, "write_doc", Map.of("path", "a.md"))));

    assertThat(askedFor.get()).as("身份是按包 id 现取的").isEqualTo("dp-42");
    assertThat(runs).hasValue(0);
    assertThat(receipt.itemResults().get(0).reason()).contains("PERMISSION_DENIED");
  }
}
