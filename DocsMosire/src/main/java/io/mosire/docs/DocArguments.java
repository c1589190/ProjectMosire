package io.mosire.docs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数 → {@code LOG} 写入请求的翻译（{@code doc_append} 用）。
 *
 * <p><b>为什么单独一个类</b>：参数解析的失败语义（哪一项必填、数组与逗号串都接受、{@code fields} 只许 JSON 可表达的标量）与工具的业务 语义无关，混在 {@link
 * DocsTools} 里会让那七个工具的实现被参数样板淹没。这里也只做<b>翻译</b>：不判纪律、不判权限。
 *
 * <p><b>身份的注入点</b>：{@code tags} 与 {@code owner} 都可以写成特殊取值让宿主填当前身份（{@code "self"}/{@code "@self"}），
 * 因为"给我自己标一个 tag"是最常见的用法，而模型<b>不知道也不该猜</b>自己的实例 id。展开由 {@link DocsTools} 完成（它拿得到 {@code
 * ToolContext}），这里只识别占位符。
 */
final class DocArguments {

  /** "填我自己"的占位符（两种写法都接受：工具面文案里出现的是 / 标记）。 */
  static final Set<String> SELF_PLACEHOLDERS = Set.of("self", "@self");

  private DocArguments() {
    throw new AssertionError("No instances");
  }

  /**
   * 造一条追加请求（{@code mount}/{@code address} 之外的字段全在 {@code arguments} 里）。
   *
   * @param virtualPath 虚拟路径（{@code <挂载名>}；只用来给错误文案定位，不参与请求构造）
   */
  static AppendRequest appendRequest(String virtualPath, Map<String, Object> arguments) {
    Map<String, Object> args = arguments == null ? Map.of() : arguments;
    String address = required(args, "address");
    long tick =
        longValue(args, "tick").orElseThrow(() -> DocException.invalid("tick 为必填参数（时刻是查询与排序的主键）"));
    String key = text(args, "key");
    Object value = args.get("value");
    if (value == null) {
      value = "";
    }
    return new AppendRequest(
        address,
        tick,
        stringSet(args, "tags"),
        stringSet(args, "affiliations"),
        optional(args, "owner"),
        key,
        value,
        optional(args, "note"),
        fields(args, "fields"),
        optional(args, "at"));
  }

  /** 必填字符串（去空白后非空）。 */
  static String required(Map<String, Object> args, String name) {
    String value = text(args, name);
    if (value.isEmpty()) {
      throw DocException.invalid(name + " 为必填参数（非空字符串）");
    }
    return value;
  }

  /** 可选字符串（缺/空 ⇒ {@link java.util.Optional#empty()}）。 */
  static java.util.Optional<String> optional(Map<String, Object> args, String name) {
    String value = text(args, name);
    return value.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(value);
  }

  /** 可选长整数（缺 ⇒ 空；给了但非整数 ⇒ 具名拒）。 */
  static java.util.Optional<Long> longValue(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return java.util.Optional.empty();
    }
    if (raw instanceof Number number) {
      return java.util.Optional.of(number.longValue());
    }
    String value = String.valueOf(raw).strip();
    if (value.isEmpty()) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      throw DocException.invalid(name + " 必须是整数: '" + value + "'");
    }
  }

  /** 可选整数（缺 ⇒ 空；给了但非整数 ⇒ 具名拒）。 */
  static java.util.Optional<Integer> intValue(Map<String, Object> args, String name) {
    return longValue(args, name)
        .map(
            value -> {
              if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                throw DocException.invalid(name + " 超出整数范围: " + value);
              }
              return value.intValue();
            });
  }

  /** 文本参数（按 {@code String.valueOf} 归一；缺 ⇒ 空串）。 */
  static String text(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    return raw == null ? "" : String.valueOf(raw).strip();
  }

  /** 字符串数组参数：接受数组/列表，也接受逗号分隔的单字符串（模型两种都会给）。 */
  static Set<String> stringSet(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Set.of();
    }
    List<String> items = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          items.add(String.valueOf(item).strip());
        }
      }
    } else if (raw instanceof String text) {
      for (String piece : text.split(",")) {
        items.add(piece.strip());
      }
    } else {
      throw DocException.invalid(name + " 必须是字符串数组（或逗号分隔的字符串）: " + raw);
    }
    Set<String> values = new LinkedHashSet<>();
    for (String item : items) {
      if (!item.isEmpty()) {
        values.add(item);
      }
    }
    return values;
  }

  /**
   * {@code fields}：字符串键的对象；<b>只允许 JSON 可表达的标量/小对象</b>（设计 §5.4 的"标量 + JSON 可表达的小对象"）。
   *
   * <p>拒绝"任意 Java 对象"是有意的：{@code fields} 要落成 NDJSON 的一行，不可表达的值会在写盘时失败——那时错误发生在 IO 层，
   * 而调用方以为它已经成功了。这里在<b>转换期</b>就拒，错误码也正好是 {@code INVALID_ARGUMENTS}。
   */
  static Map<String, Object> fields(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw DocException.invalid(name + " 必须是对象（字符串键）: " + raw);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
      if (key.isBlank()) {
        throw DocException.invalid(name + " 里有空键（空键在 JSON 里合法但在文档里没有意义）");
      }
      result.put(key, scalar(name, entry.getValue()));
    }
    return result;
  }

  private static Object scalar(String name, Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Number) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> nested = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        nested.put(String.valueOf(entry.getKey()), scalar(name, entry.getValue()));
      }
      return nested;
    }
    if (value instanceof List<?> list) {
      List<Object> items = new ArrayList<>(list.size());
      for (Object item : list) {
        items.add(scalar(name, item));
      }
      return items;
    }
    throw DocException.invalid(
        name + " 里有不能落成 JSON 的值（只接受标量/列表/字符串键对象）: " + value.getClass().getName());
  }
}
