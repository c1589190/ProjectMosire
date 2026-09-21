package io.mosire.agentlib.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.permission.AgentPermissionSet;
import java.util.Optional;

/**
 * 通用配置存储（计划 §3.5「配置即数据」）：分层合并 + schema 校验 + 原子写回滚 + 按键前缀授权。
 *
 * <p><b>分层模型</b>（低 → 高）：{@code config.json}（全局，含 {@code runtime.*}/{@code keys.*} 以及 {@code
 * agents.<id>.*} 的全局缺省，键为全量点分路径）← {@code agents/<id>.json}（该 Agent 自己的配置子树，键为后缀）← env
 * 覆盖层（读时叠加、永不落盘、不含 {@code keys.*}——对计划原文 「env 覆盖」的有意偏离，见 {@link FileConfigStore} 的 Javadoc）。
 *
 * <p><b>键</b>：全量点分键 = {@code prefix + "." + key}。{@code agents.<id>.*} 由该 id 的 Agent 写、 {@code
 * runtime.*}/{@code keys.*} 仅 SYSTEM——判定在 {@link io.mosire.agentlib.permission.ConfigAuth} （纯函数，红线
 * 1 的第二个实现点），{@code put} 入口强制执行并抛 {@link ConfigException}（{@code E_PREFIX_DENIED}）。
 *
 * <p><b>本接口不认识任何业务类型</b>（计划 p2.md:472 的「AgentSpec.fromJson 走 schema」跨层不可达： {@code AgentSpec} 在
 * Brain、本接口在 AgentLib——属计划缺陷）。值一律以 {@link JsonNode} 表达，校验吃 调用方传入的 schema（文件名——相对实现注入的 schema
 * 目录解析——或 JsonNode）。
 *
 * <p><b>密钥</b>：{@code keys.*} 只保证<b>引用</b>（与 {@code ModelRoute#credentialsRef} 同概念）
 * 的存储与授权；真实解析（取到密钥值）不在本接口（T18 R2 裁决，接线归 T23）。密钥值绝不进日志、 绝不进异常消息（{@link ConfigException} 的契约）。
 *
 * <p><b>读</b>不鉴权（工具层按计划 §4.6 分档；{@code keys.*} 不进工具可读范围属工具层过滤）； 但 {@code keys.*} 永远不会从 env
 * 覆盖层读出（R5-2）。
 */
public interface ConfigStore {

  /**
   * 读一个键的分层合并值（env 覆盖层 → {@code agents/<id>.json} → {@code config.json}，先到者胜）； 三层都没有则返回空。
   *
   * <p>既有文件<b>损坏</b>（不是合法 JSON / 顶层不是对象）→ 抛 {@link ConfigException} （{@code E_CONFIG_CORRUPT}）；JSON
   * 合法但违反 schema 的旧值照常返回（schema 是写侧契约，读不校验—— 见 {@link FileConfigStore}）。键格式不合规（空段/{@code .}/{@code
   * ..}）一律按"不存在"返回空， 不抛（读侧 deny-by-default 的镜像）。
   *
   * @param prefix 键前缀（如 {@code agents.bob}、{@code runtime}、{@code keys}）
   * @param key 前缀下的键（点分可嵌套）
   */
  Optional<JsonNode> get(String prefix, String key);

  /**
   * 写入一个键（以 {@code schemaFile} 校验合并后的<b>整个目标文件</b>；文件名相对注入的 schema 目录 解析，绝对路径则直接用）。
   *
   * <p>流程（任一失败即抛 {@link ConfigException}，磁盘不被触碰或已回滚）：前缀授权 → 合并进目标文件 （合并 ≠ 替换：同文件其他键保留）→ 写前 schema
   * 校验 → 原子写（同目录 tmp + fsync + {@code .bak} 备份 + {@code ATOMIC_MOVE}）→ 读回校验（重新解析 + schema
   * 再校验，失败回滚）→ 落盘成功后回调监听器。
   *
   * @param prefix 键前缀
   * @param key 前缀下的键（点分可嵌套）
   * @param value 值（非 null；删除请走 {@link #remove}——本接口不接受用 JSON null 表达删除）
   * @param permissions 调用者权限集（其 {@code grantedToken} 参与前缀授权；null 视为无身份 → 拒绝）
   * @param ownerId 调用者<b>自报</b>的归属 agent id（非 Agent 调用方——如运行时自身——传 null）。 本接口只做"键归属 ≟
   *     自报归属"的比对，验证调用者真实身份是上层（工具调用上下文）的职责
   * @param schemaFile schema 文件名（相对 schema 目录）或绝对路径；缺失/不可读 → {@code E_SCHEMA_UNAVAILABLE}
   */
  void put(
      String prefix,
      String key,
      JsonNode value,
      AgentPermissionSet permissions,
      String ownerId,
      String schemaFile);

  /**
   * 同 {@link #put(String, String, JsonNode, AgentPermissionSet, String, String)}，但 schema 以内联
   * {@link JsonNode} 给出（不落盘、不读文件）。
   */
  void put(
      String prefix,
      String key,
      JsonNode value,
      AgentPermissionSet permissions,
      String ownerId,
      JsonNode schemaNode);

  /**
   * 删除一个键（CRUD 的 D——provider 配置页必需：能增能改不能删，等于逼用户手改 JSON 文件）。
   *
   * <p><b>流程与 {@link #put(String, String, JsonNode, AgentPermissionSet, String, String)}
   * 逐字同源</b>（前缀授权 → 从目标文件摘除 → 写前 schema 校验 → 原子写 → 读回校验 → 失败回滚 → 落盘后回调）：删除是<b>另一种写</b>，
   * 没有理由走一条更弱的路径。schema 照传照校验——删掉一个 schema 要求的必填键，应当在落盘前被拦下，而不是等下一次读的人炸 （到那时已经查不出"是谁删的"了）。
   *
   * <p><b>键本就不存在 = 无操作</b>（幂等：文件不被触碰、不回调、不抛）。DELETE 的语义是"确保它不在"， 重复点删除按钮、或两个进程先后删同一条，都不该失败。这与
   * {@link #put} 拒绝 JSON null 不矛盾： 那是"别用 put 表达删除"，这里是"删除这个动作"本身。
   *
   * <p><b>沿途被删空的对象会一并摘掉</b>：删 {@code llm.routes.glm} 后若 {@code llm.routes} 空了，不会留下一具 {@code
   * "routes": {}} 的空壳（配置页渲染原始 JSON 时不该显示出已经不存在的分组）。整份文档被删空时<b>文件本身保留</b> （写成 {@code
   * {}}——空对象就是合法空配置；文件在不在是另一层语义：权限、备份、路径）。
   *
   * <p><b>删不掉根</b>：{@code prefix + "." + key} 必须至少含一段合法键（空键、空段、{@code .}/{@code ..} 段一律 {@code
   * E_PREFIX_DENIED}）：删除的对象永远是"某个键"，不是"这个文件"。
   *
   * @param prefix 键前缀
   * @param key 前缀下的键（点分可嵌套）
   * @param permissions 调用者权限集（与 {@link #put} 同一套前缀授权；null 视为无身份 → 拒绝）
   * @param ownerId 调用者<b>自报</b>的归属 agent id（非 Agent 调用方传 null）
   * @param schemaFile schema 文件名（相对 schema 目录解析）或绝对路径；语义与 {@link #put} 的同名参数一致
   * @throws ConfigException {@code E_PREFIX_DENIED}（越权/非法键）、{@code E_SCHEMA_INVALID}（删除后不满足 schema：
   *     未落盘或已回滚）、{@code E_SCHEMA_UNAVAILABLE}、{@code E_CONFIG_IO}（I/O 或回滚失败）、 {@code
   *     E_CONFIG_CORRUPT}（既有文件损坏）
   */
  void remove(
      String prefix, String key, AgentPermissionSet permissions, String ownerId, String schemaFile);

  /**
   * 同 {@link #remove(String, String, AgentPermissionSet, String, String)}，但 schema 以内联 {@link
   * JsonNode} 给出。
   */
  void remove(
      String prefix,
      String key,
      AgentPermissionSet permissions,
      String ownerId,
      JsonNode schemaNode);
}
