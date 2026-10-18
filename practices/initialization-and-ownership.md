# 初始化与所有权

适用场景：一个逻辑对象需要打开连接、登记订阅或创建有独立生命周期的子对象；初始化可能在中途失败。

阅读 [DocumentWorkspace.kt](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt)，按 `DocumentWorkspace.openDocument` → `DocumentSession.open` → `initialize` 的顺序跟踪资源。

## 先形成实例，再取得外部资源

私有构造函数保存借用依赖，分配 `MutableStateFlow`、事件源和空的资源槽等纯内存对象。它不调用仓库，也不向外部系统注册回调。实例形成后，工厂进入受保护初始化：取得连接后立即登记所有权，再执行可能失败的初始读取。

如果读取失败，工厂关闭已形成的文档实例，保留读取异常，并将关闭异常附加为 suppressed。测试同时让读取和连接关闭失败，验证原始错误仍是主因。基类不能在子类构造函数失败后自动找到半成品并补做这些步骤。

“调用获取函数再登记”也有边界：如果提供者在资源尚未返回时失败，回滚责任仍在提供者内部。例如 `repository.open` 自己打开了底层句柄又抛异常，它必须自行关闭该句柄；文档无法释放尚未拿到的返回值。

## 拥有与借用在对象图中可读

- 外部所有者负责共享 `DocumentRepository`。工作区与文档保存引用，但不登记它。
- 工作区通过 `CloseableGroup` 拥有文档，`closeDocument` 同时解除映射、解除所有权登记并关闭文档。
- 文档拥有连接、事件源、插件槽；插件拥有自己订阅得到的句柄。
- 关闭文档只释放该文档的资源。关闭工作区清理剩余文档。共享仓库继续可用，直到其外部所有者关闭。

`CloseableGroup.removeAndClose` 适合动态成员；只把所有子对象永久 `own` 到根上，会保留已结束子对象的引用直到根关闭。组件级 `own` 更适合与该组件同寿命的固定资源。

## 终态与清理失败

清理按登记逆序执行，因此登记顺序应对应依赖获取顺序。重复关闭不会重放清理。关闭失败后对象仍然是终态，不应再次使用或盲目重试 close；具体资源需要重试协议时，由资源自己提供明确操作。

代码仅承诺串行访问。获取外部资源时也不应任意回调并重入尚在初始化的业务对象；工厂在成功返回之前不向外发布文档引用。核心重入保护不替代业务对象的初始化不变量。

验证入口：[DocumentWorkspaceTest](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt) 的 `workspaceOwnsDocumentsButBorrowsRepository` 和 `initializationRollsBackAcquiredResourcesAndPreservesOriginalFailure`。
