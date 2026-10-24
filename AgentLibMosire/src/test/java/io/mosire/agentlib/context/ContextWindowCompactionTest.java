package io.mosire.agentlib.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 新 compact 标记的 raw/可见投影、重启还原与图片消除。 */
class ContextWindowCompactionTest {

  private static final LlmMessage OLD_USER = LlmMessage.user("旧问题");
  private static final LlmMessage OLD_ANSWER =
      LlmMessage.assistant(List.of(new ContentPart.Text("旧回答")));

  @Test
  void withoutCompactMarkerAllReadingsAreExactlyTheLegacyRawWindow() {
    ContextWindow window = new ContextWindow("legacy", List.of(LlmMessage.user("abc"), OLD_ANSWER));

    assertThat(window.messages()).containsExactly(LlmMessage.user("abc"), OLD_ANSWER);
    assertThat(window.rawMessages()).containsExactlyElementsOf(window.messages());
    assertThat(window.size()).isEqualTo(2);
    assertThat(window.rawSize()).isEqualTo(2);
    assertThat(window.hasCompaction()).isFalse();
    assertThat(window.lastCompactionSummary()).isEmpty();
    assertThat(window.estimatedTokens()).isEqualTo(4);
    assertThat(window.unsavedTail()).isEmpty();
  }

  @Test
  void compactProjectsOneSyntheticUserWithSummaryAndNextUserWhileKeepingRawHistory() {
    ContextWindow window = new ContextWindow("c", List.of(OLD_USER, OLD_ANSWER));

    window.compact("SUMMARY-BODY");
    window.append(LlmMessage.user("NEXT-USER"));

    List<LlmMessage> visible = window.messages();
    String expectedText =
        ContextWindow.COMPACTION_CHECKPOINT_PREFIX
            + "SUMMARY-BODY"
            + ContextWindow.COMPACTION_CHECKPOINT_SUFFIX
            + "\n\nNEXT-USER";
    assertThat(visible).containsExactly(LlmMessage.user(expectedText));
    assertThat(visible.get(0).role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(visible.get(0).content())
        .allSatisfy(part -> assertThat(part).isInstanceOf(ContentPart.Text.class));
    assertThat(window.size()).isEqualTo(1);
    assertThat(window.estimatedTokens()).isPositive();
    assertThat(window.hasCompaction()).isTrue();
    assertThat(window.lastCompactionSummary()).contains("SUMMARY-BODY");

    LlmMessage marker =
        new LlmMessage(ContextWindow.ROLE_COMPACT, List.of(new ContentPart.Text("SUMMARY-BODY")));
    assertThat(window.rawMessages())
        .containsExactly(OLD_USER, OLD_ANSWER, marker, LlmMessage.user("NEXT-USER"));
    assertThat(window.rawSize()).isEqualTo(4);
    assertThat(window.unsavedTail()).containsExactly(marker, LlmMessage.user("NEXT-USER"));
    assertThat(window.messages())
        .noneMatch(message -> ContextWindow.ROLE_COMPACT.equals(message.role()));
  }

  @Test
  void rebuildingFromPersistedRawProducesTheSameVisibleProjection() {
    ContextWindow original = new ContextWindow("c", List.of(OLD_USER, OLD_ANSWER));
    original.compact("PERSISTED-SUMMARY");
    original.append(LlmMessage.user("persisted-next"));

    ContextWindow restarted = new ContextWindow("c-restarted", original.rawMessages());

    assertThat(restarted.messages()).isEqualTo(original.messages());
    assertThat(restarted.size()).isEqualTo(original.size());
    assertThat(restarted.estimatedTokens()).isEqualTo(original.estimatedTokens());
    assertThat(restarted.rawMessages()).isEqualTo(original.rawMessages());
    assertThat(restarted.unsavedTail()).isEmpty();
  }

  @Test
  void compactProjectionDropsImagesFromBothOldHistoryAndNextUser() {
    LlmMessage oldWithImage =
        new LlmMessage(
            LlmMessage.ROLE_USER,
            List.of(
                new ContentPart.Text("看图"),
                new ContentPart.Image("image/png", "asset-old-secret")));
    LlmMessage nextWithImage =
        new LlmMessage(
            LlmMessage.ROLE_USER,
            List.of(
                new ContentPart.Text("下一问"),
                new ContentPart.Image("image/jpeg", "asset-next-secret")));
    ContextWindow window = new ContextWindow("c", List.of(oldWithImage));
    window.compact("IMG-SUMMARY");
    window.append(nextWithImage);

    LlmMessage visible = window.messages().get(0);
    String text = ((ContentPart.Text) visible.content().get(0)).text();
    assertThat(text)
        .contains(ContextWindow.COMPACTION_CHECKPOINT_PREFIX)
        .contains("IMG-SUMMARY")
        .contains("下一问")
        .doesNotContain("asset-old-secret")
        .doesNotContain("asset-next-secret");
    assertThat(visible.content())
        .allSatisfy(part -> assertThat(part).isInstanceOf(ContentPart.Text.class));
    assertThat(window.messages())
        .noneMatch(
            message -> message.content().stream().anyMatch(ContentPart.Image.class::isInstance));
  }

  @Test
  void onlyTheLastCompactMarkerDefinesTheVisibleProjection() {
    ContextWindow window = new ContextWindow("c", List.of(OLD_USER, OLD_ANSWER));
    window.compact("FIRST-SUMMARY");
    window.append(LlmMessage.user("after-first"));
    window.append(OLD_ANSWER);
    window.compact("LAST-SUMMARY");
    window.append(LlmMessage.user("after-last"));

    List<LlmMessage> visible = window.messages();
    String text = ((ContentPart.Text) visible.get(0).content().get(0)).text();
    assertThat(text)
        .contains("LAST-SUMMARY")
        .contains("after-last")
        .doesNotContain("FIRST-SUMMARY")
        .doesNotContain("after-first")
        .doesNotContain("旧问题");
    assertThat(visible).hasSize(1);
    assertThat(window.lastCompactionSummary()).isEqualTo(Optional.of("LAST-SUMMARY"));
    assertThat(window.rawSize()).isEqualTo(7);
  }

  @Test
  void compactMarkerWithoutFollowingUserStillProjectsASummaryOnlyUser() {
    ContextWindow window = new ContextWindow("c", List.of(OLD_USER));
    window.compact("ONLY-SUMMARY");
    window.append(OLD_ANSWER);

    assertThat(window.messages())
        .containsExactly(
            LlmMessage.user(
                ContextWindow.COMPACTION_CHECKPOINT_PREFIX
                    + "ONLY-SUMMARY"
                    + ContextWindow.COMPACTION_CHECKPOINT_SUFFIX),
            OLD_ANSWER);
  }
}
