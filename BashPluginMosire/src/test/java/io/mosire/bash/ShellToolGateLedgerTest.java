package io.mosire.bash;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.Digest;
import io.mosire.agentlib.tool.ToolContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellTool} 的 {@code gate()} / {@code ledgerArgs()} 闭环（S4-C §一.2/§一.3）：
 * 三档分流真的接在工具上、落账口径真的按配置走、<b>digest 模式的事件里没有命令明文</b>。
 *
 * <p>与 {@code BashCommandClassifierTest} 的分工：那个测纯函数分类，这个测<b>工具面</b>——配置注入（{@code
 * ToolContext.config()}） 真的被读到、{@code env} 真的不进事件、{@code full} 开关真的能取回明文。
 *
 * <p>纯离线：只构造 {@link ToolContext}，<b>不执行</b>任何命令（含 {@code rm -rf /} 这类字符串只作判定输入）。
 */
class ShellToolGateLedgerTest {

  private final ShellTool tool = new ShellTool();

  private static ToolContext context(Map<String, Object> args, BashToolConfig config) {
    return new ToolContext(
        AccessToken.DEFAULT, AgentPermissionSet.system(), config.toToolConfig(), args);
  }

  private static ToolContext context(String command) {
    return context(Map.of("command", command), BashToolConfig.defaults());
  }

  // ---------- gate：三档接在工具上 ----------

  @Test
  void gateMirrorsTheClassifierTiers() {
    assertThat(tool.gate(context("ls -la"))).isEqualTo(ToolGate.ALLOW);
    ToolGate ask = tool.gate(context("crontab -l"));
    assertThat(ask).isInstanceOf(ToolGate.Ask.class);
    assertThat(((ToolGate.Ask) ask).classKey()).isEqualTo("bash:ask:crontab");
    ToolGate block = tool.gate(context("rm -rf /"));
    assertThat(block).isInstanceOf(ToolGate.Block.class);
    assertThat(((ToolGate.Block) block).classKey()).isEqualTo("bash:block:rm");
  }

  /** 没有命令文本时不编造"需审批"：执行器自己会报参数非法，分类器不替它造档位。 */
  @Test
  void gateOnMissingCommandIsAllow() {
    assertThat(tool.gate(context(Map.of(), BashToolConfig.defaults()))).isEqualTo(ToolGate.ALLOW);
  }

  /** 配置注入真的被读到：同一个首词（{@code nc}）在缺省下直放、被列入追加表后按档拦。 */
  @Test
  void gateReadsInjectedConfig() {
    assertThat(tool.gate(context("nc -l 1234"))).isEqualTo(ToolGate.ALLOW);
    BashToolConfig stricter = new BashToolConfig(List.of("nc"), List.of("telnet"), null);
    assertThat(tool.gate(context(Map.of("command", "nc -l 1234"), stricter)))
        .isInstanceOf(ToolGate.Block.class);
    assertThat(tool.gate(context(Map.of("command", "telnet x"), stricter)))
        .isInstanceOf(ToolGate.Ask.class);
  }

  // ---------- ledgerArgs：digest 模式（缺省） ----------

  @Test
  void digestModeHidesTheCommandText() {
    Map<String, Object> ledger = tool.ledgerArgs(context("crontab -l"));
    assertThat(ledger).containsOnlyKeys("digest", "len", "class");
    assertThat(ledger.get("digest")).isEqualTo(Digest.ofCommand("crontab -l"));
    assertThat(ledger.get("len")).isEqualTo(10);
    assertThat(ledger.get("class")).isEqualTo("ask");
    // 明文不得以任何键出现在落账视图里（把整个 Map 序列化后找原文）
    assertThat(ledger.toString()).doesNotContain("crontab").doesNotContain("-l");
  }

  @Test
  void digestModeClassMatchesTheGateTier() {
    assertThat(tool.ledgerArgs(context("ls -la")).get("class")).isEqualTo("allow");
    assertThat(tool.ledgerArgs(context("rm -rf /var/log")).get("class")).isEqualTo("block");
  }

  /** {@code env} 是模型自造的任意字符串、可能承载密钥（D23/D24）：digest 模式下整个不落。 */
  @Test
  void digestModeDropsEnvButKeepsNonCommandScalars() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("command", "ls");
    args.put("env", Map.of("SECRET_TOKEN", "s3cr3t"));
    args.put("cwd", "/tmp");
    args.put("timeout", 5);
    Map<String, Object> ledger = tool.ledgerArgs(context(args, BashToolConfig.defaults()));
    assertThat(ledger).doesNotContainKey("env");
    assertThat(ledger.toString()).doesNotContain("s3cr3t").doesNotContain("SECRET_TOKEN");
    assertThat(ledger).containsEntry("cwd", "/tmp").containsEntry("timeout", 5);
  }

  @Test
  void digestModeWithoutCommandUsesPlaceholdersNotFakeDigest() {
    Map<String, Object> ledger = tool.ledgerArgs(context(Map.of(), BashToolConfig.defaults()));
    assertThat(ledger)
        .containsEntry("digest", "none")
        .containsEntry("len", 0)
        .containsEntry("class", "none");
  }

  // ---------- ledgerArgs：full 模式（显式开关） ----------

  @Test
  void fullModeReturnsArgumentsVerbatim() {
    BashToolConfig full = new BashToolConfig(List.of(), List.of(), BashToolConfig.CommandLog.FULL);
    Map<String, Object> args = Map.of("command", "crontab -l");
    assertThat(tool.ledgerArgs(context(args, full))).isEqualTo(args);
  }

  /** 同一段摘要在工具面与审批面必须一致（两处各写一份 sha256 必然分叉，见 {@link Digest} 的类 javadoc）。 */
  @Test
  void digestMatchesTheApprovalFaceDigestForACommandOnlyCall() {
    Map<String, Object> args = Map.of("command", "crontab -l");
    Map<String, Object> ledger = tool.ledgerArgs(context(args, BashToolConfig.defaults()));
    assertThat(ledger.get("digest"))
        .isEqualTo(
            io.mosire.agentlib.tool.ToolCallAuthorizer.defaultDigest(
                context(args, BashToolConfig.defaults())));
  }
}
