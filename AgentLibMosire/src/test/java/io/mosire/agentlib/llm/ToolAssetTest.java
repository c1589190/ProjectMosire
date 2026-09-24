package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolAsset} 的两条硬性质：<b>所有权</b>（两个盘边都克隆）与<b>内容等值</b>（record 对数组的默认等值是引用比较，
 * 若不显式实现，"同一张图的两份字节"会被判成不同——缓存与断言都会骗人）。
 */
class ToolAssetTest {

  @Test
  void rejectsMalformedAssets() {
    assertThatThrownBy(() -> new ToolAsset("  ", new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mediaType");
    assertThatThrownBy(() -> new ToolAsset("image/png", new byte[0]))
        .as("空字节说明上游解析出了错，不该被当成一张 0 字节的图静默发出去")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("字节");
    assertThatThrownBy(() -> new ToolAsset(null, new byte[] {1}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolAsset("image/png", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void copiesBytesOnBothEdges() {
    byte[] source = {1, 2, 3};
    ToolAsset asset = new ToolAsset("image/png", source);

    source[0] = 99; // 调用方改原数组：不该动到资产手里的那份
    assertThat(asset.bytes()).containsExactly(1, 2, 3);

    byte[] handedOut = asset.bytes();
    handedOut[0] = 42; // 调用方改取回的数组：同样不该动到资产
    assertThat(asset.bytes()).containsExactly(1, 2, 3);
  }

  @Test
  void equalsAndHashCodeCompareContentNotReferences() {
    ToolAsset first = new ToolAsset("image/png", new byte[] {1, 2, 3});
    ToolAsset sameContent = new ToolAsset("image/png", new byte[] {1, 2, 3});
    ToolAsset otherBytes = new ToolAsset("image/png", new byte[] {9});
    ToolAsset otherType = new ToolAsset("image/jpeg", new byte[] {1, 2, 3});

    assertThat(first).isEqualTo(sameContent).hasSameHashCodeAs(sameContent);
    assertThat(first).isNotEqualTo(otherBytes).isNotEqualTo(otherType);
  }
}
