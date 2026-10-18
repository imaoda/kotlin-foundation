# subscription.replace：替换与解除订阅

ID: subscription.replace
默认提供方: core `CloseableSlot<T : AutoCloseable>`；同步事件订阅返回幂等 `AutoCloseable` 句柄。
状态: v0.1 预发布公共组件；平台实测以 [兼容性记录](../providers/compatibility.md) 为准。
前提: 对象在一个位置持有当前订阅或子组件，需要替换、清空或随自身结束而关闭。
切换条件: 协程收集直接由 `Job` / `CoroutineScope` 管理；需要等待取消完成时阅读 [task.shutdown](shutdown.md)。已有订阅框架优先沿用其容器。
依赖与版本: core 只依赖 stdlib；[构建版本](../gradle/libs.versions.toml)。
契约: 监听方拥有句柄；槽接管的资源不再登记到父组；替换先接管再清理；嵌套操作获胜
验证: 槽重入/失败/终态契约测试和插件替换示例，见下方链接
替代方案: 协程收集使用原生Job；已采用框架的订阅容器

父组件拥有槽，槽拥有当前资源；当前资源不得又登记到父资源组。监听方拥有解绑句柄，发布方关闭可以让订阅失效，但不拥有或销毁监听方的业务对象。标准库 `AutoCloseable { ... }` 本身不保证幂等，不能把任意外部资源都当成可重复关闭的订阅句柄。

槽先接管新值，再关闭旧值；旧值清理抛错不撤销替换。清理回调重入替换、clear 或 close 时，嵌套操作获胜。clear 解除当前引用并关闭旧值，但保持槽开放；close 先进入终态。关闭后的非空赋值清理新资源后拒绝。所有访问要求外部串行执行。

**实现与证据**：

- [CloseableSlot](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/resources/CloseableSlot.kt)、[替换槽契约测试](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/resources/CloseableSlotTest.kt)：重复赋值、异常、重入替换及终态拒绝。
- [EventSource](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/events/EventSource.kt)：订阅句柄契约；[同步事件能力](events.md) 说明发布行为。
- [同步事件与插件实践](../practices/synchronous-events.md)、[DocumentWorkspace 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt) 的 `replacePlugin` / `DocumentPlugin.attach`、[工作区测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) 的 `replacementAndIndependentCloseDetachOnlyTheOwnedSubscription`。

需要从动态集合中提前移除并关闭成员时使用 [CloseableGroup.removeAndClose](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/resources/CloseableGroup.kt)；独立调用某个资源的 close 不会自动从所有容器里删除它。
