package io.mosire.docs;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code LOG} 纪律的追加请求（设计 §5.5 的写契约）：只追加、不覆盖，因此这里<b>没有</b> {@code status} 字段——状态恒为 {@link
 * DocStatus#EFFECTIVE}，作废走 {@link DocStore#voidEntry}（它自己追加一条 {@code VOIDED}）。
 *
 * <p><b>{@code ordinal} 也不在这里</b>：序号由存储按日志行序维护（B1）——调用方给序号等于让"同一地址的第 3 条"有两个来源。
 *
 * @param address 调用方定义的地址（合法性在写入侧校验，B6）
 * @param tick 时刻
 * @param tags 可见性轴 1（显式指派；可空）
 * @param affiliations 可见性轴 2（按归属；可空）
 * @param owner 可见性轴 3（单决策人私有；可空 = 无主）
 * @param key 正文的键
 * @param value 正文的值（不透明，原样进出）
 * @param note 备注（可空）
 * @param fields 自定义字段区（不参与身份/可见性/排序）
 * @param at 世界坐标等注入值（可空）
 * @param status 状态（调用方形态恒为 {@link DocStatus#EFFECTIVE}；{@code VOIDED} 只由 {@link DocStore#voidEntry}
 *     内部构造）
 */
public record AppendRequest(
    String address,
    long tick,
    Set<String> tags,
    Set<String> affiliations,
    Optional<String> owner,
    String key,
    Object value,
    Optional<String> note,
    Map<String, Object> fields,
    Optional<String> at,
    DocStatus status) {

  public AppendRequest {
    address = DocAddress.require(address);
    if (tick < 0) {
      throw DocException.invalid("条目 tick 不能为负: " + tick);
    }
    // 内联 Set.copyOf/Map.copyOf：让 SpotBugs 看得见不可变来源（本仓既有写法）
    tags = tags == null ? Set.of() : Set.copyOf(tags);
    affiliations = affiliations == null ? Set.of() : Set.copyOf(affiliations);
    owner = owner == null ? Optional.empty() : owner;
    key = key == null ? "" : key;
    value = value == null ? "" : value;
    note = note == null ? Optional.empty() : note;
    fields = fields == null ? Map.of() : Map.copyOf(fields);
    at = at == null ? Optional.empty() : at;
    status = status == null ? DocStatus.EFFECTIVE : status;
  }

  /**
   * 调用方形态：<b>没有</b> {@code status} 分量——追加永远是 {@link DocStatus#EFFECTIVE}（作废走 {@link
   * DocStore#voidEntry}，它自己造一条 {@code VOIDED} 请求）。
   */
  public AppendRequest(
      String address,
      long tick,
      Set<String> tags,
      Set<String> affiliations,
      Optional<String> owner,
      String key,
      Object value,
      Optional<String> note,
      Map<String, Object> fields,
      Optional<String> at) {
    this(
        address,
        tick,
        tags,
        affiliations,
        owner,
        key,
        value,
        note,
        fields,
        at,
        DocStatus.EFFECTIVE);
  }

  /** 最简形态：只要地址与正文（其余取缺省——无标签、无归属、无主、无备注）。 */
  public static AppendRequest of(String address, long tick, String key, Object value) {
    return new AppendRequest(address, tick, null, null, null, key, value, null, null, null);
  }

  /**
   * 作废请求：与目标条目<b>逐字同载荷</b>、状态换成 {@link DocStatus#VOIDED}（设计 §5.5 的"作废 = 追加一条 {@code
   * VOIDED}，留痕"）。由存储内部使用——作废不是调用方能直接表达的动作（它必须指向一个真实存在的条目）。
   */
  static AppendRequest voided(DocEntry target) {
    return new AppendRequest(
        target.address(),
        target.tick(),
        target.tags(),
        target.affiliations(),
        target.owner(),
        target.key(),
        target.value(),
        target.note(),
        target.fields(),
        target.at(),
        DocStatus.VOIDED);
  }

  /** 造一条本请求描述的条目（{@code ordinal} 由存储按行序给出）。 */
  DocEntry toEntry(long ordinal) {
    return new DocEntry(
        address, tick, ordinal, tags, affiliations, owner, key, value, note, status, fields, at);
  }

  /** 载荷指纹（与 {@link DocEntry#payloadSignature()} 同形，忽略序号）：重放判据的唯一依据。 */
  String payloadSignature() {
    return toEntry(0).payloadSignature();
  }
}
