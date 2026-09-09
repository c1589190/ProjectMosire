package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D17：AgentSpec 默认值 / Builder / 校验，以及 AgentRuntime 以 AgentSpec 装配（组合 AgentConfig，语义不变）。 */
class AgentSpecTest {

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  private static AgentConfig core() {
    return AgentConfig.builder("spec-core").build();
  }

  @Test
  void convenienceCtorAppliesAllDefaults() {
    AgentSpec spec = new AgentSpec(core());
    assertThat(spec.core().id()).isEqualTo("spec-core");
    assertThat(spec.provider()).isEmpty();
    assertThat(spec.reasoningEffort()).isEqualTo("medium");
    assertThat(spec.maxOutputTokens()).isZero();
    assertThat(spec.skills()).isEmpty();
    assertThat(spec.memoryScope()).isEqualTo("local");
    assertThat(spec.permissionMode()).isEqualTo("default");
    assertThat(spec.workingDirectory()).isNull();
  }

  @Test
  void builderSetsFieldsAndPreservesCore() {
    AgentConfig config = core();
    AgentSpec spec =
        AgentSpec.builder(config)
            .provider("glm")
            .reasoningEffort("high")
            .maxOutputTokens(4096)
            .skills(Set.of("review"))
            .memoryScope("project")
            .permissionMode("auto")
            .workingDirectory(Path.of("/tmp/opencode"))
            .build();
    assertThat(spec.core()).isSameAs(config);
    assertThat(spec.provider()).isEqualTo("glm");
    assertThat(spec.reasoningEffort()).isEqualTo("high");
    assertThat(spec.maxOutputTokens()).isEqualTo(4096);
    assertThat(spec.skills()).containsExactly("review");
    assertThat(spec.memoryScope()).isEqualTo("project");
    assertThat(spec.permissionMode()).isEqualTo("auto");
    assertThat(spec.workingDirectory()).isEqualTo(Path.of("/tmp/opencode"));
  }

  @Test
  void skillsAreCopiedDefensively() {
    HashSet<String> mutable = new HashSet<>(Set.of("a"));
    AgentSpec spec = new AgentSpec(core(), "", "medium", 0, mutable, "local", "default", null);
    mutable.add("b");
    assertThat(spec.skills()).containsExactly("a");
  }

  @Test
  void nullCoreThrows() {
    assertThatThrownBy(() -> new AgentSpec(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void blankReasoningEffortThrows() {
    assertThatThrownBy(() -> new AgentSpec(core(), "", null, 0, Set.of(), "local", "default", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void blankPermissionModeThrows() {
    assertThatThrownBy(() -> new AgentSpec(core(), "", "medium", 0, Set.of(), "local", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void blankMemoryScopeThrows() {
    assertThatThrownBy(
            () -> new AgentSpec(core(), "", "medium", 0, Set.of(), null, "default", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void negativeMaxOutputTokensThrows() {
    assertThatThrownBy(
            () -> new AgentSpec(core(), "", "medium", -1, Set.of(), "local", "default", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void agentRuntimeAcceptsSpecAndKeepsPipelineOnConfig() {
    AgentConfig config = core();
    AgentSpec spec = AgentSpec.builder(config).provider("glm").reasoningEffort("high").build();
    try (AgentRuntime runtime =
        new AgentRuntime(
            spec,
            FakeLlmClient.with(LlmResponse.text("ok")),
            new ToolRegistry(),
            store,
            bus,
            AgentPermissionSet.system())) {
      assertThat(runtime.spec()).isSameAs(spec);
      assertThat(runtime.config()).isSameAs(config);
    }
  }

  @Test
  void agentRuntimeConfigCtorWrapsIntoDefaultSpec() {
    AgentConfig config = core();
    try (AgentRuntime runtime =
        new AgentRuntime(
            config,
            FakeLlmClient.with(LlmResponse.text("ok")),
            new ToolRegistry(),
            store,
            bus,
            AgentPermissionSet.system())) {
      assertThat(runtime.spec().core()).isSameAs(config);
      assertThat(runtime.spec().reasoningEffort()).isEqualTo("medium");
    }
  }
}
