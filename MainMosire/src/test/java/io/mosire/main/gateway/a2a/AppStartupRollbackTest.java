package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppStartupRollbackTest {

  private static final String LOOPBACK = "127.0.0.1";

  @TempDir Path directory;

  @Test
  void a2aBindFailureReleasesAdminAndAllowsRestartOnBothPorts() throws Exception {
    BootConfig config;
    try (ServerSocket occupiedA2a = bind(0)) {
      int adminPort;
      try (ServerSocket adminReservation = bind(0)) {
        adminPort = adminReservation.getLocalPort();
      }
      config =
          new BootConfig(
              adminPort, directory, false, "", null, false, LOOPBACK, occupiedA2a.getLocalPort());
      assertThatThrownBy(
              () -> {
                try (App app = App.start(config)) {
                  throw new AssertionError("A2A 端口占用时启动成功: " + app.a2aPort());
                }
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("启动 A2A HTTP server 失败")
          .hasCauseInstanceOf(BindException.class);
      try (ServerSocket rebound = bind(adminPort)) {
        assertThat(rebound.getLocalPort()).isEqualTo(adminPort);
      }
    }
    try (App restarted = App.start(config)) {
      assertThat(restarted.boundPort()).isEqualTo(config.port());
      assertThat(restarted.a2aPort()).isEqualTo(config.a2aPort());
    }
  }

  @Test
  void cardFactoryFailureReleasesItsAnnouncedPortAndPreservesTheError() throws Exception {
    AtomicInteger announcedPort = new AtomicInteger();
    IllegalArgumentException failure = new IllegalArgumentException("卡片夹具失败");
    try (A2aTaskService tasks =
        new A2aTaskService(new MemoryA2aTaskStore(), (id, message, editor) -> {})) {
      var handler = new A2aJsonRpcHandler(A2aTestSupport.card(A2aTestSupport.baseUrl(0)), tasks);
      assertThatThrownBy(
              () -> {
                try (A2aHttpServer server =
                    A2aHttpServer.start(
                        new InetSocketAddress(LOOPBACK, 0),
                        port -> {
                          announcedPort.set(port);
                          throw failure;
                        },
                        handler)) {
                  throw new AssertionError("卡片生成失败时仍启动: " + server.port());
                }
              })
          .isSameAs(failure);
      assertThat(announcedPort.get()).isPositive();
      try (ServerSocket rebound = bind(announcedPort.get())) {
        assertThat(rebound.getLocalPort()).isEqualTo(announcedPort.get());
      }
    }
  }

  private static ServerSocket bind(int port) throws Exception {
    return new ServerSocket(port, 0, java.net.InetAddress.getByName(LOOPBACK));
  }
}
