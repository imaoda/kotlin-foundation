# 同步事件与动态插件

适用场景：多个逻辑对象需要在当前调用栈观察已经发生的事实，订阅者的生命周期独立于发布者。

阅读 [DocumentWorkspace.kt](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt) 中的 `changeText`、`replacePlugin` 和 `DocumentPlugin.attach`。事件的详细分发、重入与取消规则以 [EventEmitter](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/EventEmitter.kt) 的 KDoc 为准。

## 发布权限与订阅所有权

文档持有可写的 `EventEmitter`，对外只暴露 `EventSource`。每次 `subscribe` 返回一个 `AutoCloseable` 句柄；插件把句柄登记到自己的 `ManagedComponent`。关闭插件只取消其订阅，文档和其他监听器继续存在。

`replacePlugin` 使用 `CloseableSlot`。新插件在保护初始化中取得订阅，随后交给槽；槽先安装新值，再关闭旧值。旧插件关闭失败时，新插件仍由槽拥有，不会因 setter 抛异常丢失清理责任。替换也不是可回滚事务：调用方收到清理异常时，不应假设旧插件仍是当前插件。

独立关闭返回的插件会立即取消它的监听。槽仍可持有这个已关闭实例，之后替换或文档关闭时的再次 close 是幂等操作。

## 事实通知不会撤销写入

`changeText` 先写连接、再发布新的不可变状态，最后发出 `DocumentChanged`。示例显式选择 `ReportAndContinue`，因此普通插件异常会报告一次，后续监听器继续收到通知，已完成的写入保留。

核心没有默认监听失败策略。`FailFast` 适合要求立即传播通知错误的场景；它同样不撤销之前的修改。需要校验或参与命令成败的工作应通过方法参数、返回值或明确接口组织，不能用事件监听器实现隐藏的提交检查。

事件同步执行，慢监听器会阻塞发布者。监听器修改同一业务对象时，需要由业务自己定义重入契约；核心支持分发层面的重入，不意味着任意业务逻辑都可以重入。

## 状态直接复用 StateFlow

`DocumentSession.state` 是只读 `StateFlow<DocumentState>`，保存当前文本；事件表达每次已提交的修改。相同值合并、最新状态读取与事件逐次通知是不同语义，示例不为二者创建统一包装。

验证入口：[DocumentWorkspaceTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) 的插件替换与独立关闭测试，以及观察者失败后状态仍已提交、后续观察者仍收到通知的测试。
