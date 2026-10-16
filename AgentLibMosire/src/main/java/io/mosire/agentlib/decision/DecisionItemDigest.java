package io.mosire.agentlib.decision;

import io.mosire.agentlib.tool.Digest;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 决策包条目的两个<b>派生件</b>：{@link #argsDigest}（脱敏摘要，进事件面）与 {@link #itemSummary}（人读一行，进提示面）。
 *
 * <p><b>为什么住在 AgentLib 的包层</b>（设计 §5 裁定 6，{@code 设计-决策包作为唯一决策单元.md}）：显式提议口 {@code decision_propose}
 * 已移除，建包改由宿主（P1 的待裁决队列 / 回合收尾）直接调 {@link DecisionPackages}。而"条目摘要怎么算"是<b>包层的口径</b>、
 * 不是某个工具的私有实现——唯一实现点留在这里，免得每个建包点各写一份（分叉之后对账会发现"同一条调用两个摘要"）。 本类不认识任何领域（不知道 doc / skill / bash 是什么）：
 * 只吃 {@code toolName} 与 {@code args} 明文，因此它属于 AgentLib，不属于 Brain。
 *
 * <p><b>与审批摘要同源</b>：哈希算法与十六进制截断口径复用 {@link Digest}（同一个 SHA-256、同一种小写十六进制），
 * 所以"决策包条目的参数摘要"与"审批面的参数摘要"不会出现两种指纹算法。规范化写法<b>不同是有意的</b>：本类用规范化 JSON（键按字典序、无空白、 数字统一拼写），{@link
 * Digest#ofArguments} 用长度前缀定界；两者各自稳定，合并会改掉已落库/已对账的摘要值。
 *
 * <p><b>★ 密钥纪律（设计 §5.5）</b>：{@code argsDigest} 只产出 {@value #DIGEST_HEX_CHARS} 个十六进制字符； {@code
 * itemSummary} 是从 {@code args} 派生的人读一行，只属于<b>提示面</b>（决策面详情、上级判定提示），<b>不进</b>事件面—— 事件面只带 {@code
 * argsDigest}。参数明文本身只活在包内 {@link PackageItem#args()} 与提交口那条路上。
 */
public final class DecisionItemDigest {

  /** 参数摘要长度（十六进制字符数 = 6 字节）。为什么不是全长：它进提示面/事件面，短摘要足够对账且不占地方。 */
  public static final int DIGEST_HEX_CHARS = 12;

  /** 条目人读摘要里单个参数值的字符上限（一行提示，不是全文：全文在决策面详情与上级判定提示里，那两处不截断）。 */
  public static final int SUMMARY_VALUE_CHARS = 60;

  /** 条目人读摘要里最多露几个参数（越靠前的键越优先，见 {@link #SUMMARY_KEY_ORDER}）。 */
  public static final int SUMMARY_MAX_FIELDS = 4;

  /**
   * 摘要取键的优先级：靠前的键先露出来。命中本仓真实工具的常用键——文档面 {@code mount/ops}、技能面 {@code name/description/body}、命令面
   * {@code command}、检索面 {@code query/pattern}。
   *
   * <p><b>为什么不 import 各工具类的常量</b>：本类要能给<b>任何</b>工具的条目派生摘要（含 MCP 桥进来的、插件带来的），而"哪个键最像目标"是显示口径、
   * 不是契约；把某个模块的常量引进来，只会让下一次新增工具时这里静默失配。认不出的键走字典序兜底（见 {@link #itemSummary}）。
   */
  private static final List<String> SUMMARY_KEY_ORDER =
      List.of(
          "mount",
          "ops",
          "path",
          "name",
          "description",
          "body",
          "command",
          "query",
          "pattern",
          "text",
          "address",
          "tool");

  private DecisionItemDigest() {}

  /**
   * 参数摘要：<b>规范化 JSON</b> 的 SHA-256，取前 {@value #DIGEST_HEX_CHARS} 个十六进制字符（小写）。
   *
   * <p><b>为什么"规范化"是必需的</b>：同一个参数对象在两次调用里可能由不同的 Map 实现承载（插入序不同），直接对 {@code toString()}
   * 取哈希会让"同一份参数"得到两个摘要——摘要就对不上账了。规范化 = 键按字典序、无空白、数字统一拼写（见 {@link #number(Number)}）。
   *
   * <p>规范化 JSON 由本类自己写（不引新依赖、也不用 Jackson 的默认序列化）：默认序列化保持插入序，恰是这里要消除的东西。
   *
   * @param args 调用参数明文（只用来派生摘要；本方法不打印、不外传）
   * @return {@value #DIGEST_HEX_CHARS} 个小写十六进制字符
   */
  public static String argsDigest(Map<String, Object> args) {
    Objects.requireNonNull(args, "args");
    return hexDigest(json(args));
  }

  /**
   * 任意规范化串的短摘要：SHA-256 的前 {@value #DIGEST_HEX_CHARS} 个十六进制字符（小写）。
   *
   * <p><b>为什么公开</b>：包级摘要是"对包内逐条已脱敏摘要拼出来的规范化串"再取一次短摘要（{@code DecisionApprovalBridge#packageDigest}），
   * 它与 {@link #argsDigest} 必须<b>同一口径</b>——两侧各写一段"截前 N 个 hex"的循环就是两处真相（本轮顺手收掉的历史漂移）。
   *
   * <p>哈希算法与截断复用 {@link Digest#sha256(String)}（与审批摘要同源）；SHA-256 在标准 JVM 上必然可用，不可用 ⇒ 响亮失败， 绝不退回明文。
   *
   * @param canonicalMaterial 已规范化的输入串（同输入必同输出）
   * @return {@value #DIGEST_HEX_CHARS} 个小写十六进制字符
   */
  public static String hexDigest(String canonicalMaterial) {
    Objects.requireNonNull(canonicalMaterial, "canonicalMaterial");
    String full = Digest.sha256(canonicalMaterial);
    if (!full.startsWith(Digest.PREFIX)
        || full.length() < Digest.PREFIX.length() + DIGEST_HEX_CHARS) {
      // Digest 在算法不可用时返回"形状"（unavailable:len=N）而不是明文；这里按契约违规响亮失败
      throw new IllegalStateException("当前 JVM 不支持 SHA-256：" + full);
    }
    return full.substring(Digest.PREFIX.length(), Digest.PREFIX.length() + DIGEST_HEX_CHARS);
  }

  /**
   * 条目<b>人读摘要</b>（设计 §5.1 的 {@code summary(人读)}）：从 {@code args} 派生一行"这条要干什么"。
   *
   * <p><b>为什么不能是 {@code 工具名 + 摘要}</b>：那是旧写法，跑出来是 {@code doc_edit 9f2ab41c}——它既不是人读的，也答不出"要改什么"。
   * 决策人（人）与上级 Agent（一个 LLM）都要靠这一行决定批不批，所以它必须从 {@code args} 派生。
   *
   * <p><b>派生不出来就退回工具名，不许编</b>：没有参数 ⇒ 只回工具名；参数值全是空白 ⇒ 同样只回工具名。
   * 这里<b>不</b>猜测意图、不补默认值、不把摘要当成"内容"——完整内容在决策面详情（明文 {@code args}）与上级判定提示里，本行只是提示。单值超过 {@link
   * #SUMMARY_VALUE_CHARS} 会带 {@code …} 截断（截断只在<b>这一行摘要</b>里发生， 且看得见；判定提示与详情面都不截断）。
   *
   * <p>取键顺序见 {@link #SUMMARY_KEY_ORDER}，其余键按字典序——同一个 {@code args} 永远得到同一行摘要。
   *
   * @param toolName 工具名（工具目录里那个真名）
   * @param args 该条调用的参数明文（只用来派生这一行；本方法不打印、不外传）
   * @return 人读一行（派不出来 ⇒ 纯工具名）
   */
  public static String itemSummary(String toolName, Map<String, Object> args) {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(args, "args");
    if (args.isEmpty()) {
      return toolName;
    }
    List<String> parts = new ArrayList<>(SUMMARY_MAX_FIELDS);
    for (String key : summaryKeys(args)) {
      if (parts.size() >= SUMMARY_MAX_FIELDS) {
        break;
      }
      String rendered = renderValue(args.get(key));
      if (!rendered.isEmpty()) {
        parts.add(key + "=" + rendered);
      }
    }
    return parts.isEmpty() ? toolName : toolName + " " + String.join(" ", parts);
  }

  /** 摘要取键顺序：优先键在前（{@link #SUMMARY_KEY_ORDER}），其余按键名字典序（抹掉 {@code Map.copyOf} 的迭代序差异）。 */
  private static List<String> summaryKeys(Map<String, Object> args) {
    List<String> ordered = new ArrayList<>(args.size());
    for (String key : SUMMARY_KEY_ORDER) {
      if (args.containsKey(key)) {
        ordered.add(key);
      }
    }
    List<String> rest = new ArrayList<>();
    for (String key : args.keySet()) {
      if (!SUMMARY_KEY_ORDER.contains(key)) {
        rest.add(key);
      }
    }
    Collections.sort(rest);
    ordered.addAll(rest);
    return ordered;
  }

  /** 单个参数值的渲染：字符串压成一行并截断；布尔/数字原样；容器给条数（+ 首个元素的一小段预览）。 空白值渲染成空串（调用方据此跳过这个键——"没给值"不该占一行摘要）。 */
  private static String renderValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String text) {
      return clip(oneLine(text));
    }
    if (value instanceof Boolean || value instanceof Number) {
      return String.valueOf(value);
    }
    if (value instanceof Map<?, ?> map) {
      return preview(map);
    }
    if (value instanceof Collection<?> collection) {
      return containerPreview(collection.size(), collection.isEmpty() ? null : first(collection));
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      return containerPreview(length, length == 0 ? null : Array.get(value, 0));
    }
    return clip(oneLine(String.valueOf(value)));
  }

  /** 容器首个元素（{@link Collection} 没有 {@code get(0)}，用迭代器取头一个）。 */
  private static Object first(Collection<?> collection) {
    return collection.iterator().next();
  }

  /** 容器预览：条数 + 首个元素的一小段（{@code ops=[{...}]} 这类嵌套列表因此也读得出来）。 */
  private static String containerPreview(int size, Object head) {
    if (size <= 0) {
      return "";
    }
    String rendered = preview(head);
    return size + " 项" + (rendered.isEmpty() ? "" : "（" + rendered + "）");
  }

  /**
   * 嵌套值预览：map 按 {@link #summaryKeys} 序露最多 {@link #SUMMARY_MAX_FIELDS} 项；其余值同 {@link #renderValue}。
   */
  private static String preview(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return renderValue(value);
    }
    Map<String, Object> stringKeyed = stringKeyed(map);
    List<String> parts = new ArrayList<>(SUMMARY_MAX_FIELDS);
    for (String key : summaryKeys(stringKeyed)) {
      if (parts.size() >= SUMMARY_MAX_FIELDS) {
        break;
      }
      String rendered = renderValue(stringKeyed.get(key));
      if (!rendered.isEmpty()) {
        parts.add(key + "=" + rendered);
      }
    }
    return String.join(" ", parts);
  }

  /** 压成一行（摘要是一行提示；命令/正文里的换行不该把提示面撑成多行）。 */
  private static String oneLine(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /** 单值截断（只用于这一行摘要）：超过 ⇒ 前 {@value #SUMMARY_VALUE_CHARS} 字 + {@code …}。 */
  private static String clip(String text) {
    return text.length() > SUMMARY_VALUE_CHARS
        ? text.substring(0, SUMMARY_VALUE_CHARS) + "…"
        : text;
  }

  /** 把任意 Map 的键收成字符串（只做展示，不承担判定）。 */
  private static Map<String, Object> stringKeyed(Map<?, ?> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  /** 规范化 JSON（键按字典序、无空白）——摘要的输入面，唯一序列化器，不各写一套。 */
  private static String json(Object value) {
    StringBuilder out = new StringBuilder();
    writeJson(value, out);
    return out.toString();
  }

  private static void writeJson(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String text) {
      writeString(text, out);
      return;
    }
    if (value instanceof Boolean flag) {
      out.append(flag.booleanValue());
      return;
    }
    if (value instanceof Number numeric) {
      out.append(number(numeric));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      writeObject(map, out);
      return;
    }
    if (value instanceof Collection<?> collection) {
      writeArray(collection, out);
      return;
    }
    if (value.getClass().isArray()) {
      out.append('[');
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        if (i > 0) {
          out.append(',');
        }
        writeJson(Array.get(value, i), out);
      }
      out.append(']');
      return;
    }
    // 兜底：非 JSON 类型（理论上到不了——参数来自 JSON 解析）按其文本形式当字符串，保证"同一个值同一个拼写"
    writeString(String.valueOf(value), out);
  }

  /** 对象：键按字典序（规范化的核心一步），值递归。 */
  private static void writeObject(Map<?, ?> map, StringBuilder out) {
    TreeMap<String, Object> sorted = new TreeMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      sorted.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : sorted.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(entry.getKey(), out);
      out.append(':');
      writeJson(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeArray(Collection<?> collection, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object element : collection) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeJson(element, out);
    }
    out.append(']');
  }

  /**
   * 数字的统一拼写：整数族原样 {@code toString}；浮点族去掉科学计数法与尾随零 （{@code 1.0} 与 {@code 1} 都写成 {@code
   * 1}）——规范化要求"相等的值同一个拼写"。
   *
   * <p>{@code NaN}/{@code Infinity} 不是合法 JSON 数字：这里保留其字面量（摘要只需稳定），而它们<b>不可能</b>进响应面。
   */
  private static String number(Number value) {
    if (value instanceof Double || value instanceof Float) {
      double raw = value.doubleValue();
      if (Double.isNaN(raw) || Double.isInfinite(raw)) {
        return value.toString();
      }
      return BigDecimal.valueOf(raw).stripTrailingZeros().toPlainString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().toPlainString();
    }
    return value.toString();
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            String hex = Integer.toHexString(c);
            out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
