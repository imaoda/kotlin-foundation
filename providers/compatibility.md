# 兼容性与验证证据

本页记录本地实际验证结果，不代表持续集成或完整支持矩阵。复现入口见 [开发说明](../guide/development.md)。

日期：2026-10-19。配置：Kotlin 2.4.21、Gradle 9.6.0、AGP 9.4.1、JDK 25、coroutines 1.9.0、Android compileSdk 37 / minSdk 28。当前本机 Xcode 27.1 / iOS 27.1。

## 验证结果

关联操作立即完成路径的取消修复后重新执行了 `:check`、`:examples:iosSimulatorArm64Test` 和 `:examples:compileKotlinIosArm64`，全部通过。示例在 JVM 与 iOS 模拟器均重新运行 23 项测试；根 `:check` 的未变更模块复用 Gradle 的 UP-TO-DATE 结果。新增的两项回归在未修复实现上均失败，修复后在两个平台均通过。下表其余范围保留同日基础验证记录，本次未重跑 Android 设备、core Native/Android 宿主、lint 或发布物消费测试。

| 检查范围 | 结果 | 证据与复现 |
| --- | --- | --- |
| core commonTest / JVM | 47 通过，无失败或跳过 | `:core:jvmTest` |
| core commonTest / iOS 27.1 arm64 模拟器 | 47 通过，无失败或跳过 | `-PiosDevice=UDID :core:iosSimulatorArm64Test` |
| core commonTest / Android 宿主 JVM | 47 通过，无失败或跳过 | `:core:testAndroidHostTest`；不是设备测试 |
| 组合示例 / JVM、iOS 模拟器 | 每个平台 23 通过，无失败或跳过 | `:examples:jvmTest` / `:examples:iosSimulatorArm64Test` |
| iOS 真机架构 | core 与 examples 编译通过 | `:core:compileKotlinIosArm64 :examples:compileKotlinIosArm64`；未运行真机 |
| Android 消费端宿主测试 / 构建 | 1 通过，AAR 构建通过 | `:android-consumer:testDebugUnitTest :android-consumer:assembleDebug` |
| Android 17 / API 37 模拟器 | 3 设备测试通过，无失败或跳过 | `:android-consumer:connectedDebugAndroidTest`；生命周期、suppressed 异常、派发期间取消 |
| Android lint | 通过（同日基础验证记录） | `:android-consumer:lintDebug`；当前根 `:check` 不包含此任务 |
| ABI 基线 | 通过 | `:core:checkKotlinAbi`；JVM、Android、KLib 基线在 `libraries/core/api` |
| 文档与声明依赖边界 | 通过 | `checkDocs checkBoundaries` |
| 本地 Maven 发布 | 全部配置的 publication 写入本地仓库成功 | `:core:publishAllPublicationsToVerificationRepository` |
| 独立发布物消费 / JVM、iOS 模拟器 | 每个平台 1 通过 | `-p verification/published-consumer jvmTest iosSimulatorArm64Test`；无源码工程依赖 |

测试 XML 位于各模块忽略的 `build/test-results`；Android 设备 XML 位于 `verification/android-consumer/build/outputs/androidTest-results/connected/debug`。它们是本机执行证据，复现后生成，不将构建产物提交为源码。

`:core:dependencies --configuration jvmRuntimeClasspath` 实际解析为 `kotlin-stdlib:2.4.21` 及其传递依赖 `org.jetbrains:annotations:13.0`。core 没有直接引入协程、UI 或其他业务运行时；声明检查不替代所有平台的传递依赖审计。

示例验证了对象所有权与初始化回滚、动态订阅、SharedFlow 的无人订阅/背压行为、关联任务与监督隔离、调用方取消，以及异步任务结束后释放资源。关联操作回归覆盖任一子任务局部超时、取消期间普通与 fatal 清理失败优先、业务失败与取消竞争，以及跨另一层 async/await 后仍保留取消信息。立即完成路径新增两项回归：已取消调用方不启动工作，以及工作同步完成期间取消调用方仍传播取消；同时验证原取消原因、会话隔离和最终资源释放。测试不证明任意业务方法的重入安全或任意调度器下的并发安全。

## 边界

- 目前没有对旧 Kotlin 编译器进行兼容矩阵测试。
- iOS 真机架构编译不代表真机运行；Swift 消费暂未验证。
- Android 实测设备为 API 37，声明的 minSdk 28 未在 API 28 设备运行；Android 发布物的独立 Maven 消费尚未验证，当前 Android 消费端使用源码 project dependency。
- 未承诺 JS、Wasm、Windows Native、Linux Native 的支持。
- AGP/Xcode 超出当前 [Kotlin 官方兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) 对应范围，保留工具链并报告实测。
- 社区 provider 未引入依赖、未测版本，不计入本库的已验证能力。
- 工具链保留不降级。Android 测试工具的 protobuf 在 JDK 25 上输出了 `sun.misc.Unsafe` 弃用警告；本次测试通过，不代表后续 JDK 仍兼容。
