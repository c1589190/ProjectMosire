package io.mosire.agentlib.llm;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型供应商注册表：{@link ModelRoute}（baseUrl/model/credentialsRef）+ {@link ModelCapabilities} 能力
 * 描述的线程安全登记处。
 *
 * <p>以路由 {@code name} 为键、同名整体覆盖；供 AgentSpec 按 provider+model 选路由。只做登记与查询——
 * 不发网络请求、不解析密钥（credentialsRef 由 ConfigStore 按 M3 约定解析）。
 */
public final class ModelProvider {

  private final Map<String, Entry> routes = new ConcurrentHashMap<>();

  private record Entry(ModelRoute route, ModelCapabilities capabilities) {}

  /** 注册（或同名覆盖）一条路由及其能力描述。 */
  public void register(ModelRoute route, ModelCapabilities caps) {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(caps, "caps");
    routes.put(route.name(), new Entry(route, caps));
  }

  /** 按名查路由；未注册返回 {@link Optional#empty()}。 */
  public Optional<ModelRoute> resolve(String name) {
    Entry entry = routes.get(Objects.requireNonNull(name, "name"));
    return entry == null ? Optional.empty() : Optional.of(entry.route());
  }

  /** 按名查能力；未注册返回保守的 {@link ModelCapabilities#defaults()}（调用方无需判空）。 */
  public ModelCapabilities capabilities(String name) {
    Entry entry = routes.get(Objects.requireNonNull(name, "name"));
    return entry == null ? ModelCapabilities.defaults() : entry.capabilities();
  }

  /** 已注册路由数（同名覆盖不重复计数）。 */
  public int size() {
    return routes.size();
  }
}
