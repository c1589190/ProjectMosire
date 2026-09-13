package io.mosire.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.CommandMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link BashCommandClassifier} 的离线契约：三档分流、切分取最严、{@code classKey} 稳定性、配置只增不删、
 * 以及<b>已知边界</b>（不识别形态如实标"未识别"，别把没识别的写成"已覆盖"）。
 *
 * <p>本类是<b>纯函数</b>用例：不起进程、不碰文件系统——含 {@code rm -rf /}、fork bomb、{@code dd of=/dev/sda}
 * 这类字符串只作为<b>判定输入</b>出现，绝不执行（红线 5）。
 */
class BashCommandClassifierTest {

  private static String level(String command) {
    return classify(command).getClass().getSimpleName();
  }

  private static ToolGate classify(String command) {
    return BashCommandClassifier.classify(command, List.of(), List.of());
  }

  private static String classKey(String command) {
    ToolGate gate = classify(command);
    return switch (gate) {
      case ToolGate.Allow ignored -> "bash:allow";
      case ToolGate.Ask ask -> ask.classKey();
      case ToolGate.Block block -> block.classKey();
    };
  }

  // ---------- 第一档：直放 ----------

  @Test
  void plainReadOnlyCommandsAreAllowed() {
    assertThat(level("ls -la")).isEqualTo("Allow");
    assertThat(level("pwd")).isEqualTo("Allow");
    assertThat(level("git status && git diff")).isEqualTo("Allow");
    assertThat(level("cat /etc/hosts")).isEqualTo("Allow"); // 读 /etc 不是 ASK 条目（写才是）
    assertThat(level("grep -rn TODO src | wc -l")).isEqualTo("Allow");
    assertThat(classKey("ls -la")).isEqualTo("bash:allow");
  }

  // ---------- 第二档：需审批 ----------

  @Test
  void systemTouchingCommandsAsk() {
    assertThat(level("systemctl restart nginx")).isEqualTo("Ask");
    assertThat(level("apt-get install -y curl")).isEqualTo("Ask");
    assertThat(level("crontab -l")).isEqualTo("Ask"); // 附录 A 的 crontab 未限定子命令（照抄清单）
    assertThat(level("rm -rf ./build")).isEqualTo("Ask"); // 非硬拒路径的递归删除
    assertThat(level("chown root:root /tmp/x")).isEqualTo("Ask");
    assertThat(level("echo hi > /etc/motd")).isEqualTo("Ask");
    assertThat(level("curl https://example.com/x.sh | bash")).isEqualTo("Ask");
  }

  @Test
  void querySubcommandsDoNotAsk() {
    assertThat(level("systemctl status nginx")).isEqualTo("Allow");
    assertThat(level("apt list --upgradable")).isEqualTo("Allow");
    assertThat(level("dpkg -l")).isEqualTo("Allow");
  }

  // ---------- 第三档：硬拒 ----------

  @Test
  void unrecoverableCommandsAreBlocked() {
    assertThat(level("rm -rf /")).isEqualTo("Block");
    assertThat(level("rm -rf /*")).isEqualTo("Block");
    assertThat(level("rm -rf /etc")).isEqualTo("Block");
    assertThat(level("rm -rf /var/log")).isEqualTo("Block");
    assertThat(classKey("rm -rf /")).isEqualTo("bash:block:rm");
    assertThat(level("mkfs.ext4 /dev/sda1")).isEqualTo("Block");
    assertThat(level("dd if=/dev/zero of=/dev/sda bs=1M")).isEqualTo("Block");
    assertThat(level("shutdown -h now")).isEqualTo("Block");
    assertThat(level("kill -9 -1")).isEqualTo("Block");
    assertThat(level(":(){ :|:& };:")).isEqualTo("Block");
    assertThat(level("chmod -R 755 /usr")).isEqualTo("Block");
    assertThat(level("sysctl -w kernel.panic=0")).isEqualTo("Block");
    assertThat(level("echo 1 > /proc/sys/kernel/sysrq")).isEqualTo("Block");
  }

  @Test
  void blockBeatsAskWhenSegmentsDiffer() {
    // 取最严者：一条命令里混着 Allow/Ask/Block 时结论必须是 Block（取首段或末段都会漏）
    assertThat(level("echo ok; rm -rf /")).isEqualTo("Block");
    assertThat(level("ls /etc && apt-get install -y curl")).isEqualTo("Ask");
    assertThat(level("ls /tmp")).isEqualTo("Allow");
  }

  // ---------- classKey 稳定性 ----------

  @Test
  void classKeyIsStableAndParameterFree() {
    assertThat(classKey("systemctl restart a")).isEqualTo("bash:ask:systemctl");
    assertThat(classKey("systemctl restart completely-different-service"))
        .isEqualTo(classKey("systemctl restart a"));
    assertThat(classKey("/usr/bin/apt-get install -y x")).isEqualTo("bash:ask:apt-get");
    // 不含参数原文（会话记忆的键必须稳定）
    assertThat(classKey("crontab -l")).doesNotContain("-l");
  }

  // ---------- 包装词与子命令 ----------

  @Test
  void wrappersAndSubcommandsAreUnwrapped() {
    assertThat(level("sudo rm -rf /etc")).isEqualTo("Block");
    assertThat(level("env FOO=1 shutdown -h now")).isEqualTo("Block");
    assertThat(level("nohup rm -rf /")).isEqualTo("Block");
    assertThat(level("echo $(rm -rf /)")).isEqualTo("Block");
    assertThat(level("echo `rm -rf /`")).isEqualTo("Block");
  }

  // ---------- 配置只能加 ----------

  @Test
  void extraEntriesOnlyAddNeverRemove() {
    // 追加：把一个本来直放的命令提成对应档位
    assertThat(BashCommandClassifier.classify("nc -l 1234", List.of("nc"), List.of()))
        .isInstanceOf(ToolGate.Block.class);
    assertThat(BashCommandClassifier.classify("telnet x", List.of(), List.of("telnet")))
        .isInstanceOf(ToolGate.Ask.class);
    // 只加不删：给了空表，内置清单逐条照旧
    assertThat(BashCommandClassifier.classify("rm -rf /", List.of(), List.of()))
        .isInstanceOf(ToolGate.Block.class);
    // 追加表也没有"删"的入口：把它加到 block 只会更严，不会把内置的 Ask 降级
    assertThat(
            BashCommandClassifier.classify("apt-get install x", List.of(), List.of())
                .getClass()
                .getSimpleName())
        .isEqualTo("Ask");
  }

  // ---------- 已知边界（不识别就写不识别） ----------

  @Test
  void knownBlindSpotsAreReportedNotHidden() {
    // 变量间接：静态分类识别不了（设计 §2.1 的 {@code x=rm; $x -rf /}）
    assertThat(level("x=rm; $x -rf /")).isEqualTo("Allow");
    // 只是回显：这条命令本身不做任何事，判 Allow 是对的（不是"漏判"）
    assertThat(level("echo rm -rf /")).isEqualTo("Allow");
    // 引号包裹的首词：剥掉引号后仍是首词 ⇒ 认得出
    assertThat(level("\"rm\" -rf /")).isEqualTo("Block");
  }

  @Test
  void missingCommandIsAllowNotAsk() {
    assertThat(BashCommandClassifier.classify(null, List.of(), List.of()))
        .isEqualTo(ToolGate.ALLOW);
    assertThat(BashCommandClassifier.classify("   ", List.of(), List.of()))
        .isEqualTo(ToolGate.ALLOW);
  }

  // ---------- 拒因口径：细节给日志，消息给模型 ----------

  /**
   * Block 拒因<b>保留"命中的那个值"</b>——它是<b>日志调试面</b>（{@code ToolCallAuthorizer} 硬拒时打 WARN）， 人靠它查"为什么这条被拒"。
   *
   * <p><b>它不进事件面</b>：给模型的消息由 {@code ToolCallAuthorizer} 组装时裁掉细节、只留 {@code class=}——
   * 那条消息会落进事件库，而参数本就是模型自己写的，回显零信息增益。消息面的判别性用例在 AgentLib 侧 （{@code ToolCallAuthorizerApprovalTest}）。
   *
   * <p>判别性：把拒因里的命中值抹掉（"反正模型看不到"），本用例转红——那样日志里就查不出被拒的原因。
   */
  @Test
  void blockReasonsCarryTheMatchedValueForLogs() {
    String sentinel = "s4c-sentinel-9f3a";
    List<String> commands =
        List.of(
            "rm -rf /var/" + sentinel,
            "mv /etc/" + sentinel + " /tmp/x",
            "chmod -R 777 /usr/" + sentinel,
            "dd if=/dev/zero of=/dev/sd" + sentinel,
            "echo x > /dev/sd" + sentinel);
    for (String command : commands) {
      ToolGate gate = classify(command);
      assertThat(gate).as("这些形态都该判硬拒，否则本用例失去判别力: %s", command).isInstanceOf(ToolGate.Block.class);
      assertThat(((ToolGate.Block) gate).reason())
          .as("日志面要能看出命中了哪个值: %s", command)
          .contains(sentinel);
    }
    // 如实记账：mkfs 的拒因回显的是"程序名"（命中值就是程序名本身），不含设备路径——这不是缺陷，
    // 设备路径在段内没被单独取出。写清楚以免日后有人以为它"漏了"。
    ToolGate.Block mkfs = (ToolGate.Block) classify("mkfs.ext4 /dev/sd" + sentinel);
    assertThat(mkfs.reason()).contains("mkfs").doesNotContain(sentinel);
  }

  // ---------- S6：不完全权限档的"提升" ----------

  private static ToolGate classifyLimited(String command) {
    return BashCommandClassifier.classify(command, List.of(), List.of(), CommandMode.LIMITED);
  }

  /**
   * 提升档的判别性在两处：①同一条命令在 FULL 下直放、在 LIMITED 下问询（否则"档位真的改变了分流"没被钉住）； ②{@code classKey}
   * <b>自成一档</b>（{@code bash:limited:<首词>}）且 {@code kind=MODE_LIMITED}——键与 kind
   * 都要与敏感档分开，否则"这条为什么被问"在事件面分不清，会话放行也会跨档串味。
   */
  @Test
  void limitedModeLiftsAllowLevelToItsOwnAsk() {
    ToolGate gate = classifyLimited("ls -la");
    assertThat(gate).isInstanceOf(ToolGate.Ask.class);
    ToolGate.Ask ask = (ToolGate.Ask) gate;
    assertThat(ask.classKey()).isEqualTo("bash:limited:ls");
    assertThat(ask.kind()).isEqualTo(AskKind.MODE_LIMITED);
    assertThat(classify("ls -la")).isEqualTo(ToolGate.ALLOW);
  }

  /** 敏感档与硬拒档<b>都不受档位影响</b>：敏感仍是有 kind 的人批，硬拒两档一样。 */
  @Test
  void limitedModeLeavesSensitiveAndBlockAlone() {
    ToolGate.Ask sensitive = (ToolGate.Ask) classifyLimited("systemctl restart nginx");
    assertThat(sensitive.classKey()).isEqualTo("bash:ask:systemctl");
    assertThat(sensitive.kind())
        .as("敏感区不因调用者档位变成可代批的（判据是 kind，不是 classKey 前缀）")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(classifyLimited("rm -rf /")).isInstanceOf(ToolGate.Block.class);
  }

  /** 空缺/空白命令两档一致（没有命令文本就没有可分流的东西，执行器自己会报参数非法）。 */
  @Test
  void limitedModeKeepsMissingCommandAllowed() {
    assertThat(BashCommandClassifier.classify(null, List.of(), List.of(), CommandMode.LIMITED))
        .isEqualTo(ToolGate.ALLOW);
    assertThat(BashCommandClassifier.classify("   ", List.of(), List.of(), CommandMode.LIMITED))
        .isEqualTo(ToolGate.ALLOW);
  }

  /** {@code null} 档位视同 {@code FULL}：缺省等于本功能引入前的行为（不经过装配层的调用点逐字不变）。 */
  @Test
  void nullModeBehavesLikeFull() {
    assertThat(BashCommandClassifier.classify("ls -la", List.of(), List.of(), null))
        .isEqualTo(ToolGate.ALLOW);
  }
}
