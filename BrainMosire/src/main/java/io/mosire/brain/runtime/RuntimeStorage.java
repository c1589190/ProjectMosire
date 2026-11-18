package io.mosire.brain.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.store.SqliteConversationStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Brain 运行时的 SQLite 连接和事件总线。数据目录内的会话指针由单个运行时写入，资源由打开它们的宿主持有。 */
public final class RuntimeStorage implements AutoCloseable {

  private final EventStore events;
  private final SqliteConversationStore conversations;
  private final EventBus bus;
  private final AtomicBoolean closed = new AtomicBoolean();

  private RuntimeStorage(EventStore events, SqliteConversationStore conversations, EventBus bus) {
    this.events = events;
    this.conversations = conversations;
    this.bus = bus;
  }

  /**
   * 打开指定目录中的 {@code events.db}，事件库和可选会话库使用各自的连接。装配途中失败会释放已取得的资源。
   *
   * @param dataDirectory 运行时数据目录
   * @param persistConversations 开启会话落库和重启续接
   * @return 可借给 {@link AgentRuntime.Builder} 使用的资源，由打开方关闭
   */
  public static RuntimeStorage open(Path dataDirectory, boolean persistConversations) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    try {
      Files.createDirectories(dataDirectory);
    } catch (IOException failure) {
      throw new IllegalStateException("无法创建数据目录: " + dataDirectory, failure);
    }
    Path database = dataDirectory.resolve("events.db");
    EventStore events = SqliteEventStore.open(database);
    SqliteConversationStore conversations = null;
    EventBus bus = null;
    try {
      conversations = persistConversations ? SqliteConversationStore.open(database) : null;
      bus = new EventBus();
      return new RuntimeStorage(events, conversations, bus);
    } catch (RuntimeException | Error failure) {
      try (events;
          SqliteConversationStore openConversations = conversations;
          EventBus openBus = bus) {
        throw failure;
      }
    }
  }

  /** 提供给宿主的共享事件库，随本对象一起关闭。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "宿主和运行时需要操作同一个事件库")
  public EventStore eventStore() {
    return events;
  }

  /** 提供给宿主的共享总线，订阅者可在关闭时完成队列中的工作。 */
  public EventBus eventBus() {
    return bus;
  }

  ConversationStore conversationStore() {
    requireOpen();
    return conversations;
  }

  /** 排空总线后释放两个 SQLite 连接。已经关闭时重复调用安全。某项关闭失败后仍会尝试其余资源。 */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      drainAndClose(events, conversations, bus);
    }
  }

  private static void drainAndClose(
      EventStore events, SqliteConversationStore conversations, EventBus bus) {
    try (events;
        conversations;
        bus) {
      // 总线逆序先关闭，让访问数据库的订阅者先完成。
    }
  }

  private void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("运行时存储已关闭");
    }
  }
}
