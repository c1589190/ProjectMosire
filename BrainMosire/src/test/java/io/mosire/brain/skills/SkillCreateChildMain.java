package io.mosire.brain.skills;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 跨进程竞态夹具的<b>子进程侧</b>（由 {@link SkillCreateCrossProcessTest} 以 {@code java -cp ...} 拉起）： 与父 JVM
 * 用屏障文件对齐后同时 {@code create} 同名技能，结果写回屏障目录。
 *
 * <p>它<b>不是</b>测试类（名字不匹配 Surefire 的测试模式）：只是“第二个 JVM”的载体。之所以要真进程，是因为 D2 的原始
 * 事故形状就是<b>两个进程</b>同时建同名技能；同 JVM 内的两个线程只能证明线程安全，证不了“进程边界之外”仍是恰好一个赢家。
 *
 * <p>参数：[0] 技能根，[1] 屏障目录，[2] 轮数，[3] 名字前缀。
 */
public final class SkillCreateChildMain {

  private SkillCreateChildMain() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path barrier = Path.of(args[1]);
    int rounds = Integer.parseInt(args[2]);
    String prefix = args[3];
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(), ResourceScope.unlimited(), ResourceScope.unlimited());
    for (int round = 0; round < rounds; round++) {
      String name = prefix + round;
      Files.writeString(barrier.resolve("child-ready-" + round), "1");
      Path go = barrier.resolve("go-" + round);
      long deadline = System.nanoTime() + 30_000_000_000L;
      while (!Files.exists(go)) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("等待 go 屏障超时: " + go);
        }
        Thread.onSpinWait();
      }
      String outcome;
      try {
        SkillAccess access = SkillAccess.of(permissions, new SkillCatalog(), root);
        access.create(name, "跨进程-" + name, "CHILD-BODY", null);
        outcome = "OK";
      } catch (SkillException failure) {
        outcome = "FAIL:" + failure.code();
      } catch (Throwable failure) {
        outcome = "ERR:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
      }
      // 先写临时文件再原子改名：父进程按“文件存在”读，不能读到半行
      Path temp = barrier.resolve("child-outcome-" + round + ".tmp");
      Files.writeString(temp, outcome);
      Files.move(temp, barrier.resolve("child-outcome-" + round), StandardCopyOption.ATOMIC_MOVE);
    }
  }
}
