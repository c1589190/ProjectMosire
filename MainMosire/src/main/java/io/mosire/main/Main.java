package io.mosire.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 入口/CLI 分发（手写子命令分派——决策 D3：不引 CLI 框架，保持零依赖）。
 *
 * <p>子命令：{@code run}（M1：网关守护 + --demo 自检回合）、{@code health}（当前进程自检）、 {@code events}（查看本地事件库尾部，便于无
 * sqlite3 环境验收）、{@code agent}/{@code doctor}/ {@code config}（M2/M3 实现）。
 */
public final class Main {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Main() {}

  public static void main(String[] args) {
    try {
      int code = dispatch(args);
      if (code != 0) {
        System.exit(code);
      }
    } catch (IllegalArgumentException e) {
      System.err.println("参数错误: " + e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      System.err.println("运行失败: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static int dispatch(String[] args) throws InterruptedException {
    if (args.length == 0) {
      usage();
      return 2;
    }
    switch (args[0]) {
      case "run":
        return run(args);
      case "health":
        return health();
      case "events":
        return events(args);
      case "agent":
        System.err.println("agent 子命令在 M2（子 Agent stdio 模式）实现");
        return 1;
      case "doctor":
        System.err.println("doctor 子命令在 M3 实现");
        return 1;
      case "config":
        System.err.println("config 子命令在 M3（ConfigStore）实现");
        return 1;
      default:
        usage();
        return 2;
    }
  }

  private static int run(String[] args) throws InterruptedException {
    BootConfig config = BootConfig.defaults();
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> config = withPort(config, Integer.parseInt(requireValue(args, ++i)));
        case "--data-dir" -> config = withDataDir(config, Path.of(requireValue(args, ++i)));
        case "--demo" -> config = withDemo(config, true);
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    App app = App.start(config);
    System.out.printf(
        "Mosire v%s 已启动: http://127.0.0.1:%d%s%n",
        Version.VERSION, app.boundPort(), config.demo() ? "（demo 模式）" : "");
    app.awaitTermination();
    return 0;
  }

  private static String requireValue(String[] args, int index) {
    if (index >= args.length) {
      throw new IllegalArgumentException("缺少参数值（第 " + index + " 个）");
    }
    return args[index];
  }

  private static BootConfig withPort(BootConfig config, int port) {
    return new BootConfig(port, config.dataDir(), config.demo(), config.demoMessage());
  }

  private static BootConfig withDataDir(BootConfig config, Path dataDir) {
    return new BootConfig(config.port(), dataDir, config.demo(), config.demoMessage());
  }

  private static BootConfig withDemo(BootConfig config, boolean demo) {
    return new BootConfig(config.port(), config.dataDir(), demo, "");
  }

  private static int health() {
    try {
      System.out.println(
          JSON.writeValueAsString(
              Map.of(
                  "status",
                  "ok",
                  "artifact",
                  Version.ARTIFACT_ID,
                  "version",
                  Version.VERSION,
                  "message",
                  "本命令仅报告当前进程信息；运行态健康检查请访问 AdminREST /health")));
      return 0;
    } catch (Exception e) {
      System.err.println("health 自检失败: " + e.getMessage());
      return 1;
    }
  }

  /** 本地事件库尾部最近 N 条（默认 10）：无 sqlite3 环境的验收通道。 */
  private static int events(String[] args) {
    Path dataDir = BootConfig.DEFAULT_DATA_DIR;
    int limit = 10;
    for (int i = 1; i < args.length; i += 2) {
      if (i + 1 >= args.length) {
        System.err.println("events 参数成对出现: --data-dir <dir> [--limit <n>]");
        return 2;
      }
      switch (args[i]) {
        case "--data-dir" -> dataDir = Path.of(args[i + 1]);
        case "--limit" -> limit = Integer.parseInt(args[i + 1]);
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
      List<Event> events = store.query(EventQuery.recent(limit));
      System.out.println("seq | ts | type | agent | correlationId");
      for (Event event : events) {
        System.out.printf(
            "%d | %s | %s | %s | %s%n",
            event.seq(),
            DateTimeFormatter.ISO_INSTANT.format(event.ts()),
            event.type(),
            event.agent(),
            event.correlationId());
      }
      System.out.printf("[共 %d 条，显示最近 %d 条]%n", store.count(), events.size());
      return 0;
    } catch (Exception e) {
      System.err.println("读取事件库失败: " + e.getMessage());
      return 1;
    }
  }

  private static void usage() {
    // 占位符 + replace 而非 %s + formatted：文本块是普通字符串，避免 SpotBugs VA_FORMAT_STRING_USES_NEWLINE（改用 %n
    // 的提醒）
    System.out.println(
        """
        Mosire v{version} —— Java 模块化可自管理 Agent（P0）

        用法: java -jar mosire.jar <子命令> [选项]

        子命令:
          run             启动网关守护并执行主 Agent 主循环
             --port <n>      监听端口（默认 8080；0=自动分配）
             --data-dir <p>  数据目录（默认 .work/mosire）
             --demo          启动时先跑一条脚本问答（自检：事件入库）
          health          当前进程健康自检（进程内信息）
          events          查看本地事件库尾部
             --data-dir <p>  数据目录（默认 .work/mosire）
             --limit <n>     显示条数（默认 10）
          agent           （M2）子 Agent stdio 进程模式
          doctor          （M3）自检
          config          （M3）配置管理
        """
            .replace("{version}", Version.VERSION));
  }
}
