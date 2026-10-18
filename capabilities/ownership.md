# ownership.sync：同步对象所有权

ID: ownership.sync
默认提供方: stdlib `AutoCloseable` + core 的 `ManagedComponent` / `CloseableGroup`。
状态: v0.1 预发布公共组件；平台实测以 [兼容性记录](../providers/compatibility.md) 为准。
前提: 长期逻辑对象拥有需要同步清理的子对象、订阅或资源。
切换条件: 块级资源直接用 `use`；已有 Reaktive 生态先用其资源集合；任务退出需要等待时转到 [task.shutdown](shutdown.md)。
依赖与版本: core 只依赖 stdlib；构建版本见 [版本目录](../gradle/libs.versions.toml)。
契约: 唯一owner为使用契约；借用不登记；串行访问；逆序同步关闭；终态不等于异步完成
验证: 资源组、组件与对象树测试；精确入口见下方实现与证据
替代方案: 块级use；已采用Reaktive的资源集合；Arrow资源作用域按需评估

所有权树决定谁关闭谁，借用依赖图只表达谁使用谁。借用的共享仓库不登记到自己的资源组。唯一 owner 是使用契约，容器不能检测跨 owner 的双重登记，也不自动检查整个对象图是否有环。

核心要求外部串行访问。关闭先进入终态，再逆序尝试清理；清理失败仍会尝试其余项，首异常向外传播，其他异常作为 suppressed 保留。重入或重复 close 不重放清理。`isClosed` 表示关闭已开始，不能用作异步任务完成或任意线程间的完成屏障。

关闭后登记的新资源先获清理再明确拒绝，不会成功返回已关闭资源；组内重复身份和 self-own 会拒绝。初始化回滚通过工厂模式实现，基类不能自动挽救尚未构造成功的实例。

**实现与证据**：

- [ManagedComponent](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/components/ManagedComponent.kt)：`own`、`onClose`、`ensureOpen` 与 final `close`。
- [CloseableGroup](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/resources/CloseableGroup.kt)：登记、逆序释放与 `removeAndClose`。
- [资源组契约测试](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/resources/CloseableGroupTest.kt)、[组件与初始化回滚测试](../libraries/core/src/commonTest/kotlin/io/github/imaoda/foundation/components/ManagedComponentTest.kt)。
- [初始化与所有权实践](../practices/initialization-and-ownership.md)、[DocumentWorkspace 源码](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)、[工作区测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt)：工作区→文档→插件的所有权与共享仓库的借用。

复用边界见 [Kotlin 标准库](../providers/kotlin.md) 和 [社区能力条件选用](../providers/community.md)；不是所有逻辑对象都需要组件基类，见 [架构规范](../architecture/index.md)。
