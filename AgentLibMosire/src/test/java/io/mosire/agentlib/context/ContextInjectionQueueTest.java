package io.mosire.agentlib.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ContextWindow} 的注入队列语义：幂等、revision 替换、优先级、commit 后不重复。 */
class ContextInjectionQueueTest {

  @Test
  void sameIdIsInjectedOnce() {
    ContextWindow window = new ContextWindow("dedupe");
    ContextInjection injection = injection("same", ContextInjection.Priority.NORMAL, null);

    window.inject(injection);
    window.inject(injection);

    assertThat(window.pendingInjections()).hasSize(1);
  }

  @Test
  void newerRevisionReplacesOlderSameKindPending() {
    ContextWindow window = new ContextWindow("revision");
    window.inject(injection("old", ContextInjection.Priority.NORMAL, 1L));
    window.inject(injection("new", ContextInjection.Priority.NORMAL, 2L));

    assertThat(window.pendingInjections()).extracting(ContextInjection::id).containsExactly("new");
  }

  @Test
  void olderRevisionDoesNotReplaceNewerPending() {
    ContextWindow window = new ContextWindow("revision-order");
    window.inject(injection("new", ContextInjection.Priority.NORMAL, 2L));
    window.inject(injection("old", ContextInjection.Priority.NORMAL, 1L));

    assertThat(window.pendingInjections()).extracting(ContextInjection::id).containsExactly("new");
  }

  @Test
  void pendingIsOrderedByPriorityThenCreatedAt() {
    ContextWindow window = new ContextWindow("priority");
    window.inject(injection("low", ContextInjection.Priority.LOW, null));
    window.inject(injection("high", ContextInjection.Priority.HIGH, null));
    window.inject(injection("normal", ContextInjection.Priority.NORMAL, null));

    assertThat(window.pendingInjections())
        .extracting(ContextInjection::id)
        .containsExactly("high", "normal", "low");
  }

  @Test
  void committedIdIsNotDeliveredAgain() {
    ContextWindow window = new ContextWindow("commit");
    window.inject(injection("once", ContextInjection.Priority.NORMAL, null));
    window.commitInjections(List.of("once"));

    assertThat(window.pendingInjections()).isEmpty();
    window.inject(injection("once", ContextInjection.Priority.NORMAL, null));
    assertThat(window.pendingInjections()).isEmpty();
  }

  @Test
  void resetClearsPendingAndDeliveredIds() {
    ContextWindow window = new ContextWindow("reset");
    window.inject(injection("before-reset", ContextInjection.Priority.NORMAL, null));
    window.commitInjections(List.of("before-reset"));
    window.inject(injection("pending", ContextInjection.Priority.NORMAL, null));

    window.reset("new-conversation");

    assertThat(window.pendingInjections()).isEmpty();
    // 已投递记录也随 reset 清掉：同 id 在新会话里可以重新投递
    window.inject(injection("before-reset", ContextInjection.Priority.NORMAL, null));
    assertThat(window.pendingInjections()).hasSize(1);
  }

  @Test
  void textLengthIsCapped() {
    String tooLong = "x".repeat(ContextInjection.MAX_TEXT_CHARS + 1);
    assertThatThrownBy(
            () ->
                new ContextInjection(
                    "id",
                    ContextInjectionKinds.REMINDER,
                    ContextInjection.Priority.NORMAL,
                    "summary",
                    tooLong,
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过上限");
  }

  private static ContextInjection injection(
      String id, ContextInjection.Priority priority, Long revision) {
    Map<String, Object> data = revision == null ? Map.of() : Map.of("revision", revision);
    return new ContextInjection(
        id, ContextInjectionKinds.REMINDER, priority, "summary-" + id, "text-" + id, data, null);
  }
}
