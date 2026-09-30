package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 模型供应商注册表：登记/解析/能力查询/同名覆盖（D18 前置：provider+model 选路由的数据基础）。 */
class ModelProviderTest {

  @Test
  void registerThenResolveReturnsRouteAndCapabilities() {
    ModelProvider provider = new ModelProvider();
    ModelRoute route = ModelRoute.of("main", "https://api.example.com/v1", "gpt-x", "keys.openai");
    ModelCapabilities caps = new ModelCapabilities(true, true, true, true, false, 200_000, 8_192);

    provider.register(route, caps);

    assertThat(provider.resolve("main")).contains(route);
    assertThat(provider.capabilities("main")).isEqualTo(caps);
    assertThat(provider.size()).isEqualTo(1);
  }

  @Test
  void unknownNameYieldsEmptyRouteAndConservativeDefaults() {
    ModelProvider provider = new ModelProvider();

    assertThat(provider.resolve("missing")).isEmpty();
    // 未注册模型的能力查询必须可空安全：返回保守全关的 defaults，调用方无需判空
    assertThat(provider.capabilities("missing")).isEqualTo(ModelCapabilities.defaults());
    assertThat(ModelCapabilities.defaults().echoReasoningContent())
        .as("A6 修复版的新能力位默认关闭，老调用点行为不变")
        .isFalse();
    assertThat(provider.size()).isZero();
  }

  /** 同名覆盖：注册表以 name 为键，重复 register 必须整体替换（路由+能力），而非报错或追加。 */
  @Test
  void registerSameNameOverwritesRouteAndCapabilities() {
    ModelProvider provider = new ModelProvider();
    ModelRoute first = ModelRoute.of("main", "https://old.example.com", "m1", "keys.old");
    ModelRoute second = ModelRoute.of("main", "https://new.example.com", "m2", "");
    ModelCapabilities replaced = ModelCapabilities.defaults();

    provider.register(first, new ModelCapabilities(true, false, false, false, false, 1, 1));
    provider.register(second, replaced);

    assertThat(provider.size()).isEqualTo(1);
    assertThat(provider.resolve("main")).contains(second);
    assertThat(provider.capabilities("main")).isEqualTo(replaced);
  }

  @Test
  void distinctNamesCountSeparately() {
    ModelProvider provider = new ModelProvider();
    provider.register(ModelRoute.of("a", "https://a", "ma", ""), ModelCapabilities.defaults());
    provider.register(ModelRoute.of("b", "https://b", "mb", ""), ModelCapabilities.defaults());

    assertThat(provider.size()).isEqualTo(2);
  }
}
