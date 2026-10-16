package io.mosire.agentlib.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.LlmMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 判据 7/8/9 + 设计 §3.1 的选定语义：{@link ContextWindow} 的改写口、重置、未落库尾部与"快照不可变"。
 *
 * <p>判据出处（{@code 设计-上下文窗口下沉.md} §五）：判据 7 = {@code replaceRange} 三种越界必抛且窗口未被改动、空 replacement =
 * 删除、合法替换生效； 判据 8 = {@code reset(newId)} 后 {@code messages()} / {@code unsavedTail()} 皆空且 id 换新；判据
 * 9 = append 3 → 尾部 3 → {@code markSaved()} → 0 → append 1 → 1，且两个读口返回的是不可变快照。§3.1
 * 的选定语义：构造带初值、{@code replaceAll} 之后尾部为空（"初始内容/整体替换视为已处置"）。
 *
 * <p>为什么负向用例要连"窗口未被改动"一起断言：只证明"抛了"是不够的——若实现先动窗口再校验，调用方拿到的是一条半截历史，而这正是本类"不做追加了一半再抛"的承诺要拦住的形状。
 */
class ContextWindowTest {

  @Test
  void replaceRangeRejectsTheThreeIllegalIndexShapesAndLeavesTheWindowUntouched() {
    ContextWindow window =
        new ContextWindow(
            "c", List.of(LlmMessage.user("一"), LlmMessage.user("二"), LlmMessage.user("三")));
    List<LlmMessage> before = window.messages();
    List<LlmMessage> tailBefore = window.unsavedTail();

    // 档一：from < 0
    assertThatThrownBy(() -> window.replaceRange(-1, 1, List.of(LlmMessage.user("x"))))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("replaceRange 下标非法")
        .hasMessageContaining("from=-1")
        .hasMessageContaining("size=3");
    // 档二：to > size()
    assertThatThrownBy(() -> window.replaceRange(0, 4, List.of(LlmMessage.user("x"))))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("to=4");
    // 档三：from > to
    assertThatThrownBy(() -> window.replaceRange(2, 1, List.of(LlmMessage.user("x"))))
        .isInstanceOf(IndexOutOfBoundsException.class)
        .hasMessageContaining("from=2")
        .hasMessageContaining("to=1");

    assertThat(window.size()).as("失败的三次调用都不许改动窗口").isEqualTo(3);
    assertThat(window.messages()).containsExactlyElementsOf(before);
    assertThat(window.unsavedTail()).containsExactlyElementsOf(tailBefore);
  }

  @Test
  void replaceRangeWithEmptyReplacementDeletesTheRange() {
    ContextWindow window =
        new ContextWindow(
            "c", List.of(LlmMessage.user("一"), LlmMessage.user("二"), LlmMessage.user("三")));

    window.replaceRange(1, 2, List.of());

    assertThat(window.messages()).containsExactly(LlmMessage.user("一"), LlmMessage.user("三"));
    assertThat(window.size()).isEqualTo(2);
  }

  @Test
  void replaceRangeAppliesLegalReplacementInPlace() {
    ContextWindow window =
        new ContextWindow(
            "c", List.of(LlmMessage.user("一"), LlmMessage.user("二"), LlmMessage.user("三")));

    window.replaceRange(
        0, 2, List.of(LlmMessage.user("甲"), LlmMessage.user("乙"), LlmMessage.user("丙")));
    assertThat(window.messages())
        .containsExactly(
            LlmMessage.user("甲"), LlmMessage.user("乙"), LlmMessage.user("丙"), LlmMessage.user("三"));

    // 空区间 [size, size) 是合法插入点（同一个口也能表达"就地插入"）
    window.replaceRange(window.size(), window.size(), List.of(LlmMessage.user("尾")));
    assertThat(window.messages())
        .containsExactly(
            LlmMessage.user("甲"),
            LlmMessage.user("乙"),
            LlmMessage.user("丙"),
            LlmMessage.user("三"),
            LlmMessage.user("尾"));
  }

  @Test
  void resetClearsMessagesAndTailAndAdoptsTheNewId() {
    ContextWindow window = new ContextWindow("旧", List.of(LlmMessage.user("一")));
    window.append(LlmMessage.user("二"));

    window.reset("新");

    assertThat(window.id()).isEqualTo("新");
    assertThat(window.messages()).isEmpty();
    assertThat(window.unsavedTail()).isEmpty();
    assertThat(window.size()).isZero();
  }

  @Test
  void unsavedTailTracksAppendsUntilMarkSaved() {
    ContextWindow window = new ContextWindow("c");

    window.append(LlmMessage.user("1"));
    window.append(LlmMessage.user("2"));
    window.append(LlmMessage.user("3"));
    assertThat(window.unsavedTail())
        .containsExactly(LlmMessage.user("1"), LlmMessage.user("2"), LlmMessage.user("3"));

    window.markSaved();
    assertThat(window.unsavedTail()).isEmpty();

    window.append(LlmMessage.user("4"));
    assertThat(window.unsavedTail()).containsExactly(LlmMessage.user("4"));
    assertThat(window.size()).isEqualTo(4);
  }

  @Test
  void messagesAndUnsavedTailAreImmutableSnapshotsAndDoNotFollowLaterAppends() {
    ContextWindow window = new ContextWindow("c");
    window.append(LlmMessage.user("1"));

    List<LlmMessage> messages = window.messages();
    List<LlmMessage> tail = window.unsavedTail();

    assertThatThrownBy(() -> messages.add(LlmMessage.user("注入")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> messages.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> tail.add(LlmMessage.user("注入")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> tail.remove(0)).isInstanceOf(UnsupportedOperationException.class);

    // 改注入的 List 失败之外，快照本身也不被后续 append 改写
    window.append(LlmMessage.user("2"));
    assertThat(messages).as("messages() 是不可变快照，不是活视图").hasSize(1);
    assertThat(tail).hasSize(1);
    assertThat(window.messages()).containsExactly(LlmMessage.user("1"), LlmMessage.user("2"));
    assertThat(window.unsavedTail()).containsExactly(LlmMessage.user("1"), LlmMessage.user("2"));
  }

  @Test
  void constructorInitialContentAndReplaceAllLeaveNoUnsavedTail() {
    ContextWindow window =
        new ContextWindow("c", List.of(LlmMessage.user("历史一"), LlmMessage.user("历史二")));
    assertThat(window.unsavedTail()).as("构造带初值 = 已处置").isEmpty();

    window.appendAll(List.of(LlmMessage.user("新一"), LlmMessage.user("新二")));
    assertThat(window.unsavedTail()).containsExactly(LlmMessage.user("新一"), LlmMessage.user("新二"));

    window.replaceAll(
        List.of(LlmMessage.user("整窗一"), LlmMessage.user("整窗二"), LlmMessage.user("整窗三")));
    assertThat(window.messages())
        .containsExactly(LlmMessage.user("整窗一"), LlmMessage.user("整窗二"), LlmMessage.user("整窗三"));
    assertThat(window.unsavedTail()).as("replaceAll = 已处置").isEmpty();
  }

  /** §3.1 的尾部不变量：改写点落在已落库前缀上时尾部起点只会前移（变大），绝不缩小——窗口不替宿主宣布"这段我存过了"。 */
  @Test
  void replaceRangeTouchingThePersistedPrefixOnlyGrowsTheUnsavedTail() {
    ContextWindow window =
        new ContextWindow("c", List.of(LlmMessage.user("已落库一"), LlmMessage.user("已落库二")));
    window.append(LlmMessage.user("未落库"));
    assertThat(window.unsavedTail()).containsExactly(LlmMessage.user("未落库"));

    window.replaceRange(0, 1, List.of(LlmMessage.user("改写")));
    assertThat(window.unsavedTail())
        .as("改到了已落库前缀 ⇒ 从那一点起的旧内容也不再能证明与存储一致")
        .containsExactly(LlmMessage.user("改写"), LlmMessage.user("已落库二"), LlmMessage.user("未落库"));

    window.markSaved();
    assertThat(window.unsavedTail()).isEmpty();

    window.replaceRange(1, 2, List.of(LlmMessage.user("尾部改写")));
    assertThat(window.unsavedTail())
        .containsExactly(LlmMessage.user("尾部改写"), LlmMessage.user("未落库"));
  }
}
