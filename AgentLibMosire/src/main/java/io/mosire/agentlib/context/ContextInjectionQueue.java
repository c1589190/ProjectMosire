package io.mosire.agentlib.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 待投递的 {@link ContextInjection} 队列（线程安全）。
 *
 * <p><b>为什么线程安全</b>：注入常由注册表变更、MCP 回调、配置热更新触发，可能来自与 Agent 回合不同的线程；而 {@link ContextWindow}
 * 的消息方法仍保持“调用方串行”的旧契约。把并发收在这个小队列里，别让整个窗口变成并发容器。
 *
 * <p><b>语义</b>：
 *
 * <ul>
 *   <li>同 id 只入队一次；已投递的 id 不再入队（幂等）；
 *   <li>同 kind 且新 {@code data.revision} 更大 ⇒ 替换旧 pending（工具面连续变化只提醒最新一次）；
 *   <li>队列上限 {@value #MAX_PENDING}：溢出丢最旧（注入是提醒，不是审计；审计在事件库）；
 *   <li>{@link #pending()} 按 priority 升序（HIGH 先）、createdAt 升序返回快照。
 * </ul>
 */
final class ContextInjectionQueue {

  private static final Logger LOG = LoggerFactory.getLogger(ContextInjectionQueue.class);

  static final int MAX_PENDING = 16;
  private static final int MAX_DELIVERED_IDS = 256;

  private final Object lock = new Object();
  private final Map<String, ContextInjection> pending = new LinkedHashMap<>();
  private final LinkedHashSet<String> deliveredIds = new LinkedHashSet<>();

  /** 入队；同 id 已投递/已在队 ⇒ 空操作。 */
  void inject(ContextInjection injection) {
    Objects.requireNonNull(injection, "injection");
    synchronized (lock) {
      if (deliveredIds.contains(injection.id()) || pending.containsKey(injection.id())) {
        return;
      }
      // 同 kind 的旧 revision 被新 revision 替换；旧 pending 直接丢（它从未投递）
      Long incomingRevision =
          injection.revision().isPresent() ? injection.revision().getAsLong() : null;
      if (incomingRevision != null) {
        Iterator<Map.Entry<String, ContextInjection>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
          ContextInjection existing = iterator.next().getValue();
          if (!existing.kind().equals(injection.kind()) || existing.revision().isEmpty()) {
            continue;
          }
          long existingRevision = existing.revision().getAsLong();
          if (existingRevision >= incomingRevision) {
            return;
          }
          iterator.remove();
        }
      }
      if (pending.size() >= MAX_PENDING) {
        Iterator<String> oldest = pending.keySet().iterator();
        if (oldest.hasNext()) {
          String dropped = oldest.next();
          oldest.remove();
          LOG.warn("上下文注入队列已满（{} 条），丢弃最旧一条: id={}", MAX_PENDING, dropped);
        }
      }
      pending.put(injection.id(), injection);
    }
  }

  /** 待投递快照：priority 高者先，同优先级按 createdAt。 */
  List<ContextInjection> pending() {
    synchronized (lock) {
      List<ContextInjection> snapshot = new ArrayList<>(pending.values());
      snapshot.sort(
          Comparator.comparingInt((ContextInjection injection) -> injection.priority().ordinal())
              .thenComparing(ContextInjection::createdAt));
      return List.copyOf(snapshot);
    }
  }

  /** 投递成功后清出这些 id；未在队中的 id 忽略。 */
  void commit(Collection<String> ids) {
    Objects.requireNonNull(ids, "ids");
    synchronized (lock) {
      for (String id : ids) {
        if (pending.remove(id) != null) {
          deliveredIds.add(id);
        }
      }
      while (deliveredIds.size() > MAX_DELIVERED_IDS) {
        Iterator<String> iterator = deliveredIds.iterator();
        iterator.next();
        iterator.remove();
      }
    }
  }

  /** 清空待投递与已投递记录（新会话）。 */
  void clear() {
    synchronized (lock) {
      pending.clear();
      deliveredIds.clear();
    }
  }

  int size() {
    synchronized (lock) {
      return pending.size();
    }
  }
}
