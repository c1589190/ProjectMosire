package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.http.A2AHttpClientFactory;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/**
 * 官方 client（1.3.1.Final）↔ 自写服务器的全链路互通（D11：本地回环、无外部依赖）。
 *
 * <p>认证链路：getAgentCard → ClientBuilder(JSONRPC 传输) → sendMessage（ClientConfig 默认 streaming=true
 * 且卡声明 流式 → 走 SendStreamingMessage 的 SSE 事件流）→ subscribeToTask（SubscribeToTask SSE，逐字节走官方
 * SSEEventListener 解析）→ getTask（同步）。
 */
class A2aClientRoundTripTest {

  @Test
  void officialClientGetCardSendMessageSubscribeAndGetTask() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (A2aTaskService service =
            new A2aTaskService(
                new MemoryA2aTaskStore(),
                (taskId, message, editor) -> {
                  started.countDown();
                  try {
                    release.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                  editor.transition(
                      TaskState.TASK_STATE_WORKING, A2A.toAgentMessage("working on it"));
                  editor.appendArtifact(
                      Artifact.builder()
                          .artifactId("a-1")
                          .name("result")
                          .parts(new org.a2aproject.sdk.spec.TextPart("ok"))
                          .build());
                  editor.transition(TaskState.TASK_STATE_COMPLETED);
                },
                Executors.newVirtualThreadPerTaskExecutor());
        A2aHttpServer server =
            A2aHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0),
                port -> A2aTestSupport.card(A2aTestSupport.baseUrl(port)),
                new A2aJsonRpcHandler(A2aTestSupport.card("http://127.0.0.1:0"), service))) {
      String url = A2aTestSupport.baseUrl(server);

      // 1) 官方便携入口：自行拉取 Agent Card（验证卡 JSON 序列化互通）
      AgentCard card = A2A.getAgentCard(url);
      assertThat(card.name()).isEqualTo("mosire-test");
      assertThat(card.capabilities().streaming()).isTrue();

      // 2) 官方 client（JSONRPC 传输；默认 ClientConfig.isStreaming()=true 且卡声明 streaming → 走流式 SSE 路径）
      Client client =
          Client.builder(card)
              .withTransport(
                  JSONRPCTransport.class, new JSONRPCTransportConfig(A2AHttpClientFactory.create()))
              .build();
      try (client) {
        // 3) 流式 sendMessage → SendStreamingMessage 流：首个事件 = TaskEvent（SUBMITTED 快照）
        CountDownLatch sendDone = new CountDownLatch(1);
        AtomicReference<String> taskId = new AtomicReference<>();
        List<Object> events = new CopyOnWriteArrayList<>();
        client.sendMessage(
            A2A.toUserMessage("ping"),
            List.of(
                (event, agCard) -> {
                  events.add(event);
                  if (event instanceof TaskEvent taskEvent) {
                    taskId.set(taskEvent.getTask().id());
                    sendDone.countDown();
                  }
                }),
            t -> {
              // 官方约定：流正常结束时错误回调收到 null（SSEEventListener.onComplete → errorHandler.accept(null)）
              if (t != null) {
                fail("sendMessage 不应有错误: " + t);
              }
            },
            new ClientCallContext(Map.of(), Map.of()));
        assertThat(sendDone.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(taskId.get()).isNotNull().isNotBlank();
        assertThat(events.get(0)).isInstanceOf(TaskEvent.class);
        assertThat(((TaskEvent) events.get(0)).getTask().status().state())
            .isEqualTo(TaskState.TASK_STATE_SUBMITTED);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 4) subscribeToTask（runner 尚未推进 → 快照 SUBMITTED；随后经 statusUpdate/artifactUpdate 增量至终态）
        CountDownLatch finalEvent = new CountDownLatch(1);
        List<Object> streamEvents = new CopyOnWriteArrayList<>();
        client.subscribeToTask(
            new TaskIdParams(taskId.get(), null),
            List.of(
                (event, agCard) -> {
                  streamEvents.add(event);
                  if (event instanceof TaskUpdateEvent update
                      && update.getTask().status().state().isFinal()) {
                    finalEvent.countDown();
                  }
                }),
            t -> {
              // 官方约定：流正常结束时错误回调收到 null
              if (t != null) {
                fail("subscribeToTask 不应有错误: " + t);
              }
            },
            new ClientCallContext(Map.of(), Map.of()));
        // 等待订阅流建立（回环下 300ms 足够；runner 保持在 latch 上，订阅时刻状态必为 SUBMITTED）
        Thread.sleep(300);
        release.countDown();
        assertThat(finalEvent.await(10, TimeUnit.SECONDS)).isTrue();

        TaskUpdateEvent last = (TaskUpdateEvent) streamEvents.get(streamEvents.size() - 1);
        assertThat(last.getTask().status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(last.getTask().artifacts()).extracting(Artifact::name).contains("result");
      }
    }
  }

  @Test
  void officialClientCancelAndGetTaskFallback() throws Exception {
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    try (A2aTaskService service =
            new A2aTaskService(
                new MemoryA2aTaskStore(),
                (taskId, message, editor) -> {
                  started.countDown();
                  try {
                    release.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                },
                Executors.newVirtualThreadPerTaskExecutor());
        A2aHttpServer server =
            A2aHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0),
                port -> A2aTestSupport.card(A2aTestSupport.baseUrl(port)),
                new A2aJsonRpcHandler(A2aTestSupport.card("http://127.0.0.1:0"), service))) {
      String url = A2aTestSupport.baseUrl(server);
      AgentCard card = A2A.getAgentCard(url);
      Client client =
          Client.builder(card)
              .withTransport(
                  JSONRPCTransport.class, new JSONRPCTransportConfig(A2AHttpClientFactory.create()))
              .build();
      try (client) {
        CountDownLatch sendDone = new CountDownLatch(1);
        AtomicReference<String> taskId = new AtomicReference<>();
        client.sendMessage(
            A2A.toUserMessage("ping"),
            List.of(
                (event, agCard) -> {
                  if (event instanceof TaskEvent taskEvent) {
                    taskId.set(taskEvent.getTask().id());
                    sendDone.countDown();
                  }
                }),
            t -> {
              // 官方约定：流正常结束时错误回调收到 null
              if (t != null) {
                fail("sendMessage 不应有错误: " + t);
              }
            },
            new ClientCallContext(Map.of(), Map.of()));
        assertThat(sendDone.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // spec CancelTaskParams 的 metadata 非空断言（便捷构造器默认 Map.of()）
        Task canceled =
            client.cancelTask(
                new CancelTaskParams(taskId.get()), new ClientCallContext(Map.of(), Map.of()));
        assertThat(canceled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);

        Task got =
            client.getTask(
                new TaskQueryParams(taskId.get(), null, null),
                new ClientCallContext(Map.of(), Map.of()));
        assertThat(got.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
        release.countDown();
      }
    }
  }
}
