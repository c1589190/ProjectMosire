package io.mosire.brain.context;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.memory.MemoryRetriever;
import io.mosire.brain.skills.SkillCatalog;
import java.util.Objects;
import java.util.Set;

/**
 * 装配期内容源（P2-4 接线）：把技能目录与记忆检索两个协作者交给 {@link BasicContextAssembler}，灌进 {@link
 * ContextLayer#SKILL_INDEX} / {@link ContextLayer#LOADED_SKILLS} / {@link ContextLayer#MEMORY}
 * 三个槽位。
 *
 * <p><b>为什么调用者身份在这里，而不是 {@code buildRequest} 的参数里</b>：{@link ContextAssembler#buildRequest} 的四个参数
 * （config/userMessage/history/tools）都是<b>每回合</b>的事实，而 {@link MemoryRetriever#retrieve} 还需要"调用者是谁"
 * 做令牌级过滤——这个维度在一次请求里根本不存在。改接口签名会波及 {@code AgentPipeline} 与 A2A/AG-UI 两条外部路径，并动摇 P2-3
 * 的逐字节断言；于是改为<b>装配期注入</b>：assembler 本就是每 runtime 一个实例（{@code AgentRuntime} 构造注入），
 * 身份因此固定为<b>每实例</b>而非每请求。代价明确：同一实例服务多个身份时记忆可见性会串，跨身份请各自装配实例——共享运行时实例 （A2A/AG-UI 同一 Agent
 * 身份）是当下的既定形态。
 *
 * <p><b>缺省即关闭</b>：{@link #none()} 表示两个来源都没接线，输出与接线前逐字节一致（P2-3 的保证由此对新增构造同样成立）。
 * 两个来源互相独立——只接技能、只接记忆、都接都合法。
 *
 * @param skills 技能目录（三档披露的供给面）；{@code null} = 未接线技能
 * @param preloadedSkills 预载技能名集（对应 {@code AgentSpec.skills}）：与目录扫描序取交集后读正文进第二档；名字不在目录里
 *     时跳过而非抛错（陈旧名字不得让每个回合都崩）
 * @param memory 记忆检索流水线；{@code null} = 未接线记忆
 * @param identity 调用者权限集（记忆可见性的身份维度）；接线记忆时必填
 * @param memoryK 记忆召回条数上限（默认 {@link MemoryRetriever#DEFAULT_K}）
 */
public record ContextSources(
    SkillCatalog skills,
    Set<String> preloadedSkills,
    MemoryRetriever memory,
    AgentPermissionSet identity,
    int memoryK) {

  /** 校验装配不变式。预载集做防御拷贝；"预载集非空却没有目录"判为装配错误（构造期就拒，好过每回合静默不加载）。 */
  public ContextSources {
    preloadedSkills = Set.copyOf(preloadedSkills == null ? Set.of() : preloadedSkills);
    if (memory != null && identity == null) {
      throw new IllegalArgumentException("接线记忆检索时必须给出调用者权限集（可见性按身份令牌过滤）");
    }
    if (skills == null && !preloadedSkills.isEmpty()) {
      throw new IllegalArgumentException("预载技能集非空但未接线技能目录: " + preloadedSkills);
    }
    if (memoryK <= 0) {
      throw new IllegalArgumentException("memoryK 必须为正: " + memoryK);
    }
  }

  /** 空内容源：两个来源都未接线——只组装 SYSTEM 层，输出与 P2-3 逐字节一致。 */
  public static ContextSources none() {
    return new ContextSources(null, Set.of(), null, null, MemoryRetriever.DEFAULT_K);
  }

  /** 只接技能目录、不预载任何技能：每回合只披露第一档（名称 + 用途）。 */
  public static ContextSources skills(SkillCatalog skills) {
    return skills(skills, Set.of());
  }

  /** 接技能目录 + 预载集（对应 {@code AgentSpec.skills}）：命中目录者额外进第二档正文。 */
  public static ContextSources skills(SkillCatalog skills, Set<String> preloadedSkills) {
    return new ContextSources(
        Objects.requireNonNull(skills, "skills"),
        preloadedSkills,
        null,
        null,
        MemoryRetriever.DEFAULT_K);
  }

  /** 只接记忆检索（召回条数取默认 K = {@link MemoryRetriever#DEFAULT_K}）。 */
  public static ContextSources memory(MemoryRetriever memory, AgentPermissionSet identity) {
    return memory(memory, identity, MemoryRetriever.DEFAULT_K);
  }

  /** 只接记忆检索并显式给定召回条数。 */
  public static ContextSources memory(
      MemoryRetriever memory, AgentPermissionSet identity, int memoryK) {
    return new ContextSources(
        null,
        Set.of(),
        Objects.requireNonNull(memory, "memory"),
        Objects.requireNonNull(identity, "identity"),
        memoryK);
  }

  /** 是否接线了技能来源。 */
  public boolean hasSkills() {
    return skills != null;
  }

  /** 是否接线了记忆来源。 */
  public boolean hasMemory() {
    return memory != null;
  }

  /** 换预载集（原记录不变）——装配层按 {@code AgentSpec} 逐字段覆盖时用。 */
  public ContextSources withPreloadedSkills(Set<String> names) {
    return new ContextSources(skills, names, memory, identity, memoryK);
  }

  /** 换召回条数（原记录不变）。 */
  public ContextSources withMemoryK(int k) {
    return new ContextSources(skills, preloadedSkills, memory, identity, k);
  }
}
