package io.mosire.brain.tools;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.CommandMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * bash 命令的<b>三档分流器</b>（S4-C）：直放 {@link ToolGate.Allow} / 问人 {@link ToolGate.Ask} / 硬拒 {@link
 * ToolGate.Block}。纯函数：不吃文件系统、不起进程、不读时钟、不读配置——同一条命令每次得到同一个结论（这是它能被单测与变异钉住的前提）。
 *
 * <p><b>★ 诚实声明（逐字照拷设计 §2.1，别把这段删短）</b>：静态分类可被变量/别名/{@code eval}/写脚本二次执行绕过（{@code x=rm; $x -rf
 * /}）——<b>分类器是"人的检查点的分流器"，不是安全边界</b>。真正的边界是：L3 OS 沙箱（S5，子体强制）+ 权限面（工具名级）+ <b>人</b>（审批）。
 * 因此内置清单<b>刻意小、高精度、宁可漏不可误伤</b>（误伤的代价是主 Agent 正常干活被拦，会促使用户关掉整个机制）。
 *
 * <p><b>判断的骨架</b>：
 *
 * <ol>
 *   <li><b>切分</b>：按 {@code ;} {@code &&} {@code ||} {@code |} 换行 与 {@code $(...)}/反引号内的子命令切开，
 *       逐段分类、<b>取最严者</b>（严 → 宽：{@code Block > Ask > Allow}）。取最严是"切分"这个动作的全部意义—— 取首段或末段都会让 {@code
 *       echo ok; rm -rf /} 这类复合命令漏判；
 *   <li><b>每段归一化</b>：剥掉 {@code VAR=x} 前缀与包装词（sudo/env/nohup/time/nice/setsid/…），得到<b>可执行名</b>
 *       与关键参数尾部（{@code rm} 的路径、{@code dd} 的 {@code of=}、{@code chmod}/{@code chown} 的递归标志与目标、 重定向目标
 *       {@code > /xxx}）；
 *   <li><b>三张清单</b>见下（清单原文 = 设计附录 A）。
 * </ol>
 *
 * <p><b>{@code classKey}</b>（{@code bash:<档>:<首词>}，如 {@code bash:ask:crontab}）是会话级 remember 的键：
 * 它必须<b>稳定</b>（同一类命令每次相同）且<b>不含命令全文</b>——把参数拼进去会让 session 记忆永远命中不了。 故 {@code classKey}
 * 只用"最严那一段的可执行名"（路径前缀 {@code /usr/bin/apt} 归一为 {@code apt}）。
 *
 * <p><b>{@code summary}</b> 可以含命令原文（它只进内存态审批请求与 tty/HTTP 提示面，<b>不进事件库</b>）；事件库里只有 {@code
 * digest}。<b>{@code reason}（Block 用）带"命中的那个值"是给日志调试用的</b>——它<b>不进事件面</b>：给模型的消息由 {@code
 * ToolCallAuthorizer} 组装时裁掉细节，只留 {@code class=bash:block:<首词>}（模型只需要知道"这一类被拒"，
 * 参数本来就是它自己写的，回显零信息增益，而那条消息会落进事件库）。
 *
 * <p><b>配置只增不删（结构性，不是"我们记得别删"）</b>：内置清单是本类的 {@code private static final} 常量， 外界给进来的只有 {@code
 * blockedExtra}/{@code askExtra} 两个<b>追加</b>入口，本类<b>没有</b>任何"覆盖/清空内置清单"的形参或分支
 * ——要"删掉某条内置条目"必须改本类源码。配置项被 {@code addAll} 进副本，原清单逐字不动。
 *
 * <p><b>已知的边界（如实记账，别把没识别的写成"已覆盖"）</b>：变量间接（{@code x=rm; $x -rf /}）、{@code eval}/{@code sh -c
 * "<字符串>"}、{@code xargs rm}、别名、脚本二次执行都<b>不</b>识别；这些是最外层"不是安全边界"的具体形态，属设计接受的漏判。
 */
public final class BashCommandClassifier {

  /** 档位（严 → 宽：{@code BLOCK > ASK > ALLOW}）。 */
  public enum Level {
    ALLOW,
    ASK,
    BLOCK;

    /** {@code tool.call} 事件里 {@code args.class} 的取值（小写，闭词表）。 */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 硬拒（不可审批）：判据 = <b>不可回滚 × 几乎不可能是任务本意</b>。 */
  private static final Set<String> BLOCKED_HEADS =
      Set.of(
          // 块设备/文件系统（前缀匹配另见 mkfs*）
          "fdisk",
          "parted",
          // 关机/重启
          "shutdown",
          "reboot",
          "halt",
          "poweroff",
          // 进程灾变
          "killall5");

  /** {@code mkfs} 家族（mkfs、mkfs.ext4、mkfs.xfs…）——用前缀判，因为后缀是文件系统类型。 */
  private static final String MKFS_PREFIX = "mkfs";

  /** 硬拒路径树：{@code rm} 命中其中任何一个（等于或在其下）即硬拒；{@code chmod -R}/{@code chown -R}/{@code mv} 同表。 */
  private static final List<String> HARD_PATH_ROOTS = List.of("/etc", "/usr", "/boot", "/var");

  /** 硬拒路径的<b>字面形态</b>（含变量写法 {@code $HOME}，以及"根 + 通配"两种最危险写法）。 */
  private static final Set<String> HARD_PATH_LITERALS =
      Set.of(
          "/",
          "/*",
          "/**",
          "//",
          "~",
          "~/",
          "~/*",
          "$HOME",
          "$HOME/",
          "$HOME/*",
          "${HOME}",
          "${HOME}/");

  /** 块设备路径前缀（{@code dd of=…} 与 {@code > /dev/…} 用）。 */
  private static final List<String> DEVICE_PREFIXES = List.of("sd", "nvme", "mmcblk", "vd");

  /** 包管理器（ASK）：子命令面按附录 A"install/remove/purge/upgrade"实现。 */
  private static final Set<String> PACKAGE_MANAGERS =
      Set.of("apt", "apt-get", "aptitude", "yum", "dnf", "pacman");

  private static final Set<String> PACKAGE_ASK_SUBCOMMANDS =
      Set.of(
          "install",
          "remove",
          "purge",
          "upgrade",
          "full-upgrade",
          "dist-upgrade",
          "autoremove",
          "erase",
          "reinstall");

  /** {@code systemctl} 的<b>查询</b>子命令：这些不问人（附录 A 明写"非查询子命令"才 ASK）。 */
  private static final Set<String> SYSTEMCTL_QUERIES =
      Set.of(
          "status",
          "is-active",
          "is-enabled",
          "is-failed",
          "is-system-running",
          "list-units",
          "list-unit-files",
          "list-timers",
          "list-dependencies",
          "list-sockets",
          "show",
          "cat",
          "get-default",
          "show-environment",
          "help");

  /** {@code systemctl} 里等于"关机/重启"的子命令：归 BLOCK（与附录 A 的 shutdown/reboot 同类，不可回滚）。 */
  private static final Set<String> SYSTEMCTL_POWER_SUBCOMMANDS =
      Set.of("reboot", "poweroff", "halt", "kexec", "suspend", "hibernate", "emergency", "rescue");

  /** 包装词：跳过它们继续找真正的可执行名（否则 {@code sudo rm -rf /} 的首词是 sudo，永远匹配不上）。 */
  private static final Set<String> WRAPPERS =
      Set.of(
          "sudo", "doas", "env", "nohup", "time", "nice", "ionice", "setsid", "command", "exec",
          "stdbuf", "timeout");

  /**
   * 包装词里"吃一个值"的短选项（跳过它们<b>以及</b>紧随的值）：如 {@code sudo -u root cmd}、{@code env -u X cmd}。
   *
   * <p>这张表刻意小：多写一个"其实不吃值"的选项会吞掉真正的命令名（{@code sudo -n rm …} 里把 {@code rm} 当值吃掉）， 那正是漏判。故宁可不吃值。
   */
  private static final Set<String> WRAPPER_VALUE_FLAGS =
      Set.of("-u", "-g", "-p", "-C", "-U", "--user", "--group", "--chdir", "--prompt");

  /** 管道下游的 shell：{@code curl … | sh} 形态（下载即执行）的右半边。 */
  private static final Set<String> SHELLS = Set.of("sh", "bash", "dash", "zsh", "ksh");

  /** {@code curl}/{@code wget}：只有"管道进 shell"才 ASK，单纯下载不问（主 Agent 的正常工作）。 */
  private static final Set<String> DOWNLOADERS = Set.of("curl", "wget");

  /** 写 {@code ~/.bashrc} 一类的持久化目标（ASK；不在 /etc 下但同属"计划任务/持久化"）。 */
  private static final Set<String> PERSISTENCE_FILES =
      Set.of("~/.bashrc", "$HOME/.bashrc", "${HOME}/.bashrc");

  /** 概况行里命令原文的截断长度（提示面给人看，太长反而不便判读）。 */
  private static final int SUMMARY_COMMAND_MAX = 160;

  private static final String LEVEL_PREFIX = "bash:";

  private BashCommandClassifier() {}

  /**
   * 分类一条命令（<b>纯函数</b>）。
   *
   * @param command 命令原文（可为 null/空白 ⇒ {@link ToolGate.Allow}：没有命令文本时执行器自己会报参数非法，
   *     分类器不替它报错、更不编造一个"需审批"）
   * @param blockedExtra 追加硬拒条目（可执行名；<b>只能加</b>，null = 无）。命中的一律 Block——它比内置 ASK 与 askExtra 都严，故优先判定
   * @param askExtra 追加需审批条目（可执行名；<b>只能加</b>，null = 无）
   */
  public static ToolGate classify(
      String command, List<String> blockedExtra, List<String> askExtra) {
    return classify(command, blockedExtra, askExtra, CommandMode.FULL);
  }

  /**
   * 同上，但带<b>调用者档位</b>（2026-09-13 用户裁决）：
   *
   * <ul>
   *   <li>{@link CommandMode#FULL}：逐字等于三参重载（直放 / 问人 / 硬拒 三档照旧）；
   *   <li>{@link CommandMode#LIMITED}：<b>所有</b>命令都要审批——"直放"档被<b>提升</b>为 {@link ToolGate.Ask}
   *       （{@code classKey} 用<b>独立</b>的一段 {@code bash:limited:<首词>}，见 {@link #limitedAsk}），
   *       硬拒档<b>不变</b>（硬拒不可审批，档位改不了它）。
   * </ul>
   *
   * <p><b>提升只在这里做</b>：审批人怎么定（沿派生链向上找第一个 {@code FULL} 的 Agent、找不到就到人）是审批层的事， 分类器只管"这一档要不要问"。
   *
   * @param mode 调用者档位（null 视同 {@link CommandMode#FULL}：缺省等于本功能引入前的行为）
   */
  public static ToolGate classify(
      String command, List<String> blockedExtra, List<String> askExtra, CommandMode mode) {
    if (command == null || command.isBlank()) {
      return ToolGate.ALLOW;
    }
    CommandMode effective = mode == null ? CommandMode.FULL : mode;
    Set<String> blockedNames = names(blockedExtra);
    Set<String> askNames = names(askExtra);

    if (isForkBomb(command)) {
      // fork bomb 的形态本身就由分隔符构成（{@code :(){ :|:& };:} 全是 | & ; ），切段之后再也拼不回来
      // ⇒ 必须在切段<b>之前</b>按整条命令判（去空白后比对）。段内那条同判据保留，兜住"某段文本自己就是完整形态"的情形。
      return blockedGate(FORK_BOMB_HEAD, "fork bomb（不可回滚）");
    }

    List<Segment> segments = segments(command);
    Verdict worst = null;
    String worstHead = null;
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      Verdict verdict = classifySegment(segment.text(), blockedNames, askNames);
      if (segment.pipedFromDownloader() && isShell(verdict.head())) {
        // 下载即执行（附录 A 的 ASK 条目）：判据跨段，故在这里而不是段内规则里补
        verdict = stricter(verdict, new Verdict(Level.ASK, verdict.head(), "下载内容管道进 shell"));
      }
      if (worst == null || verdict.level().ordinal() > worst.level().ordinal()) {
        worst = verdict;
        worstHead = verdict.head() != null ? verdict.head() : firstHead(segment.text());
      }
    }
    if (worst == null) {
      // 命令非空白却切不出任何段（纯赋值等）：没有可执行的命令 ⇒ 直放（执行器自己会跑它/报错）。
      // 不完全权限档例外：那一档"所有命令都要审批"，不留例外口（否则 `x=1; $x -rf /` 的赋值段就是绕过面）。
      return effective == CommandMode.LIMITED ? limitedAsk(command, null) : ToolGate.ALLOW;
    }
    if (worst.level() == Level.ALLOW && effective == CommandMode.LIMITED) {
      return limitedAsk(command, worstHead);
    }
    String classKey = classKey(worst.level(), worstHead);
    return switch (worst.level()) {
      case ALLOW -> ToolGate.ALLOW;
      case ASK -> new ToolGate.Ask(classKey, summary(command, classKey), AskKind.SENSITIVE);
      case BLOCK -> blockedGate(worstHead, worst.reason());
    };
  }

  // ---------- 段内判定 ----------

  /** 逐段三档判定：按"最严者胜出"由调用方汇总。 */
  private static Verdict classifySegment(
      String segment, Set<String> blockedNames, Set<String> askNames) {
    List<String> tokens = tokens(segment);
    int headAt = headIndex(tokens);
    if (headAt < 0) {
      return new Verdict(Level.ALLOW, null, null);
    }
    String head = baseName(tokens.get(headAt));
    List<String> args = List.copyOf(tokens.subList(headAt + 1, tokens.size()));
    List<String> targets = redirectTargets(segment);

    if (isForkBomb(segment)) {
      // fork bomb 不是"某个可执行名"的形态判定（{@code :(){ :|:& };:} 的"首词"没有意义）⇒ 按整段文本判
      return block(FORK_BOMB_HEAD, "fork bomb（不可回滚）");
    }
    if (blockedNames.contains(head)) {
      return new Verdict(Level.BLOCK, head, "配置的硬拒条目: " + head);
    }
    Verdict blocked = blockVerdict(head, args, targets);
    if (blocked != null) {
      return blocked;
    }
    Verdict asked = askVerdict(head, args, targets);
    if (asked != null) {
      return asked;
    }
    if (askNames.contains(head)) {
      return new Verdict(Level.ASK, head, "配置的需审批条目: " + head);
    }
    return new Verdict(Level.ALLOW, head, null);
  }

  /** 硬拒清单（附录 A 第一组；判据 = 不可回滚 × 几乎不可能是任务本意）。 */
  private static Verdict blockVerdict(String head, List<String> args, List<String> targets) {
    // 拒因带"命中的那个值"：这是日志调试面（ToolCallAuthorizer 在硬拒时打 WARN），
    // 不进事件面——给模型的消息只留 class。
    if (head.startsWith(MKFS_PREFIX)) {
      return block(head, "格式化文件系统（不可回滚）: " + head);
    }
    if (BLOCKED_HEADS.contains(head)) {
      return block(head, "关机/重启/分区/进程灾变类命令（不可回滚）: " + head);
    }
    if (head.equals("init") && args.stream().anyMatch(a -> a.equals("0") || a.equals("6"))) {
      return block(head, "init 0/6 = 关机或重启（不可回滚）");
    }
    if (head.equals("kill") && isKillAll(args)) {
      return block(head, "kill -9 -1 / kill -9 1 = 杀全部进程或 init（不可回滚）");
    }
    if (head.equals("rm")) {
      String hit = hardTarget(nonFlagTokens(args));
      if (hit != null) {
        return block(head, "rm 作用于系统关键路径（不可回滚）: " + hit);
      }
    }
    if (head.equals("mv")) {
      List<String> sources = nonFlagTokens(args);
      String hit = sources.isEmpty() ? null : hardPath(sources.get(0));
      if (hit != null) {
        return block(head, "mv 的源是系统关键路径（不可回滚）: " + hit);
      }
    }
    if (head.equals("chmod") || head.equals("chown")) {
      if (recursive(args)) {
        String hit = hardTarget(nonFlagTokens(args));
        if (hit != null) {
          return block(head, "递归改权限/属主于系统关键路径（不可回滚）: " + hit);
        }
      }
    }
    if (head.equals("dd")) {
      for (String arg : args) {
        if (arg.startsWith("of=") && isBlockDevice(arg.substring("of=".length()))) {
          return block(head, "dd 直写块设备（不可回滚）: " + arg);
        }
      }
    }
    if (head.equals("sysctl") && writesKernelParam(args)) {
      return block(head, "写内核参数（白名单为空，全部硬拒）");
    }
    for (String target : targets) {
      if (isBlockDevice(target)) {
        return block(head, "重定向直写块设备（不可回滚）: " + target);
      }
      if (target.equals("/proc/sys") || target.startsWith("/proc/sys/")) {
        return block(head, "写内核参数 /proc/sys（不可回滚）");
      }
    }
    return null;
  }

  /** 需审批清单（附录 A 第二组；判据 = 可回滚但不是本任务该碰的）。 */
  private static Verdict askVerdict(String head, List<String> args, List<String> targets) {
    for (String target : targets) {
      if (isSystemConfigPath(target) || isPersistenceFile(target)) {
        return ask(head, "写系统配置/持久化文件: " + target);
      }
    }
    if (head.equals("tee")) {
      String hit = firstSystemTarget(args);
      if (hit != null) {
        return ask(head, "tee 写系统配置/持久化文件: " + hit);
      }
    }
    if (head.equals("sed") && hasFlag(args, "-i")) {
      String hit = firstSystemTarget(nonFlagTokens(args));
      if (hit != null) {
        return ask(head, "sed -i 改系统配置/持久化文件: " + hit);
      }
    }
    if (PACKAGE_MANAGERS.contains(head) && packageMutation(head, args)) {
      return ask(head, "改动系统包管理状态（安装/卸载/升级）");
    }
    if (head.equals("dpkg") && dpkgMutation(args)) {
      return ask(head, "改动系统包管理状态（dpkg 安装/卸载）");
    }
    if (head.equals("systemctl")) {
      String sub = firstNonFlag(args);
      if (sub != null && SYSTEMCTL_POWER_SUBCOMMANDS.contains(sub)) {
        // 与 shutdown/reboot 同类：不可回滚 ⇒ 走 Block（附录 A 的关机/重启一组）
        return block(head, "systemctl " + sub + " = 关机/重启类（不可回滚）");
      }
      if (sub != null && !SYSTEMCTL_QUERIES.contains(sub)) {
        return ask(head, "改系统服务状态: systemctl " + sub);
      }
    }
    if (head.equals("service")) {
      return ask(head, "改系统服务状态: service");
    }
    if (head.equals("crontab")) {
      // 附录 A 的"计划任务/持久化：crontab"<b>未</b>限定子命令（与 systemctl 的"非查询子命令"写法不同）——
      // 故 -l 也走审批。这是逐字照抄清单的结果，不是遗漏。
      return ask(head, "计划任务 crontab（含 -l；附录 A 未限定子命令）");
    }
    if (head.equals("chown")) {
      // 纯函数判不出"是不是自身文件"（要看属主）⇒ 一律问人；误问的代价是一次点击，漏判的代价是权限扩散
      return ask(head, "chown 改属主（纯函数判不出是否自身文件）");
    }
    if (head.equals("chmod") && worldWritable(args)) {
      return ask(head, "chmod 777 = 权限扩散");
    }
    if (head.equals("rm") && recursive(args)) {
      return ask(head, "递归删除（可在硬拒清单之外的路径）");
    }
    if (head.equals("find") && args.contains("-delete")) {
      return ask(head, "find -delete 大规模删除");
    }
    if (head.equals("rsync") && args.contains("--delete")) {
      return ask(head, "rsync --delete 大规模删除");
    }
    if ((head.equals("pip") || head.equals("pip3"))
        && firstNonFlag(args) != null
        && firstNonFlag(args).equals("install")) {
      return ask(head, "pip install 下载即执行");
    }
    if (head.equals("npm") && npmGlobalInstall(args)) {
      return ask(head, "npm 全局安装 下载即执行");
    }
    return null;
  }

  // ---------- 切分与归一化 ----------

  /** 一段命令 + 它前面的分隔符（{@code "|"} 才有"管道相邻"语义，见 {@link #segments(String)}）。 */
  private record Segment(String text, String separatorBefore, boolean pipedFromDownloader) {}

  /**
   * 把命令切成若干段：按 {@code ;} {@code &&} {@code ||} {@code |} 换行与单 {@code &} 切，<b>并</b>把 {@code
   * $(...)}/反引号里的子命令各切成段（子命令不是"另一个参数"，它自己就是一条命令）。
   *
   * <p>引号内的操作符不切（{@code echo "a;b"} 是一段）；{@code >&}（fd 复制，如 {@code 2>&1}）不切； {@code >>} 是重定向不切。段序 =
   * 原序，故"本段的左邻"就是上一段（分隔符为 {@code |} 时判"管道相邻"）。
   */
  private static List<Segment> segments(String command) {
    List<Raw> raw = new ArrayList<>();
    splitInto(command, raw);
    List<Segment> out = new ArrayList<>(raw.size());
    for (int i = 0; i < raw.size(); i++) {
      boolean fromDownloader = false;
      if ("|".equals(raw.get(i).separatorBefore()) && i > 0) {
        List<String> prevTokens = tokens(raw.get(i - 1).text());
        int at = headIndex(prevTokens);
        fromDownloader = at >= 0 && DOWNLOADERS.contains(baseName(prevTokens.get(at)));
      }
      out.add(new Segment(raw.get(i).text(), raw.get(i).separatorBefore(), fromDownloader));
    }
    // 子命令（$(...) / 反引号）：各按独立命令再切一次，追加在尾部（它们内部自己的管道相邻在递归里判定）
    for (String sub : subcommands(command)) {
      out.addAll(segments(sub));
    }
    return out;
  }

  /** 切分中间态（段文本 + 它前面的分隔符）。 */
  private record Raw(String text, String separatorBefore) {}

  /** 按操作符切分；段文本不含操作符本身，分隔符随段一起带走（{@code |} 与 {@code &&} 必须分得清）。 */
  private static void splitInto(String command, List<Raw> out) {
    StringBuilder current = new StringBuilder();
    String pendingSeparator = "";
    boolean single = false;
    boolean doubled = false;
    int i = 0;
    while (i < command.length()) {
      char c = command.charAt(i);
      if (c == '\\' && !single) {
        current.append(c);
        if (i + 1 < command.length()) {
          current.append(command.charAt(i + 1));
          i += 2;
          continue;
        }
        i++;
        continue;
      }
      if (c == '\'' && !doubled) {
        single = !single;
        current.append(c);
        i++;
        continue;
      }
      if (c == '"' && !single) {
        doubled = !doubled;
        current.append(c);
        i++;
        continue;
      }
      String op = single || doubled ? null : operatorAt(command, i);
      if (op != null) {
        flush(out, current, pendingSeparator);
        pendingSeparator = op;
        i += op.length();
        continue;
      }
      current.append(c);
      i++;
    }
    flush(out, current, pendingSeparator);
  }

  /** 该位置是否是一个<b>段分隔</b>操作符；返回其字面量（用于跳过），不是则 null。 */
  private static String operatorAt(String command, int i) {
    char c = command.charAt(i);
    if (c == '\n' || c == ';') {
      return String.valueOf(c);
    }
    if (c == '&') {
      if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
        return "&&";
      }
      // >& / <& / 2>&1 里的 & 不是分隔符
      return i > 0 && (command.charAt(i - 1) == '>' || command.charAt(i - 1) == '<') ? null : "&";
    }
    if (c == '|') {
      return i + 1 < command.length() && command.charAt(i + 1) == '|' ? "||" : "|";
    }
    return null;
  }

  /** 收一段（空段丢弃，但分隔符<b>不丢</b>——它要传给下一段）。 */
  private static void flush(List<Raw> out, StringBuilder current, String separatorBefore) {
    String text = current.toString().strip();
    if (!text.isEmpty()) {
      out.add(new Raw(text, separatorBefore));
    }
    current.setLength(0);
  }

  /** 提取 {@code $(...)} 与反引号内的子命令（不含外层符号）。 */
  private static List<String> subcommands(String command) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < command.length()) {
      char c = command.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '`') {
        int end = command.indexOf('`', i + 1);
        if (end < 0) {
          break;
        }
        out.add(command.substring(i + 1, end));
        i = end + 1;
        continue;
      }
      if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
        int depth = 0;
        int j = i + 1;
        for (; j < command.length(); j++) {
          char d = command.charAt(j);
          if (d == '(') {
            depth++;
          } else if (d == ')') {
            depth--;
            if (depth == 0) {
              break;
            }
          }
        }
        if (j < command.length()) {
          out.add(command.substring(i + 2, j));
          i = j + 1;
          continue;
        }
      }
      i++;
    }
    return out;
  }

  /**
   * 词法切分：按空白切，<b>剥掉引号</b>（{@code "rm" -rf /} 的承载体就是"引号里的首词其实还是首词"—— 简单引号不是"绕过"，把它当绕过会让 {@code rm
   * -rf "/etc"} 反而漏判）。
   */
  private static List<String> tokens(String segment) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean single = false;
    boolean doubled = false;
    int i = 0;
    while (i < segment.length()) {
      char c = segment.charAt(i);
      if (c == '\\' && !single && i + 1 < segment.length()) {
        current.append(segment.charAt(i + 1));
        i += 2;
        continue;
      }
      if (c == '\'' && !doubled) {
        single = !single;
        i++;
        continue;
      }
      if (c == '"' && !single) {
        doubled = !doubled;
        i++;
        continue;
      }
      if (Character.isWhitespace(c) && !single && !doubled) {
        if (current.length() > 0) {
          out.add(current.toString());
          current.setLength(0);
        }
        i++;
        continue;
      }
      current.append(c);
      i++;
    }
    if (current.length() > 0) {
      out.add(current.toString());
    }
    return out;
  }

  /**
   * 可执行名的下标：跳过 {@code VAR=x} 前缀与包装词（含包装词的选项与选项值）。
   *
   * @return 可执行名的下标；没有（纯赋值段/空段）返回 -1
   */
  private static int headIndex(List<String> tokens) {
    int i = 0;
    while (i < tokens.size()) {
      String token = tokens.get(i);
      if (isAssignment(token)) {
        i++;
        continue;
      }
      if (WRAPPERS.contains(baseName(token))) {
        i++;
        while (i < tokens.size() && tokens.get(i).startsWith("-")) {
          boolean takesValue = WRAPPER_VALUE_FLAGS.contains(tokens.get(i));
          i++;
          if (takesValue && i < tokens.size()) {
            i++;
          }
        }
        // nice -n 10 / timeout 10s 这类"数值参数"：跳过，别把 10 当成命令名
        while (i < tokens.size() && isNumericish(tokens.get(i))) {
          i++;
        }
        continue;
      }
      return i;
    }
    return -1;
  }

  /** 该段的可执行名（不跳过包装词，仅用于兜底 classKey 的首词）。 */
  private static String firstHead(String segment) {
    List<String> tokens = tokens(segment);
    if (tokens.isEmpty()) {
      return null;
    }
    return baseName(tokens.get(0));
  }

  /** {@code VAR=x} 形态的前缀赋值（含 {@code VAR=} 空值）。 */
  private static boolean isAssignment(String token) {
    int eq = token.indexOf('=');
    if (eq <= 0) {
      return false;
    }
    String name = token.substring(0, eq);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean ok = Character.isLetterOrDigit(c) || c == '_';
      if (!ok || (i == 0 && Character.isDigit(c))) {
        return false;
      }
    }
    return true;
  }

  /** 数值样形态（{@code 10}、{@code 10s}、{@code 1.5m}）：包装词的参数，不是命令名。 */
  private static boolean isNumericish(String token) {
    return token.matches("\\d+(\\.\\d+)?[smhd]?");
  }

  /** 取可执行名的<b>基名</b>：{@code /usr/bin/apt} → {@code apt}（classKey 稳定性要求同一命令的不同写法归一）。 */
  private static String baseName(String token) {
    int slash = token.lastIndexOf('/');
    return slash >= 0 && slash + 1 < token.length() ? token.substring(slash + 1) : token;
  }

  // ---------- 参数面的小工具 ----------

  /** 非选项参数（跳过 {@code -x} 与 {@code --} 以及其后的选项）。 */
  private static List<String> nonFlagTokens(List<String> args) {
    List<String> out = new ArrayList<>();
    for (String arg : args) {
      if (arg.equals("--")) {
        continue;
      }
      if (arg.startsWith("-") && arg.length() > 1) {
        continue;
      }
      out.add(arg);
    }
    return out;
  }

  private static String firstNonFlag(List<String> args) {
    List<String> rest = nonFlagTokens(args);
    return rest.isEmpty() ? null : rest.get(0);
  }

  private static boolean hasFlag(List<String> args, String flag) {
    for (String arg : args) {
      if (arg.equals(flag)) {
        return true;
      }
    }
    return false;
  }

  /** 递归标志：{@code -R} / {@code -r} / {@code --recursive} / 组合短选项（{@code -Rf}、{@code -rf}）。 */
  private static boolean recursive(List<String> args) {
    for (String arg : args) {
      if (arg.equals("--recursive")) {
        return true;
      }
      if (arg.startsWith("-") && !arg.startsWith("--") && arg.length() > 1) {
        String body = arg.substring(1);
        if (body.indexOf('R') >= 0 || body.indexOf('r') >= 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code chmod} 的世界可写形态：mode ∈ {777, 0777, a+rwx, a=rwx}。 */
  private static boolean worldWritable(List<String> args) {
    for (String arg : args) {
      if (arg.startsWith("-")) {
        continue;
      }
      if (arg.equals("777") || arg.equals("0777") || arg.equals("a+rwx") || arg.equals("a=rwx")) {
        return true;
      }
    }
    return false;
  }

  private static boolean packageMutation(String head, List<String> args) {
    if (head.equals("pacman")) {
      for (String arg : args) {
        if (arg.startsWith("-S") || arg.startsWith("-R") || arg.startsWith("-U")) {
          return true;
        }
      }
      return false;
    }
    String sub = firstNonFlag(args);
    if (sub == null) {
      return false;
    }
    if (head.equals("apt") || head.equals("apt-get") || head.equals("aptitude")) {
      return PACKAGE_ASK_SUBCOMMANDS.contains(sub);
    }
    // yum/dnf：update 是"升级"的别名，同 apt 的 upgrade 一档
    return PACKAGE_ASK_SUBCOMMANDS.contains(sub)
        || sub.equals("update")
        || sub.equals("groupinstall");
  }

  /**
   * {@code dpkg} 的安装/卸载形态：{@code -i}/{@code --install}/{@code -r}/{@code --remove}/{@code
   * -P}/{@code --purge}。
   */
  private static boolean dpkgMutation(List<String> args) {
    for (String arg : args) {
      if (arg.equals("--install")
          || arg.equals("--remove")
          || arg.equals("--purge")
          || arg.equals("-i")
          || arg.equals("-r")
          || arg.equals("-P")) {
        return true;
      }
    }
    return false;
  }

  /** {@code npm} 的全局安装形态：子命令 install/i + 全局标志（{@code -g}/{@code --global}）。 */
  private static boolean npmGlobalInstall(List<String> args) {
    boolean install = false;
    boolean global = false;
    for (String arg : args) {
      if (arg.equals("install") || arg.equals("i")) {
        install = true;
      }
      if (arg.equals("-g") || arg.equals("--global")) {
        global = true;
      }
    }
    return install && global;
  }

  private static boolean writesKernelParam(List<String> args) {
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("-w") || arg.equals("--write")) {
        return true;
      }
      // sysctl vm.swappiness=10 形态（无 -w 也是写）
      if (arg.indexOf('=') > 0 && !arg.startsWith("-")) {
        return true;
      }
    }
    return false;
  }

  /** {@code kill -9 -1} / {@code kill -9 1} / {@code kill -KILL -1}。 */
  private static boolean isKillAll(List<String> args) {
    boolean deadly = false;
    boolean all = false;
    for (String arg : args) {
      if (arg.equals("-9") || arg.equals("-KILL") || arg.equals("-s9")) {
        deadly = true;
      }
      if (arg.equals("-1") || arg.equals("1")) {
        all = true;
      }
    }
    return deadly && all;
  }

  /** fork bomb 的 {@code classKey} 首词：形态本身没有"可执行名"，用一个固定的稳定词（不拼命令原文）。 */
  private static final String FORK_BOMB_HEAD = "forkbomb";

  /** fork bomb：经典形态 {@code :(){ :|:& };:}（把空白去掉后比对；只认这<b>一种</b>形态，变体不识别——见类 javadoc 的边界）。 */
  private static boolean isForkBomb(String text) {
    String squashed = text.replaceAll("\\s", "");
    return squashed.startsWith(":(){") && squashed.contains(":|:&");
  }

  /** 该路径是否落在硬拒路径树里（等于树根、或在树根之下）。 */
  private static String hardPath(String path) {
    if (HARD_PATH_LITERALS.contains(path)) {
      return path;
    }
    String stripped = stripTrailingSlash(path);
    for (String root : HARD_PATH_ROOTS) {
      if (stripped.equals(root) || stripped.startsWith(root + "/")) {
        return path;
      }
    }
    return null;
  }

  private static String hardTarget(List<String> candidates) {
    for (String candidate : candidates) {
      String hit = hardPath(candidate);
      if (hit != null) {
        return hit;
      }
    }
    return null;
  }

  private static String stripTrailingSlash(String path) {
    String stripped = path;
    while (stripped.length() > 1 && stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  /** 块设备路径：{@code /dev/sd*}、{@code /dev/nvme*}、{@code /dev/mmcblk*}、{@code /dev/vd*}。 */
  private static boolean isBlockDevice(String path) {
    if (!path.startsWith("/dev/")) {
      return false;
    }
    String name = path.substring("/dev/".length());
    for (String prefix : DEVICE_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** 写系统配置：{@code /etc} 树（附录 A 的"写 /etc/**"）。 */
  private static boolean isSystemConfigPath(String target) {
    String stripped = stripTrailingSlash(target);
    return stripped.equals("/etc") || stripped.startsWith("/etc/");
  }

  private static boolean isPersistenceFile(String target) {
    return PERSISTENCE_FILES.contains(target);
  }

  private static String firstSystemTarget(List<String> candidates) {
    for (String candidate : candidates) {
      if (isSystemConfigPath(candidate) || isPersistenceFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * 重定向目标（{@code > path} / {@code >> path} / {@code cmd>path} 三种写法都认）。
   *
   * <p>不把 {@code >&}（fd 复制）当重定向：它的右值不是路径。
   */
  private static List<String> redirectTargets(String segment) {
    List<String> out = new ArrayList<>();
    boolean single = false;
    boolean doubled = false;
    int i = 0;
    while (i < segment.length()) {
      char c = segment.charAt(i);
      if (c == '\\' && !single) {
        i += 2;
        continue;
      }
      if (c == '\'' && !doubled) {
        single = !single;
        i++;
        continue;
      }
      if (c == '"' && !single) {
        doubled = !doubled;
        i++;
        continue;
      }
      if (c == '>' && !single && !doubled) {
        int j = i + 1;
        if (j < segment.length() && segment.charAt(j) == '>') {
          j++;
        }
        while (j < segment.length() && (segment.charAt(j) == ' ' || segment.charAt(j) == '\t')) {
          j++;
        }
        if (j < segment.length() && segment.charAt(j) == '&') {
          i = j + 1; // >& 是 fd 复制，不是路径重定向
          continue;
        }
        int start = j;
        while (j < segment.length() && !Character.isWhitespace(segment.charAt(j))) {
          j++;
        }
        if (j > start) {
          out.add(segment.substring(start, j).replace("\"", "").replace("'", ""));
        }
        i = j;
        continue;
      }
      i++;
    }
    return out;
  }

  // ---------- 汇总与出口 ----------

  private static boolean isShell(String head) {
    return head != null && SHELLS.contains(head);
  }

  private static Verdict stricter(Verdict current, Verdict candidate) {
    return candidate.level().ordinal() > current.level().ordinal()
        ? new Verdict(candidate.level(), current.head(), candidate.reason())
        : current;
  }

  /** 硬拒门的统一构造（口径一致：同一个 reason 前缀 + 同一个 {@code classKey} 拼法）。 */
  private static ToolGate blockedGate(String head, String reason) {
    return new ToolGate.Block(classKey(Level.BLOCK, head), "命令被硬拒（不可回滚，不可审批）: " + reason);
  }

  /**
   * 不完全权限档的"提升门"：把本该直放的命令转成需审批。摘要里带命令原文（提示面专用，不进事件库）， 并<b>明写</b>"不完全权限"，让人/上级判定知道这条不是系统敏感区、而是档位使然。
   *
   * <p><b>键自成一档</b>（{@code bash:limited:<首词>}，不是 ask 档的 {@code bash:ask:<首词>}）：两个理由——
   *
   * <p><b>键自成一档</b>（{@code bash:limited:<首词>}，不是 ask 档的 {@code bash:ask:<首词>}）：两个理由——
   * 事件面上"这条为什么被问"要一眼可辨（提升问询可以被上级 Agent 代批，敏感问询不能）；会话放行的键也不许跨档串味 （人给敏感区 {@code bash:ask:systemctl}
   * 批的"本会话"不该顺带放行一条同名的提升问询，反之亦然）。
   */
  private static ToolGate limitedAsk(String command, String head) {
    String classKey = LEVEL_PREFIX + "limited:" + headWord(head);
    return new ToolGate.Ask(
        classKey, "不完全权限（所有命令需审批）: " + summary(command, classKey), AskKind.MODE_LIMITED);
  }

  private static Verdict block(String head, String reason) {
    return new Verdict(Level.BLOCK, head, reason);
  }

  private static Verdict ask(String head, String reason) {
    return new Verdict(Level.ASK, head, reason);
  }

  /** {@code classKey} = {@code bash:<档>:<首词>}；首词不可得时用 {@code unknown}（仍稳定，不拼参数）。 */
  private static String classKey(Level level, String head) {
    return LEVEL_PREFIX + level.wireName() + ":" + headWord(head);
  }

  /** 键里的首词：不可得时用 {@code unknown}（仍稳定，不拼参数）。 */
  private static String headWord(String head) {
    return head == null || head.isBlank() ? "unknown" : head;
  }

  /** 给人看的一行（<b>可</b>含命令原文：它只进提示面与内存态审批请求，不进事件库）。 */
  private static String summary(String command, String classKey) {
    String shown =
        command.length() > SUMMARY_COMMAND_MAX
            ? command.substring(0, SUMMARY_COMMAND_MAX) + "…"
            : command;
    return "类别=" + classKey + " 命令=" + shown;
  }

  private static Set<String> names(List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String name : configured) {
      if (name != null && !name.isBlank()) {
        out.add(baseName(name.strip()));
      }
    }
    return out;
  }

  /** 段内判定结果（{@code head} 进 classKey；{@code reason} 只对 Block/Ask 有意义）。 */
  private record Verdict(Level level, String head, String reason) {}
}
