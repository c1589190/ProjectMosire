package io.mosire.agentlib.llm;

import java.util.Map;
import java.util.Objects;

/**
 * LLM 消息内容分片——封闭代数类型。
 *
 * <p>与 LLM 供应商消息结构对齐的最小公倍数：文本、工具调用请求、工具调用结果（对模型而言 工具调用的发出与回填都是消息内容的一部分）、 图片（引用式，见 {@link Image}）。
 */
public sealed interface ContentPart
    permits ContentPart.Text, ContentPart.ToolCall, ContentPart.ToolResult, ContentPart.Image {

  /** 纯文本分片。 */
  record Text(String text) implements ContentPart {
    public Text {
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * 图片分片——<b>引用式</b>：只声明"哪个媒体类型 + 哪件资产"，<b>不带字节</b>。
   *
   * <p>★ <b>为什么字节不进分片</b>：分片会落会话库（{@code SqliteConversationStore}）、进事件与日志、被反复回放；一张几百 KB 的 PNG
   * 一旦内联进来，存储与上下文预算都会被顶爆。真正的字节在<b>发送那一刻</b>由宿主经 {@link ToolAssetResolver} 按 {@code assetId}
   * 解析（{@code OpenAICompatibleLlmClient} 负责解析 + 拼 data-URI）。
   *
   * <p>★ <b>解析不到要响亮</b>（{@link ToolAssetResolver} 的类注）：发送侧不静默丢图。若调用方想"缺图时给模型一句文本说明"，请在
   * <b>构造消息之前</b>按引用是否可解析来决定，而不是把一张解析不了的图放进消息里。
   *
   * <p>★ <b>放哪条消息</b>：图片只对 {@code user}/{@code assistant} 角色合法（OpenAI 兼容线的 {@code content} 数组）；
   * {@code tool}/{@code system} 角色带图属调用方编程错误，发送侧响亮拒绝（见 {@code
   * OpenAICompatibleLlmClient#appendMessage}）。
   *
   * @param mediaType 媒体类型，必须是 {@code image/*}（如 {@code image/png}）
   * @param assetId 资产标识（宿主发放、可解析；不得为空白）
   */
  record Image(String mediaType, String assetId) implements ContentPart {
    public Image {
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(assetId, "assetId");
      if (!mediaType.startsWith("image/")) {
        throw new IllegalArgumentException("图片分片的 mediaType 必须是 image/*: " + mediaType);
      }
      if (assetId.isBlank()) {
        throw new IllegalArgumentException("图片分片的 assetId 不得为空白（字节走引用解析，见 ToolAssetResolver）");
      }
    }
  }

  /** 模型发起的工具调用请求；{@code arguments} 为 JSON 对象应反序列化成的 Map。 */
  record ToolCall(String id, String name, Map<String, Object> arguments) implements ContentPart {
    public ToolCall {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(arguments, "arguments");
      // 立即不可变快照；参数 Map 中不允许 null 值（与 MCP 适配层 Map.copyOf 语义一致）
      arguments = Map.copyOf(arguments);
    }
  }

  /** 工具执行结果回填；{@code content} 与 {@code error} 至多一个非空——有 {@code error} 时按失败回填。 */
  record ToolResult(String toolCallId, String name, String content, String error)
      implements ContentPart {
    public ToolResult {
      Objects.requireNonNull(toolCallId, "toolCallId");
      Objects.requireNonNull(name, "name");
      if ((content == null) == (error == null)) {
        throw new IllegalArgumentException("content 与 error 必须且只能有一个非空");
      }
    }

    public boolean isError() {
      return error != null;
    }
  }
}
