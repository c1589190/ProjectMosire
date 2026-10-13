package io.mosire.docs;

import java.util.Optional;

/**
 * 一处坏数据/读失败的披露（设计 §5.3："坏行/坏文件<b>不静默</b>（进 {@code broken} 披露并继续）"）。
 *
 * <p><b>为什么不抛</b>：一条坏行不该让整个地址读不出来——那会把"数据有一点脏"升级成"文档全没了"。也不能静默跳过：静默跳过等于<b>篡改</b> 读者看到的历史。折中是"继续读 +
 * 把它列出来"。
 *
 * <p><b>面向模型只出虚拟地址</b>（A7）：{@link #where()} 是虚拟路径或地址，{@link #physical()} 只进日志——它是"给人修数据"用的，不是给模型看的。
 *
 * @param where 出问题的位置（{@code FILE} 纪律 = 虚拟路径；{@code LOG} 纪律 = 地址 + 行号）
 * @param line 行号（1 起；{@code LOG} 为日志行号，{@code FILE} 为文件行号；未知写 0）
 * @param reason 人可读的原因（不含物理路径）
 * @param physical 物理落点（<b>仅用于日志</b>；可为空）
 */
public record BrokenDoc(String where, long line, String reason, Optional<String> physical) {

  public BrokenDoc {
    where = where == null ? "" : where;
    reason = reason == null ? "" : reason;
    physical = physical == null ? Optional.empty() : physical;
  }

  /** 面向模型的一行渲染（不含物理路径）。 */
  public String label() {
    return line > 0 ? where + ":" + line + "（" + reason + "）" : where + "（" + reason + "）";
  }
}
