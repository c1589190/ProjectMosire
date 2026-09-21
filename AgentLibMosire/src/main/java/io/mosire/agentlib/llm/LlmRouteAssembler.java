package io.mosire.agentlib.llm;

import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import java.util.List;
import java.util.Objects;

/**
 * <b>官方装配入口</b>（A4③）：把"零件"（{@link LlmRouteLoader} 读出的 {@link ModelRoute}、{@link ConfigApiKeySource}
 * 解析的密钥引用、{@link LlmTransport} 声明的协议与超时）装成"能用的一件东西"（{@link LlmClient} 或 {@link ModelProvider}）。
 *
 * <p><b>为什么必须有这个类</b>：在此之前，{@code LlmRouteLoader} / {@code ModelProvider} / {@code
 * ConfigApiKeySource} 三件零件都齐了，却<b>没有一个官方的总装点</b>——每个宿主（Main、SubagentProcessMain）各自写一遍装配循环，于是"配置怎么变成
 * 客户端"这件事有 N 份实现，任何一处改口径（例如新增一种协议）都要靠人去把所有副本找齐。这个类就是那个"只有一份"的地方。
 *
 * <p><b>官方示例（3 行拿到可用的 LlmClient）</b>：
 *
 * <pre>{@code
 * ConfigStore store = new FileConfigStore(configRoot);
 * LlmClient llm = LlmRouteAssembler.client(store, "deepseek", AccessToken.SYSTEM);
 * String answer = llm.text(new LlmRequest(messages).withTemperature(0));
 * }</pre>
 *
 * <p><b>装配是全有或全无的</b>（{@link #provider}）：任何一条路由配置坏了都让整次装配响亮失败。想"列表里显示出坏掉的那一条"请用 {@link
 * LlmRouteLoader#availableNames}——它只枚举名字、不校验内容，正是为配置页那种"先看到全部、再逐条报错"的界面准备的。
 * 两个入口的分工是有意的：<b>枚举要给全</b>（否则用户看不见自己写坏的那条，无从修），<b>装配要全对</b>（否则坏路由会悄悄缺席）。
 */
public final class LlmRouteAssembler {

  private LlmRouteAssembler() {}

  /**
   * 按名字装配一个可用的 {@link LlmClient}（配置 → 路由 → 方言 → 客户端 + 取密钥 SPI）。
   *
   * <p>密钥<b>不在装配期解析</b>：{@link ConfigApiKeySource} 每次 {@link LlmClient#chat} 现取一次（支持轮换/过期感知），
   * 所以"密钥此刻缺失"要等到真正调用才响亮抛出——这与"装配失败"是两回事，不要指望本方法替你验证密钥。
   *
   * @param store 配置存储（本进程配置根上的实例）
   * @param routeName 路由名；空白 = {@code "default"}（{@link LlmRouteLoader#load} 的口径）
   * @param token 调用方进程运行时身份，必须至少 {@link AccessToken#SYSTEM}（取本进程 LLM 密钥的门槛，见 {@link
   *     ConfigApiKeySource}）
   * @return 按该路由配置装配好的客户端
   * @throws io.mosire.agentlib.config.ConfigException 路由缺失/形态不对/协议未知/身份不足
   * @throws IllegalArgumentException {@code baseUrl} 不是合法根（见 {@link OpenAICompatibleLlmClient}
   *     的构造期校验）
   */
  public static LlmClient client(ConfigStore store, String routeName, AccessToken token) {
    Objects.requireNonNull(store, "store");
    ModelRoute route = LlmRouteLoader.load(store, routeName);
    OpenAICompatibleLlmClient.ApiKeySource keySource =
        new ConfigApiKeySource(store, route.credentialsRef(), token);
    return client(route, keySource);
  }

  /**
   * 按<b>内存里的一条路由</b>装一个客户端——给"配置还没写盘"的场景用：设置向导 / provider 配置页的"测试连接"探的是
   * 用户刚在界面上敲的那份候选配置，它此刻<b>并不在</b> {@link ConfigStore} 里，{@link #client(ConfigStore, String,
   * AccessToken)} 天生覆盖不到。
   *
   * <p><b>为什么这个重载必须存在</b>：装配里唯一"会长歪"的一步是 {@code switch (protocol)}。探测路径若自己 {@code new}
   * 客户端，将来新增一种方言时它就会漏掉——而"漏掉"的表现是<b>探测通过、真实装配却走另一条路</b>， 用户看到的是"测试连接成功了但用不了"。把协议开关收到这一处，"新增方言 ⇒
   * 只有一个地方编译不过"才对<b>所有</b>装配路径成立。
   *
   * <p>密钥源由调用方给：候选密钥来自用户刚敲进去的值（或另一个存储），天然不在 {@code keys.*} 里。传什么源由调用方负责，
   * 但<b>不可协商的约束跟着走</b>：密钥值绝不进日志、异常、事件、argv、stdio。
   *
   * @param route 待试的路由（可以与配置里的任何一条都不同，甚至尚未存在）
   * @param keySource 取密钥的 SPI（空 = 匿名调用）
   * @return 按该路由装配好的客户端（此方法<b>不发请求</b>——"测试连接"由调用方自己发一次最小请求）
   * @throws IllegalArgumentException {@code baseUrl} 不是合法根，或路由声明的协议本库未实现（见 {@link
   *     OpenAICompatibleLlmClient} 的构造期校验）
   */
  public static LlmClient client(
      ModelRoute route, OpenAICompatibleLlmClient.ApiKeySource keySource) {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(keySource, "keySource");
    // switch 表达式 + 无 default：新增方言时**这里编译不过**（而不是运行时悄悄按 OpenAI 协议发出去）
    return switch (route.transport().protocol()) {
      case OPENAI_COMPATIBLE -> new OpenAICompatibleLlmClient(route, keySource);
    };
  }

  /**
   * 把配置里<b>全部</b>路由登记成一个 {@link ModelProvider}（路由 + 能力描述）。
   *
   * <p>不需要 {@link AccessToken}：{@link ModelProvider} 只登记"有哪些路由、各自什么能力"，<b>不解析也不持有</b>密钥 （密钥在每次
   * {@code chat} 时由 {@link ConfigApiKeySource} 现取）。装配与密钥在此<b>解耦</b>是刻意的：枚举/选择路由 不需要密钥，只有真正调用才需要。
   *
   * <p>枚举用 {@link LlmRouteLoader#availableNames}（已确认它是权威入口：具名表 {@code llm.routes.*} 的全部键名，外加扁平形态
   * 在场时的 {@code "default"}），逐条 {@link LlmRouteLoader#load}。
   *
   * @param store 配置存储
   * @return 含全部路由的登记表（可能为空——没有任何已配置路由时返回空表而非抛错）
   * @throws io.mosire.agentlib.config.ConfigException 任何一条路由坏掉（全有或全无，见类 Javadoc）
   */
  public static ModelProvider provider(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    ModelProvider provider = new ModelProvider();
    List<String> names = LlmRouteLoader.availableNames(store);
    for (String name : names) {
      provider.register(LlmRouteLoader.load(store, name), LlmRouteLoader.capabilities(store, name));
    }
    return provider;
  }
}
