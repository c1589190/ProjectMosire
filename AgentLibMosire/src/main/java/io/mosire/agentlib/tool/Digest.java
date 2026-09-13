package io.mosire.agentlib.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>摘要的唯一实现</b>：规范化写法 + SHA-256 截断（前 16 字节 ⇒ {@code sha256:<32 hex>}）。
 *
 * <p><b>为什么必须只有一处</b>：本仓有<b>两个</b>不同用途的摘要——审批摘要（{@link
 * ToolCallAuthorizer#defaultDigest(ToolContext)}， 覆盖<b>全部参数</b>，进 {@code approval.requested}
 * 事件与提示面）与命令落账摘要（{@code ShellTool.ledgerArgs}，现 {@code io.mosire.bash.ShellTool}， 只要 {@code
 * command} 一个字段，进 {@code tool.call} 事件）。两者的<b>输入面不同是有意的</b>（一条命令 + cwd/timeout 的参数集合
 * 大于命令本身），但<b>哈希算法与规范化写法必须共用本类</b>：各写一份 sha256 必然分叉，日后对账会发现"同一条命令两个指纹"。
 *
 * <p><b>规范化写法（长度前缀定界 + 类型标记）</b>：逐项 {@code 键长:键 + 值类型 + 值长:值} 再换行。长度前缀是必需的——值里可能
 * 出现任何字符（空格/冒号/换行），拿定界符本身当地界不可靠；类型标记挡住"渲染成同一串、其实是两回事"的碰撞 （{@code {"a":"1"}} 与 {@code
 * {"a":1}}，{@code {"x":["a","b"]}} 与 {@code {"x":"[a, b]"}} 都曾同摘）。
 *
 * <p><b>对账口径</b>：只带 {@code command} 一个参数的 bash 调用，其审批摘要与落账摘要<b>逐字相同</b> （两者都是 {@link
 * #ofCommand(String)} 的产物）；带 {@code cwd}/{@code timeout} 的调用两者<b>本就不同</b>——见上面"输入面不同"的说明，别把它 当缺陷。
 *
 * <p><b>诚实的边界</b>：sha256 是单向的，但参数里若有<b>低熵</b>内容（短口令、常见短命令），摘要仍可被字典攻击反推——
 * 本类只是"不留明文"的缺省件，不是"内容可安全入摘要"的证明；更不是"加密"。
 */
public final class Digest {

  /** 摘要前缀与长度口径（16 字节 = 32 个十六进制字符）：与 B1 落地的 {@code defaultDigest} 逐字一致。 */
  public static final String PREFIX = "sha256:";

  private static final int HASH_BYTES = 16;

  private Digest() {}

  /**
   * 命令文本的摘要（落账用）：{@code sha256:<32 hex>}。只吃命令本身，不掺任何其它字段。
   *
   * <p>实现走 {@link #ofArguments(Map)} 的单字段形态（{@code {"command": …}}），于是"命令摘要"与"单参数审批摘要"是同一段代码的同一结果。
   */
  public static String ofCommand(String command) {
    return ofArguments(Map.of("command", command));
  }

  /**
   * 参数集合的摘要（审批摘要用）：按参数名<b>排序</b>后规范化再哈希——同参同摘，便于对账。
   *
   * <p>排序是口径的一部分：{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 是同一份参数（Map 无序），必须同摘。
   */
  public static String ofArguments(Map<String, Object> arguments) {
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, Object> argument : new TreeMap<>(arguments).entrySet()) {
      appendCanonical(canonical, argument.getKey(), argument.getValue());
    }
    return sha256(canonical.toString());
  }

  /**
   * 追加一条规范化条目（{@code 键长:键 + 类型标记 + 值长:值 + 换行}）。
   *
   * <p>键有长度前缀 ⇒ 键里出现冒号/换行也不会歧义；类型标记来自闭词表（见 {@link #typeTag(Object)}），不含冒号、不以数字开头 ⇒ 与长度前缀拼接后仍无歧义。
   */
  public static void appendCanonical(StringBuilder out, String key, Object value) {
    String rendered = String.valueOf(value);
    out.append(key.length())
        .append(':')
        .append(key)
        .append(typeTag(value))
        .append(rendered.length())
        .append(':')
        .append(rendered)
        .append('\n');
  }

  /** 规范化串的摘要（{@code sha256:<32 hex>}）。算法不可用时返回"形状"而不是退回明文。 */
  public static String sha256(String canonicalText) {
    byte[] raw = canonicalText.getBytes(StandardCharsets.UTF_8);
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException absent) {
      // 任何标准 JVM 都有 SHA-256；真到这一步也不能退回明文，只能给"形状"
      return "unavailable:len=" + raw.length;
    }
    return PREFIX + HexFormat.of().formatHex(sha256.digest(raw), 0, HASH_BYTES);
  }

  /**
   * 值的<b>类别</b>标记（进规范化串）：挡住"渲染成同一串、其实是两回事"的碰撞。
   *
   * <p>只按<b>类别</b>分（{@code text}/{@code number}/{@code bool}/...）而不是照 {@code
   * getClass().getSimpleName()} 记实现类名：同类别的两种实现（{@code List.of(...)} 与 {@code new
   * ArrayList<>(...)}）是<b>同一个逻辑参数</b>， 记成两种标记会让"同参同摘"这条对账口径碎掉。类别集合是闭词表，所以定界仍然无歧义。
   */
  public static String typeTag(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof CharSequence) {
      return "text";
    }
    if (value instanceof Number) {
      return "number";
    }
    if (value instanceof Boolean) {
      return "bool";
    }
    if (value instanceof Character) {
      return "char";
    }
    if (value instanceof Map<?, ?>) {
      return "map";
    }
    if (value instanceof Collection<?>) {
      return "list";
    }
    return "other";
  }
}
