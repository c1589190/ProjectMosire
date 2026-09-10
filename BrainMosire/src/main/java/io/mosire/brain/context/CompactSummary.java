package io.mosire.brain.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化压缩摘要（{@code 开发计划.md:101}：{@code Compactor} 档 2「局部摘要」的产物）——固定八字段，覆盖"这段对话发生过 什么"，<b>不含</b>"Agent
 * 是谁/有哪些技能/记得什么"（那是持久层的内容，见 R3）。
 *
 * <p><b>八字段的语义边界（R3 的判据落点）</b>：objective（这段对话要达成的目标）、completed（已完成）、pending（待办）、
 * decisions（关键决策）、changedFiles（涉及文件）、errors（错误）、activeSkills（用过的技能名）、unresolved（未决问题）。它们是
 * <b>会话语义</b>的回忆——持久层（system 提示 / 规则 / 技能目录 / 记忆）每回合由各自来源<b>重新构造</b>，<b>绝不</b>烤进摘要：
 * 摘要是"对话的记忆替身"，不是"Agent 的身份替身"。
 *
 * <p><b>落点与形态</b>：{@link #render()} 是摘要的<b>唯一</b>文本形态，两个消费方共用同一份字节——{@link
 * ContextLayer#COMPACT_SUMMARY} 层的段落正文（{@link BasicContextAssembler} 加层标题后入 system），以及 {@link
 * io.mosire.agentlib.store.ConversationStore#compact} 的 {@code summary} 参数（档 3 持久化；回灌时按 T16 契约成为首条
 * {@code assistant} 消息）。单一形态是为了"活实例的摘要"与"重启后 load 回来的摘要"逐字节一致。
 *
 * <p><b>解析（{@link #parse}）</b>：摘要由 LLM 产出，故解析必须容错但<b>不许猜测</b>——键缺失/类型不符 → 该字段为空列表（不编造内容）； 整段不是 JSON
 * 对象、或八个字段全空 → 返回 {@code null} 表示"这次摘要不可用"（调用方据此降级，而不是拿一个空摘要冒充成功）。 容错仅限形态：接受 {@code ```json}
 * 围栏、接受字符串代替单元素数组（LLM 常见偏差），不做语义填补。
 */
public record CompactSummary(
    String objective,
    List<String> completed,
    List<String> pending,
    List<String> decisions,
    List<String> changedFiles,
    List<String> errors,
    List<String> activeSkills,
    List<String> unresolved) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 八个字段的 JSON 键名（解析与提示词共用同一份常量，避免两处漂移）。 */
  static final String KEY_OBJECTIVE = "objective";

  static final String KEY_COMPLETED = "completed";
  static final String KEY_PENDING = "pending";
  static final String KEY_DECISIONS = "decisions";
  static final String KEY_CHANGED_FILES = "changedFiles";
  static final String KEY_ERRORS = "errors";
  static final String KEY_ACTIVE_SKILLS = "activeSkills";
  static final String KEY_UNRESOLVED = "unresolved";

  /** 字段渲染标签（渲染格式是逐字节 pin 的契约：层段落与落库摘要共用）。 */
  private static final String OBJECTIVE_LABEL = "目标：";

  private static final String COMPLETED_LABEL = "已完成：";
  private static final String PENDING_LABEL = "待办：";
  private static final String DECISIONS_LABEL = "决策：";
  private static final String CHANGED_FILES_LABEL = "改动文件：";
  private static final String ERRORS_LABEL = "错误：";
  private static final String ACTIVE_SKILLS_LABEL = "激活技能：";
  private static final String UNRESOLVED_LABEL = "未决问题：";

  /** 空字段的占位：八字段恒全部渲染——"这一类是空的"与"这一类没渲染"必须可区分。 */
  private static final String EMPTY_FIELD = "（无）";

  public CompactSummary {
    objective = objective == null ? "" : objective;
    // 逐字段内联 List.copyOf（而非抽一个 copy() 辅助方法）：SpotBugs 的不可变集合建模只认得
    // 直接出现在赋值右侧的 List.copyOf/List.of——隔一层方法调用它就看不见，字段被判为可变，
    // 8 个访问器会齐齐报 EI_EXPOSE_REP（本仓库无排除过滤器，门禁直接红）。null → 空列表的容错
    // 必须保留：解析路径与外部构造都以 null 表示"该字段没有内容"（见 CompactSummaryTest）。
    completed = completed == null ? List.of() : List.copyOf(completed);
    pending = pending == null ? List.of() : List.copyOf(pending);
    decisions = decisions == null ? List.of() : List.copyOf(decisions);
    changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    errors = errors == null ? List.of() : List.copyOf(errors);
    activeSkills = activeSkills == null ? List.of() : List.copyOf(activeSkills);
    unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
  }

  /** 是否八个字段全空（= 没有任何可保留的信息，调用方应视为"摘要不可用"）。 */
  public boolean isEmpty() {
    return objective.isBlank()
        && completed.isEmpty()
        && pending.isEmpty()
        && decisions.isEmpty()
        && changedFiles.isEmpty()
        && errors.isEmpty()
        && activeSkills.isEmpty()
        && unresolved.isEmpty();
  }

  /** 八个字段的确定性文本渲染（行序 = 声明序，恒含八个标签）。 */
  public String render() {
    StringBuilder text = new StringBuilder();
    text.append(OBJECTIVE_LABEL).append(objective.isBlank() ? EMPTY_FIELD : objective);
    appendItems(text, COMPLETED_LABEL, completed);
    appendItems(text, PENDING_LABEL, pending);
    appendItems(text, DECISIONS_LABEL, decisions);
    appendItems(text, CHANGED_FILES_LABEL, changedFiles);
    appendItems(text, ERRORS_LABEL, errors);
    appendItems(text, ACTIVE_SKILLS_LABEL, activeSkills);
    appendItems(text, UNRESOLVED_LABEL, unresolved);
    return text.toString();
  }

  private static void appendItems(StringBuilder text, String label, List<String> items) {
    text.append('\n').append(label);
    if (items.isEmpty()) {
      text.append('\n').append(EMPTY_FIELD);
      return;
    }
    for (String item : items) {
      text.append("\n- ").append(item);
    }
  }

  /**
   * 解析 LLM 回复为结构化摘要；不可用返回 {@code null}（调用方据此降级为档 1 的披露式丢弃，而不是静默用空摘要顶替）。
   *
   * <p>不可用 = 空文本 / 不是 JSON 对象 / 八字段全空。三者在调用方眼里是同一件事：<b>这次摘要没有可保留的信息</b>。
   */
  public static CompactSummary parse(String reply) {
    if (reply == null || reply.isBlank()) {
      return null;
    }
    JsonNode root;
    try {
      root = JSON.readTree(stripCodeFence(reply));
    } catch (JsonProcessingException e) {
      return null;
    }
    if (root == null || !root.isObject()) {
      return null;
    }
    CompactSummary summary =
        new CompactSummary(
            String.join("\n", values(root.get(KEY_OBJECTIVE))),
            values(root.get(KEY_COMPLETED)),
            values(root.get(KEY_PENDING)),
            values(root.get(KEY_DECISIONS)),
            values(root.get(KEY_CHANGED_FILES)),
            values(root.get(KEY_ERRORS)),
            values(root.get(KEY_ACTIVE_SKILLS)),
            values(root.get(KEY_UNRESOLVED)));
    return summary.isEmpty() ? null : summary;
  }

  /** 去掉 {@code ```}/{@code ```json} 围栏（LLM 常见输出偏差）；无围栏则原样返回（去首尾空白）。 */
  private static String stripCodeFence(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }
    String body = trimmed.substring(firstNewline + 1);
    int closing = body.lastIndexOf("```");
    return (closing < 0 ? body : body.substring(0, closing)).strip();
  }

  /**
   * 字段取值：数组（取其中文本元素）或单个字符串都接受，缺失/类型不符 → 空列表。
   *
   * <p>非文本元素<b>丢弃而不是字符串化</b>：把 {@code {"a":1}} 变成 {@code "{a=1}"} 是编造内容， 而摘要里出现编造内容比缺一条更糟。
   */
  private static List<String> values(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (node.isTextual()) {
      String single = node.textValue().strip();
      return single.isEmpty() ? List.of() : List.of(single);
    }
    if (!node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (item.isTextual() && !item.textValue().isBlank()) {
        values.add(item.textValue());
      }
    }
    return List.copyOf(values);
  }
}
