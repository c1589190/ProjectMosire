package io.mosire.workspace;

import java.util.Optional;

/**
 * 能力目录：装配期注册（docs-mosire 注册 {@code docs}；{@code bash} 工具族注册 {@code bash}）。
 *
 * <p><b>为什么要有目录而不是让挂载直接写命名空间</b>：挂载声明的是"能力 id"，落格是"命名空间 + 前缀"。若让每次挂载自己写
 * 命名空间，同一个能力在不同装配点就会写出不同的落格（"docs 能力在这里落了 docs-edit，在那里没有"），而"能不能读"这条 规则就有了第二个归属地。目录把"能力 →
 * 落格"钉成一份装配期数据。
 *
 * <p>实现必须是线程安全的（装配期与运行期都可能读）。{@link #register} 的形状与失败语义由实现写死（见 {@link
 * CapabilityCatalogs#inMemory()}）。
 */
public interface CapabilityCatalog {

  /**
   * 注册一个能力（装配期动作）。
   *
   * @throws IllegalArgumentException 能力定义非法，或同一 id 已注册且定义不同（同名不同义会让落格取决于装配顺序）
   */
  void register(Capability capability);

  /** 按 id 取能力；未注册 ⇒ {@link Optional#empty()}（调用方须 fail-closed，不许当"不限"读）。 */
  Optional<Capability> get(String id);
}
