package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * S5-C 资源 SPI 经<b>工具调用唯一入口</b>的判别性用例——<b>判别性样本</b>：一个测试用"插件样工具"（不入产品出厂）， 声明 {@code
 * plugin:sample/data} 命名空间，工具体里对命令式 SPI 做一次真实用法（读前 {@code require(READ)}、写前 {@code
 * require(WRITE)}）。
 *
 * <p>每条用例都回答"把它对应的那行代码改坏，这条会转红吗"：
 *
 * <ul>
 *   <li><b>同一工具、同一参数、换调用者 ⇒ 一个放行一个拒</b>：可达面判定若改成"看工具/看参数"或干脆不判 ⇒ {@link
 *       #sameToolSameArgumentsTwoCallersOnePassesOneIsDenied} 转红；
 *   <li><b>可达面锚在调用者身份上</b>：改成构造期常量/工具自报 ⇒ 同上转红（两个调用者拿到的判定者必须不同）；
 *   <li><b>被拒时副作用为零</b>：把判定挪到访问之后 ⇒ 计数器断言转红；
 *   <li><b>执行前拒（声明式闸）</b>：取消前置闸 ⇒ 改成"够不着也进工具体"时 {@link #noSideEffectsWhenThePreGateDenies} 转红；
 *   <li><b>资源闸在审批闸之前</b>：把资源段挪到审批之后 ⇒ {@link #resourcePreGateRunsBeforeTheApprovalGate} 会从 {@code
 *       RESOURCE_DENIED} 变成 {@code APPROVAL_DENIED} ⇒ 转红；
 *   <li><b>不用资源 SPI 的工具行为不变</b>：把缺省 {@code resources()} 改成非空声明、或让缺省闸变成"拒一切" ⇒ {@link
 *       #toolsWithoutAManifestAreUntouched} 转红；
 *   <li><b>catch 是窄的</b>：把 {@code catch (ResourceDeniedException)} 扩大成 {@code catch
 *       (RuntimeException)} ⇒ {@link #otherRuntimeExceptionsStillPropagate} 转红。
 * </ul>
 *
 * <p><b>诚实边界（用例里钉住）</b>：{@link #aToolThatSwallowsTheDenialDefeatsTheSpi} 证明"工具自己吞掉 {@link
 * ResourceDeniedException}"能绕过判定——这条防线靠<b>契约</b>（工具不得吞），不靠结构性强制。工具是宿主装的代码（不是模型给的），
 * 契约在这里是可接受的载体；但它是契约，不是围栏。
 */
class ToolCallAuthorizerResourceTest {

  private static final String TOOL = "plugin_sample";
  private static final String NS = "plugin:sample/data";
  private static final ResourceId STATE = ResourceId.of(NS, "tenant/9/state.json");

  /** 调用者权限集：白名单通配（SYSTEM 级）→ 唯一能挡住它的只有资源这一维（不与白名单/身份混淆）。 */
  private static AgentPermissionSet callerDeclaring(ResourceScope area) {
    return AgentPermissionSet.builder(AccessToken.SYSTEM)
        .allowAll()
        .sensitiveAllowed(true)
        .destructiveAllowed(true)
        .resourceScopes(ResourceScopeMap.of(NS, area))
        .build();
  }

  private static AgentPermissionSet silentCaller() {
    return AgentPermissionSet.builder(AccessToken.SYSTEM)
        .allowAll()
        .sensitiveAllowed(true)
        .destructiveAllowed(true)
        .build();
  }

  private static ToolContext contextWith(AgentPermissionSet permissions) {
    return ToolContext.of(AccessToken.SYSTEM, permissions);
  }

  /**
   * 测试用"插件样工具"：声明命名空间 + 在访问点调命令式 SPI。{@code gate} 可注入（默认 {@link ToolGate#ALLOW}）， 用来咬"资源闸与审批闸的先后"。
   */
  private static final class PluginSampleTool implements AgentTool {

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final ResourceManifest manifest;
    volatile ToolGate gate = ToolGate.ALLOW;

    PluginSampleTool(ResourceManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public String name() {
      return TOOL;
    }

    @Override
    public ResourceManifest resources() {
      return manifest;
    }

    @Override
    public ToolGate gate(ToolContext context) {
      return gate;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      context.resources().require(Operation.READ, STATE);
      reads.incrementAndGet();
      context.resources().require(Operation.WRITE, STATE);
      writes.incrementAndGet();
      return ToolResult.ok("plugin: done");
    }

    int reads() {
      return reads.get();
    }

    int writes() {
      return writes.get();
    }
  }

  private static ToolRegistry registryWith(AgentTool... tools) {
    ToolRegistry registry = new ToolRegistry();
    for (AgentTool tool : tools) {
      registry.register(tool);
    }
    return registry;
  }

  @Test
  void sameToolSameArgumentsTwoCallersOnePassesOneIsDenied() {
    // 工具缺省策略 deny（插件作者的 fail-closed 缺省）：够不够得着全看调用者有没有显式表态
    PluginSampleTool tool = new PluginSampleTool(ResourceManifest.of(NS, ResourcePolicy.DENY));
    ToolRegistry registry = registryWith(tool);
    ToolCallAuthorizer authorizer = ToolCallAuthorizer.standard();

    // 调用者 A：显式表态"我能碰 tenant/9" ⇒ 读与写都过
    ToolResult allowed =
        authorizer.execute(
            registry, TOOL, contextWith(callerDeclaring(ResourceScope.of("tenant/9"))));
    assertThat(allowed.success()).isTrue();
    assertThat(allowed.message()).isEqualTo("plugin: done");
    assertThat(tool.reads()).isEqualTo(1);
    assertThat(tool.writes()).isEqualTo(1);

    // 调用者 B：显式表态"我只能碰 tenant/8" ⇒ 同一工具、同一参数，被拒在访问点上，且**读都没读到**
    ToolResult denied =
        authorizer.execute(
            registry, TOOL, contextWith(callerDeclaring(ResourceScope.of("tenant/8"))));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(denied.message()).contains("不在调用者的可达面内").contains("tenant/9");
    // ★ 判别性：被拒的这一次没让工具体里的任何一次读写发生（计数器停在 A 的那一次）
    assertThat(tool.reads()).isEqualTo(1);
    assertThat(tool.writes()).isEqualTo(1);
  }

  @Test
  void noSideEffectsWhenThePreGateDenies() {
    // 调用者什么都没表态 + 工具缺省 deny ⇒ 声明式前置闸在执行前拒掉整个调用（连工具体都不进）
    PluginSampleTool tool = new PluginSampleTool(ResourceManifest.of(NS, ResourcePolicy.DENY));
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(registryWith(tool), TOOL, contextWith(silentCaller()));

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(result.message()).contains("够不着").contains(NS);
    assertThat(tool.reads()).isZero();
    assertThat(tool.writes()).isZero();
  }

  @Test
  void resourcePreGateRunsBeforeTheApprovalGate() {
    // 同一个"够不着"的调用：工具自报 Ask。资源闸在审批之前 ⇒ 不该去问人（问了人也解决不了"够不着"）
    PluginSampleTool tool = new PluginSampleTool(ResourceManifest.of(NS, ResourcePolicy.DENY));
    tool.gate = new ToolGate.Ask("plugin:ask", "插件要读数据");
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(registryWith(tool), TOOL, contextWith(silentCaller()));

    // 未装配编排器 ⇒ 若走到审批段，码必然是 APPROVAL_DENIED；这里必须是 RESOURCE_DENIED（顺序判别点）
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(result.code()).isNotEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);

    // 反面对照：够得着的调用者在同一个 Ask 工具上确实会走到审批段（否则上面的断言可能只是"审批永远不触发"）
    ToolResult askReached =
        ToolCallAuthorizer.standard()
            .execute(
                registryWith(tool),
                TOOL,
                contextWith(callerDeclaring(ResourceScope.of("tenant/9"))));
    assertThat(askReached.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.reads()).isZero();
  }

  @Test
  void toolsWithoutAManifestAreUntouched() {
    // 不用资源 SPI 的工具（本仓既有工具全是这一形态）：声明空 = 判定器对一切都拒，但它从不调 require ⇒ 行为逐字不变
    AtomicInteger bodyRan = new AtomicInteger();
    AgentTool plain =
        new AgentTool() {
          @Override
          public String name() {
            return "plain";
          }

          @Override
          public ToolResult execute(ToolContext context) {
            bodyRan.incrementAndGet();
            return ToolResult.ok("plain: done");
          }
        };
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(registryWith(plain), "plain", contextWith(silentCaller()));
    assertThat(result.success()).isTrue();
    assertThat(bodyRan).hasValue(1);

    // 而"声明了空却又要用 SPI"的工具：访问点一律拒（"没声明" ≠ "随便用"）
    PluginSampleTool undeclared = new PluginSampleTool(ResourceManifest.NONE);
    ToolResult refused =
        ToolCallAuthorizer.standard()
            .execute(
                registryWith(undeclared),
                TOOL,
                contextWith(callerDeclaring(ResourceScope.of("/"))));
    assertThat(refused.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(refused.message()).contains("未声明资源命名空间");
    assertThat(undeclared.reads()).isZero();
  }

  @Test
  void otherRuntimeExceptionsStillPropagate() {
    // 入口只接住 ResourceDeniedException：别的意外异常照旧外泄（由管线记录，别在唯一入口里被悄悄吞成"工具失败"）
    AgentTool broken =
        new AgentTool() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public ToolResult execute(ToolContext context) {
            throw new IllegalStateException("工具实现自己炸了");
          }
        };
    assertThatThrownBy(
            () ->
                ToolCallAuthorizer.standard()
                    .execute(registryWith(broken), "broken", contextWith(silentCaller())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("工具实现自己炸了");
  }

  @Test
  void aToolThatSwallowsTheDenialDefeatsTheSpi() {
    // 诚实边界（用例钉住，不当它不存在）：工具若自己 catch 掉 ResourceDeniedException，判定就被绕过了。
    AgentTool swallower =
        new AgentTool() {
          @Override
          public String name() {
            return "swallower";
          }

          @Override
          public ResourceManifest resources() {
            return ResourceManifest.of(NS, ResourcePolicy.DENY);
          }

          @Override
          public ToolResult execute(ToolContext context) {
            try {
              context.resources().require(Operation.READ, STATE);
            } catch (ResourceDeniedException swallowed) {
              return ToolResult.ok("我读了（其实没有）");
            }
            return ToolResult.ok("读了");
          }
        };
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(registryWith(swallower), "swallower", contextWith(silentCaller()));
    // 前置闸挡住了这一次（调用者未表态 + 缺省 deny）——所以这里的"绕过"发生在能走到工具体时才成立：
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    // 够得着（前置闸不拦）+ 访问点越界（工具要读的是别的租户）⇒ 工具自己吞掉拒绝，入口无从知晓
    AgentPermissionSet narrow = callerDeclaring(ResourceScope.of("tenant/8"));
    ToolResult swallowed =
        ToolCallAuthorizer.standard()
            .execute(registryWith(swallower), "swallower", contextWith(narrow));
    assertThat(swallowed.success()).isTrue();
    assertThat(swallowed.message()).isEqualTo("我读了（其实没有）");
  }
}
