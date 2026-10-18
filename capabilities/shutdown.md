# task.shutdown：任务完成与异步关闭

ID: task.shutdown
默认提供方: 原生 `Job` / `CoroutineScope` 与应用自己的显式 `suspend shutdown()`。
状态: v0.1 规范与可执行组合示例；不提供公共异步组件基类。平台实测见 [兼容性记录](../providers/compatibility.md)。
前提: 长期对象拥有异步工作，工作退出或 finally 完成前仍会使用同步资源。
切换条件: 块级工作优先 `coroutineScope`；需要 acquire/use/release 和挂起 finalizer 组合时条件评估 Arrow Resource。
依赖与版本: examples 使用 coroutines 1.9.0；core 不依赖协程；[版本目录](../gradle/libs.versions.toml)。
契约: 停止接单、取消并等待、再释放；不取消借用scope；中断关闭仍需外部所有者完成
验证: AsyncWorkSession取消、完成屏障、关闭顺序与重试测试，见下方链接
替代方案: 块级coroutineScope；按需评估Arrow Resource；应用自己的关闭状态机

`Job.cancel()` 是取消请求，`join()` / `cancelAndJoin()` 才提供等待完成的步骤；对象的 `isClosed` 也不是异步任务完成信号。关闭顺序是停止接单、取消并等待拥有的工作、再释放工作依赖的资源。借用的父 scope 不是自己的资源，不得取消它。

示例创建自己的子 `SupervisorJob`，不让所有业务对象自动获得一个 scope。所有入口与任务使用同一串行上下文。`shutdown()` 由会话任务树外的所有者执行；自己的子任务不能等待包含自身的任务树结束。

`shutdown()` 的等待可被调用者取消，此时保持 `Stopping`，资源继续打开，必须由另一个活跃外部所有者重试。应用也可显式选择在 `NonCancellable` finally 中完成关闭，代价是工作不合作或清理不结束时可能一直等待。操作级 `runRelated` 则在取消后等待本次工作结束再返回；它与长期会话的可重试关闭是不同契约。

**实践与证据**：

- [AsyncWorkSession 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt)：`SupervisorJob`、`runRelated`、`launchIndependent`、`shutdown`。
- [AsyncWorkSessionTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt)：延迟 finally、调用者取消与重试、重叠关闭、关联操作取消后的完成屏障。
- [异步关闭实践](../practices/asynchronous-shutdown.md)：完成责任、关闭异常、取消传播和等待代价。

原生能力见 [coroutines 提供方](../providers/coroutines.md)；[Arrow 资源管理](../providers/community.md#arrow) 是未引入、未实测的条件选项。`ManagedComponent.close()` 不承担这里的异步步骤，见 [逻辑层架构](../architecture/index.md)。
