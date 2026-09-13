package io.mosire.agentlib.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.plugin.PluginToolSource.PluginLoadException;
import io.mosire.agentlib.plugin.PluginToolSource.PluginStatus;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.ExtensionPoint;
import org.pf4j.PluginClassLoader;

/**
 * {@link PluginToolSource} 的端到端用例：插件 JAR <b>在测试期现场构造</b>（javac API + {@link JarOutputStream}，全离线， 见
 * T19 R7），装载 → 工具可见 → disable 消失 → enable 复现，外加负面路径（坏 JAR / 缺描述符 / 缺扩展索引 / id 冒用 / 工具名冲突）。
 *
 * <p><b>判别性（R10）</b>：每条"成功"断言都带归属证据——工具类由插件自己的 {@link PluginClassLoader} 装载（不是测试类加载器）；
 * 每条"消失"断言都同时断言<b>其它源一条不少</b>；事件断言要求 state 与顺序可辨（不是"收到过一次回调"）。
 *
 * <p>失败路径同样要求事件可辨（评审 F1）：描述符阶段就失败的 JAR（坏 JAR / 缺描述符 / Plugin-Id 重复）在事件流里也各有一条 {@code FAILED}，标识退化为
 * JAR 文件名；且失败是<b>每次尝试一条</b>，不是"状态恒定只报一次"。
 */
@Timeout(180)
class PluginToolSourceTest {

  private static final String BUILTIN = PluginToolSource.HOST_BUILTIN_SOURCE_ID;

  /** 编译插件源码用的 classpath：宿主 API 的代码位置 + PF4J——只放这两处，正好证明插件只依赖宿主 API 与 PF4J。 */
  private static final String PLUGIN_COMPILE_CP =
      codeSource(io.mosire.agentlib.plugin.ToolSource.class)
          + File.pathSeparator
          + codeSource(ExtensionPoint.class);

  private static final String PF4J_JAR = codeSource(ExtensionPoint.class);

  @TempDir Path tempDir;

  private Path pluginsDir;
  private ToolRegistry registry;
  private PluginToolSource source;
  private List<String> events;
  private int buildCounter;

  @BeforeEach
  void setUp() throws IOException {
    pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
    registry = new ToolRegistry();
    events = new CopyOnWriteArrayList<>();
    source =
        new PluginToolSource(
            pluginsDir,
            registry,
            (pluginId, version, state) -> events.add(pluginId + "|" + state + "|" + version));
  }

  // ---------- 正向路径 ----------

  @Test
  void loadAllRegistersPluginToolsUnderTheirOwnSource() throws IOException {
    registerBuiltin("builtin_alpha");
    Path classes =
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.alpha.AlphaSource",
                    sourceTemplate(
                        "plug.alpha", "AlphaSource", "alpha", List.of("alpha_one", "alpha_two")))));
    writeJar(pluginsDir.resolve("a-alpha.jar"), classes, "alpha-plugin", "1.2.3");

    assertThat(source.loadAll()).containsExactly("alpha-plugin");

    // 归属可辨：工具登记在插件声明的 sourceId 下（不是无源、不是别的源）
    assertThat(registry.sourceIds()).containsExactly("alpha", BUILTIN);
    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one", "alpha_two");
    assertThat(registry.find("builtin_alpha")).isPresent();

    // 确实来自该插件：工具类由插件的类加载器装载，且执行结果回带该身份（一个"只是同名的内建工具"过不了这两条）
    AgentTool tool = registry.find("alpha_one").orElseThrow();
    ClassLoader pluginCl = tool.getClass().getClassLoader();
    assertThat(pluginCl)
        .isInstanceOf(PluginClassLoader.class)
        .isNotSameAs(getClass().getClassLoader());
    assertThat(tool.execute(null).message()).contains(pluginCl.toString());

    // 两层信息：插件清单回答 pluginId/sourceId/state（registry 只回答"在册的源"）
    assertThat(source.list())
        .containsExactly(
            new PluginStatus("alpha-plugin", "alpha", "1.2.3", PluginListener.State.STARTED));
    assertThat(events).containsExactly("alpha-plugin|STARTED|1.2.3");
  }

  @Test
  void disableRemovesOnlyItsOwnToolsWhileOtherSourcesStayIntact() throws IOException {
    registerBuiltin("builtin_alpha", "builtin_beta");
    registry.register("other-source", fakeTool("other_tool"));
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    writeJar(
        pluginsDir.resolve("b-beta.jar"),
        pluginClasses("beta", "beta_one"),
        "beta-plugin",
        "1.0.0");
    source.loadAll();

    source.disable("alpha-plugin");

    // 消失的只有 alpha 自己的工具
    assertThat(registry.find("alpha_one")).isEmpty();
    assertThat(registry.listBySource("alpha")).isEmpty();
    assertThat(registry.sourceIds()).containsExactly("beta", BUILTIN, "other-source");
    // 其它源一条不少：另一个插件、内建、以及手写的第三方源
    assertThat(registry.find("beta_one")).isPresent();
    assertThat(registry.listBySource(BUILTIN))
        .extracting(AgentTool::name)
        .containsExactly("builtin_alpha", "builtin_beta");
    assertThat(registry.find("other_tool")).isPresent();

    // 事件 state 与顺序可辨（不是"收到过一次回调"）：两次回调、state 不同、pluginId 正确
    assertThat(source.list())
        .extracting(PluginStatus::pluginId, PluginStatus::state)
        .containsExactly(
            tuple("alpha-plugin", PluginListener.State.STOPPED),
            tuple("beta-plugin", PluginListener.State.STARTED));
    assertThat(events)
        .containsExactly(
            "alpha-plugin|STARTED|1.0.0",
            "beta-plugin|STARTED|1.0.0",
            "alpha-plugin|STOPPED|1.0.0");
  }

  @Test
  void disableReleasesThePluginClassLoaderAndEnableReloadsIt() throws IOException {
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    source.loadAll();
    URLClassLoader firstCl =
        (URLClassLoader) registry.find("alpha_one").orElseThrow().getClass().getClassLoader();
    String resource = "plug/alpha/AlphaSource.class";
    assertThat(firstCl.getResource(resource)).as("前置条件：启用态下类加载器能读到 JAR 内资源").isNotNull();

    source.disable("alpha-plugin");
    // 停用必须卸载插件（释放类加载器与 JAR 句柄），不是只把工具从表里摘掉
    assertThat(firstCl.getResource(resource)).as("停用后旧类加载器已关闭").isNull();
    registry.register("other", fakeTool("other_tool"));

    source.enable("alpha-plugin");
    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(registry.find("other_tool")).isPresent();
    ClassLoader secondCl = registry.find("alpha_one").orElseThrow().getClass().getClassLoader();
    assertThat(secondCl).as("重新装载用的是新的类加载器，而非复用已关闭的旧实例").isNotSameAs(firstCl);
    assertThat(firstCl.getResource(resource)).isNull();
    assertThat(events)
        .containsExactly(
            "alpha-plugin|STARTED|1.0.0",
            "alpha-plugin|STOPPED|1.0.0",
            "alpha-plugin|STARTED|1.0.0");
  }

  @Test
  void loadAllIsIdempotentAndDoesNotUndoAnOperatorDisable() throws IOException {
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    assertThat(source.loadAll()).containsExactly("alpha-plugin");

    // 已在本实例清单里 → 跳过，不重复登记（重复登记会撞"工具名重复"）
    assertThat(source.loadAll()).isEmpty();
    assertThat(registry.listBySource("alpha")).hasSize(1);

    // disable 是运维意图：loadAll 不得把它悄悄撤销
    source.disable("alpha-plugin");
    assertThat(source.loadAll()).isEmpty();
    assertThat(registry.find("alpha_one")).isEmpty();
    assertThat(source.list())
        .extracting(PluginStatus::state)
        .containsExactly(PluginListener.State.STOPPED);
  }

  @Test
  void emptyOrNonJarPluginDirectoryIsNotAnError() throws IOException {
    Files.writeString(pluginsDir.resolve("README.txt"), "不是插件");
    Files.createDirectories(pluginsDir.resolve("subdir"));

    assertThat(source.loadAll()).isEmpty();
    assertThat(source.list()).isEmpty();
    assertThat(registry.sourceIds()).isEmpty();
  }

  // ---------- 负面路径：id 冒用（R3 判别点） ----------

  @Test
  void rejectsPluginImpersonatingTheBuiltinSourceId() throws IOException {
    registerBuiltin("builtin_alpha", "builtin_beta");
    Path rogue =
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.rogue.RogueSource",
                    sourceTemplate("plug.rogue", "RogueSource", BUILTIN, List.of("rogue_tool")))));
    writeJar(pluginsDir.resolve("a-rogue.jar"), rogue, "rogue-plugin", "9.9.9");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("a-rogue.jar")
        .hasMessageContaining(BUILTIN);

    // 判别点：内建工具一条不少（错误实现会让一次 unregisterAllBySource("builtin") 把内建工具整组误删）
    assertThat(registry.listBySource(BUILTIN))
        .extracting(AgentTool::name)
        .containsExactly("builtin_alpha", "builtin_beta");
    assertThat(registry.find("rogue_tool")).isEmpty();
    assertThat(source.list()).as("冒名插件未被装载").isEmpty();
    assertThat(events).containsExactly("rogue-plugin|FAILED|9.9.9");
  }

  @Test
  void rejectsReservedBuiltinSourceIdEvenBeforeAnyBuiltinToolIsRegistered() throws IOException {
    // 保留名与"此刻 registry 里有没有内建工具"无关：否则内建工具随后注册进来就会与插件同源，disable 时被一起摘掉
    Path rogue =
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.rogue.RogueSource",
                    sourceTemplate("plug.rogue", "RogueSource", BUILTIN, List.of("rogue_tool")))));
    writeJar(pluginsDir.resolve("a-rogue.jar"), rogue, "rogue-plugin", "9.9.9");

    ToolRegistry emptyRegistry = new ToolRegistry();
    PluginToolSource fresh = new PluginToolSource(pluginsDir, emptyRegistry, PluginListener.none());

    assertThatThrownBy(fresh::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("保留");
    assertThat(emptyRegistry.list()).isEmpty();
    assertThat(fresh.list()).isEmpty();
  }

  @Test
  void rejectsSecondPluginDeclaringAnAlreadyUsedSourceId() throws IOException {
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    writeJar(
        pluginsDir.resolve("b-beta.jar"),
        pluginClasses("alpha", "beta_one"),
        "beta-plugin",
        "1.0.0");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("b-beta.jar")
        .hasMessageContaining("beta-plugin")
        .hasMessageContaining("alpha");

    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(source.list())
        .extracting(PluginStatus::pluginId)
        .as("后到者被拒，先到者不受影响")
        .containsExactly("alpha-plugin");
    assertThat(events).containsExactly("alpha-plugin|STARTED|1.0.0", "beta-plugin|FAILED|1.0.0");
  }

  @Test
  void rejectsSourceIdAlreadyClaimedByADisabledPlugin() throws IOException {
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    writeJar(
        pluginsDir.resolve("b-beta.jar"),
        pluginClasses("beta", "beta_one"),
        "beta-plugin",
        "1.0.0");
    source.loadAll();
    source.disable("alpha-plugin");
    // alpha 的工具已摘除 → registry 里看不到 "alpha" 这个源了（只按在册源判冲突会漏掉这一窗口）
    assertThat(registry.sourceIds()).containsExactly("beta");

    // disable 窗口期不得被别的插件偷走 sourceId：否则 alpha 再 enable 时两个插件的工具会挤进同一个源，
    // 此后 disable 任一方都会把另一方整组误摘
    writeJar(
        pluginsDir.resolve("c-gamma.jar"),
        pluginClasses("alpha", "gamma_one"),
        "gamma-plugin",
        "1.0.0");
    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("c-gamma.jar")
        .hasMessageContaining("已发现的插件冲突");

    assertThat(source.list())
        .extracting(PluginStatus::pluginId)
        .containsExactly("alpha-plugin", "beta-plugin");
    source.enable("alpha-plugin");
    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(registry.listBySource("beta"))
        .extracting(AgentTool::name)
        .containsExactly("beta_one");
  }

  @Test
  void rejectsSecondJarWithTheSamePluginIdAndKeepsTheFirstOneIntact() throws IOException {
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "same-plugin",
        "1.0.0");
    writeJar(
        pluginsDir.resolve("b-beta.jar"),
        pluginClasses("beta", "beta_one"),
        "same-plugin",
        "2.0.0");

    // PF4J 对"两个 JAR 声明同一 Plugin-Id"是抛错的（批量 loadPlugins 则会静默少装一个）——本类必须响亮并保住先到者
    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("b-beta.jar");

    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(registry.find("beta_one")).isEmpty();
    assertThat(source.list()).extracting(PluginStatus::pluginId).containsExactly("same-plugin");
    // 如实形态：先到者一条 STARTED（标识是真 pluginId），后到者一条 FAILED——它失败在描述符阶段（PF4J 连类加载器都没建），
    // 此刻没有属于它的 pluginId（"same-plugin" 归先到者），故标识退化为自己的 JAR 文件名（version 空）。
    // 语义：事件流里"b-beta.jar 试过并失败"与"从未发现有这个文件"可辨，且不与先到者的身份混淆。
    assertThat(events).containsExactly("same-plugin|STARTED|1.0.0", "b-beta.jar|FAILED|");
  }

  @Test
  void rejectsBlankSourceIdFromPlugin() throws IOException {
    writeJar(
        pluginsDir.resolve("a-blank.jar"),
        pluginClasses("   ", "blank_tool"),
        "blank-plugin",
        "1.0.0");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("空白");
    assertThat(registry.list()).isEmpty();
  }

  @Test
  void disableAndEnableRejectUnknownIdsWithoutTouchingTheRegistry() throws IOException {
    registerBuiltin("builtin_alpha", "builtin_beta");
    writeJar(
        pluginsDir.resolve("a-alpha.jar"),
        pluginClasses("alpha", "alpha_one"),
        "alpha-plugin",
        "1.0.0");
    source.loadAll();

    // 判别点（R3）：错误实现会把传入的 id 直接交给 unregisterAllBySource（传 "builtin" 就整组误删内建工具）
    assertThatThrownBy(() -> source.disable(BUILTIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(BUILTIN);
    assertThatThrownBy(() -> source.disable("alpha"))
        .as("sourceId 不是 pluginId")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> source.enable("no-such-plugin"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(registry.listBySource(BUILTIN))
        .extracting(AgentTool::name)
        .containsExactly("builtin_alpha", "builtin_beta");
    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(events).containsExactly("alpha-plugin|STARTED|1.0.0");
  }

  // ---------- 负面路径：坏 JAR / 缺描述符 / 缺扩展索引 / 工具名冲突 ----------

  @Test
  void badJarAndMissingDescriptorFailLoudlyWithoutPollutingOtherPluginsOrSources()
      throws IOException {
    registerBuiltin("builtin_alpha");
    Path classes = pluginClasses("alpha", "alpha_one");
    writeJar(pluginsDir.resolve("a-good.jar"), classes, "alpha-plugin", "1.0.0");
    Files.write(pluginsDir.resolve("b-garbage.jar"), new byte[] {1, 2, 3, 4}); // 不是 zip
    writeJar(
        pluginsDir.resolve("c-no-descriptor.jar"),
        classes,
        null,
        null); // 合法 zip，但 MANIFEST 无 Plugin-Id

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("b-garbage.jar")
        .hasMessageContaining("c-no-descriptor.jar");

    // 响亮失败之外，其余插件与其它源照常
    assertThat(registry.listBySource("alpha"))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(registry.find("builtin_alpha")).isPresent();
    assertThat(source.list()).extracting(PluginStatus::pluginId).containsExactly("alpha-plugin");
    // 失败在事件通道也可见（不止是调用方接到的聚合异常）：两个失败 JAR 各一条 FAILED，标识退化为各自的 JAR 文件名
    // （描述符阶段还没有可信的 pluginId），顺序与处理顺序一致；失败者绝无 STARTED——判别点：
    // "只在已识别的插件上回调"（本条会缺两条 FAILED）、"用同一个常量标识"（此处两个文件名不同会红）、
    // "不区分失败与成功"（多出的 STARTED 会让 containsExactly 红）三种假实现都过不了这条。
    assertThat(events)
        .containsExactly(
            "alpha-plugin|STARTED|1.0.0", "b-garbage.jar|FAILED|", "c-no-descriptor.jar|FAILED|");
  }

  @Test
  void everyFailedAttemptEmitsItsOwnFailedEventInOrder() throws IOException {
    Files.write(pluginsDir.resolve("a-garbage.jar"), new byte[] {1, 2, 3, 4});
    Files.write(pluginsDir.resolve("b-garbage.jar"), new byte[] {5, 6, 7, 8});

    // 失败的 JAR 不进"已发现"清单 → 每次 loadAll 都会重试，故失败是"每次尝试一条 FAILED"，不是"状态恒定只报一次"
    assertThatThrownBy(source::loadAll).isInstanceOf(PluginLoadException.class);
    assertThatThrownBy(source::loadAll).isInstanceOf(PluginLoadException.class);

    // 判别点：两次尝试 × 两个文件 = 四条，且逐条可辨（哪个 JAR / 第几次尝试 / 顺序）；
    // "只在第一次失败时回调"或"同一 JAR 只报一次"的假实现会在这里红。
    assertThat(events)
        .containsExactly(
            "a-garbage.jar|FAILED|",
            "b-garbage.jar|FAILED|",
            "a-garbage.jar|FAILED|",
            "b-garbage.jar|FAILED|");
    assertThat(source.list()).isEmpty();
  }

  @Test
  void jarWithoutExtensionIndexIsRejectedInsteadOfLoadingZeroToolsSilently() throws IOException {
    Path classes = pluginClasses("alpha", "alpha_one");
    // 描述符与类都合法，PF4J 能装载，但缺 @Extension 生成的索引 → 找不到任何 ToolSource 扩展（默认发现路径只认 extensions.idx）
    Files.delete(classes.resolve("META-INF/extensions.idx"));
    writeJar(pluginsDir.resolve("a-no-index.jar"), classes, "no-index-plugin", "1.0.0");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("extensions.idx")
        .hasMessageContaining("实际 0 个");

    assertThat(registry.list()).isEmpty();
    assertThat(source.list()).isEmpty();
    assertThat(events).containsExactly("no-index-plugin|FAILED|1.0.0");
  }

  @Test
  void pluginProvidingTwoToolSourcesIsRejectedLoudly() throws IOException {
    Path classes =
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.two.FirstSource",
                    sourceTemplate("plug.two", "FirstSource", "first", List.of("first_tool"))),
                new PluginSource(
                    "plug.two.SecondSource",
                    sourceTemplate("plug.two", "SecondSource", "second", List.of("second_tool")))));
    writeJar(pluginsDir.resolve("a-two.jar"), classes, "two-plugin", "1.0.0");

    // 一个插件声明多个 sourceId 会让 (pluginId → sourceId) 不再是映射（审计与按源卸载都依赖它）→ 拒绝而不是静默取一个
    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("实际 2 个");
    assertThat(registry.list()).isEmpty();
    assertThat(events).containsExactly("two-plugin|FAILED|1.0.0");
  }

  @Test
  void toolNameConflictRollsBackTheWholePluginAndKeepsTheExistingTool() throws IOException {
    registerBuiltin("alpha_one"); // 与插件工具同名
    Path classes = pluginClasses("gamma", "gamma_first", "alpha_one");
    writeJar(pluginsDir.resolve("a-gamma.jar"), classes, "gamma-plugin", "1.0.0");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("gamma-plugin");

    // 全有或全无：先登记成功的 gamma_first 也必须回滚（半装载的插件会污染审计与按源卸载）
    assertThat(registry.find("gamma_first")).isEmpty();
    assertThat(registry.listBySource("gamma")).isEmpty();
    assertThat(registry.sourceIds()).doesNotContain("gamma");
    // 既有的同名工具仍是原来的实例，没被顶替
    assertThat(registry.listBySource(BUILTIN))
        .extracting(AgentTool::name)
        .containsExactly("alpha_one");
    assertThat(source.list()).isEmpty();
    assertThat(events).containsExactly("gamma-plugin|FAILED|1.0.0");
  }

  // ---------- 收尾与构造契约 ----------

  @Test
  void closeUnloadsAllPluginsKeepsOtherSourcesAndRefusesFurtherMutation() throws IOException {
    registerBuiltin("builtin_alpha");
    Path classes = pluginClasses("alpha", "alpha_one");
    writeJar(pluginsDir.resolve("a-alpha.jar"), classes, "alpha-plugin", "1.0.0");
    source.loadAll();
    URLClassLoader pluginCl =
        (URLClassLoader) registry.find("alpha_one").orElseThrow().getClass().getClassLoader();

    source.close();

    assertThat(pluginCl.getResource("plug/alpha/AlphaSource.class")).isNull();
    assertThat(registry.find("alpha_one")).isEmpty();
    assertThat(registry.listBySource(BUILTIN))
        .extracting(AgentTool::name)
        .containsExactly("builtin_alpha");
    assertThat(source.list())
        .extracting(PluginStatus::state)
        .containsExactly(PluginListener.State.STOPPED);
    assertThatThrownBy(source::loadAll).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> source.enable("alpha-plugin"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> source.disable("alpha-plugin"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(events).containsExactly("alpha-plugin|STARTED|1.0.0", "alpha-plugin|STOPPED|1.0.0");
    source.close(); // 幂等
  }

  @Test
  void constructorRequiresAnExplicitExistingDirectory() {
    assertThatThrownBy(
            () -> new PluginToolSource(tempDir.resolve("missing"), registry, PluginListener.none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
    assertThatThrownBy(() -> new PluginToolSource(pluginsDir, registry, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(new PluginToolSource(pluginsDir, registry, PluginListener.none()).pluginsDir())
        .isEqualTo(pluginsDir);
  }

  // ---------- 装载期服务面（init）与卸载期回收（close） ----------

  @Test
  void initReceivesTheHostServicesAndRunsBeforeListTools() throws IOException {
    Path marker = tempDir.resolve("init-order.txt");
    FileConfigStore store = new FileConfigStore(tempDir.resolve("cfg"));
    writeJar(
        pluginsDir.resolve("a-init.jar"),
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.init.InitSource",
                    lifecycleTemplate("plug.init", "InitSource", "init_source", marker)))),
        "init-plugin",
        "1.0.0");
    PluginToolSource withHost =
        new PluginToolSource(pluginsDir, registry, PluginListener.none(), new HostServices(store));

    withHost.loadAll();

    // 判别点有三：① init 被调了（不调 ⇒ 只有 listTools 一行）；
    // ② 拿到的是宿主传的那个 ConfigStore 实例（传 null/空 HostServices ⇒ 行变成 init:null，identityHashCode 对不上）；
    // ③ 顺序在 listTools 之前（反了 ⇒ 两行顺序颠倒）。
    assertThat(lines(marker))
        .containsExactly("init:" + System.identityHashCode(store), "listTools");
  }

  @Test
  void unconfiguredHostServicesAreHandedOverAsAnEmptyConfig() throws IOException {
    Path marker = tempDir.resolve("init-none.txt");
    writeJar(
        pluginsDir.resolve("a-none.jar"),
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.none.NoneSource",
                    lifecycleTemplate("plug.none", "NoneSource", "none_source", marker)))),
        "none-plugin",
        "1.0.0");

    // 3 参构造（既有调用点）：宿主没装配服务面 ⇒ 插件拿到的 config() 必须是 null（可判"未装配"，不是"空 store"）
    source.loadAll();

    assertThat(lines(marker)).containsExactly("init:null", "listTools");
  }

  @Test
  void initFailureRollsBackThePluginAndIsReportedAsAFailedLoad() throws IOException {
    writeJar(
        pluginsDir.resolve("a-boom.jar"),
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.boom.BoomSource",
                    """
                    package plug.boom;

                    import io.mosire.agentlib.plugin.HostServices;
                    import io.mosire.agentlib.plugin.ToolSource;
                    import io.mosire.agentlib.tool.AgentTool;
                    import java.util.List;
                    import org.pf4j.Extension;

                    @Extension
                    public class BoomSource implements ToolSource {

                      @Override
                      public void init(HostServices services) {
                        throw new IllegalStateException("装载期校验失败：tools.bash.sandbox=bogus");
                      }

                      @Override
                      public String id() {
                        return "boom_source";
                      }

                      @Override
                      public List<AgentTool> listTools() {
                        throw new AssertionError("init 已抛异常，listTools 不该再被调");
                      }

                      @Override
                      public AutoCloseable onChange(Runnable listener) {
                        return () -> {};
                      }
                    }
                    """))),
        "boom-plugin",
        "1.0.0");

    assertThatThrownBy(source::loadAll)
        .isInstanceOf(PluginLoadException.class)
        .hasMessageContaining("boom-plugin")
        .hasMessageContaining("装载期校验失败");

    // 判别点：装载失败必须是"响亮异常 + FAILED 事件 + 工具没进 registry"三件齐备——
    // 只吞异常（静默跳过）、只抛异常不回调、或把工具登记完才发现问题，三种假实现各缺一件。
    assertThat(events).containsExactly("boom-plugin|FAILED|1.0.0");
    assertThat(registry.list()).isEmpty();
    assertThat(source.list()).isEmpty();
  }

  @Test
  void disableCallsThePluginCloseAndStopsHoldingItsInstance() throws IOException {
    Path marker = tempDir.resolve("lifecycle.txt");
    writeJar(
        pluginsDir.resolve("a-life.jar"),
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.life.LifeSource",
                    lifecycleTemplate("plug.life", "LifeSource", "life_source", marker)))),
        "life-plugin",
        "1.0.0");
    source.loadAll();
    assertThat(registry.find("life_source_tool")).isPresent();

    source.disable("life-plugin");

    // 判别点 ①：disable 调了插件的 close()（删掉调用 ⇒ 只剩 init/listTools 两行）
    assertThat(lines(marker)).containsExactly("init:null", "listTools", "close");
    // 判别点 ②：工具整组摘除
    assertThat(registry.find("life_source_tool")).isEmpty();
    // 判别点 ③：清单项不再持有插件实例——持有就会替已停用的插件钉住 PluginClassLoader，
    // 把 unloadPlugin 的"释放类加载器"变成账面动作。断言的是<b>引用已置空</b>这一结构事实（不是"GC 后类加载器真的被回收"，
    // 后者本用例不做——见测试类注释与账本的诚实边界）。
    assertThat(entrySource("life-plugin")).isNull();
    assertThat(entrySource("missing-plugin")).as("未知 pluginId 无登记项").isNull();
  }

  @Test
  void closeAlsoCallsThePluginCloseOnItsWayOut() throws IOException {
    Path marker = tempDir.resolve("lifecycle-close.txt");
    writeJar(
        pluginsDir.resolve("a-life2.jar"),
        compilePlugin(
            List.of(
                new PluginSource(
                    "plug.life2.Life2Source",
                    lifecycleTemplate("plug.life2", "Life2Source", "life2_source", marker)))),
        "life2-plugin",
        "1.0.0");
    source.loadAll();

    source.close();

    assertThat(lines(marker)).containsExactly("init:null", "listTools", "close");
    assertThat(entrySource("life2-plugin")).isNull();
  }

  /**
   * 登记的插件实例引用（反射读私有记录项，可判"启用态持有 / 停用态置空"）。
   *
   * <p>为什么用反射：这是本类<b>唯一</b>可确定判定的观测面。等价的"弱引用 + System.gc()"写法是概率性的（GC 不保证发生）， 本仓的既有教训是"概率性判别等于没判别"。
   */
  private Object entrySource(String pluginId) {
    try {
      Field entriesField = PluginToolSource.class.getDeclaredField("entries");
      entriesField.setAccessible(true);
      Object entry = ((java.util.Map<?, ?>) entriesField.get(source)).get(pluginId);
      if (entry == null) {
        return null;
      }
      Field sourceField = entry.getClass().getDeclaredField("source");
      sourceField.setAccessible(true);
      return sourceField.get(entry);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("读取插件登记项失败（字段改名了？本用例须同步）", e);
    }
  }

  /** 标记文件的行（文件不存在 = 空表：没有任何一次调用落过标记）。 */
  private static List<String> lines(Path marker) throws IOException {
    return Files.exists(marker) ? Files.readAllLines(marker, StandardCharsets.UTF_8) : List.of();
  }

  // ---------- 测试期插件 JAR 构造（R7：javac API + JarOutputStream，全离线） ----------

  /** 一段待编译的插件源码（fqn 决定落盘路径，与源码里的 package/类名一致）。 */
  private record PluginSource(String fqn, String source) {}

  private Path pluginClasses(String sourceId, String... toolNames) throws IOException {
    return compilePlugin(
        List.of(
            new PluginSource(
                "plug.alpha.AlphaSource",
                sourceTemplate("plug.alpha", "AlphaSource", sourceId, List.of(toolNames)))));
  }

  /** 现场编译插件源码（javac API + PF4J 的 APT 处理器路径，全离线）；返回类输出目录（含 {@code META-INF/extensions.idx}）。 */
  private Path compilePlugin(List<PluginSource> sources) throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("build-" + buildCounter++));
    Path sourcesRoot = Files.createDirectories(root.resolve("src"));
    Path classes = Files.createDirectories(root.resolve("classes"));
    Path generated = Files.createDirectories(root.resolve("generated"));

    List<Path> files = new ArrayList<>();
    for (PluginSource source : sources) {
      Path file = sourcesRoot.resolve(source.fqn().replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, source.source(), StandardCharsets.UTF_8);
      files.add(file);
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("插件 JAR 的现场构造需要 JDK（javax.tools）").isNotNull();
    List<String> args =
        new ArrayList<>(
            List.of(
                "-classpath",
                PLUGIN_COMPILE_CP,
                // PF4J 的 @Extension 处理器（随 pf4j jar 自带）：生成 META-INF/extensions.idx——实测唯一的发现路径
                "-processorpath",
                PF4J_JAR,
                "-processor",
                "org.pf4j.processor.ExtensionAnnotationProcessor",
                "-d",
                classes.toString(),
                "-s",
                generated.toString()));
    files.forEach(f -> args.add(f.toString()));

    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int rc =
        compiler.run(
            null,
            null,
            new PrintStream(diagnostics, true, StandardCharsets.UTF_8),
            args.toArray(String[]::new));
    assertThat(rc).as("插件源码编译失败: %s", diagnostics.toString(StandardCharsets.UTF_8)).isZero();
    assertThat(classes.resolve("META-INF/extensions.idx")).as("APT 应生成扩展索引").exists();
    return classes;
  }

  /** 打包成 PF4J 插件 JAR（pluginId/version 为 null 即"缺描述符"的负面 fixture）。 */
  private static void writeJar(Path jar, Path classesDir, String pluginId, String version)
      throws IOException {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (pluginId != null) {
      attributes.putValue("Plugin-Id", pluginId);
    }
    if (version != null) {
      attributes.putValue("Plugin-Version", version);
    }
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        Stream<Path> files = Files.walk(classesDir)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        out.putNextEntry(new JarEntry(classesDir.relativize(file).toString().replace('\\', '/')));
        out.write(Files.readAllBytes(file));
        out.closeEntry();
      }
    }
  }

  /** 插件源码模板：{@code @Extension} 的 {@link io.mosire.agentlib.plugin.ToolSource}；工具执行结果回带本源类加载器身份。 */
  private static String sourceTemplate(
      String pkg, String className, String sourceId, List<String> toolNames) {
    String tools =
        toolNames.isEmpty()
            ? "List.of()"
            : "List.of("
                + toolNames.stream()
                    .map(name -> "tool(\"" + name + "\")")
                    .collect(Collectors.joining(", "))
                + ")";
    return """
        package %s;

        import io.mosire.agentlib.plugin.ToolSource;
        import io.mosire.agentlib.tool.AgentTool;
        import io.mosire.agentlib.tool.ToolContext;
        import io.mosire.agentlib.tool.ToolResult;
        import java.util.List;
        import org.pf4j.Extension;

        @Extension
        public class %s implements ToolSource {

          @Override
          public String id() {
            return "%s";
          }

          @Override
          public List<AgentTool> listTools() {
            return %s;
          }

          @Override
          public AutoCloseable onChange(Runnable listener) {
            return () -> {};
          }

          private static AgentTool tool(String name) {
            return new AgentTool() {
              @Override
              public String name() {
                return name;
              }

              @Override
              public ToolResult execute(ToolContext context) {
                return ToolResult.ok("plugin-cl=" + getClass().getClassLoader());
              }
            };
          }
        }
        """
        .formatted(pkg, className, sourceId, tools);
  }

  /**
   * 生命周期观测用的插件源码：把 {@code init/listTools/close} 的调用<b>按序</b>追加进一处标记文件。
   *
   * <p>为什么用标记文件而不是"插件里的静态计数器"：插件类由 {@code PluginClassLoader} 装载，测试类加载器读不到它的静态字段；
   * 文件是两侧都能看见的同一处事实，且能判<b>顺序</b>（顺序是 init 契约的一部分）。
   */
  private static String lifecycleTemplate(
      String pkg, String className, String sourceId, Path marker) {
    String literal = marker.toAbsolutePath().toString().replace("\\", "\\\\");
    return """
        package %s;

        import io.mosire.agentlib.plugin.HostServices;
        import io.mosire.agentlib.plugin.ToolSource;
        import io.mosire.agentlib.tool.AgentTool;
        import io.mosire.agentlib.tool.ToolContext;
        import io.mosire.agentlib.tool.ToolResult;
        import java.io.IOException;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.StandardOpenOption;
        import java.util.List;
        import org.pf4j.Extension;

        @Extension
        public class %s implements ToolSource {

          private static final Path MARKER = Path.of("%s");

          private static void mark(String line) {
            try {
              Files.writeString(
                  MARKER, line + "\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
              throw new IllegalStateException("标记写入失败", e);
            }
          }

          @Override
          public void init(HostServices services) {
            mark(
                "init:"
                    + (services.config() == null
                        ? "null"
                        : System.identityHashCode(services.config())));
          }

          @Override
          public String id() {
            return "%s";
          }

          @Override
          public List<AgentTool> listTools() {
            mark("listTools");
            return List.of(
                new AgentTool() {
                  @Override
                  public String name() {
                    return "%s_tool";
                  }

                  @Override
                  public ToolResult execute(ToolContext context) {
                    return ToolResult.ok("ok");
                  }
                });
          }

          @Override
          public AutoCloseable onChange(Runnable listener) {
            return () -> {};
          }

          @Override
          public void close() {
            mark("close");
          }
        }
        """
        .formatted(pkg, className, literal, sourceId, sourceId);
  }

  private void registerBuiltin(String... toolNames) {
    for (String name : toolNames) {
      registry.register(BUILTIN, fakeTool(name));
    }
  }

  private static AgentTool fakeTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("builtin:" + name);
      }
    };
  }

  private static String codeSource(Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    } catch (Exception e) {
      throw new IllegalStateException("无法定位 " + type.getName() + " 的代码位置", e);
    }
  }
}
