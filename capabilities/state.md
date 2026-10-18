# state.observe：观察当前状态

ID: state.observe
默认提供方: 原生 `kotlinx.coroutines.StateFlow`，不增加本库包装类型。
状态: v0.1 推荐规范与文档状态示例；平台实测以 [兼容性记录](../providers/compatibility.md) 为准。
前提: 消费者需要当前不可变状态快照。
切换条件: 无需观察时使用普通查询方法；需要逐次通知时选择 [同步 Event](events.md) 或按投递要求配置 [SharedFlow](async-events.md)。切换为事件也不自动提供持久化或恰好一次处理。
依赖与版本: examples 使用 coroutines 1.9.0，core 不依赖协程；[版本目录](../gradle/libs.versions.toml)。
契约: 只读StateFlow与不可变快照；收集者拥有收集Job；关闭源对象不会完成Flow
验证: 文档状态写入与监听失败后状态保留；收集/重新订阅等未实测
替代方案: 普通查询；有逐次通知需求时选择Event或明确配置的SharedFlow

业务对象持有私有 `MutableStateFlow`，对外暴露只读 `StateFlow` 和不可变快照。StateFlow 合并相等值及过快的中间更新，不能把它当成逐条事件记录。状态所有者与收集者可以有不同寿命：收集者负责自己的收集 Job；关闭状态所有者不会让 StateFlow 自动完成。

**实践与证据**：

- [DocumentWorkspace 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)：`DocumentState`、`DocumentSession.state`、`changeText`。
- [同步事件与插件实践中的状态说明](../practices/synchronous-events.md#状态直接复用-stateflow)。
- [DocumentWorkspaceTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) 验证状态写入、独立文档及监听失败后已提交状态；这些测试没有验证 StateFlow 收集/重新订阅、相等值合并或慢消费者行为，不把它们写成本库实测。

StateFlow 本身的合并与收集契约参考 [提供方说明](../providers/coroutines.md) 及其链接的官方 API。所有权边界见 [架构规范](../architecture/index.md)，不能把订阅者依赖某个状态源等同于拥有该状态源。
