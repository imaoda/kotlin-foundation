# 社区能力的条件选用

## Arrow

需要显式领域错误组合、累积验证时，评估 [Either/Raise](https://arrow-kt.io/learn/typed-errors/)；需要 acquire/use/release、挂起 finalizer 时，评估 [资源管理](https://arrow-kt.io/learn/coroutines/resource-safety/)。这两类能力不在本项目重建。

这是条件推荐，当前未引入或测试 Arrow 依赖，未声明任何版本兼容范围。选择时应核验目标平台、版本、取消与清理契约。资源管理指南仍有 AutoCloseable 仅 JVM 的旧描述；跨平台事实以 [Kotlin 当前 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-auto-closeable/) 为准。

## Reaktive

已有 Reaktive 项目的订阅应优先采用其 [CompositeDisposable](https://github.com/badoo/Reaktive/blob/master/reaktive/src/commonMain/kotlin/com/badoo/reaktive/disposable/CompositeDisposable.kt)。它证明资源集合需求已有成熟实现。本项目为 stdlib AutoCloseable、明确逆序清理、严格终态、OOP组件契约保留小实现，不宣称发明资源管理，也不要求 Rx 项目迁移。

## Essenty

[Essenty](https://github.com/arkivanov/Essenty) 提供 KMP 生命周期与实例管理等能力。已有该生态的项目应先复用既有对象寿命边界。本项目不提供宿主/UI生命周期集成或第二套页面状态机。

上述项目本次仅做官方文档/源码对照，未构建消费样例。最近核验：2026-10-19。后续若升格默认 provider，需要精确版本、可运行例子和取消/失败证据。
