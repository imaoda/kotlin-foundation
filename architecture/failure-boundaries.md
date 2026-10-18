# 失败边界

组件共同遵循失败语义，业务负责具体错误类型。核心不提供通用错误码、自定义 `Result`、全局错误总线或公开 `FailureReporter` 框架。

## 调用、取消与后台故障

| 情况 | 规则 |
| --- | --- |
| 已知业务失败 | 由业务类型表达；需要 typed error 组合时选择成熟 provider |
| 外部接口抛出的已知异常 | 在适当边界捕获并映射，保留必要原因 |
| 普通或 suspend 调用中的意外异常 | 沿调用链传播，由调用者观察；不逐层重复上报 |
| 协程取消 | 保持取消传播，不自动重试，也不转为普通业务失败 |
| 独立后台工作没有调用者接收的异常 | 在明确监督边界报告一次，并由业务决定后续状态 |
| 同步资源清理失败 | 继续尝试剩余清理，保留原始异常和 suppressed 异常 |

[`runCatching`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/run-catching.html) 会捕获任何 `Throwable`，包括取消；不能直接用它实现“永不失败”的业务入口。基础包不把 fatal errors 转成可恢复结果。清理容器会暂时捕获 `Throwable` 以完成剩余同步清理，再重新抛出原始首个异常；这与吞掉异常或承诺恢复不同。

[`CoroutineExceptionHandler`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-exception-handler/) 是无正常传播路径的异常出口，不是可以恢复已失败协程的 try/catch。在任意子协程上放置它，不能建立新的隔离边界。

[AsyncWorkSession](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt) 明确区分两种工作：独立后台任务使用 `SupervisorJob` 和应用传入的报告回调；关联任务在同一次 `coroutineScope` 中共同成功或失败，经 `await` 交还调用者，不再作为无人处理的故障上报。示例不保证每个项目的工作都适合监督隔离。

## 同步事件必须选择失败策略

[EventEmitter](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/EventEmitter.kt) 构造时必须提供策略，没有隐藏默认值：

- `FailFast`：首个监听异常直接传播，后续监听器不再接收当前事件。适合要求调用方立即发现错误、接受本轮通知不完整的场景。
- `ReportAndContinue`：普通 `Exception` 交给指定回调后继续通知。`CancellationException`、`Error` 和其他非 `Exception` 的 `Throwable` 直接传播。报告回调自己失败时，报告异常成为主异常，监听异常作为 suppressed 保留，并停止当前派发。

事件表示已经发生的事实。这两种策略都不撤销发事件之前的写入，也不构成事务。`FailFast` 允许调用者观察通知失败，不意味着业务修改没有发生；调用方不能因此盲目重试原命令。[文档示例](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt) 为独立插件显式选择 `ReportAndContinue`，这个选择不是库的默认策略。

## 清理与初始化失败

`CloseableGroup` 按逆序尝试所有登记项，抛出首个清理异常，将其余不同异常附加为 suppressed。一次关闭进入终态后，即使清理失败，再次关闭也不会重试有副作用的资源。

初始化已发生异常时，工厂必须保留该异常为主因，再附加回滚关闭时的异常。`DocumentSession.open` 和 `DocumentPlugin.attach` 展示这个边界。它们捕获 `Throwable` 只是为了释放已取得资源，然后原样重抛；取消或 fatal failure 不被变成成功结果。

“报告一次”是边界设计要求，不是全局按异常实例去重。没有统一的全局报告器；同一个 `Throwable` 被业务重新用于不同操作，也可能代表不同故障。跨组件调用优先传播，最终没有调用者观察时才报告。

可执行证据见 [DocumentWorkspaceTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) 和 [AsyncWorkSessionTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt)。
