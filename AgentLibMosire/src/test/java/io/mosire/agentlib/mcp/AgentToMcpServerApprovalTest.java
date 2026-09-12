package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * H7（S4-B2，V8 结构性判据）：桩 {@code Ask} 工具经<b>真实 MCP 握手</b>的 {@code tools/call} 到达时， <b>走的是审批判定，而不是裸
 * {@code tool.execute}</b>。
 *
 * <p>走真管道 + SDK 官方客户端（照抄 {@code AgentToMcpServerAuthorityTest} 的取法），不直接调私有方法——避免"测了个假入口"。
 * 人这一侧由用例扮演：请求被 {@code publish} 后，用例经 {@link PendingApprovals#decide} 答复（与 HTTP/tty 通道同一条写表路径）。
 *
 * <p>判别性（本包最相关的那条装配线）：把 {@code ToolCallAuthorizer.of(guard, coordinator)} 换成 {@link
 * ToolCallAuthorizer#standard()}（= 不含协调器的那个），{@code Ask} 一律 fail-closed 拒 ⇒ 本用例第一条与第三条断言转红 （反向对照用例
 * {@code withoutACoordinatorAskIsDeniedAndTheBodyNeverRuns} 就是这个形态的固定版）。
 */
class AgentToMcpServerApprovalTest {

  private static final String CLASS_KEY = "bash:ask:apt";

  /** 自报 {@link ToolGate.Ask} 的工具：体一旦被执行就置位（"审批没放行时体绝不执行"的确定性观测点）。 */
  private static AgentTool askTool(AtomicBoolean bodyRan) {
    return new AgentTool() {
      @Override
      public String name() {
        return "apt_install";
      }

      @Override
      public String description() {
        return "安装一个系统包（测试用；真实形态 = C 包的 bash）";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRan.set(true);
        return ToolResult.ok("installed:" + context.arguments().getOrDefault("pkg", ""));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, "安装系统包 " + context.arguments().getOrDefault("pkg", ""));
      }
    };
  }

  /**
   * 正例：真 MCP 握手 → 审批通道里出现同一条请求 → 人的答复（写进登记表）让调用通过，工具体真的执行。
   *
   * <p>{@code bodyRan} 是判别性的关键：只有"审批通过之后才执行"这条语义真的成立，它才会是 true。
   */
  @Test
  void askToolOverTheMcpHandshakeWaitsForTheHumanThenRuns() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));
    PendingApprovals pending = new PendingApprovals();
    RecordingChannel channel = new RecordingChannel("stub-http", pending);
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(List.of(), List.of(channel), pending, Duration.ofSeconds(5), null);

    try (Rig rig = start(registry, ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator))) {
      ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<McpSchema.CallToolResult> call =
            calls.submit(
                () ->
                    rig.client()
                        .callTool(
                            new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl"))));

        // 编排器真的把请求发给了通道（提示面能看到它），且 id 就是登记表发的那个
        ApprovalRequest served = channel.awaitPublished(Duration.ofSeconds(10));
        assertThat(served.tool()).isEqualTo("apt_install");
        assertThat(served.classKey()).isEqualTo(CLASS_KEY);
        assertThat(served.summary()).as("摘要由工具自报的 Ask 提供，不由 MCP 层拼参数").contains("curl");
        assertThat(pending.pending()).extracting(ApprovalRequest::id).containsExactly(served.id());

        // 人答复（生产里由 HTTP/tty 通道写进同一份登记表；这里直接调用那唯一的写表口）
        assertThat(pending.decide(served.id(), ApprovalDecision.APPROVE_ONCE, "http")).isTrue();

        McpSchema.CallToolResult result = call.get(10, TimeUnit.SECONDS);
        assertThat(result.isError()).isFalse();
        assertThat(wireText(result)).isEqualTo("installed:curl");
        assertThat(bodyRan).as("审批放行之后工具体才执行").isTrue();
      } finally {
        calls.shutdownNow();
      }
    }
  }

  /**
   * 反向对照：同一注册表、同一工具、同一管道，只把入口换成不含协调器的 {@link ToolCallAuthorizer#standard()} ⇒ 调用被 fail-closed
   * 拒（{@code APPROVAL_DENIED}）且工具体<b>一次都没执行</b>。
   *
   * <p>没有这一条，正例里的"执行成功"无法与"审批压根不存在、直接裸执行"区分开。
   */
  @Test
  void withoutACoordinatorAskIsDeniedAndTheBodyNeverRuns() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));

    try (Rig rig = start(registry, ToolCallAuthorizer.standard())) {
      McpSchema.CallToolResult result =
          rig.client()
              .callTool(new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl")));
      assertThat(result.isError()).isTrue();
      assertThat(wireText(result))
          .startsWith("[mosire:code=" + ToolCallAuthorizer.APPROVAL_DENIED + "]");
      assertThat(bodyRan).isFalse();
    }
  }

  private record Rig(AgentToMcpServer server, McpSyncClient client) implements AutoCloseable {

    @Override
    public void close() {
      client.closeGracefully();
      server.close();
    }
  }

  /** 起一个 server（给定工具调用入口）+ 管道客户端并完成 initialize。 */
  private static Rig start(ToolRegistry registry, ToolCallAuthorizer authorizer) {
    try {
      PipedInputStream serverReads = new PipedInputStream();
      PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
      PipedInputStream clientReads = new PipedInputStream();
      PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
      AgentToMcpServer server =
          AgentToMcpServer.start(
              registry,
              "approval-test",
              "0.1.0",
              ToolContext.of(AccessToken.SYSTEM, AgentPermissionSet.system()),
              authorizer,
              serverReads,
              serverWrites);
      PipeMcpClientTransport transport =
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), clientReads, clientWrites);
      McpSyncClient client = McpClient.sync(transport).build();
      client.initialize();
      return new Rig(server, client);
    } catch (Exception e) {
      throw new AssertionError("MCP 管道装配失败", e);
    }
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent t ? t.text() : content.toString();
  }

  /**
   * "真通道"形态的桩：{@link #await} 把等待交给登记表（与生产的 tty/HTTP 通道同构），{@link #publish} 记下请求当同步点。
   *
   * <p>不写死答复脚本：人这一侧由用例经 {@link PendingApprovals#decide} 扮演——这样"决议从登记表到等待方"这条链是真的。
   */
  private static final class RecordingChannel implements ApprovalChannel {

    private final String name;
    private final PendingApprovals pending;
    private final List<ApprovalRequest> published = Collections.synchronizedList(new ArrayList<>());

    RecordingChannel(String name, PendingApprovals pending) {
      this.name = name;
      this.pending = pending;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public void publish(ApprovalRequest req) {
      published.add(req);
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      return pending.await(id, wait);
    }

    /** 等编排器把请求发到提示面（publish 之后）——到点还没有即判失败。 */
    ApprovalRequest awaitPublished(Duration limit) {
      long deadlineNanos = System.nanoTime() + limit.toNanos();
      while (System.nanoTime() < deadlineNanos) {
        List<ApprovalRequest> snapshot = List.copyOf(published);
        if (!snapshot.isEmpty()) {
          return snapshot.get(0);
        }
        try {
          Thread.sleep(5L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      throw new AssertionError("审批通道没有被 publish（等了 " + limit + "）");
    }
  }
}
