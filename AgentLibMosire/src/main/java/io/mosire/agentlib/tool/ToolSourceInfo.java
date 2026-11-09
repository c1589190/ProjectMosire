package io.mosire.agentlib.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具供给源（L2）的<b>展示元数据</b>：一个 sourceId 显示成什么名字、什么版本。
 *
 * <p><b>它不参与权限/判定/卸载</b>：按源卸载仍只认 {@link ToolRegistry#unregisterAllBySource(String)} 的 sourceId；
 * 本记录只回答“这批工具来自哪个模块/版本”（Web 管理面、{@code tool_catalog} 查看工具、审计）。
 *
 * <p><b>为什么与 {@link ToolRegistry} 分开存放</b>：工具可以整组下线，但“这个 source 上次是什么版本”仍是管理面要展示的事实；因此 {@code
 * unregisterAllBySource} <b>不</b>删除本记录，由 {@link ToolRegistry#unregisterSourceInfo(String)} 显式删除。
 *
 * @param sourceId 供给源 id（非空白；与注册工具时的 sourceId 逐字一致）
 * @param displayName 展示名（null/空白 ⇒ 回落为 sourceId）
 * @param version 版本（null ⇒ 空串；不要求 semver——不同模块的版本口径归模块自己）
 * @param description 可选说明（null ⇒ 空串）
 * @param attributes 可选附加键值（null ⇒ 空 Map；立即不可变拷贝）
 */
public record ToolSourceInfo(
    String sourceId,
    String displayName,
    String version,
    String description,
    Map<String, String> attributes) {

  public ToolSourceInfo {
    Objects.requireNonNull(sourceId, "sourceId");
    if (sourceId.isBlank()) {
      throw new IllegalArgumentException("ToolSourceInfo.sourceId 不得为空白");
    }
    displayName = displayName == null || displayName.isBlank() ? sourceId : displayName;
    version = version == null ? "" : version;
    description = description == null ? "" : description;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** 便捷工厂：无说明/附加属性。 */
  public static ToolSourceInfo of(String sourceId, String displayName, String version) {
    return new ToolSourceInfo(sourceId, displayName, version, "", Map.of());
  }
}
