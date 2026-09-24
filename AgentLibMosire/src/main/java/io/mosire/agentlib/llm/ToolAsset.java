package io.mosire.agentlib.llm;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一件可被 LLM 请求引用的二进制资产（图像等）：媒体类型 + 原始字节。
 *
 * <p>它出现在"分片只带引用、字节由宿主保管"的链路上（见 {@link ContentPart.Image} 与 {@link ToolAssetResolver}）： LLM
 * 层不认识文件系统/工件库，只认识"给我 id、还我字节"。
 *
 * <p>★ <b>两个盘边都不许越</b>：构造期克隆入参、访问器克隆出参——这个类持有的是原始字节，调用方（或本类）任何一方改数组都会
 * 悄悄改掉另一端手里的"同一份数据"。克隆是这里唯一说得清所有权的方式（代价是一次拷贝；资产是 KB 级、每次调用一次）。
 *
 * <p>★ <b>record 的等值语义对数组天然失效</b>（{@code byte[]} 比的是引用），所以本类显式给出基于内容的 {@link #equals}/{@link
 * #hashCode}，否则"同一张图的两份字节"在断言与缓存里会被判成不同。
 */
public record ToolAsset(String mediaType, byte[] bytes) {

  public ToolAsset {
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(bytes, "bytes");
    if (mediaType.isBlank()) {
      throw new IllegalArgumentException("资产的 mediaType 不得为空白");
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("资产的字节不得为空（空资产说明上游解析出了错，不该静默当成 0 字节图）");
    }
    bytes = bytes.clone();
  }

  /** 字节的<b>克隆</b>（调用方改不动本对象手里的那份）。 */
  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  /** 字节数（不克隆，供日志/预算判断用）。 */
  public int size() {
    return bytes.length;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof ToolAsset asset
        && mediaType.equals(asset.mediaType)
        && Arrays.equals(bytes, asset.bytes);
  }

  @Override
  public int hashCode() {
    return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "ToolAsset[" + mediaType + ", " + bytes.length + " 字节]";
  }
}
