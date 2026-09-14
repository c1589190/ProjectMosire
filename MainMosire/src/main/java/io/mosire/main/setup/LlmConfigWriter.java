package io.mosire.main.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.permission.AgentPermissionSet;
import java.util.Objects;

/**
 * 把引导得到的 provider 写进 {@code config.json}（配置引导 D2；CLI 向导与 HTTP setup 端点共用一处实现）： {@code
 * llm.routes.<name>} = {baseUrl, model, credentialsRef} + {@code keys.<name>}（apiKey 非空时）。
 *
 * <p><b>走 {@code ConfigStore.put} 而不是直接写文件</b>：合并语义保住文件里的其他键（approval 等）、原子写 + 读回校验免重造；授权面由 {@code
 * ConfigAuth} 的 {@code llm.*}/{@code keys.*} 仅 SYSTEM 分支把守， 本类以 {@link
 * AgentPermissionSet#system()}（运行时自身，{@code ownerId=null}）写入。schema 用<b>内联宽松 object
 * schema</b>：目标文件是全量 config.json（无既有 llm schema 基建），结构合法性由 {@link LlmRouteLoader#load}
 * 读侧把关——坏值在下次启动响亮失败，与本仓口径一致。
 *
 * <p><b>兄弟路由保留</b>：写 {@code llm.routes} 前先读现值逐键搬入再 set 目标名——put 的合并在<b>键路径</b>层， 整表覆盖会抹掉用户已配的其他路由（多
 * provider 并存是既有裁决，不能被引导破坏）。
 *
 * <p>密钥纪律：本类不打印、不记日志，apiKey 只落 {@code keys.*}。
 */
public final class LlmConfigWriter {

  private static final ObjectMapper JSON = new ObjectMapper();

  private LlmConfigWriter() {}

  /**
   * 写一条路由（及其密钥）。
   *
   * @param store 宿主配置根上的存储（{@code new FileConfigStore(configRoot)}）
   * @param routeName 路由名（须过 {@link LlmRouteLoader#isValidRouteName}）
   * @param baseUrl 完整 OpenAI 兼容地址（非空白）
   * @param model 模型名（非空白）
   * @param apiKey API 密钥；空白 = 匿名（不写 keys.*，credentialsRef 留空）
   * @throws IllegalArgumentException 路由名/baseUrl/model 不合法
   * @throws io.mosire.agentlib.config.ConfigException 落盘/校验失败（原样上抛，磁盘已回滚）
   */
  public static void write(
      FileConfigStore store, String routeName, String baseUrl, String model, String apiKey) {
    Objects.requireNonNull(store, "store");
    if (!LlmRouteLoader.isValidRouteName(routeName)) {
      throw new IllegalArgumentException("路由名非法（字母/数字开头，仅含字母数字_-）: " + routeName);
    }
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(model, "model");
    if (baseUrl.isBlank() || model.isBlank()) {
      throw new IllegalArgumentException("baseUrl/model 不得为空白");
    }
    String credentialsRef = apiKey == null || apiKey.isBlank() ? "" : "keys." + routeName;
    ObjectNode schema = lenientObjectSchema();

    ObjectNode routes = JSON.createObjectNode();
    store
        .get("llm", "routes")
        .filter(JsonNode::isObject)
        .ifPresent(
            existing ->
                existing.fields().forEachRemaining(e -> routes.set(e.getKey(), e.getValue())));
    ObjectNode route = routes.putObject(routeName);
    route.put("baseUrl", baseUrl);
    route.put("model", model);
    route.put("credentialsRef", credentialsRef);
    store.put("llm", "routes", routes, AgentPermissionSet.system(), null, schema);

    if (credentialsRef.isEmpty()) {
      return;
    }
    store.put(
        "keys", routeName, TextNode.valueOf(apiKey), AgentPermissionSet.system(), null, schema);
  }

  /** 宽松 object schema：只要求"是对象"——结构判据归读侧（见类注释）。 */
  private static ObjectNode lenientObjectSchema() {
    return JSON.createObjectNode().put("type", "object");
  }
}
