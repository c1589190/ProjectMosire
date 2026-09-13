package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AgentTemplate} 的 {@code allowedWorkingDirs}（S5-B）：模板对子体工作目录的<b>建议</b>。
 *
 * <p>判别性约定：
 *
 * <ul>
 *   <li><b>字段缺失 vs 显式空表</b>：缺失 ⇒ {@code null} ⇒ "不限"（不额外收窄）；{@code []} ⇒ 哪里都不许。 两者取相反值——谁把 {@code
 *       null} 归一成空表（"顺手统一一下"），第一条用例转红；
 *   <li>有值时装的是一份<b>目录作用域</b>（绝对化 + 已存在的根取 realpath，段边界包含），不是字符串清单；
 *   <li><b>坏值在装载期响亮失败</b>：模板是权限边界，坏模板被静默跳过/静默放宽，等于子体的围栏悄悄不见了。
 * </ul>
 */
class AgentTemplateWorkingDirsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void missingFieldMeansNoSuggestionWhileEmptyArrayMeansNothing() throws Exception {
    AgentTemplate missing = parse(templateJson("reader", null));
    assertThat(missing.allowedWorkingDirs()).as("缺失保留 null，不归一成空表").isNull();
    assertThat(missing.workingDirScope().unrestricted()).isTrue();
    assertThat(missing.workingDirScope().allowsDir(tempDir.resolve("anywhere"))).isTrue();

    AgentTemplate empty = parse(templateJson("reader", "[]"));
    assertThat(empty.allowedWorkingDirs()).isEmpty();
    assertThat(empty.workingDirScope().unrestricted()).as("空表不是'不限'").isFalse();
    assertThat(empty.workingDirScope().prefixList()).isEmpty();
    assertThat(empty.workingDirScope().allowsDir(tempDir)).isFalse();
  }

  @Test
  void dirsBecomeADirectoryScopeWithRealPaths() throws Exception {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    AgentTemplate template = parse(templateJson("reader", "[\"" + work + "\"]"));

    assertThat(template.workingDirScope().dirList()).containsExactly(work.toRealPath());
    assertThat(template.workingDirScope().allowsDir(work.resolve("a/b"))).isTrue();
    // 段边界：同级的 work-other 是另一个目录（字符串前缀会把它放进来）
    Path sibling = Files.createDirectories(tempDir.resolve("work-other"));
    assertThat(template.workingDirScope().allowsDir(sibling)).isFalse();
  }

  @Test
  void dotDotSegmentsAreLexicallyResolvedNotRejected() throws Exception {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    // 目录形态按 Path 的语义归一（"x/../x" 就是 x）：相对段是"路径字面"问题，不是提权问题——
    // 真正越界的条目由父级求交与请求面拒绝去管，不靠这里拒字面量（与 agents.workingDirs 同读法）
    AgentTemplate template =
        parse(templateJson("reader", "[\"" + tempDir.resolve("work/../work") + "\"]"));
    assertThat(template.workingDirScope().dirList()).containsExactly(work.toRealPath());
  }

  @Test
  void blankElementFailsLoudlyAtLoadTime() {
    assertThatThrownBy(() -> templateWithDirs(List.of("  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedWorkingDirs");
  }

  @Test
  void unparsableDirFailsLoudlyAtLoadTime() {
    // 含 NUL 的路径根本不是合法路径（Path.of 抛 InvalidPathException）——装载期就炸，不流到 spawn 期
    List<String> dirs = List.of("bad" + (char) 0 + "path");
    assertThatThrownBy(() -> templateWithDirs(dirs))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            failure ->
                assertThat(causeChain(failure))
                    .as("原因链: %s", causeChain(failure))
                    .contains(InvalidPathException.class.getSimpleName()));
  }

  @Test
  void storeLoadFailsFastOnABadTemplateInsteadOfSkippingIt() throws Exception {
    Path dir = tempDir.resolve("templates");
    Files.createDirectories(dir);
    Files.write(
        dir.resolve("reader.json"),
        templateJson("reader", "[\"  \"]").getBytes(StandardCharsets.UTF_8));

    IllegalArgumentException failure =
        catchThrowableOfType(
            IllegalArgumentException.class, () -> new AgentTemplateStore(dir).load());
    assertThat(failure).as("坏模板必须炸掉整个 load（fail-fast），不许静默跳过").isNotNull();
    assertThat(failure.getMessage()).contains("模板文件装载失败").contains("reader.json");
  }

  private static String causeChain(Throwable failure) {
    StringBuilder chain = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return chain.toString();
  }

  private static AgentTemplate parse(String json) throws Exception {
    return JSON.readValue(json, AgentTemplate.class);
  }

  private static AgentTemplate templateWithDirs(List<String> dirs) {
    return new AgentTemplate(
        "reader",
        "",
        "只读子 Agent",
        "fake",
        AccessToken.DEFAULT,
        Set.of("read"),
        Set.of(),
        true,
        false,
        false,
        5,
        5,
        60,
        0,
        List.of(),
        dirs);
  }

  private static String templateJson(String id, String allowedWorkingDirs) {
    return ("{\"id\":\"%s\",\"systemPrompt\":\"x\",\"maxTurns\":2,\"maxToolCallsPerTurn\":2,"
            + "\"timeBudgetSeconds\":10,\"quotaMaxTokens\":0%s}")
        .formatted(
            id, allowedWorkingDirs == null ? "" : ",\"allowedWorkingDirs\":" + allowedWorkingDirs);
  }
}
