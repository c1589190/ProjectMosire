package io.mosire.docs;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code LOG} 纪律的一条文档条目（设计 §5.4，字段与顺序照抄）。
 *
 * <p><b>条目是不可变的</b>：{@code LOG} 上没有"改一条"这个动作——{@link #withStatus(DocStatus)}
 * 产出的是<b>新条目</b>，它会被<b>追加</b> 成一条新记录（作废语义），磁盘上的原记录一个字都不动。
 *
 * <p><b>身份与有序列</b>：{@link #address()} 由调用方定义，{@link #ordinal()} 由存储按日志行序维护，两者唯一决定 {@link #id()}。
 * 条目内部<b>不再存 id</b> ——派生值存两份就有了两个可能不一致的来源（B1：身份拼法各只有一处）。
 *
 * <p><b>可见性三轴</b>：{@link #owner()}（单决策人私有）、{@link #tags()}（显式指派）、{@link #affiliations()}（按归属）。
 * 判据只有一处：{@link DocVisibility#canSee}。
 *
 * <p><b>{@link #fields()} 不参与身份、可见性、排序</b>（隔壁 R-C4）：它是"自定义字段区"，只负责原样进原样出。
 *
 * @param address 调用方定义的地址（不透明；合法性见 {@link DocAddress}）
 * @param tick 时刻（核心；查询与排序的主键，降序）
 * @param ordinal 有序列（存储维护；同名地址内从 0 起）
 * @param tags 可见性轴 1：显式指派（命中调用者身份键即可见）
 * @param affiliations 可见性轴 2：按归属（与调用者的归属集合求交）
 * @param owner 可见性轴 3：单决策人私有（空 = 无主，<b>不是</b>"大家都能看"，fail-closed）
 * @param key 正文的键（{@code value} 的解释权归调用方，Docs 不解析）
 * @param value 正文的值（不透明：不解析、不改写，原样进原样出）
 * @param note 备注（可空）
 * @param status 状态（{@code EFFECTIVE}/{@code VOIDED}）
 * @param fields 自定义字段区（标量 + JSON 可表达的小对象；<b>不参与</b>身份/可见性/排序）
 * @param at 世界坐标等注入值（可空；读回原样）
 */
public record DocEntry(
    String address,
    long tick,
    long ordinal,
    Set<String> tags,
    Set<String> affiliations,
    Optional<String> owner,
    String key,
    Object value,
    Optional<String> note,
    DocStatus status,
    Map<String, Object> fields,
    Optional<String> at) {

  public DocEntry {
    address = DocAddress.require(address);
    if (tick < 0) {
      throw DocException.invalid("条目 tick 不能为负: " + tick);
    }
    if (ordinal < 0) {
      throw DocException.invalid("条目序号不能为负: " + ordinal);
    }
    // 内联 Set.copyOf/Map.copyOf：让 SpotBugs 看得见不可变来源（本仓既有写法）
    tags = tags == null ? Set.of() : Set.copyOf(tags);
    affiliations = affiliations == null ? Set.of() : Set.copyOf(affiliations);
    owner = owner == null ? Optional.empty() : owner;
    key = key == null ? "" : key;
    value = value == null ? "" : value;
    note = note == null ? Optional.empty() : note;
    Objects.requireNonNull(status, "status");
    fields = fields == null ? Map.of() : Map.copyOf(new TreeMap<>(fields));
    at = at == null ? Optional.empty() : at;
  }

  /** 条目身份：{@code <地址>#<序号>}（Docs 派生，B1）。 */
  public DocEntryId id() {
    return new DocEntryId(address, ordinal);
  }

  /** 状态替身（作废 = 用它追加一条新记录，原记录不动）。 */
  public DocEntry withStatus(DocStatus newStatus) {
    return new DocEntry(
        address, tick, ordinal, tags, affiliations, owner, key, value, note, newStatus, fields, at);
  }

  /** 序号替身（存储按行序定序号时用；其余分量逐字不变）。 */
  DocEntry withOrdinal(long newOrdinal) {
    return new DocEntry(
        address, tick, newOrdinal, tags, affiliations, owner, key, value, note, status, fields, at);
  }

  /**
   * 载荷指纹的<b>规范化串</b>（不是哈希）：{@code id} 之外的分量全部进去——同地址 + 同载荷 ⇒ 同 id（可重放）， 靠的就是逐字比较这个串。
   *
   * <p><b>为什么手写而不是 {@code toString()}</b>：{@code Map}/{@code Set} 的 {@code toString()} 不保证跨 JVM
   * 运行的遍历顺序， 拿它当"同载荷"的判据会让重放时而命中时而落空。这里按键/按元素排序后拼，结果只取决于内容。
   *
   * <p><b>{@link #ordinal()} 不在串里</b>（它正是被推导的那一项）；{@code status} 在串里（作废记录与生效记录不是同一份载荷）。
   */
  public String payloadSignature() {
    StringBuilder text = new StringBuilder();
    text.append("tick=").append(tick);
    text.append("|tags=").append(sorted(tags));
    text.append("|affiliations=").append(sorted(affiliations));
    text.append("|owner=").append(owner.orElse(""));
    text.append("|key=").append(key);
    text.append("|value=").append(value);
    text.append("|note=").append(note.orElse(""));
    text.append("|status=").append(status);
    text.append("|fields=").append(fieldsSignature(fields));
    text.append("|at=").append(at.orElse(""));
    return text.toString();
  }

  private static String sorted(Set<String> values) {
    return String.join(",", new java.util.TreeSet<>(values));
  }

  private static String fieldsSignature(Map<String, Object> fields) {
    StringBuilder text = new StringBuilder("{");
    new TreeMap<>(fields)
        .forEach(
            (key, value) -> {
              if (text.length() > 1) {
                text.append(',');
              }
              text.append(key).append('=').append(value);
            });
    return text.append('}').toString();
  }
}
