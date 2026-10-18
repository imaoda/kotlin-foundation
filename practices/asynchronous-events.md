# 异步广播与订阅生命周期

适用场景：多个协程观察者需要收到同一份业务通知，允许处理在发布调用返回后继续进行。实现直接使用 `kotlinx.coroutines` 的 `SharedFlow`，不增加 core API 或通用包装。

阅读 [AsyncNotifications.kt](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncNotifications.kt) 中的 `AsyncDocument`。它提交文本修订后，用私有 `MutableSharedFlow` 发布 `AsyncRevisionCommitted`，对外暴露只读 `SharedFlow`。这是供应用参考的 internal 逻辑对象，不属于本库的公共组件。

## 明确投递与缓冲承诺

示例固定使用 `replay = 0`、`extraBufferCapacity = 1`、`BufferOverflow.SUSPEND`，通过挂起的 `emit` 发布。

| 情况 | 示例行为 |
| --- | --- |
| 两个收集协程已经订阅 | 两者分别收到通知；它们不会分摊任务 |
| 一个观察者处理较慢 | 多出的一个缓冲位置容纳后续通知；缓冲耗尽后，发布协程挂起等待空间 |
| 没有观察者 | `replay = 0` 使通知直接丢弃，额外缓冲容量不会为未来订阅者保留通知 |
| 新观察者加入 | 仅收到加入后的通知，不补发历史修订 |
| `emit` 已返回 | 不保证观察者的业务处理已经完成 |

这些配置用于表达可中断的在线通知。需要每条消息都得到处理确认、离线补偿或持久化时，应另外设计业务协议；增加缓冲容量不能提供这些保证。缓冲规则来自 [SharedFlow 官方契约](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)。

## 所有权与取消

每个观察者在自己的作用域启动 `collect`，订阅随收集协程结束。测试用 `cancelAndJoin` 同时表达请求取消和等待解除订阅；其他观察者继续工作。`SharedFlow` 不提供 `close()`，也不会自然完成，不应把它登记进同步资源容器。参见 [SharedFlow 收集与取消契约](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)。

示例对象不创建作用域，发布操作由调用方拥有。示例和测试使用同一个串行执行上下文；一个操作在 `emit` 挂起时，观察者仍可以运行。不要把 SharedFlow 自身支持并发调用，误认为示例对象的 `text` 和 `revision` 也已获得并发保护。

## 失败与业务状态

`changeText` 先提交修订，再通知观察者。`emit` 挂起等待期间的取消异常会继续传播，已经提交的文本不会自动撤销；已投递给部分观察者的通知同样不能撤回。示例没有捕获后伪装成成功，也不宣称通知与写入构成事务。

观察者的处理失败由其协程所有者的故障边界处理；它不是同步监听器异常策略的替代名称。需要观察者参与命令成败时，应改用明确的普通或挂起方法调用。

## 验证位置

[AsyncNotificationsTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncNotificationsTest.kt) 覆盖双观察者广播、取消一个订阅、无人订阅时丢弃，以及慢处理器占满缓冲后使第三次发布挂起。测试使用调度器推进与显式门闩控制时序，不依赖真实延时，也不复制 SharedFlow 的实现。

本地运行 `./scripts/gradle :examples:jvmTest`；各平台实际验证结果见 [兼容性记录](../providers/compatibility.md)。
