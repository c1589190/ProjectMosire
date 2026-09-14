package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.runtime.EventTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件面宿主接线用例（工作束四，设计 §五）：
 *
 * <ul>
 *   <li><b>解析顺序（D4，纯函数）</b>：{@code plugins.enabled=false} 不装载 / {@code plugins.dir} 显式目录优先 / 缺省 =
 *       代码位置同目录 {@code plugins/}（不存在 ⇒ 未启用，不报错）；键值非法 ⇒ 响亮失败；
 *   <li><b>真装载经 App.start</b>：插件目录配好后 bash 工具进全局目录、{@code plugin.lifecycle}{@code STARTED} 落库 （判据 5
 *       的离线内核）、关停后整组摘除；
 *   <li><b>装载期校验不回退（判据 2，App 级钉子）</b>：{@code tools.bash.*} 值非法 ⇒ App.start 响亮失败—— 变异 = 把插件 {@code
 *       init} 里的配置加载删掉 ⇒ 本用例转红（启动会变成"成功但按缺省配置"）。
 * </ul>
 *
 * <p>夹具直接打包 {@code BashPluginMosire/target/classes}（reactor 中先于本模块构建），与 {@code
 * BashPluginLoadPathTest} 同手法：生产 class + APT 产物原样入包，缺 {@code extensions.idx} 即响亮失败。
 */
@Timeout(120)
class AppPluginsWiringTest {

  private static final String PLUGIN_VERSION = "0.1.0-SNAPSHOT";

  @TempDir Path tempDir;

  // ---------- resolvePluginsDir：D4 解析顺序（纯函数） ----------

  @Test
  void withoutAnyConfigTheDefaultDirectoryIsNextToTheJar() throws IOException {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));
    Files.createDirectories(tempDir.resolve("apps"));
    Path jar = Files.createFile(tempDir.resolve("apps").resolve("mosire.jar"));

    App.PluginDirResolution resolution = App.resolvePluginsDir(store, jar);

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason())
        .contains("目录不存在")
        .contains(tempDir.resolve("apps").resolve("plugins").toString());
  }

  @Test
  void classesFormStartDoesNotDeriveADefaultPluginsDirectory() throws IOException {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));
    Path classesDir = Files.createDirectories(tempDir.resolve("apps").resolve("classes"));

    App.PluginDirResolution resolution = App.resolvePluginsDir(store, classesDir);

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("非 jar 启动");
  }

  @Test
  void missingCodeLocationDoesNotDeriveADefaultPluginsDirectory() {
    FileConfigStore store = new FileConfigStore(tempDir.resolve("conf"));

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(store, tempDir.resolve("nowhere").resolve("mosire.jar"));

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("非 jar 启动");
  }

  @Test
  void explicitPluginsDirWinsOverTheDefault() throws IOException {
    Path configured = tempDir.resolve("my-plugins");
    Files.createDirectories(configured);
    Files.createDirectories(tempDir.resolve("apps").resolve("plugins"));
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"dir\":\"" + configured + "\"}}");

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar"));

    assertThat(resolution.dir()).isEqualTo(configured.toAbsolutePath());
    assertThat(resolution.skipReason()).isNull();
  }

  @Test
  void defaultDirectoryIsDerivedWhenItExistsNextToTheJar() throws IOException {
    Files.createDirectories(tempDir.resolve("apps").resolve("plugins"));
    Path jar = Files.createFile(tempDir.resolve("apps").resolve("mosire.jar"));

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(tempDir.resolve("conf")), jar);

    // 生产路径的正分支：真 jar + 同目录 plugins/ ⇒ 原样返回（后续 loadAll 在其上装载）
    assertThat(resolution.dir())
        .isEqualTo(tempDir.resolve("apps").resolve("plugins").toAbsolutePath());
    assertThat(resolution.skipReason()).isNull();
  }

  @Test
  void explicitPluginsDirMissingFailsLoudlyAtConstruction() throws IOException {
    // 显式目录是硬要求：指向缺失目录 ≠ 缺省目录不存在（软跳过）——构造器响亮，语义由 resolvePluginsDir javadoc 定死
    Path missing = tempDir.resolve("no-such-plugins");
    Path dataDir = tempDir.resolve("data");
    writeConfig(dataDir, "{\"plugins\":{\"dir\":\"" + missing + "\"}}");

    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);
    assertThatThrownBy(() -> App.start(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("插件目录不存在");
  }

  @Test
  void enabledFalseDisablesThePluginFace() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"enabled\":false}}");

    App.PluginDirResolution resolution =
        App.resolvePluginsDir(new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar"));

    assertThat(resolution.dir()).isNull();
    assertThat(resolution.skipReason()).contains("plugins.enabled=false");
  }

  @Test
  void nonBooleanEnabledFailsLoudly() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"enabled\":\"yes\"}}");

    assertThatThrownBy(
            () ->
                App.resolvePluginsDir(
                    new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("plugins.enabled");
  }

  @Test
  void nonTextualPluginsDirFailsLoudly() throws IOException {
    Path conf = tempDir.resolve("conf");
    writeConfig(conf, "{\"plugins\":{\"dir\":123}}");

    assertThatThrownBy(
            () ->
                App.resolvePluginsDir(
                    new FileConfigStore(conf), tempDir.resolve("apps").resolve("x.jar")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("plugins.dir");
  }

  // ---------- App.start 接线：真插件装载 + 生命周期事件 + 装载期校验 ----------

  @Test
  void appStartLoadsBashPluginWritesLifecycleEventAndRemovesToolsOnClose() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path pluginsDir = tempDir.resolve("plugins");
    Files.createDirectories(pluginsDir);
    writeBashPluginJar(pluginsDir.resolve("bash.jar"));
    writeConfig(dataDir, "{\"plugins\":{\"dir\":\"" + pluginsDir + "\"}}");

    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);
    App app = App.start(config);
    try {
      // 判据 3 的离线内核：bash 工具进了全局目录，且归属插件源（不是 builtin）
      assertThat(app.runtime().registry().listBySource("bash"))
          .extracting(AgentTool::name)
          .containsExactly("bash");
      // 判据 5 的离线内核：plugin.lifecycle STARTED 落库，带真实 pluginId/version
      List<Event> lifecycle =
          app.queryEvents(new EventQuery("", EventTypes.PLUGIN_LIFECYCLE, "", -1, 50));
      assertThat(lifecycle)
          .anySatisfy(
              event -> {
                assertThat(event.payload())
                    .contains("\"pluginId\":\"bash\"")
                    .contains("\"version\":\"" + PLUGIN_VERSION + "\"")
                    .contains("\"state\":\"STARTED\"");
              });
    } finally {
      app.close();
    }
    // close 链回收插件面：工具整组摘除（"关停后不留外发面"）
    assertThat(app.runtime().registry().listBySource("bash")).isEmpty();
  }

  @Test
  void badBashConfigFailsAppStartLoudlyAtLoadTime() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path pluginsDir = tempDir.resolve("plugins");
    Files.createDirectories(pluginsDir);
    writeBashPluginJar(pluginsDir.resolve("bash.jar"));
    writeConfig(
        dataDir,
        "{\"plugins\":{\"dir\":\""
            + pluginsDir
            + "\"},\"tools\":{\"bash\":{\"sandbox\":\"maybe\"}}}");

    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);
    // 判据 2（App 级）：坏配置在装载期响亮失败，不是"第一次 bash 调用才炸"；
    // 变异 = 删掉 BashToolSource.init 里的 BashToolConfigLoader.load ⇒ 本用例转红（启动会"成功"且按缺省配置）
    assertThatThrownBy(() -> App.start(config))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("sandbox");
  }

  // ---------- 夹具 ----------

  /** BashPluginMosire 的编译产物（reactor 中先于本模块构建；基于 surefire 的运行目录反查，不假设 cwd）。 */
  private static Path bashModuleClasses() {
    Path classes =
        Path.of("..", "BashPluginMosire", "target", "classes").toAbsolutePath().normalize();
    if (!Files.isDirectory(classes)) {
      throw new IllegalStateException(
          "找不到 BashPluginMosire/target/classes（reactor 应已先行构建插件模块）: " + classes);
    }
    return classes;
  }

  /** 把插件模块的编译产物打成插件 JAR（Plugin-Id=bash）；缺 extensions.idx 说明 APT 没跑——响亮失败。 */
  private static void writeBashPluginJar(Path jar) throws IOException {
    Path classes = bashModuleClasses();
    Path idx = classes.resolve("META-INF").resolve("extensions.idx");
    if (!Files.isRegularFile(idx)) {
      throw new IllegalStateException("插件编译产物缺 META-INF/extensions.idx（PF4J 注解处理器没跑？）: " + idx);
    }
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Plugin-Id", "bash");
    attributes.putValue("Plugin-Version", PLUGIN_VERSION);
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        Stream<Path> files = Files.walk(classes)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        out.write(Files.readAllBytes(file));
        out.closeEntry();
      }
    }
  }

  private static void writeConfig(Path configRoot, String json) throws IOException {
    Files.createDirectories(configRoot);
    Files.writeString(configRoot.resolve("config.json"), json, StandardCharsets.UTF_8);
  }
}
