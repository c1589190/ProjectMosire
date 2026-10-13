package io.mosire.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * 映射策略（扩展点 1，设计 §4.4）。<b>v1 只交付 {@link Resolvers#direct()}：一对一。</b>
 *
 * <p><b>实现必须 fail-closed：越界一律抛，不猜。</b>解析结果最终还要过 {@link WorkspaceRegistry#resolve} 的 containment
 * 校验（G1）——但那不是"resolver 可以乱来"的借口：一个把 {@code docs} 映射到 {@code /etc} 的 resolver 就是越权后门， G1 是兜底，不是免责。
 *
 * <p>★ <b>推迟项（N8）</b>：多物理根覆盖（overlay）与分片（sharded）留在这个接口后面，等真有第三个消费者时再实现—— 第一版不放进来。因此 v1
 * 不存在"多义"这一失败形态：{@code direct()} 的解析结果唯一。
 *
 * <p><b>虚拟拼法不归 resolver 管</b>（G3）：{@code <挂载名>/<相对路径>} 的拼法由标准定死，resolver 只回答"这个相对路径
 * 落到哪个物理位置"；它拿不到挂载名，也就造不出第二处拼写点。
 */
public interface Resolver {

  /**
   * 相对路径 → 物理位置。
   *
   * @param dir 该挂载的物理根（绝对路径，已归一）
   * @param relativePath 挂载内的相对路径（可为空串 = 挂载根；不得以 {@code /} 开头、不得含 {@code .}/{@code ..} 段）
   * @return 物理位置（绝对路径）；解析不出/输入非法 ⇒ 抛 {@link WorkspaceException}
   */
  Path resolve(Path dir, String relativePath);

  /**
   * 列出某个相对前缀下的<b>直接子项</b>，返回<b>相对于 {@code dir} 的路径</b>（POSIX 分隔符、不含前导 {@code /}）。
   *
   * <p><b>为什么返回相对路径而不是虚拟路径</b>：resolver 不知道挂载名（也不该知道）——投影成虚拟路径是 {@link WorkspaceRegistry#list}
   * 的唯一职责（G3/A7）。
   *
   * <p><b>不递归</b>：本 SPI 没有深度参数；"列出工作区的直接子项"是唯一能由现有参数确定语义的形态，需要更深就逐层下钻。
   *
   * @param dir 该挂载的物理根（绝对路径，已归一）
   * @param relativePrefix 挂载内的相对前缀（可为空串 = 挂载根）
   * @throws WorkspaceException 前缀不存在 / 不是目录 / 越界
   */
  List<String> list(Path dir, String relativePrefix);
}
