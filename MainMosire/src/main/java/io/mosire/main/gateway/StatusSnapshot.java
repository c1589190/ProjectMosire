package io.mosire.main.gateway;

/** 健康/状态快照（AdminREST 的 /health 与 /api/status 数据源；App 自产）。 */
public record StatusSnapshot(
    String status,
    String artifact,
    String version,
    long pid,
    long uptimeMillis,
    String agentId,
    long eventCount) {

  public static StatusSnapshot healthy(
      String artifact, String version, String agentId, long uptimeMillis, long eventCount) {
    return new StatusSnapshot(
        "ok", artifact, version, ProcessHandle.current().pid(), uptimeMillis, agentId, eventCount);
  }
}
