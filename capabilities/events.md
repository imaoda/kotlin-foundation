# event.sync：同步对象通知

ID: event.sync
默认提供方: core `EventEmitter<T>` 与只允许订阅的 `EventSource<T>`。
状态: v0.1 预发布公共组件；平台实测以 [兼容性记录](../providers/compatibility.md) 为准。
前提: 需要即时注册、在发布者调用栈内执行的对象通知。
切换条件: 跨协程上下文广播使用 [SharedFlow](async-events.md)；参与命令成功或失败的工作使用普通或 suspend 方法调用。
依赖与版本: core 只依赖 stdlib；[构建版本](../gradle/libs.versions.toml)。
契约: 串行访问；快照与深度优先重入；显式失败策略；监听方拥有解绑句柄
验证: EventEmitter契约测试和插件订阅示例，见下方链接
替代方案: SharedFlow；使用方已有的响应式生态

发布器私有，对外只暴露订阅视图。每次订阅返回独立、幂等的解绑句柄，由监听方拥有；发布器关闭不销毁监听者业务对象。事件只表示已发生的事实，监听失败不会回滚之前的业务写入。

访问要求外部串行执行。每次发布按订阅顺序取得快照：尚未轮到的已解除订阅被跳过；新增订阅从下一次发布可见，包含嵌套发布；重入发布深度优先。关闭发布器会解除订阅并拒绝新的订阅与发布，不中断已经开始的回调。

创建发布器必须显式选择 `FailFast` 或 `ReportAndContinue`，没有默认策略。后者只隔离普通 `Exception`；取消、`Error` 和其他非 `Exception` 直接传播。报告器自身失败会终止通知并保留监听异常；精确的同发布器嵌套规则见策略 KDoc。同步慢监听会阻塞发布者。

**实现与证据**：

- [EventEmitter](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/EventEmitter.kt)、[EventSource](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/EventSource.kt)、[ListenerFailurePolicy](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/ListenerFailurePolicy.kt)。
- [EventEmitterTest](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/events/EventEmitterTest.kt)：顺序、订阅增删、重入、终态、取消及报告器失败。
- [同步事件与插件实践](../practices/synchronous-events.md)、[DocumentWorkspace 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)、[工作区测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt)。

失败策略选择见 [失败边界](../architecture/failure-boundaries.md)，复用理由见 [实现决定](../decisions/0001-reuse-and-scope.md)。已有响应式生态优先使用其既有能力，而不是迁移到本库以统一名称。
