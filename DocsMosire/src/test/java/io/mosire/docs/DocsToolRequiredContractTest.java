package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code doc_*} 的 {@code required} 契约（设计 §5.11 "R8 实现事实"段的 <b>required 对齐全等表</b>，R8b 责任区），全离线。
 *
 * <p><b>两条判据</b>：
 *
 * <ol>
 *   <li>{@link #declaredRequiredMatchesDesignTableExactly()}：逐工具断 {@code
 *       jsonSchema().get("required")} 与 设计给出的表<b>全等</b>（含次序；空表连键都不写——{@code doc_guide}
 *       靠这一点在清单里写"无"）；
 *   <li>{@link #runtimeRejectionMatchesDeclaredRequiredExactly()}：<b>声明必须与运行期一致</b>——只给 {@code
 *       required} 就能成功；少给任何一个 {@code required} ⇒ {@code INVALID_ARGUMENTS}； 多给任何一个非 {@code
 *       required} 的参数 ⇒ 仍然成功。三者合起来才排除"标了必填其实可选 / 标了可选其实必填"。
 * </ol>
 *
 * <p><b>表是测试侧字面量，不是从代码里读回来的</b>：从代码读回来就成了同义反复（本仓 §四.4）。值取自 R8-fix 的真跑核对： {@code
 * doc_read=[mount]}、{@code doc_create=[mount]}、{@code doc_list=无}、{@code doc_query=[mount,limit]}、
 * {@code doc_append=[mount,address,tick]}、{@code doc_void=[mount,id]}、{@code doc_edit=[mount,ops]}、
 * {@code doc_guide=无}。
 *
 * <p>注意 {@code doc_list} 的 {@code mount} 与 {@code doc_read} 的 {@code id}/{@code address}：前者缺省 =
 * 列全部文档根， 后者是"二选一"（schema 表达不了，故不标必填）——所以第 ② 条按<b>逐工具</b>的调用装置跑，而不是拿一个通用夹具硬套所有工具。
 */
class DocsToolRequiredContractTest {

  private static final String MAIN = "main";

  /** 设计 §5.11 的 required 表（测试侧字面量；表也必须覆盖家族里的每个工具）。 */
  private static final Map<String, List<String>> DESIGN_REQUIRED =
      Map.of(
          DocsTools.DOC_READ, List.of("mount"),
          DocsTools.DOC_CREATE, List.of("mount"),
          DocsTools.DOC_LIST, List.of(),
          DocsTools.DOC_QUERY, List.of("mount", "limit"),
          DocsTools.DOC_APPEND, List.of("mount", "address", "tick"),
          DocsTools.DOC_VOID, List.of("mount", "id"),
          DocsTools.DOC_EDIT, List.of("mount", "ops"),
          DocsTools.DOC_GUIDE, List.of());

  @TempDir Path tempDir;

  private Path logDir;
  private Path fileDir;
  private AgentPermissionSet permissions;
  private WorkspaceRegistry registry;
  private DocsEngine engine;

  /** 每次调用用新的地址/文件名：同一用例里不会互相撞名（撞名会把"成功"变成 DOC_EXISTS）。 */
  private final AtomicInteger sequence = new AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    logDir = Files.createDirectories(tempDir.resolve("logs"));
    fileDir = Files.createDirectories(tempDir.resolve("files"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    permissions = DocsFixture.permissions(tempDir);
    WorkspaceCaller main = new WorkspaceCaller(MAIN, permissions);
    registry = DocsFixture.registry(catalog);
    engine = new DocsEngine(registry);
    registry.mount(DocsFixture.mount("logs", MAIN, MAIN, logDir, Kind.LOG), main);
    registry.mount(DocsFixture.mount("files", MAIN, MAIN, fileDir, Kind.FILE), main);
  }

  /** 判据一：{@code required} 与设计表<b>全等</b>（空表 = 连 {@code required} 键都不写）。 */
  @Test
  void declaredRequiredMatchesDesignTableExactly() {
    List<AgentTool> tools = DocsTools.all(registry);

    assertThat(tools)
        .extracting(AgentTool::name)
        .as("表必须覆盖家族里的每个工具（新增工具而没补表 ⇒ 当场红）")
        .containsExactlyInAnyOrderElementsOf(DESIGN_REQUIRED.keySet());
    for (AgentTool tool : tools) {
      List<String> expected = DESIGN_REQUIRED.get(tool.name());
      boolean declaredInSchema = tool.jsonSchema().get("required") != null;
      assertThat(declaredInSchema)
          .as("%s：required 为空时不写进 schema（清单里的「无」靠这一点）", tool.name())
          .isEqualTo(!expected.isEmpty());
      assertThat(declaredRequired(tool))
          .as("%s 的 required（全等，含次序）", tool.name())
          .containsExactlyElementsOf(expected);
    }
  }

  /** 判据二：声明的 {@code required} 与运行期的缺参行为<b>逐格对齐</b>。 */
  @Test
  void runtimeRejectionMatchesDeclaredRequiredExactly() throws IOException {
    for (AgentTool tool : DocsTools.all(registry)) {
      List<String> required = DESIGN_REQUIRED.get(tool.name());
      assertThat(required).as("表格必须覆盖这个工具: %s", tool.name()).isNotNull();

      // ① 只给声明的 required ⇒ 必须真的成功
      ToolResult minimal = call(tool, argumentsFor(tool.name(), new LinkedHashSet<>(required)));
      assertThat(minimal.success())
          .as(
              "%s：只给声明的 required %s 就该成功（不是「只求不报错」）: code=%s message=%s",
              tool.name(), required, minimal.code(), minimal.message())
          .isTrue();

      // ② 少给任何一个 required ⇒ INVALID_ARGUMENTS
      for (String name : required) {
        Set<String> given = new LinkedHashSet<>(required);
        given.remove(name);
        ToolResult missing = call(tool, argumentsFor(tool.name(), given));
        assertThat(missing.success()).as("%s：缺 %s 必须被拒", tool.name(), name).isFalse();
        assertThat(missing.code())
            .as("%s：缺 %s 的错误码（声明了必填 ⇒ 运行期也不能默认补一个）: %s", tool.name(), name, missing.message())
            .isEqualTo(DocException.INVALID_ARGUMENTS);
      }

      // ③ 多给任何一个非 required 参数 ⇒ 仍然成功（"标了可选"不是"一给就炸"）
      Set<String> optional = new LinkedHashSet<>(propertiesOf(tool.jsonSchema()).keySet());
      optional.removeAll(required);
      for (String name : optional) {
        Set<String> given = new LinkedHashSet<>(required);
        given.add(name);
        ToolResult extra = call(tool, argumentsFor(tool.name(), given));
        assertThat(extra.success())
            .as(
                "%s：required 之外还给 %s 不该变成错误: code=%s message=%s",
                tool.name(), name, extra.code(), extra.message())
            .isTrue();
      }
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private ToolResult call(AgentTool tool, Map<String, Object> arguments) {
    return tool.execute(DocsFixture.context(MAIN, permissions, tool, arguments));
  }

  /**
   * 逐工具的调用装置：给定"这一次要给哪些参数"，造出一份<b>纪律匹配</b>的具体参数。
   *
   * <p>纪律必须匹配（读/建/改文件走 {@code FILE} 挂载、追加/查询/作废走 {@code LOG} 挂载）：否则"缺参"的判定会被 {@code
   * DOC_INVALID}（纪律不符）抢先，用例就红在夹具上了。
   */
  private Map<String, Object> argumentsFor(String toolName, Set<String> given) throws IOException {
    int n = sequence.incrementAndGet();
    Map<String, Object> args = new LinkedHashMap<>();
    switch (toolName) {
      case DocsTools.DOC_LIST -> {
        if (given.contains("mount")) {
          args.put("mount", "files");
        }
      }
      case DocsTools.DOC_READ -> {
        Files.writeString(fileDir.resolve("r" + n + ".md"), "hello\n");
        if (given.contains("mount")) {
          args.put("mount", "files/r" + n + ".md");
        }
        if (given.contains("id")) {
          args.put("id", "a#0");
        }
        if (given.contains("address")) {
          args.put("address", "a");
        }
        if (given.contains("lines")) {
          args.put("lines", "1,1");
        }
      }
      case DocsTools.DOC_CREATE -> {
        if (given.contains("mount")) {
          args.put("mount", "files/c" + n + ".md");
        }
        if (given.contains("text")) {
          args.put("text", "hello\n");
        }
      }
      case DocsTools.DOC_EDIT -> {
        Files.writeString(fileDir.resolve("e" + n + ".md"), "hello\nworld\n");
        if (given.contains("mount")) {
          args.put("mount", "files/e" + n + ".md");
        }
        if (given.contains("ops")) {
          args.put("ops", List.of(Map.of("op", "replace", "line", 1, "text", "x")));
        }
      }
      case DocsTools.DOC_QUERY -> {
        if (given.contains("mount")) {
          args.put("mount", "logs");
        }
        if (given.contains("limit")) {
          args.put("limit", 5);
        }
        if (given.contains("tick")) {
          args.put("tick", 1);
        }
        if (given.contains("from")) {
          args.put("from", 1);
        }
        if (given.contains("to")) {
          args.put("to", 1);
        }
      }
      case DocsTools.DOC_APPEND -> {
        if (given.contains("mount")) {
          args.put("mount", "logs");
        }
        if (given.contains("address")) {
          args.put("address", "a" + n);
        }
        if (given.contains("tick")) {
          args.put("tick", 1);
        }
        if (given.contains("key")) {
          args.put("key", "k");
        }
        if (given.contains("value")) {
          args.put("value", "v");
        }
        if (given.contains("tags")) {
          args.put("tags", List.of("self"));
        }
        if (given.contains("affiliations")) {
          args.put("affiliations", List.of("main"));
        }
        if (given.contains("owner")) {
          args.put("owner", "main");
        }
        if (given.contains("note")) {
          args.put("note", "n");
        }
        if (given.contains("fields")) {
          args.put("fields", Map.of("k", "v"));
        }
        if (given.contains("at")) {
          args.put("at", "0,0");
        }
      }
      case DocsTools.DOC_VOID -> {
        if (given.contains("mount")) {
          args.put("mount", "logs");
        }
        if (given.contains("id")) {
          // 真造一条再作废：id 必须指向盘上真实存在、且这个调用者看得见的条目
          DocEntry entry =
              engine.append(
                  "logs",
                  new WorkspaceCaller(MAIN, permissions),
                  AppendRequest.of("void" + n, 1, "k", "v"));
          args.put("id", entry.id().toString());
        }
      }
      case DocsTools.DOC_GUIDE -> {
        // 无参数工具：什么都不放（给了也不该变红——那是判据三的事，这里没有 properties）
      }
      default -> throw new AssertionError("表格里没有这个工具的调用装置: " + toolName);
    }
    return args;
  }

  /** schema 里的 {@code required}（缺 ⇒ 空表），逐个元素断类型。 */
  private static List<String> declaredRequired(AgentTool tool) {
    Object raw = tool.jsonSchema().get("required");
    if (raw == null) {
      return List.of();
    }
    assertThat(raw).as("%s 的 required 必须是列表", tool.name()).isInstanceOf(List.class);
    List<String> names = new ArrayList<>();
    for (Object item : (List<?>) raw) {
      assertThat(item).as("%s 的 required 元素必须是字符串", tool.name()).isInstanceOf(String.class);
      names.add((String) item);
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
    Object properties = schema.get("properties");
    assertThat(properties).as("schema 必须有 properties").isInstanceOf(Map.class);
    return (Map<String, Object>) properties;
  }
}
