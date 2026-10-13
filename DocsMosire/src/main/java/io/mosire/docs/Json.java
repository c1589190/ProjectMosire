package io.mosire.docs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>内部</b>极简 JSON 编解码（{@code LOG} 日志的 NDJSON 行）。
 *
 * <p><b>为什么不用 Jackson</b>：本模块的两条依赖是刻意的（{@code agentlib} 通用件 + {@code workspace} 解析点）。日志行要承载的只有 "字符串
 * / 数字 / 布尔 / null / 列表 / 字符串键对象"这几样——为它引入一个 JSON 库，会让 Docs 的依赖面多出一个"可以顺手解析别的东西"的入口。 这里实现的编解码器<b>只认
 * JSON 的语法</b>、不做 schema 校验，遇到不合法输入一律抛（由调用方转成"坏行披露"）。
 *
 * <p><b>诚实边界</b>：这里的 JSON 支持是<b>够用即可</b>的（无 {@code \uD83D\uDE00} 代理对以外的特殊处理、不做数字精度以外的 __
 * 语义）；它的唯一用途是 "把一条不可变记录原样写进去、原样读回来"。任何"用这个解析器处理外部 JSON"的想法都是误用——那不是它的目的，也不是它的保证。
 */
final class Json {

  private static final String NULL = "null";

  private Json() {
    throw new AssertionError("No instances");
  }

  /** 编码一个值（字符串 / 数字 / 布尔 / null / 列表 / 字符串键对象）。 */
  static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  /** 解码一个值；空串 ⇒ {@code null}。 */
  static Object decode(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    Parser parser = new Parser(trimmed);
    Object value = parser.readValue();
    parser.requireEnd();
    return value;
  }

  /** 把 {@code value} 读成 Map（不是对象 ⇒ 抛）。 */
  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("不是 JSON 对象: " + value);
    }
    return (Map<String, Object>) map;
  }

  /** 取对象里的字段（缺 ⇒ {@code fallback}）。 */
  static Object field(Map<String, Object> object, String key, Object fallback) {
    Object value = object.get(key);
    return value == null ? fallback : value;
  }

  /** 取字符串字段（非字符串/缺 ⇒ 空串）。 */
  static String string(Map<String, Object> object, String key) {
    Object value = object.get(key);
    return value instanceof String text ? text : "";
  }

  /** 取长整数字段（非数字/缺 ⇒ {@code fallback}）。 */
  static long number(Map<String, Object> object, String key, long fallback) {
    Object value = object.get(key);
    return value instanceof Number n ? n.longValue() : fallback;
  }

  /** 取字符串列表字段（非列表 ⇒ 空表；元素按 {@code String.valueOf} 归一）。 */
  static List<String> strings(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return result;
  }

  /**
   * 把解码出来的嵌套结构归一到"只含 {@code String} 键、不可变、可安全进 record"的形态（{@code LOG} 的 {@code fields} 用）。
   *
   * <p>嵌套对象在 JSON 里可能有非字符串键吗？不能（JSON 的键只能是字符串）——但<b>解码产物</b>的静态类型是 {@code Map<Object, Object>}，直接塞进
   * {@code Map<String, Object>} 字段会变成一处未检查强转。这里显式重建，顺带把每个容器都变成不可变快照（ 读回的条目与"刚写进去的"在不可变性上必须一致）。
   */
  static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        result.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
      }
      return Map.copyOf(result);
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) {
        result.add(normalize(item));
      }
      return List.copyOf(result);
    }
    return value;
  }

  private static void write(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append(NULL);
      case String text -> writeString(text, out);
      case Boolean flag -> out.append(flag);
      case Double number -> {
        requireFinite(number);
        out.append(number);
      }
      case Float number -> {
        requireFinite(number);
        out.append(number);
      }
      case Number number -> out.append(number);
      case Map<?, ?> map -> writeObject(map, out);
      case Iterable<?> items -> writeArray(items, out);
      default -> writeString(String.valueOf(value), out);
    }
  }

  /**
   * {@code NaN}/{@code ±Infinity} 不是 JSON 值：静默写成 {@code null} 会让"原样进出"变成谎言（读回来是另一个东西）。
   * 这里响亮拒——调用方要么换成可表达的标量，要么把它放进 {@code value}（那是字符串面）。
   */
  private static void requireFinite(double number) {
    if (Double.isNaN(number) || Double.isInfinite(number)) {
      throw DocException.invalid("JSON 不能表达 NaN/Infinity（原样进出的前提是可表达）: " + number);
    }
  }

  private static void writeObject(Map<?, ?> map, StringBuilder out) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(String.valueOf(entry.getKey()), out);
      out.append(':');
      write(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeArray(Iterable<?> items, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object item : items) {
      if (!first) {
        out.append(',');
      }
      first = false;
      write(item, out);
    }
    out.append(']');
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** 极简递归下降解析器（拒绝多余字符、拒绝尾随垃圾）。 */
  private static final class Parser {

    private final String text;
    private int index;

    Parser(String text) {
      this.text = text;
    }

    Object readValue() {
      skipWhitespace();
      if (index >= text.length()) {
        throw new IllegalArgumentException("JSON 在此处结束（缺一个值）");
      }
      char c = text.charAt(index);
      return switch (c) {
        case '{' -> readObject();
        case '[' -> readArray();
        case '"' -> readString();
        case 't' -> readLiteral("true", Boolean.TRUE);
        case 'f' -> readLiteral("false", Boolean.FALSE);
        case 'n' -> readLiteral("null", null);
        default -> readNumber();
      };
    }

    void requireEnd() {
      skipWhitespace();
      if (index < text.length()) {
        throw new IllegalArgumentException("JSON 尾部有多余内容: " + text.substring(index));
      }
    }

    private Map<String, Object> readObject() {
      expect('{');
      Map<String, Object> result = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        index++;
        return result;
      }
      while (true) {
        skipWhitespace();
        String key = readString();
        skipWhitespace();
        expect(':');
        result.put(key, readValue());
        skipWhitespace();
        char c = next();
        if (c == '}') {
          return result;
        }
        if (c != ',') {
          throw new IllegalArgumentException("JSON 对象里缺 ',' 或 '}': " + c);
        }
      }
    }

    private List<Object> readArray() {
      expect('[');
      List<Object> result = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        index++;
        return result;
      }
      while (true) {
        result.add(readValue());
        skipWhitespace();
        char c = next();
        if (c == ']') {
          return result;
        }
        if (c != ',') {
          throw new IllegalArgumentException("JSON 数组里缺 ',' 或 ']': " + c);
        }
      }
    }

    private String readString() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char escape = next();
        switch (escape) {
          case '"' -> out.append('"');
          case '\\' -> out.append('\\');
          case '/' -> out.append('/');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (index + 4 > text.length()) {
              throw new IllegalArgumentException("JSON \\u 转义不完整");
            }
            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
            index += 4;
          }
          default -> throw new IllegalArgumentException("JSON 未知转义: \\" + escape);
        }
      }
    }

    private Object readNumber() {
      int start = index;
      while (index < text.length() && "+-.eE0123456789".indexOf(text.charAt(index)) >= 0) {
        index++;
      }
      String token = text.substring(start, index);
      if (token.isEmpty()) {
        throw new IllegalArgumentException("JSON 此处不是值: " + text.substring(index));
      }
      try {
        if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
          return Long.parseLong(token);
        }
        return Double.parseDouble(token);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("JSON 数字非法: " + token, e);
      }
    }

    private Object readLiteral(String literal, Object value) {
      if (!text.startsWith(literal, index)) {
        throw new IllegalArgumentException("JSON 此处不是值: " + text.substring(index));
      }
      index += literal.length();
      return value;
    }

    private void expect(char expected) {
      skipWhitespace();
      char c = next();
      if (c != expected) {
        throw new IllegalArgumentException("JSON 期望 '" + expected + "'，实得 '" + c + "'");
      }
    }

    private char peek() {
      return index < text.length() ? text.charAt(index) : '\0';
    }

    private char next() {
      if (index >= text.length()) {
        throw new IllegalArgumentException("JSON 意外结束");
      }
      return text.charAt(index++);
    }

    private void skipWhitespace() {
      while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
        index++;
      }
    }
  }
}
