package io.mosire.main.setup;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.LlmException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * CLI 配置引导向导（配置引导 D1/D7）：渠道菜单 → 填 baseUrl/model（预设可回车采纳）→ 密钥（经 {@link SecretReader}，生产是 {@code
 * Console.readPassword} 不回显）→ 探测（{@link LlmProviderProbe}， 失败回到菜单重试）→ 写盘（{@link LlmConfigWriter}）。
 *
 * <p><b>输入输出全部经注入</b>（{@link LineReader}/{@link SecretReader}/{@link PrintStream}）：生产由 {@code Main}
 * 绑 {@code System.console()}（仅真交互终端会进向导，D24 的 TTY 门在那边），测试注入脚本流。 输出走 stderr 通道由调用方决定——主进程 stdout 是
 * MCP stdio 流，本类不碰 {@code System.out}。
 *
 * <p>路由名恒写 {@code default}（单渠道起步；多路由属既有按名路由的配置手艺，不进向导）。
 */
public final class LlmSetupWizard {

  /** 一行文本输入（prompt → 行；EOF 抛 {@link IOException} 由调用方处置）。 */
  public interface LineReader {

    String readLine(String prompt) throws IOException;
  }

  /** 密钥输入（prompt → 字符数组；生产不回显）。 */
  public interface SecretReader {

    char[] readPassword(String prompt) throws IOException;
  }

  /** 探测口（D5）：生产 = {@link LlmProviderProbe#ping}；测试注入假探测，不碰真网。 */
  @FunctionalInterface
  public interface Probe {

    void ping(String baseUrl, String model, String apiKey) throws LlmException;
  }

  /** 渠道预设（D7）：缺省 baseUrl/model 可回车采纳。 */
  private record Channel(String label, String defaultBaseUrl, String defaultModel) {}

  private static final List<Channel> CHANNELS =
      List.of(
          new Channel(
              "GLM（智谱，OpenAI 兼容）", "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.3-flash"),
          new Channel("DeepSeek（OpenAI 兼容）", "https://api.deepseek.com", "deepseek-chat"));

  private static final String CANCEL = "4";

  /** 引导期间用的路由名（单渠道起步，见类注释）。 */
  static final String ROUTE_NAME = "default";

  private final Probe probe;

  /** 生产装配：真探测。 */
  public LlmSetupWizard() {
    this(LlmProviderProbe::ping);
  }

  /** 测试装配：注入探测（失败即抛 {@link LlmException}）。 */
  LlmSetupWizard(Probe probe) {
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  /**
   * 跑一轮引导（探测失败自动回到菜单；用户选取消返回 false）。
   *
   * @param configRoot 宿主配置根（config.json 落这里）
   * @return true = 已写好配置，调用方可继续启动；false = 用户放弃
   * @throws IOException 终端读失败（调用方处置——通常是直接失败退出）
   */
  public boolean run(Path configRoot, LineReader in, SecretReader secrets, PrintStream out)
      throws IOException {
    Objects.requireNonNull(configRoot, "configRoot");
    FileConfigStore store = new FileConfigStore(configRoot);
    out.println();
    out.println("未检测到 LLM 配置，进入配置引导（写入 " + configRoot.resolve("config.json") + "）");
    while (true) {
      out.println("选择 LLM 渠道：");
      out.println("  1) " + CHANNELS.get(0).label());
      out.println("  2) " + CHANNELS.get(1).label());
      out.println("  3) 自定义 OpenAI 兼容端点");
      out.println("  4) 取消（退出；可改用 --setup 经 HTTP 引导，或手写 config.json）");
      String choice = in.readLine("选择 [1-4]: ").strip();
      if (CANCEL.equals(choice)) {
        return false;
      }
      String baseUrl;
      String model;
      if ("1".equals(choice) || "2".equals(choice)) {
        Channel channel = CHANNELS.get(Integer.parseInt(choice) - 1);
        out.println("渠道：" + channel.label());
        baseUrl = withDefault(in, "baseUrl", channel.defaultBaseUrl());
        model = withDefault(in, "model", channel.defaultModel());
      } else if ("3".equals(choice)) {
        out.println("自定义 OpenAI 兼容端点");
        baseUrl = requireNonBlank(in, out, "baseUrl（完整地址，如 http://localhost:11434/v1）");
        model = requireNonBlank(in, out, "model");
      } else {
        out.println("无效选择。");
        continue;
      }
      String apiKey = new String(secrets.readPassword("API Key（留空 = 匿名；输入不回显）: ")).strip();
      out.println("探测中：" + model + " @ " + baseUrl + " …");
      try {
        probe.ping(baseUrl, model, apiKey);
      } catch (LlmException probeFailure) {
        out.println("探测失败：" + probeFailure.getMessage());
        out.println("配置未写入。请重选渠道重试（4 = 放弃）。");
        continue;
      }
      LlmConfigWriter.write(store, ROUTE_NAME, baseUrl, model, apiKey);
      out.println("配置已写入（路由 " + ROUTE_NAME + "，模型 " + model + "）。继续启动。");
      return true;
    }
  }

  /** 带缺省值的输入：直接回车 = 采纳缺省。 */
  private static String withDefault(LineReader in, String what, String dflt) throws IOException {
    String line = in.readLine(what + " [回车 = " + dflt + "]: ").strip();
    return line.isEmpty() ? dflt : line;
  }

  /** 必填输入：空白提示后重问。 */
  private static String requireNonBlank(LineReader in, PrintStream out, String what)
      throws IOException {
    while (true) {
      String line = in.readLine(what + ": ").strip();
      if (!line.isEmpty()) {
        return line;
      }
      out.println("不得为空白，请重填。");
    }
  }
}
