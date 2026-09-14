package io.mosire.main.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.permission.AccessToken;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LlmConfigWriter}：写入后经<b>读侧原路</b>（{@link LlmRouteLoader} + {@link ConfigApiKeySource}）
 * 读得回——写读同源是判据，不是写完看 JSON 长相；兄弟路由保留、匿名形态、非法入参各一钉。
 */
class LlmConfigWriterTest {

  @TempDir Path tempDir;

  @Test
  void writtenRouteAndKeyRoundTripThroughTheLoaders() {
    FileConfigStore store = new FileConfigStore(tempDir);

    LlmConfigWriter.write(store, "default", "https://example.com/v4", "glm-5.3-flash", "sk-abc");

    ModelRoute route = LlmRouteLoader.load(store);
    assertThat(route.name()).isEqualTo("default");
    assertThat(route.baseUrl()).isEqualTo("https://example.com/v4");
    assertThat(route.model()).isEqualTo("glm-5.3-flash");
    assertThat(route.credentialsRef()).isEqualTo("keys.default");
    assertThat(new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM).apiKey())
        .isEqualTo(Optional.of("sk-abc"));
  }

  @Test
  void siblingRoutesSurviveANewWrite() {
    FileConfigStore store = new FileConfigStore(tempDir);
    LlmConfigWriter.write(store, "glm", "https://glm.example/v4", "glm-5.3-flash", "sk-glm");

    LlmConfigWriter.write(store, "default", "https://ds.example", "deepseek-chat", "sk-ds");

    assertThat(LlmRouteLoader.load(store, "glm").model()).isEqualTo("glm-5.3-flash");
    assertThat(LlmRouteLoader.load(store, "default").model()).isEqualTo("deepseek-chat");
    assertThat(new ConfigApiKeySource(store, "keys.glm", AccessToken.SYSTEM).apiKey())
        .isEqualTo(Optional.of("sk-glm"));
  }

  @Test
  void blankApiKeyMeansAnonymousAndWritesNoKey() {
    FileConfigStore store = new FileConfigStore(tempDir);

    LlmConfigWriter.write(store, "default", "http://localhost:11434/v1", "llama3", "  ");

    ModelRoute route = LlmRouteLoader.load(store);
    assertThat(route.credentialsRef()).isEmpty();
    assertThat(new ConfigApiKeySource(store, "", AccessToken.SYSTEM).apiKey()).isEmpty();
    assertThat(store.get("keys", "default")).isEmpty();
  }

  @Test
  void rejectsBadRouteNameAndBlankRequiredFields() {
    FileConfigStore store = new FileConfigStore(tempDir);

    assertThatThrownBy(() -> LlmConfigWriter.write(store, "bad name", "https://x", "m", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("路由名非法");
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", " ", "m", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LlmConfigWriter.write(store, "default", "https://x", " ", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
