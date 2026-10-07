package io.mosire.brain.skills;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import java.nio.file.Path;

/**
 * 测试用装配件：把裸 {@link SkillCatalog} 包成“可见/可编辑都不限”的 {@link SkillAccess}。
 *
 * <p><b>为什么需要它（老测试迁移说明）</b>：技能来源从“裸目录”改成“访问面”是<b>架构性</b>变更——裸目录在每个读点都会隐式读成
 * “全可见”，而新的访问面要求调用方显式表态。旧用例（{@code BasicContextAssemblerSourcesTest} / {@code
 * AgentPipelineCompactionTest} 等）判的是“三档披露与层序”，不判可见面，因此迁移成显式的“不限”而不是删掉它们：
 * 判据逐条保留，只是把过去隐式的那一层写成明文。可见面本身的判据在新用例 （{@code SkillAccessTest} / {@code SkillToolsTest} / {@code
 * SkillPermissionsTest}）里。
 *
 * <p>本类只在测试源集里，不进发行物。
 */
public final class SkillTestSupport {

  private SkillTestSupport() {}

  /**
   * 不限面的访问件。
   *
   * @param catalog 技能目录
   * @param root 技能根（本访问件不写盘，root 只影响写路径；读路径按技能自身路径判可见面）
   */
  public static SkillAccess unrestricted(SkillCatalog catalog, Path root) {
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(), ResourceScope.unlimited(), ResourceScope.unlimited());
    return SkillAccess.of(permissions, catalog, root);
  }

  /** 便捷形态：root 取进程 CWD（只读用例不需要真实根）。 */
  public static SkillAccess unrestricted(SkillCatalog catalog) {
    return unrestricted(catalog, Path.of(""));
  }
}
