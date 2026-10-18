# failure.domain：领域失败与运行异常

ID: failure.domain
默认提供方: 业务自己的 sealed 领域类型及 Kotlin 原生异常；协程沿用取消、结构化并发与监督机制。
状态: v0.1 规范、异常/取消示例与清理组件契约；没有通用错误框架，也没有已验证的领域错误组合实现。平台实测见 [兼容性记录](../providers/compatibility.md)。
前提: 需要区分预期业务失败、外部异常、程序缺陷、取消，以及没有调用者接收的后台故障。
切换条件: 需要 typed error 组合或累积验证时评估 Arrow Either/Raise；未引入依赖，也未声明其版本兼容性。
依赖与版本: core 使用 stdlib；异步示例使用 coroutines 1.9.0；[版本目录](../gradle/libs.versions.toml)。
契约: 仅取消时保持传播，关联操作真实失败优先；调用与后台故障边界明确；初始化主因保留；报告不等于恢复
验证: 异常/取消/回滚/事件报告契约；sealed领域流程和Arrow消费未实测
替代方案: Arrow Either/Raise条件评估，未引入依赖或实测版本

已知业务失败由业务类型表达；调用失败沿调用链传播，取消不得变成普通业务失败。只有明确的独立后台故障边界负责报告没有调用者观察的异常；报告不代表恢复，也不自动重试。对象所有权树不能替代故障边界：父对象拥有子对象，不代表每个子对象的失败都应终止所有同级对象。

关联操作同时观察各项任务，局部取消或超时也会取消其他任务并等待退出。调用者取消后，示例等待操作及清理完成，再观察最终结果：非取消失败优先，并保留取消信息；仅取消时仍传播取消。异常交给调用者，不重复作为独立后台故障报告；调用者未处理的真实失败仍可能影响其父任务树。

初始化失败保持原始异常为主因，再附加回滚清理异常。同步资源组会暂时捕获所有 `Throwable` 以尝试剩余清理，然后抛出原始首异常，后续异常作为 suppressed 保留；这不把取消或 fatal failure 转成成功。`runCatching` 会捕获 `Throwable`，不能用作无条件吞错入口。

**规范、实现与证据**：

- [失败边界](../architecture/failure-boundaries.md)：领域失败、调用异常、取消、独立后台故障及事件报告策略。
- [AsyncWorkSession](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt)、[测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt)、[异步关闭实践](../practices/asynchronous-shutdown.md)：关联任务共同失败、独立任务隔离、调用者取消。
- [DocumentWorkspace](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)、[测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt)、[初始化实践](../practices/initialization-and-ownership.md)：初始化与清理同时失败时保留主因。
- [CloseableGroupTest](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/resources/CloseableGroupTest.kt)、[EventEmitterTest](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/events/EventEmitterTest.kt)：清理错误与监听/报告错误的不同边界。

当前示例验证异常机制，没有提供 sealed 业务失败流程或 Arrow 消费样例。类型建模是规范建议，不能视为已验证的通用领域错误方案；选型依据见 [Kotlin 标准库](../providers/kotlin.md) 和 [Arrow 条件选用](../providers/community.md#arrow)。
