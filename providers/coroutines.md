# kotlinx.coroutines

默认使用 StateFlow 表达当前状态，SharedFlow 表达协程广播，Job/CoroutineScope 表达任务关系。直接保留原生概念，不添加 launchSafely 或 own(Job) 来隐藏取消与失败。

- [StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) 合并更新，不保证每个中间值都被处理。
- [SharedFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) 的缓冲、重放与无人订阅行为必须按事件承诺配置。
- [Job](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/) 的 cancel 不等于完成；依赖资源释放前需要等待。
- [callbackFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html) 适配回调时仍需让底层注册/注销满足其并发要求；不能直接把线程受限 Event 接到任意 dispatcher。
- [SupervisorJob](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-supervisor-job.html) 隔离子任务失败，不替业务决定哪些工作独立。

示例锁定 1.9.0，沿用当前项目已使用的版本，没有为了获得更新API而升级。官网页面可能展示更新版本；具体可用行为以本仓库编译和测试为证据。见 [示例](../examples/src/) 与 [验证记录](compatibility.md)。核验日期：2026-10-19。
