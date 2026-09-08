package io.mosire.main.gateway.a2a;

import com.google.gson.JsonSyntaxException;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome.Single;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.StreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A JSON-RPC 绑定（计划 §5.2.3；D6：官方 spec + jsonrpc-common 纯模块 + 自写绑定——不引 server-common）。
 *
 * <p><b>偏差记录（M2-E 文档）</b>：官方线方法名为 CamelCase（{@code SendMessage/GetTask/...}，取自 {@code A2AMethods}），
 * 非计划里的 小写 {@code message/send} 形态；body 解析/响应序列化用 spec-grpc 的 {@link JSONRPCUtils}（proto 桥接——官方
 * client / TCK 同一套 wire 转换）， 非计划里的 {@code JsonUtil → 密封 A2AMessage 手工分发}。流式事件机制：首个事件为 {@code
 * {"jsonrpc":"2.0","id":<请求id>,"result":{"task":...}}}（规范 §3.1.6 快照），其后为增量 {@code
 * statusUpdate}/{@code artifactUpdate}（官方客户端 {@code ClientTaskManager} 的 One-Task-Then-UpdateEvent
 * 契约；A2aClientRoundTripTest 提供逐字节互操作证明）。
 *
 * <p>处理链（错误映射照官方 {@code A2AServerRoutes.processRequest} 的 catch 链）： parse →（版本协商在方法处理前：缺省 "0.3"， 不符
 * → -32009）→ 分发 → 失败路径：A2AError → 同名错误响应；InvalidParams→-32602；MethodNotFound→-32601（parse 期即抛）；
 * Id（/结构）非法→-32600；JSON 解析失败→-32700；其余→-32603。
 */
public final class A2aJsonRpcHandler {

  private static final Logger LOG = LoggerFactory.getLogger(A2aJsonRpcHandler.class);

  /** 处理结果：非流式 = 单发 JSON 响应；流式 = SSE 事件源。 */
  public sealed interface Outcome permits Outcome.Single, Outcome.Stream {

    /** 单发响应（完整 JSON-RPC 信封串，直接作为响应体）。 */
    record Single(String json) implements Outcome {}

    /** 流式响应：事件源逐个产出 data 行（每个 = 完整 JSON-RPC 信封串），终态/出错后自然结束。 */
    record Stream(StreamSource source) implements Outcome {}

    /** SSE 事件源：{@code sink} 从推送线程消费（须线程安全、廉价——HTTP 层用无界队列承接）。 */
    @FunctionalInterface
    interface StreamSource {
      void run(Consumer<String> sink);
    }
  }

  private final AgentCard agentCard;
  private final A2aTaskService service;

  public A2aJsonRpcHandler(AgentCard agentCard, A2aTaskService service) {
    this.agentCard = java.util.Objects.requireNonNull(agentCard, "agentCard");
    this.service = java.util.Objects.requireNonNull(service, "service");
  }

  /**
   * 处理一次 JSON-RPC 请求体。
   *
   * @param body POST 原始体
   * @param requestedVersion HTTP 头 {@code A2A-Version}（可空）
   */
  public Outcome handle(String body, String requestedVersion) {
    A2ARequest<?> request;
    try {
      request = JSONRPCUtils.parseRequestBody(body, null);
    } catch (A2AError e) {
      return new Single(JSONRPCUtils.toJsonRPCErrorResponse(null, e));
    } catch (InvalidParamsJsonMappingException e) {
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              e.getId(), new InvalidParamsError(null, e.getMessage(), null)));
    } catch (MethodNotFoundJsonMappingException e) {
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              e.getId(), new MethodNotFoundError(null, e.getMessage(), null)));
    } catch (IdJsonMappingException e) {
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              e.getId(), new InvalidRequestError(null, e.getMessage(), null)));
    } catch (JsonMappingException e) {
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              null, new InvalidRequestError(null, e.getMessage(), null)));
    } catch (JsonSyntaxException | JsonProcessingException e) {
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(null, new JSONParseError(e.getMessage())));
    } catch (Throwable t) {
      LOG.error("解析 JSON-RPC 请求失败", t);
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              null, new InternalError("解析请求失败: " + t.getMessage())));
    }

    if (request instanceof StreamingJSONRPCRequest<?> streaming) {
      try {
        return dispatchStreaming(streaming, requestedVersion);
      } catch (A2AError e) {
        // 流式方法内的错误同样走 SSE 单事件（与官方 SseResponseWriter 行为一致）
        return new Stream(
            sink -> sink.accept(JSONRPCUtils.toJsonRPCErrorResponse(request.getId(), e)));
      } catch (Throwable t) {
        LOG.error("流式请求处理失败: {}", request.getMethod(), t);
        return new Stream(
            sink ->
                sink.accept(
                    JSONRPCUtils.toJsonRPCErrorResponse(
                        request.getId(), new InternalError("内部错误: " + t.getMessage()))));
      }
    }
    try {
      return new Single(dispatchNonStreaming(request, requestedVersion));
    } catch (A2AError e) {
      return new Single(JSONRPCUtils.toJsonRPCErrorResponse(request.getId(), e));
    } catch (Throwable t) {
      LOG.error("非流式请求处理失败: {}", request.getMethod(), t);
      return new Single(
          JSONRPCUtils.toJsonRPCErrorResponse(
              request.getId(), new InternalError("内部错误: " + t.getMessage())));
    }
  }

  private String dispatchNonStreaming(A2ARequest<?> request, String requestedVersion) {
    if (request instanceof SendMessageRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      MessageSendParams params = r.getParams();
      Task task = service.sendMessage(params.message());
      return JSONRPCUtils.toJsonRPCResultResponse(
          request.getId(), ProtoUtils.ToProto.taskOrMessage(task));
    }
    if (request instanceof GetTaskRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      return JSONRPCUtils.toJsonRPCResultResponse(
          request.getId(), ProtoUtils.ToProto.task(service.requireTask(r.getParams().id())));
    }
    if (request instanceof CancelTaskRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      return JSONRPCUtils.toJsonRPCResultResponse(
          request.getId(), ProtoUtils.ToProto.task(service.cancelTask(r.getParams().id())));
    }
    if (request instanceof ListTasksRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      return JSONRPCUtils.toJsonRPCResultResponse(
          request.getId(), ProtoUtils.ToProto.listTasksResult(service.listTasks(r.getParams())));
    }
    // P0 未实现的官方方法（推送通知配置、扩展卡等）：明确报“不支持”而非 MethodNotFound
    throw new UnsupportedOperationError(
        null, "Unsupported method: '" + request.getMethod() + "'", null);
  }

  private Outcome dispatchStreaming(StreamingJSONRPCRequest<?> request, String requestedVersion) {
    if (request instanceof SendStreamingMessageRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      MessageSendParams params = r.getParams();
      Task task = service.sendMessage(params.message());
      return streamOf(task.id(), request.getId());
    }
    if (request instanceof SubscribeToTaskRequest r) {
      A2aVersionValidator.validate(agentCard, requestedVersion);
      Task task = service.requireTask(r.getParams().id()); // 不存在：TaskNotFoundError（-32001）
      return streamOf(task.id(), request.getId());
    }
    throw new UnsupportedOperationError(
        null, "Unsupported streaming method: '" + request.getMethod() + "'", null);
  }

  /**
   * 订阅任务至终态，事件格式按规范 §3.1.6 的官方客户端契约（A2aClientRoundTripTest 证明互操作）： **首个事件 = Task
   * 快照**；其后为增量事件——{@code statusUpdate} / {@code artifactUpdate} （官方客户端 {@code ClientTaskManager}
   * 只接受一个 Task 事件，其后靠 UpdateEvent 合并； 且终态 {@code statusUpdate} 必须是末帧——客户端在 final 事件上自动关闭连接）。
   */
  private Outcome streamOf(String taskId, Object requestId) {
    return new Stream(
        sink -> {
          AtomicReference<Task> previous = new AtomicReference<>();
          service.subscribeUntilFinal(
              taskId,
              task -> {
                Task prev = previous.getAndSet(task);
                if (prev == null) {
                  sink.accept(toStreamEnvelope(requestId, task));
                  return;
                }
                // 先增量事件（产物排在状态更新之前，保证终态 statusUpdate 是末帧）
                for (Artifact artifact : newArtifacts(prev, task)) {
                  sink.accept(toArtifactUpdateEnvelope(requestId, task, artifact));
                }
                if (task.status().state() != prev.status().state()) {
                  sink.accept(toStatusUpdateEnvelope(requestId, task));
                }
              });
        });
  }

  /** 任务快照 → 流式事件信封（wire：{"jsonrpc","id","result":{"task":...}}）。 */
  private static String toStreamEnvelope(Object requestId, Task task) {
    return JSONRPCUtils.toJsonRPCResultResponse(requestId, ProtoUtils.ToProto.streamResponse(task));
  }

  /** 状态增量 → wire：{"jsonrpc","id","result":{"statusUpdate":...}}。 */
  private static String toStatusUpdateEnvelope(Object requestId, Task task) {
    TaskStatusUpdateEvent event =
        TaskStatusUpdateEvent.builder()
            .taskId(task.id())
            .contextId(task.contextId())
            .status(task.status())
            .metadata(task.metadata())
            .build();
    return JSONRPCUtils.toJsonRPCResultResponse(
        requestId, ProtoUtils.ToProto.streamResponse(event));
  }

  /**
   * 产物增量 →
   * wire：{"jsonrpc","id","result":{"artifactUpdate":...}}（append/lastChunk=false/true：单块全新产物）。
   */
  private static String toArtifactUpdateEnvelope(Object requestId, Task task, Artifact artifact) {
    TaskArtifactUpdateEvent event =
        TaskArtifactUpdateEvent.builder()
            .taskId(task.id())
            .contextId(task.contextId())
            .artifact(artifact)
            .append(false)
            .lastChunk(true)
            .build();
    return JSONRPCUtils.toJsonRPCResultResponse(
        requestId, ProtoUtils.ToProto.streamResponse(event));
  }

  /** 两快照间新增的产物（快照单调 append-only——尾部切片即可）。 */
  private static List<Artifact> newArtifacts(Task before, Task after) {
    List<Artifact> prev = before.artifacts() == null ? List.of() : before.artifacts();
    List<Artifact> current = after.artifacts() == null ? List.of() : after.artifacts();
    List<Artifact> added = new ArrayList<>();
    for (int i = prev.size(); i < current.size(); i++) {
      added.add(current.get(i));
    }
    return added;
  }
}
