package io.mosire.main;

import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;

/**
 * <b>测试缝</b>——只存在于测试源码树（{@code src/test/java}，永不出现在任何产物里；名称里的 {@code TestSeam} 即此意）：把包私有的生产装配入口
 * {@link Main#startApp} 借给隔壁包（{@code io.mosire.main.agent}）的用例。
 *
 * <p><b>存在的唯一理由</b>：{@code MainRunSubagentConfigDirGateTest} 必须从生产装配入口驱动、又必须复用 {@code
 * io.mosire.main.agent} 包里包私有的回环假端点夹具 {@code
 * OpenAiStubServer}——代码落在哪个包就受哪个包的可见性约束，两个约束的交集只有"同包薄转发"。 本类因此同时是 {@code public}（跨包可见）且不引入任何逻辑。
 *
 * <p><b>为什么不给生产类提权</b>：{@code startApp} 与 {@link Main#selectLlm}/{@link Main#realLlm}
 * 一样是生产装配的内部缝，把它开成 {@code public} 等于让"测试需要的可见性"改写生产 API 面（且会随构件发布出去）。测试缝只活在测试期，生产面保持原样。
 */
public final class MainStartAppTestSeam {

  private MainStartAppTestSeam() {}

  /** 跨包转发到生产装配入口 {@link Main#startApp}（本方法无逻辑：委托 + 返回）。 */
  public static App startApp(BootConfig config, boolean fake, String fakeScript) {
    return Main.startApp(config, fake, fakeScript);
  }
}
