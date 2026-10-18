# event.async：协程广播

ID: event.async
默认提供方: 原生 `kotlinx.coroutines.SharedFlow`，不增加本库事件包装。
状态: v0.1 组合实践与行为测试；示例为 internal 应用对象，不是公共框架。平台实测见 [兼容性记录](../providers/compatibility.md)。
前提: 多个协程观察者需要分别收到通知，允许其处理在发布调用返回后继续进行。
切换条件: 同调用栈通知使用 [同步 Event](events.md)；任务分摊、单接收者或消费队列另评估 Channel，不能混同广播；需要确认、离线补偿或持久化时另设计业务协议。
依赖与版本: examples 使用 coroutines 1.9.0；core 不依赖协程；[版本目录](../gradle/libs.versions.toml)。
契约: 明确replay/buffer/无人订阅行为；收集者拥有收集Job；emit返回不等于处理完成
验证: 双观察者、取消单个订阅、无人订阅丢弃、缓冲挂起测试，见下方链接
替代方案: 同步Event；需任务分摊时单独评估Channel；持久投递另设计协议

示例私有 `MutableSharedFlow`，对外提供只读 `SharedFlow`，明确使用 `replay = 0`、`extraBufferCapacity = 1`、`BufferOverflow.SUSPEND`。活跃观察者各自接收通知；一个额外缓冲位置用尽后，发布协程挂起等待空间。无人订阅时通知丢弃，额外缓冲不为未来订阅者保留事件。`emit()` 返回不表示所有观察者处理完毕。

观察者拥有自己的收集协程；取消一个收集者不取消其他收集者。SharedFlow 不提供 close，也不自然完成，不登记到同步资源容器。示例对象不创建 scope；调用者拥有发布操作。示例的普通状态字段仍要求外部串行访问，不能从 SharedFlow 自身的并发能力推导整个对象线程安全。

示例先提交文本再发布通知；发布挂起时取消会传播，但不回滚已提交状态，也不能撤回其他观察者已经接收的通知。它不保证通知与写入的事务性或持久投递。

**实践与证据**：

- [AsyncNotifications 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncNotifications.kt)：`AsyncDocument.revisions` 与 `changeText`。
- [AsyncNotificationsTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncNotificationsTest.kt)：双观察者广播、取消单个收集者、无人订阅丢弃，以及慢观察者使后续发布挂起。
- [异步广播实践](../practices/asynchronous-events.md)：配置、订阅所有权、取消与状态写入边界。

提供方契约见 [coroutines 说明](../providers/coroutines.md) 及其官方链接。上面的测试只覆盖示例选择的配置，没有验证所有 replay、buffer 和溢出策略组合。
