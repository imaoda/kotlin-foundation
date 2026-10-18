# 可执行实践

这些实践围绕具体对象生命周期编写。代码和测试是事实依据，文档说明前提、理由和适用边界；第三方能力直接使用，不为了统一名称再包装一次。

| 问题 | 实践 | 代码与验证 |
| --- | --- | --- |
| 多层逻辑对象如何拥有资源，初始化中途失败怎么办 | [初始化与所有权](initialization-and-ownership.md) | [DocumentWorkspace](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)、[测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) |
| 插件如何订阅、替换、独立结束，通知失败影响什么 | [同步事件与插件](synchronous-events.md) | 同一文档示例中的 `DocumentPlugin` 与 `replacePlugin` |
| 多个协程如何接收同一通知，慢订阅与无人订阅分别会怎样 | [异步广播](asynchronous-events.md) | [AsyncNotifications](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncNotifications.kt)、[测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncNotificationsTest.kt) |
| 如何先结束异步工作，再关闭它仍在使用的资源 | [异步关闭](asynchronous-shutdown.md) | [AsyncWorkSession](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt)、[测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt) |

本地执行 `./scripts/gradle :examples:jvmTest`。这些测试位于 `commonTest`，也用于已配置的 Native 测试目标；某个平台是否实际执行通过，以仓库验证记录为准。

进入实践前，先确认 [逻辑层架构](../architecture/index.md) 和 [失败边界](../architecture/failure-boundaries.md) 是否适合当前对象。尤其不要把“有一个对象”自动等同于“需要组件基类、协程作用域和错误处理器”。
