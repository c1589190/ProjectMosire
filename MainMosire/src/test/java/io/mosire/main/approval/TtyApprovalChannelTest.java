package io.mosire.main.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * tty 审批通道（S4-B2）的离线判别性用例（H6）：无控制终端 ⇒ <b>不可用</b>（不是默认同意）；注入假终端时 {@code y/a/回车} 分别解析为
 * once/session/deny，且答复<b>真的落进登记表</b>。
 *
 * <p>本机跑测试没有控制终端是常态（派发包 §一.5 H6），所以"真终端"那半条路只能验 {@code available()==false}；解析与
 * "先落表再返回"两条硬契约靠包内可见的注入缝（假读入/假输出）验——<b>单测里绝不开真 {@code /dev/tty}</b>。
 */
class TtyApprovalChannelTest {

  /**
   * H6-a：真构造形态下，<b>没有控制终端 ⇒ 通道不可用</b>。
   *
   * <p>判别性：把 {@code available()} 里那条"无控制终端"判断删掉，本用例在无 tty 的机器上会因为 {@code /dev/tty} 打不开而 <b>仍然</b>返回
   * false（这条本身不具判别力）——判别力由下面 {@code controlTerminal=true} 的注入用例提供：一对用例合起来才钉死 "有终端 ⇒ 可用 / 没终端 ⇒
   * 不可用"。
   */
  @Test
  void withoutAControlTerminalTheChannelIsUnavailable() throws Exception {
    assumeTrue(System.console() == null, "本用例只在无控制终端环境成立（有 tty 的开发机跳过）");
    try (TtyApprovalChannel channel = new TtyApprovalChannel(new PendingApprovals())) {
      assertThat(channel.available()).isFalse();
      // 自带等待上界：未知 id 上的等待必须立刻（有界）返回空，而不是挂在那儿
      long started = System.nanoTime();
      assertThat(channel.await("ap-nobody", Duration.ofMillis(150))).isEmpty();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
  }

  /**
   * H6-b：注入"有控制终端 + 能打开的终端流" ⇒ 通道可用，{@code y} 的答复落进登记表，被 {@code await} 的等待方拿到。
   *
   * <p>判别性：把 {@code controlTerminal} 那条判断改成恒 false（"没终端也当不可用"写成"永远不可用"），本用例红；把 {@code publish}
   * 的入队去掉（提示根本不发）⇒ {@code pending.await} 拿到空 ⇒ 红。
   */
  @Test
  void injectedTerminalAnswersYForOnceAndLandsInTheRegistry() throws Exception {
    try (FakeTerminal terminal = new FakeTerminal()) {
      TtyApprovalChannel channel = terminal.channel;
      assertThat(channel.available()).isTrue();

      ApprovalRequest req = terminal.submit("SYSTEM");
      channel.publish(req);
      terminal.type("y\n");

      assertThat(channel.await(req.id(), Duration.ofSeconds(3)))
          .as("y = 本次放行，且答复先落登记表（等待方由条件变量唤醒）")
          .contains(ApprovalDecision.APPROVE_ONCE);
      assertThat(terminal.pending.get(req.id())).isPresent();
      assertThat(terminal.pending.pending()).as("已决议项不再是待裁决").isEmpty();
      assertThat(terminal.prompts())
          .as("提示行只带工具名/类别/摘要，不带参数明文")
          .contains("工具=bash")
          .contains("类别=bash:ask:apt")
          .contains("摘要=" + req.summary());
    }
  }

  /** H6-c：{@code a} ⇒ 会话级放行（收窄不在这层做——通道如实转达人的答复，收窄是编排器的事）。 */
  @Test
  void injectedTerminalAnswersAForSession() throws Exception {
    try (FakeTerminal terminal = new FakeTerminal()) {
      ApprovalRequest req = terminal.submit("DEFAULT");
      terminal.channel.publish(req);
      terminal.type("a\n");
      assertThat(terminal.channel.await(req.id(), Duration.ofSeconds(3)))
          .contains(ApprovalDecision.APPROVE_SESSION);
    }
  }

  /**
   * H6-d：<b>回车 = 拒绝</b>（终端上最廉价的动作必须落到最保守的结果），无法识别的词同样拒。
   *
   * <p>判别性：把 {@code parse} 的 {@code default} 改成 {@code APPROVE_ONCE}（"回车默认成同意"），本用例与 H6-e 转红。
   */
  @Test
  void injectedTerminalTreatsEnterAndUnknownWordsAsDeny() throws Exception {
    try (FakeTerminal terminal = new FakeTerminal()) {
      ApprovalRequest empty = terminal.submit("SYSTEM");
      terminal.channel.publish(empty);
      terminal.type("\n");
      assertThat(terminal.channel.await(empty.id(), Duration.ofSeconds(3)))
          .as("回车 = 拒（不是同意）")
          .contains(ApprovalDecision.DENY);

      ApprovalRequest unknown = terminal.submit("SYSTEM");
      terminal.channel.publish(unknown);
      terminal.type("yes\n");
      assertThat(terminal.channel.await(unknown.id(), Duration.ofSeconds(3)))
          .as("只认 y/a 两个词，yes 不在词表里 ⇒ 拒")
          .contains(ApprovalDecision.DENY);
    }
  }

  /** H6-e：{@code parse} 的纯函数口径（大小写/空白无关；空行/未知词 ⇒ 拒）。 */
  @Test
  void parseAcceptsOnlyYAndA() {
    assertThat(TtyApprovalChannel.parse("y")).isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(TtyApprovalChannel.parse("Y ")).isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(TtyApprovalChannel.parse("a")).isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(TtyApprovalChannel.parse(" A\n")).isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(TtyApprovalChannel.parse("")).isEqualTo(ApprovalDecision.DENY);
    assertThat(TtyApprovalChannel.parse("   ")).isEqualTo(ApprovalDecision.DENY);
    assertThat(TtyApprovalChannel.parse("n")).isEqualTo(ApprovalDecision.DENY);
    assertThat(TtyApprovalChannel.parse("yes")).isEqualTo(ApprovalDecision.DENY);
    assertThat(TtyApprovalChannel.parse("approve")).isEqualTo(ApprovalDecision.DENY);
  }

  /**
   * H6-f：读面坏掉（EOF：控制终端消失）⇒ fail-closed——当次请求立刻按"没人答"拒（记 DENY，不让调用方白等到超时）， 通道随后恒不可用。
   *
   * <p>判别性：把 EOF 分支改成 return（不记 DENY、不置 broken），{@code pending.await} 会一直空到窗口用尽（断言超时红）， 且 {@code
   * available()} 仍为 true（第二条断言红）。
   */
  @Test
  void eofOnTheTerminalFailsClosedInsteadOfHanging() throws Exception {
    try (FakeTerminal terminal = new FakeTerminal()) {
      ApprovalRequest req = terminal.submit("SYSTEM");
      terminal.channel.publish(req);
      terminal.closeInput(); // 终端消失：readLine 返回 null

      assertThat(terminal.channel.await(req.id(), Duration.ofSeconds(3)))
          .as("读面坏掉 ⇒ 当次按 fail-closed 记 DENY")
          .contains(ApprovalDecision.DENY);
      assertThat(terminal.channel.available()).as("读面已废 ⇒ 通道不可用").isFalse();
    }
  }

  /**
   * 注入式假终端：管道读入（可逐行"敲"）、{@link StringWriter} 收提示、登记表共享。
   *
   * <p>用 {@link PipedReader} 而不是预置的 {@code StringReader}：读线程是长驻的，喂完一行还得继续阻塞在下一行上——预置字符串读到底就
   * EOF，会把"下一条请求"误伤成读面故障。
   */
  private static final class FakeTerminal implements AutoCloseable {

    private final PendingApprovals pending = new PendingApprovals();
    private final PipedWriter keys = new PipedWriter();
    private final StringWriter screen = new StringWriter();
    private final TtyApprovalChannel channel;

    FakeTerminal() throws IOException {
      BufferedReader reader = new BufferedReader(new PipedReader(keys));
      Writer writer = new BufferedWriter(screen);
      // 注入缝只替换"怎么打开终端流"，路径本身不参与判定（本类唯一会打开的路径恒为 /dev/tty）
      channel = new TtyApprovalChannel(pending, () -> true, path -> reader, path -> writer);
    }

    ApprovalRequest submit(String callerKey) {
      return pending.submitRequest(
          callerKey,
          "bash",
          "bash:ask:apt",
          "安装 apt 包（工具自报的一行说明）",
          "sha256:0123456789abcdef",
          System.currentTimeMillis() + 30_000L);
    }

    /** 在终端上敲一行（人按下回车）。 */
    void type(String line) throws IOException {
      keys.write(line);
      keys.flush();
    }

    /** 终端消失（EOF）。 */
    void closeInput() throws IOException {
      keys.close();
    }

    String prompts() {
      return screen.toString();
    }

    @Override
    public void close() throws IOException {
      channel.close();
      keys.close();
    }
  }
}
