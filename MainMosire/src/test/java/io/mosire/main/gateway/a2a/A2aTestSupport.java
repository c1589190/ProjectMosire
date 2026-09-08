package io.mosire.main.gateway.a2a;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;

/** M2-B 测试公共装配：AgentCard / URL。 */
final class A2aTestSupport {

  private A2aTestSupport() {}

  /**
   * 标准测试卡：JSONRPC 绑定（官方 client 按 {@code TransportProtocol.JSONRPC.asString()} = "JSONRPC"
   * 匹配协议）、流式能力、一个 skill。
   */
  static AgentCard card(String url) {
    return AgentCard.builder()
        .name("mosire-test")
        .description("ProjectMosire test agent")
        .version("0.1.0")
        .capabilities(AgentCapabilities.builder().streaming(true).build())
        .defaultInputModes(List.of("application/json"))
        .defaultOutputModes(List.of("application/json"))
        .skills(
            List.of(
                AgentSkill.builder()
                    .id("test-skill")
                    .name("Test Skill")
                    .description("a test skill")
                    .tags(List.of())
                    .build()))
        .url(url)
        .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url)))
        .build();
  }

  static String baseUrl(A2aHttpServer server) {
    return baseUrl(server.port());
  }

  static String baseUrl(int port) {
    return "http://127.0.0.1:" + port;
  }
}
