package io.mosire.workspace;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CapabilityCatalog} 的装配期实现（内存态）。
 *
 * <p><b>重复注册的语义写死</b>：同一 id、<b>定义相同</b> ⇒ 幂等（装配代码重复跑一遍不算错）；同一 id、<b>定义不同</b> ⇒
 * 响亮失败。理由：能力定义决定了"这个能力落到哪些命名空间"，两个不同的定义同时存在时，落格结果将取决于装配顺序—— 那是最难查的一类缺陷（"在我机器上看得见"）。
 */
public final class CapabilityCatalogs {

  private CapabilityCatalogs() {
    throw new AssertionError("No instances");
  }

  /** 空目录（线程安全、可并发读；装配期写）。 */
  public static CapabilityCatalog inMemory() {
    return new InMemoryCatalog();
  }

  private static final class InMemoryCatalog implements CapabilityCatalog {

    private final Map<String, Capability> byId = new ConcurrentHashMap<>();

    @Override
    public void register(Capability capability) {
      Objects.requireNonNull(capability, "capability");
      Capability previous = byId.putIfAbsent(capability.id(), capability);
      if (previous != null && !previous.equals(capability)) {
        throw new IllegalArgumentException(
            "能力 id 已注册且定义不同（装配期一次性动作，同名不同义会让落格取决于装配顺序）: "
                + capability.id()
                + " 已注册="
                + previous
                + " 本次="
                + capability);
      }
    }

    @Override
    public Optional<Capability> get(String id) {
      if (id == null) {
        return Optional.empty();
      }
      String key = id.strip();
      return key.isEmpty() ? Optional.empty() : Optional.ofNullable(byId.get(key));
    }
  }
}
