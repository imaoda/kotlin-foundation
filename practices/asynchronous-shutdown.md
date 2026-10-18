# 异步工作与显式关闭

适用场景：长期逻辑对象拥有协程任务，任务退出前仍需要使用同步资源。

阅读 [AsyncWorkSession.kt](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt) 和 [AsyncWorkSessionTest.kt](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt)。这是应用级会话例子，不是核心包公开的异步组件基类。

## 关闭分三个阶段

`shutdown` 是 suspend 方法，由不属于会话任务树的外部所有者执行：

1. 将 `Active` 改为 `Stopping`，拒绝新工作。
2. 取消会话拥有的任务，并等待它们及其 finally 完成。
3. 进入 `Closed` 后关闭同步资源。

任务被取消不代表已经退出。只调用 `Job.cancel()` 就关闭任务仍在使用的连接，可能造成使用已关闭资源的问题；测试通过一个受控的 finally 等待点证明资源直到任务结束才被关闭。

`ManagedComponent.close()` 不承担上述异步步骤。不能让会话自己的子任务调用并等待 `shutdown`，因为它会等待包含自身的任务树完成。示例把这条作为调用前提；外部所有者安排关闭，不把关闭任务加入被关闭的 scope。

## 调用方取消与最终完成责任

等待过程使用调用方的取消语义。若调用方取消，`shutdown` 传播取消，保持 `Stopping`，资源仍然打开；任务最终结束也不会自动关闭它们。之后必须有一个活跃的外部所有者再次调用 `shutdown` 完成释放。

若外部所有者的策略要求“自己的 finally 必须等到会话完全关闭”，它可以明确在 `withContext(NonCancellable)` 中调用 `shutdown`。对应测试是 `externalOwnerMayExplicitlyFinishShutdownInNonCancellableFinally`。这是外部所有者主动选择等待，不是 `shutdown` 内部吞掉取消；若工作不合作或清理不结束，这个等待也不会自动获得超时保障。

同一串行上下文中的重叠关闭调用只会执行一次同步 close。清理失败后会话仍为 `Closed`，异常交给完成该次清理的调用者，重复关闭不重试或重放该异常。`Closed` 表示任务已退出且同步关闭已经开始/执行，不是对任意资源成功释放的保证。

## 关联任务与独立任务

`runRelated` 把一次操作的两项工作放入 `coroutineScope`，任一项失败会取消另一项，失败通过 `await` 传播给调用者。JVM 的协程堆栈恢复可能复制异常并保留原始 cause，因此跨协程验证关注类型、信息和原因链，不承诺异常对象身份不变。这次操作在会话监督边界内，不会顺带终止其他会话操作。

调用方取消时，`runRelated` 的 finally 明确在 `NonCancellable` 中执行 `operation.cancelAndJoin()`：既取消操作，也等其子任务及 finally 完成，然后才向调用方传播取消。因此返回或抛出异常时，这次操作已经结束，不会把仍在清理的任务悄悄留给会话。代价是没有合作取消的工作或始终不结束的清理会延迟返回，示例不承诺等待上限。`cancelledRelatedCallerWaitsForDelayedChildCleanupBeforeReturning` 用受控的延迟清理验证完成屏障与取消传播。

这个操作级完成契约与 `shutdown` 的可重试契约不同：一次调用必须收完自己发起的工作，整个长期会话则允许外部所有者在中断后重新接手关闭。共享的上层会话仍由自己的所有者关闭。

`launchIndependent` 用于彼此独立的后台工作。会话的 `SupervisorJob` 隔离它们的失败；没有调用者接收的异常通过注入回调报告一次。取消不作为故障报告。这个回调是应用参数，不是新的公共错误框架；示例要求报告回调本身可靠，不在回调里进行会抛异常的恢复逻辑。

报告故障不表示业务恢复：实际应用仍需更新自己的失败状态，决定是否重试或停止接单。示例只验证隔离与报告语义，不自动实现恢复、重试或后台任务重启。

所有会话入口和协程执行都要求同一串行上下文。示例没有锁，也没有把协程调度能力包装成线程安全组件。
