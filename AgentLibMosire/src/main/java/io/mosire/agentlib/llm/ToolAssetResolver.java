package io.mosire.agentlib.llm;

import java.util.Optional;

/**
 * 资产解析 SPI：{@code assetId → 媒体类型 + 字节}。
 *
 * <p><b>为什么存在</b>：对话历史与消息分片里只许出现<b>引用</b>（{@link ContentPart.Image}），字节由宿主保管——把 base64 塞进
 * 会话库/事件/日志会同时顶爆存储与上下文预算（一张 512² 的 PNG 就是几百 KB）。真正发请求（或回 MCP 结果）的那一刻才需要字节， 于是把"从 id 取字节"这件事留给宿主：LLM
 * 层不认识文件系统、工件库或 TTL 策略。
 *
 * <p>★ <b>解析失败要响亮、不许静默降级</b>：{@link #resolve} 返回 {@link Optional#empty()} 表示"这件资产确实不在"，调用方 （见
 * {@code OpenAICompatibleLlmClient}）据此抛错——悄悄把图片丢掉会让模型在毫无提示的情况下对着残缺上下文作答，比报错难查得多。
 * 想让模型在缺图时降级，请在<b>构造消息之前</b>决定（换成文本说明），而不是让发送侧吞掉。
 */
@FunctionalInterface
public interface ToolAssetResolver {

  /**
   * 取一件资产。
   *
   * @param assetId 资产标识（由宿主发放；不得为 null）
   * @return 资产；{@link Optional#empty()} = 不存在（或已过期）
   */
  Optional<ToolAsset> resolve(String assetId);

  /** 缺省实现：什么都不认（离线单测、不用图片的部署无需任何资产基础设施）。 */
  static ToolAssetResolver none() {
    return assetId -> Optional.empty();
  }
}
