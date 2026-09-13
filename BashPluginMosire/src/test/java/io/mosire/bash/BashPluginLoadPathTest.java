package io.mosire.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.plugin.HostServices;
import io.mosire.agentlib.plugin.PluginListener;
import io.mosire.agentlib.plugin.PluginToolSource;
import io.mosire.agentlib.plugin.PluginToolSource.PluginLoadException;
import io.mosire.agentlib.plugin.PluginToolSource.PluginStatus;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginClassLoader;

/**
 * <b>真装载路径</b>用例（设计 §五.2/§五.3 的自动化版本；派发包 F.3）：把本模块自己的编译产物（{@code target/classes} 下的 {@code
 * io/mosire/bash/*.class} + APT 生成的 {@code META-INF/extensions.idx}）打成临时插件 JAR，经 {@link
 * PluginToolSource#loadAll()} 真装一遍。
 *
 * <p><b>为什么这条用例不可省</b>：{@code AgentLibMosire} 的 {@code PluginToolSourceTest} 用的是<b>测试期现场编译的合成插件</b>
 * ——它证明装载器对"形态正确的插件"有效，但证不了<b>本模块真的产出那种形态</b>。这条链路里的每一环都可能断：注解处理器没跑（{@code extensions.idx}
 * 缺失）、描述符没写（{@code Plugin-Id} 缺失）、类没编进去、插件声明的 sourceId 与工具名和宿主对不上。 它钉住的正是这三样：<b>idx 在、描述符在、装载后
 * registry 里出现 {@code bash} 源与 {@code bash} 工具</b>。
 *
 * <p><b>打的是本模块自己的 class 文件</b>（不复制、不重编）：{@code BashToolSource} 的 {@code @Extension} 与它引用的 {@code
 * ShellTool}/{@code BashToolConfigLoader} 就是生产那份，所以这条用例的绿＝"这个模块的产物能被真装载"。 打包形态的另外两条判据（宿主 fat jar
 * 里不含 bash、{@code plugins/} 里恰好一个 JAR）是 Maven 打包面的检查，不在本用例射程内。
 *
 * <p>版本串用<b>生产同款形态</b>（带 {@code -SNAPSHOT} 后缀，与 pom 的 {@code Plugin-Version=${project.version}}
 * 同形）：PF4J 必须接受这种形态，否则真部署起不来——本用例顺带把它钉住。
 */
@Timeout(120)
class BashPluginLoadPathTest {

  /** 与 {@code BashPluginMosire/pom.xml} 的 {@code Plugin-Version} 同形（SNAPSHOT 后缀是重点，不是具体数字）。 */
  private static final String PLUGIN_VERSION = "0.1.0-SNAPSHOT";

  @TempDir Path tempDir;

  @Test
  void theRealJarLoadsThroughThePluginToolSourceAndReportsStarted() throws IOException {
    Path pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
    writePluginJar(pluginsDir.resolve("bash-plugin-mosire-" + PLUGIN_VERSION + ".jar"));

    ToolRegistry registry = new ToolRegistry();
    List<String> events = new ArrayList<>();
    try (PluginToolSource source =
        new PluginToolSource(
            pluginsDir,
            registry,
            (pluginId, version, state) -> events.add(pluginId + "|" + state + "|" + version),
            HostServices.none())) {

      assertThat(source.loadAll()).containsExactly(BashToolSource.ID);

      // 工具登记在插件自己声明的 sourceId 下（不是无源、不是 builtin），且工具类来自插件的类加载器
      assertThat(registry.sourceIds()).contains(BashToolSource.ID);
      List<AgentTool> tools = registry.listBySource(BashToolSource.ID);
      assertThat(tools).extracting(AgentTool::name).containsExactly(ShellTool.NAME);
      assertThat(tools.get(0).getClass().getClassLoader()).isInstanceOf(PluginClassLoader.class);

      // 状态快照：pluginId/sourceId 都是 bash，state=STARTED（判据 5 的"启动行与事件同源"在这里的可判据内核）
      assertThat(source.list())
          .extracting(
              PluginStatus::pluginId,
              PluginStatus::sourceId,
              PluginStatus::version,
              PluginStatus::state)
          .containsExactly(
              tuple(
                  BashToolSource.ID,
                  BashToolSource.ID,
                  PLUGIN_VERSION,
                  PluginListener.State.STARTED));
      assertThat(events).containsExactly(BashToolSource.ID + "|STARTED|" + PLUGIN_VERSION);
    }
  }

  /** 宿主经 {@link HostServices} 注入的 {@code ConfigStore} 真的走到了工具里（不是"init 收到了但没用"）。 */
  @Test
  void theHostConfigStoreReachesTheToolThroughInit() throws IOException {
    Path configRoot = Files.createDirectories(tempDir.resolve("config"));
    Files.write(
        configRoot.resolve("config.json"),
        "{\"tools\":{\"bash\":{\"commandLog\":\"full\"}}}".getBytes(StandardCharsets.UTF_8));
    Path pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
    writePluginJar(pluginsDir.resolve("bash.jar"));

    ToolRegistry registry = new ToolRegistry();
    try (PluginToolSource source =
        new PluginToolSource(
            pluginsDir,
            registry,
            PluginListener.none(),
            new HostServices(new FileConfigStore(configRoot)))) {
      source.loadAll();

      AgentTool tool = registry.listBySource(BashToolSource.ID).get(0);
      Map<String, Object> ledger =
          tool.ledgerArgs(
              new ToolContext(
                  AccessToken.DEFAULT,
                  AgentPermissionSet.system(),
                  Map.of(),
                  Map.of("command", "ls -la")));
      // commandLog=full 才会把原参数原样落账——缺省（digest）下这里只有 digest/len/class
      assertThat(ledger).containsEntry("command", "ls -la");
    }
  }

  /**
   * 判据 2（装载期校验不回退）：{@code tools.bash.*} 值非法 ⇒ <b>装载</b>响亮失败，不是"跑到第一次调用才炸"。
   *
   * <p>变异自证：把 {@link BashToolSource#init} 里的 {@code BashToolConfigLoader.load} 换掉/删掉，本用例转红。
   */
  @Test
  void badConfigFailsTheLoadLoudlyAtLoadTime() throws IOException {
    Path configRoot = Files.createDirectories(tempDir.resolve("config"));
    Files.write(
        configRoot.resolve("config.json"),
        "{\"tools\":{\"bash\":{\"sandbox\":\"maybe\"}}}".getBytes(StandardCharsets.UTF_8));
    Path pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
    writePluginJar(pluginsDir.resolve("bash.jar"));

    ToolRegistry registry = new ToolRegistry();
    List<String> events = new ArrayList<>();
    try (PluginToolSource source =
        new PluginToolSource(
            pluginsDir,
            registry,
            (pluginId, version, state) -> events.add(pluginId + "|" + state + "|" + version),
            new HostServices(new FileConfigStore(configRoot)))) {

      assertThatThrownBy(source::loadAll)
          .isInstanceOf(PluginLoadException.class)
          .hasMessageContaining("sandbox");
      // 回滚到底：没有半装载的源、没有工具，但"试过并失败"在事件里可辨
      assertThat(registry.sourceIds()).doesNotContain(BashToolSource.ID);
      assertThat(registry.list()).isEmpty();
      assertThat(events).containsExactly(BashToolSource.ID + "|FAILED|" + PLUGIN_VERSION);
    }
  }

  /** 插件 JAR 的形态（D3.2）：带 idx、带插件自己的类、<b>不带</b>宿主 API 的类（那些由父加载器给）。 */
  @Test
  void thePluginJarCarriesOnlyThePluginsOwnClasses() throws IOException {
    Path pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
    Path jar = writePluginJar(pluginsDir.resolve("bash.jar"));

    List<String> entries = jarEntries(jar);
    assertThat(entries)
        .contains("META-INF/extensions.idx", "io/mosire/bash/BashToolSource.class")
        .contains("io/mosire/bash/ShellTool.class")
        .noneMatch(name -> name.startsWith("io/mosire/agentlib/"))
        .noneMatch(name -> name.startsWith("io/mosire/brain/"));
  }

  // ---------- 夹具 ----------

  /** 本模块的编译产物目录（surefire 下是 {@code BashPluginMosire/target/classes}，由代码位置反查，不读 cwd）。 */
  private static Path moduleClasses() {
    try {
      return Path.of(
          BashToolSource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (Exception e) {
      throw new IllegalStateException("拿不到本模块的 target/classes 位置", e);
    }
  }

  /**
   * 把本模块的编译产物打成插件 JAR（{@code Plugin-Id=bash}）。
   *
   * <p>不筛文件：<b>整个 {@code target/classes} 原样入包</b>——筛过的清单会让"这个模块的产物里混进了宿主类"这类问题查不出来。 {@code
   * extensions.idx} 缺失说明注解处理器没跑（那是插件形态的根本缺陷），故这里<b>响亮失败</b>而不是跳过。
   */
  private static Path writePluginJar(Path jar) throws IOException {
    Path classes = moduleClasses();
    Path idx = classes.resolve("META-INF/extensions.idx");
    if (!Files.isRegularFile(idx)) {
      throw new IllegalStateException("编译产物里没有 META-INF/extensions.idx（PF4J 注解处理器没跑？）: " + idx);
    }
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Plugin-Id", BashToolSource.ID);
    attributes.putValue("Plugin-Version", PLUGIN_VERSION);
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        Stream<Path> files = Files.walk(classes)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        out.write(Files.readAllBytes(file));
        out.closeEntry();
      }
    }
    return jar;
  }

  private static List<String> jarEntries(Path jar) throws IOException {
    List<String> entries = new ArrayList<>();
    try (JarFile in = new JarFile(jar.toFile())) {
      in.stream().map(JarEntry::getName).forEach(entries::add);
    }
    return entries;
  }
}
