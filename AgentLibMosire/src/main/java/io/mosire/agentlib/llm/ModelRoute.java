package io.mosire.agentlib.llm;

import java.util.Objects;

/**
 * 模型路由：一个命名入口 → baseUrl/model + 密钥引用。
 *
 * <p>有意**不**内嵌数组/密钥值：密钥只以引用（环境变量名或 keys.* 配置键）形式存在， 由 ConfigStore（M3）解析；本记录是纯数据。OpenAI 兼容供应商均可用。
 */
public record ModelRoute(String name, String baseUrl, String model, String credentialsRef) {

  public ModelRoute {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(model, "model");
    // credentialsRef 允许空：匿名/本地部署（如 Ollama）无密钥
  }

  public static ModelRoute of(String name, String baseUrl, String model, String credentialsRef) {
    return new ModelRoute(name, baseUrl, model, credentialsRef);
  }
}
