package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ApprovalEventTypes;
import org.junit.jupiter.api.Test;

/**
 * 审批事件类型的<b>跨层别名</b>用例：AgentLib 的 {@link ApprovalEventTypes} 与 Brain 的 {@link EventTypes}
 * 各有一组常量（两层互不依赖），字符串值必须逐字一致——两套别名一旦分叉，事件写入与消费就会对不上， 而那种错在运行期表现为"订阅方收不到"，很难查。
 *
 * <p>同时钉住字面值：两处一起改（"改别名"）也不能悄悄改掉线上事件名。
 */
class EventTypesApprovalValueTest {

  @Test
  void approvalEventNamesAreIdenticalAcrossLayersAndPinned() {
    assertThat(EventTypes.APPROVAL_REQUESTED).isEqualTo(ApprovalEventTypes.REQUESTED);
    assertThat(EventTypes.APPROVAL_DECIDED).isEqualTo(ApprovalEventTypes.DECIDED);

    assertThat(EventTypes.APPROVAL_REQUESTED).isEqualTo("approval.requested");
    assertThat(EventTypes.APPROVAL_DECIDED).isEqualTo("approval.decided");
  }
}
