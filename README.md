# kotlin-foundation

面向开发者与 AI 的 Kotlin / KMP **面向对象逻辑层**基础：架构规范、能力选择、可验证实践，以及少量补足机制的组件。

从 [使用入口](guide/index.md) 开始，按 [能力索引](capabilities/index.md) 查找问题。维护本仓库请读 [AGENTS.md](AGENTS.md)，它不属于使用方项目的指令。

## 提供什么

- 普通 Kotlin 对象封装业务状态与行为；资源拥有者统一结束子对象、资源和订阅。
- `AutoCloseable` 资源集合、替换槽、`ManagedComponent` 和同步类型化事件。
- 直接使用 StateFlow、SharedFlow、结构化并发及标准异常；通过可运行示例说明组合方式。
- 有前提和验证证据的复用指南，避免把成熟 API 换名转发。

逻辑核心不依赖 Android、Compose、DI 或协程。`:examples` 使用协程演示实际组合，`:android-consumer` 只验证公开 API 的消费。UI、宿主生命周期集成及跨语言统一 API 均不在范围内。

## v0.1 契约

核心操作同步执行，**要求调用方串行访问**；允许文档规定的重入，不保证跨线程并发安全。同步 close 不等待协程完成，不能把 Job 注册成同步资源后宣称安全异步关闭。

API 处于 `0.1.0-SNAPSHOT` 验证阶段，尚未发布 Maven Central。公共 API 变更通过 ABI 基线检查和行为测试审查；当前不承诺兼容其他 Kotlin 编译器版本。见 [开发与验证](guide/development.md)、[兼容性证据](providers/compatibility.md)。

源码仓库已公开，当前仅供验证，尚未发布依赖包或正式 Release。待维护者完成验证并明确确认后再发布；许可证选择另行决定。

## 结构

| 内容 | 入口 |
| --- | --- |
| 对象架构与运行边界 | [architecture](architecture/) |
| 按问题选择能力 | [capabilities](capabilities/index.md) |
| 可执行组合实践 | [practices](practices/) |
| 推荐与验证证据 | [providers](providers/index.md) |
| 自研取舍 | [decisions](decisions/0001-reuse-and-scope.md) |
| 核心源代码 | [core](libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/) |
| 可测试纯逻辑示例 | [examples](examples/src/) |

使用方可以只采用规范或现成库指南，无须引入 core。需要组件时也可以独立使用资源原语，不强迫已有工程迁移所有业务父类。
