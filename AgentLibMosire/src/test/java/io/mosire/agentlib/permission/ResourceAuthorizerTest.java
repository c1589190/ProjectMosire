package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S5-C 资源判定表（{@link ResourceAuthorizer}）：逐格判一遍，每格都能说清"哪一行被改坏时它会转红"。
 *
 * <p>判别性重点：
 *
 * <ul>
 *   <li><b>未声明即拒</b>：工具没声明的命名空间一律拒（"没声明"≠"随便用"）——去掉这一格 ⇒ {@link
 *       #undeclaredNamespaceIsDeniedEvenWhenTheCallerIsFree} 转红；
 *   <li><b>可达面必须守住</b>：调用者显式表态后，越界的路径仍然拒——去掉可达面那一跳 ⇒ {@link
 *       #explicitCallerScopeGovernsTheArea}、{@link #explicitlyEmptyScopeDeniesEveryPath} 转红；
 *   <li><b>"缺省不是封顶"</b>：调用者显式表态取代工具的缺省策略（把策略改成封顶）⇒ {@link
 *       #explicitCallerScopeOverridesTheManifestDefault} 转红；
 *   <li><b>空 ≠ 不限</b>：显式 {@code none()} 与"未表态"行为相反——把两者混成一个 ⇒ {@link
 *       #explicitlyEmptyScopeDeniesEveryPath} 转红；
 *   <li><b>前置闸只在"一个都够不着"时说话</b>：多命名空间里有一个可用就不许拦（把它改成"有一个不可用就拦"）⇒ {@link
 *       #preGateStaysSilentWhileAnyDeclaredNamespaceIsUsable} 转红；
 *   <li><b>没有判定者 = 全拒</b>：把 {@code denying()} 改成放行 ⇒ {@link #withoutAJudgeEveryRequireIsDenied} 转红。
 * </ul>
 */
class ResourceAuthorizerTest {

  private static final String NS = "plugin:sample/data";
  private static final ResourceId STATE = ResourceId.of(NS, "tenant/9/state.json");

  /** 调用者权限集：白名单通配 + 只声明"某一个命名空间的某一块"——让"被挡住"只能来自资源这一维。 */
  private static AgentPermissionSet callerDeclaring(String namespace, ResourceScope scope) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .resourceScopes(ResourceScopeMap.of(namespace, scope))
        .build();
  }

  /** 什么都没表态的调用者（资源这一维未声明）。 */
  private static AgentPermissionSet silentCaller() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
  }

  @Test
  void withoutAJudgeEveryRequireIsDenied() {
    ResourceAuthorizer judge = ResourceAuthorizer.denying();
    assertThat(judge.allows(Operation.READ, STATE)).isFalse();
    assertThatThrownBy(() -> judge.require(Operation.READ, STATE))
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageContaining("没有资源判定者")
        .hasMessageContaining(NS);
    // 前置闸这时不说话："够不够得着"问的是工具声明的那几个命名空间，无判定者时无从谈起（访问点那一侧仍然全拒）
    assertThat(judge.denial()).isEmpty();
  }

  @Test
  void undeclaredNamespaceIsDeniedEvenWhenTheCallerIsFree() {
    // 工具声明了别的命名空间，却有人来要 plugin:sample/data
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            silentCaller(), ResourceManifest.of("plugin:other", ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, STATE)).isFalse();
    assertThatThrownBy(() -> judge.require(Operation.READ, STATE))
        .hasMessageContaining("未声明资源命名空间");
    // 声明过的那一个照常放行（否则"全拒"无法与"判定点坏了"区分）
    assertThat(judge.allows(Operation.READ, ResourceId.of("plugin:other", "x"))).isTrue();
  }

  @Test
  void manifestDenyBlocksTheCallerWhoSaidNothing() {
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(silentCaller(), ResourceManifest.of(NS, ResourcePolicy.DENY));
    assertThat(judge.allows(Operation.READ, STATE)).isFalse();
    assertThatThrownBy(() -> judge.require(Operation.READ, STATE)).hasMessageContaining("deny");
    // 前置闸：这一个命名空间对本次调用者不可用 ⇒ 说话（工具声明里没有第二个命名空间可用）
    assertThat(judge.denial()).isPresent();
  }

  @Test
  void manifestReadOnlyLetsReadsThroughAndBlocksWrites() {
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(silentCaller(), ResourceManifest.of(NS, ResourcePolicy.READ_ONLY));
    assertThat(judge.allows(Operation.READ, STATE)).isTrue();
    assertThatThrownBy(() -> judge.require(Operation.WRITE, STATE))
        .hasMessageContaining("read-only")
        .hasMessageContaining(STATE.fullId());
    // 只读缺省下"够得着"（读得了）⇒ 前置闸不许拦整个调用
    assertThat(judge.denial()).isEmpty();
  }

  @Test
  void manifestUnrestrictedLetsBothOperatorsThroughWhenTheCallerIsSilent() {
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(silentCaller(), ResourceManifest.of(NS, ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, STATE)).isTrue();
    assertThat(judge.allows(Operation.WRITE, STATE)).isTrue();
    assertThat(judge.denial()).isEmpty();
  }

  @Test
  void explicitCallerScopeGovernsTheArea() {
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            callerDeclaring(NS, ResourceScope.of("tenant/9")),
            ResourceManifest.of(NS, ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, STATE)).isTrue();
    assertThat(judge.allows(Operation.WRITE, STATE)).isTrue();
    // 同一命名空间的另一块：越界
    ResourceId other = ResourceId.of(NS, "tenant/10/state.json");
    assertThat(judge.allows(Operation.READ, other)).isFalse();
    assertThatThrownBy(() -> judge.require(Operation.READ, other))
        .hasMessageContaining("不在调用者的可达面内")
        .hasMessageContaining("tenant/9");
  }

  @Test
  void explicitCallerScopeOverridesTheManifestDefault() {
    // "缺省不是封顶"：工具缺省 deny，调用者显式表态 ⇒ 以调用者为准（理由见 ResourcePolicy 类注释）
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            callerDeclaring(NS, ResourceScope.of("tenant/9")),
            ResourceManifest.of(NS, ResourcePolicy.DENY));
    assertThat(judge.allows(Operation.READ, STATE)).isTrue();
    assertThat(judge.allows(Operation.WRITE, STATE)).isTrue();
    assertThat(judge.denial()).isEmpty();
  }

  @Test
  void explicitlyEmptyScopeDeniesEveryPath() {
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            callerDeclaring(NS, ResourceScope.none()),
            ResourceManifest.of(NS, ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, STATE)).isFalse();
    assertThatThrownBy(() -> judge.require(Operation.READ, STATE)).hasMessageContaining("可达面");
    assertThat(judge.denial()).isPresent(); // 唯一的命名空间显式配成"哪里都不许" ⇒ 整个调用够不着
  }

  @Test
  void preGateStaysSilentWhileAnyDeclaredNamespaceIsUsable() {
    String usable = "plugin:other";
    ResourceManifest two =
        ResourceManifest.of(Map.of(NS, ResourcePolicy.DENY, usable, ResourcePolicy.READ_ONLY));
    // 一个是 deny，另一个只读 ⇒ 调用并非"哪儿都去不了"，前置闸不许拦
    assertThat(ResourceAuthorizer.of(silentCaller(), two).denial()).isEmpty();
    // 两个都够不着 ⇒ 说话
    assertThat(
            ResourceAuthorizer.of(
                    silentCaller(),
                    ResourceManifest.of(
                        Map.of(NS, ResourcePolicy.DENY, usable, ResourcePolicy.DENY)))
                .denial())
        .isPresent();
    // 只读的那一个被调用者显式表态收窄成"哪里都不许" ⇒ 又回到"哪儿都去不了"
    assertThat(ResourceAuthorizer.of(callerDeclaring(usable, ResourceScope.none()), two).denial())
        .isPresent();
    // 工具一个命名空间都没声明（不用本 SPI 的工具）⇒ 前置闸不说话，工具行为不受影响
    assertThat(ResourceAuthorizer.of(silentCaller(), ResourceManifest.NONE).denial()).isEmpty();
  }

  @Test
  void namespaceIdentityIsExactText() {
    // "plugin:sample" 与 "plugin:sample/data" 是两个命名空间：不做前缀/大小写之类的"好意归一"
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            silentCaller(), ResourceManifest.of("plugin:sample", ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, STATE)).isFalse();
    assertThat(judge.allows(Operation.READ, ResourceId.of("plugin:sample", "anything"))).isTrue();
  }

  @Test
  void namespaceTextIsTrimmedOnBothSides() {
    // 声明面与资源标识同口径去掉首尾空白（三处口径一致：ResourceScopeMap / ResourceManifest / ResourceId）
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            silentCaller(),
            ResourceManifest.of(" plugin:sample/data ", ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, ResourceId.of("plugin:sample/data", "x"))).isTrue();
  }

  @Test
  void relativeSegmentsAreResolvedBeforeComparing() {
    // S5-C 顺手补的洞：候选串过去不解析 '..'，于是 /work/../etc 会被判成"在 /work 之内"。判定点是工具代码喂进来的，
    // 这一跳必须与 allowsDir(Path) 同口径（那条路早就在 Path 层归一过）。
    ResourceAuthorizer judge =
        ResourceAuthorizer.of(
            callerDeclaring(NS, ResourceScope.of("tenant/9")),
            ResourceManifest.of(NS, ResourcePolicy.UNRESTRICTED));
    assertThat(judge.allows(Operation.READ, ResourceId.of(NS, "tenant/9/../../10/state.json")))
        .isFalse();
    assertThat(judge.allows(Operation.READ, ResourceId.of(NS, "tenant/9/./sub/x"))).isTrue();
    // 含 NUL 的串（不可能成路径）在这里得到的是"一次判定"而不是异常：它在 tenant/9 段之下 ⇒ 判"在面内"。
    // 这不是安全承诺——真围栏是 L3 沙箱（本类型只判命名，见 ResourceScope 的诚实分级）。
    assertThat(judge.allows(Operation.READ, ResourceId.of(NS, "tenant/9/" + (char) 0 + "x")))
        .isTrue();
    // 前置闸也不因怪串炸
    assertThat(judge.denial()).isEmpty();
  }

  @Test
  void resourceIdKeepsNamespaceAndPathApart() {
    // 命名空间自己含 ':' 也拆得开——fullId 只是展示串（不是可回解的编码）
    ResourceId id = ResourceId.of("plugin:foo/data", "tenant/9");
    assertThat(id.namespace()).isEqualTo("plugin:foo/data");
    assertThat(id.path()).isEqualTo("tenant/9");
    assertThat(id.fullId()).isEqualTo("plugin:foo/data:tenant/9");
  }

  @Test
  void manifestAndResourceIdRejectEmptyPartsLoudly() {
    assertThatThrownBy(() -> ResourceId.of("  ", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ResourceId.of("fs", " ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ResourceManifest.of("  ", ResourcePolicy.DENY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
