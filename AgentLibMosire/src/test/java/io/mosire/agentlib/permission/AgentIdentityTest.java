package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentIdentity} 的离线契约（S6）：缺省口径、显式身份，以及 {@link AgentIdentity#mainFrom} 的<b>现读</b>。
 *
 * <p>判别性重点在最后一条：主 Agent 的档位从运行时缝里<b>每次现读</b>。把 {@code mainFrom} 写成"读一次就缓存" （或读配置初值而非持有者），HTTP
 * 断点改档就退化成"重启才生效"——本组用例转红，而真实使用模式测试跑一次 也能看出来，但那时已经晚了一轮。
 */
class AgentIdentityTest {

  @Test
  void unknownDefaultIsFullNotMain() {
    // 缺省身份<b>不得</b>继承主 Agent 的 id：那会让"没设身份"的调用者与主 Agent 串号（审批按实例归类）
    assertThat(AgentIdentity.UNKNOWN.instanceId()).isNotEqualTo(AgentIdentity.MAIN_ID);
    assertThat(AgentIdentity.UNKNOWN.mode()).isEqualTo(CommandMode.FULL);
    assertThat(AgentIdentity.UNKNOWN.isUnknown()).isTrue();
    assertThat(AgentIdentity.main(CommandMode.FULL).isUnknown()).isFalse();
  }

  @Test
  void externalIdentityIsFullAndRecognisable() {
    AgentIdentity external = AgentIdentity.external();
    assertThat(external.instanceId()).isEqualTo(AgentIdentity.EXTERNAL_ID);
    assertThat(external.mode()).isEqualTo(CommandMode.FULL);
    assertThat(external.goal()).isEmpty();
  }

  @Test
  void mainFromWithoutHolderIsFullBehaviour() {
    assertThat(AgentIdentity.mainFrom(null)).isEqualTo(AgentIdentity.main(CommandMode.FULL));
    assertThat(AgentIdentity.mainFrom(Map.of())).isEqualTo(AgentIdentity.main(CommandMode.FULL));
    // 缝里有别的东西（不是持有者）：同样按"没有档位机制"办，绝不 cast 崩
    assertThat(AgentIdentity.mainFrom(Map.of(CommandModeHolder.CONFIG_KEY, "limited")))
        .isEqualTo(AgentIdentity.main(CommandMode.FULL));
  }

  @Test
  void mainFromReadsTheHolderLive() {
    CommandModeHolder holder = new CommandModeHolder(CommandMode.LIMITED);
    Map<String, Object> config = Map.of(CommandModeHolder.CONFIG_KEY, holder);
    assertThat(AgentIdentity.mainFrom(config).mode()).isEqualTo(CommandMode.LIMITED);
    // 改档后<b>下一次</b>取数就是新值：缓存实现本行转红
    holder.set(CommandMode.FULL);
    assertThat(AgentIdentity.mainFrom(config).mode()).isEqualTo(CommandMode.FULL);
    holder.set(CommandMode.LIMITED);
    assertThat(AgentIdentity.mainFrom(config).mode()).isEqualTo(CommandMode.LIMITED);
  }

  @Test
  void blankInstanceIdIsRejectedAtConstruction() {
    assertThat(
            catchThrowableOfType(
                IllegalArgumentException.class,
                () -> AgentIdentity.subagent("  ", CommandMode.LIMITED, "目标")))
        .isNotNull();
  }

  @Test
  void nullGoalBecomesEmpty() {
    assertThat(AgentIdentity.subagent("bash-probe-1", CommandMode.LIMITED, null).goal()).isEmpty();
  }
}
