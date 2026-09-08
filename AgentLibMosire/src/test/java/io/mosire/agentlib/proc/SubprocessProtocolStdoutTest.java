package io.mosire.agentlib.proc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SpawnSpec#protocolStdout()} 语义（W3b 协议侧接管道必须的支点）：协议接管模式下 stdout 泵 关闭（不占用输出流），{@link
 * ManagedProcess#stdout()} 直接暴露原始流给协议层；默认模式行为不变 （既有 SubprocessManagerTest 保证）。
 */
class SubprocessProtocolStdoutTest {

  private static final SpawnSpec SPEC =
      new SpawnSpec("sh", List.of("-c", "printf 'hello\\n'; sleep 20"), Map.of(), null, 1024, true);

  @Test
  void protocolStdoutExposesStreamAndSkipsDiagnosticsPump() throws Exception {
    try (SubprocessManager processes = new SubprocessManager()) {
      ManagedProcess managed = processes.spawn(SPEC);
      try {
        assertThat(managed.stdout()).isPresent();
        try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(managed.stdout().orElseThrow(), StandardCharsets.UTF_8))) {
          assertThat(reader.readLine()).isEqualTo("hello");
        }
        // 泵被跳过：诊断尾部不抢协议层的行（输出上限也不再被误判）
        assertThat(managed.outputTail()).isEmpty();
        assertThat(managed.overflowed()).isFalse();
      } finally {
        managed.stopGracefully(Duration.ofMinutes(1));
      }
      assertThat(managed.isAlive()).isFalse();
    }
  }

  @Test
  void defaultModeKeepsPumpAndNoProtocolStream() throws Exception {
    try (SubprocessManager processes = new SubprocessManager()) {
      ManagedProcess managed =
          processes.spawn(
              new SpawnSpec(
                  "sh", List.of("-c", "printf 'tail\\n'; sleep 0.3"), Map.of(), null, 1024, false));
      try {
        assertThat(managed.stdout()).isEmpty();
        // 默认模式：泵负责捕获，诊断尾部可读
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && managed.outputTail().isEmpty()) {
          Thread.sleep(20);
        }
        assertThat(managed.outputTail()).contains("tail");
      } finally {
        managed.close();
      }
    }
  }
}
