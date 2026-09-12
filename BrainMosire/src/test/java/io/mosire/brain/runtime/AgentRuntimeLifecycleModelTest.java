package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code agent.lifecycle started} 的 {@code model} 字段必须报<b>客户端实际在用的模型</b>（{@link
 * LlmClient#model()}）， 不是装配层标签 {@link AgentConfig#model()}。
 *
 * <p><b>为什么这条要单独立案</b>：2026-09-12 U6 使用模式实跑抓到——子体明明在调 {@code deepseek-flash}（{@code llm.call}
 * 事件可证），生命周期事件却写 {@code "model":"fake"}（模板缺省字面量渗进观测面）。凡"靠 {@code agent.lifecycle} 判真假模型" 的检查（含 D24
 * 的假模型探针）都会被带偏，而且<b>两个方向都会错</b>。
 *
 * <p>判别性：把 {@code AgentRuntime} 的发射点改回 {@code config.model()}，本类两条用例同时转红。
 */
class AgentRuntimeLifecycleModelTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /** 客户端声明什么，事件就报什么——装配层标签（缺省字面量 {@code fake}）不许覆盖它。 */
  @Test
  void lifecycleReportsTheClientsModelNotTheAssemblyLabel() throws Exception {
    AgentConfig config = AgentConfig.builder("main").build();
    assertThat(config.model()).as("前提：装配层标签确实是缺省字面量").isEqualTo("fake");

    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("named.db"));
        EventBus bus = new EventBus()) {
      AgentRuntime runtime =
          new AgentRuntime(
              config,
              new NamedLlmClient("deepseek-flash"),
              new ToolRegistry(),
              store,
              bus,
              AgentPermissionSet.system());
      assertThat(lifecycleModel(store)).isEqualTo("deepseek-flash");
      runtime.close();
    }
  }

  /** 没声明模型的客户端报 {@code unknown}：说"不知道"可以，报 {@code fake} 是撒谎（"没声明" ≠ "假模型"）。 */
  @Test
  void undeclaredModelReportsUnknownNotFake() throws Exception {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("unnamed.db"));
        EventBus bus = new EventBus()) {
      AgentRuntime runtime =
          new AgentRuntime(
              AgentConfig.builder("main").build(),
              request -> LlmResponse.text("不调用"),
              new ToolRegistry(),
              store,
              bus,
              AgentPermissionSet.system());
      assertThat(lifecycleModel(store)).isEqualTo("unknown");
      runtime.close();
    }
  }

  /** 读构造期那一条 {@code agent.lifecycle} 的 model 字段（此时只有这一条）。 */
  private static String lifecycleModel(SqliteEventStore store) throws Exception {
    List<Event> events = store.query(new EventQuery("", EventTypes.AGENT_LIFECYCLE, "", -1, 10));
    assertThat(events).hasSize(1);
    return JSON.readTree(events.get(0).payload()).get("model").asText();
  }

  /** 只有"名字"有用的最小客户端：构造即发事件，{@code chat} 永不被调用。 */
  private record NamedLlmClient(String model) implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
      throw new IllegalStateException("本用例不调用 chat");
    }
  }
}
