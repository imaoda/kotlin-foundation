# 能力索引

先选择要解决的问题，再读对应条目。默认路径优先标准库、协程与已采用的成熟生态；自研组件只补明确的契约缺口。这张表不要求一次安装所有提供方。

| ID | 问题 | 默认路径 | v0.1 交付 |
| --- | --- | --- | --- |
| ownership.sync | 动态同步资源和子对象如何结束 | [AutoCloseable 与所有权组件](ownership.md) | core 公共组件、实践与测试 |
| subscription.replace | 订阅如何替换、提前解除 | [CloseableSlot](subscriptions.md) | core 公共组件、实践与测试 |
| state.observe | 如何观察当前状态 | [StateFlow](state.md) | 原生能力规范、状态写入示例 |
| event.sync | 同步对象通知如何订阅 | [EventSource / EventEmitter](events.md) | core 公共组件、实践与测试 |
| event.async | 协程广播如何处理缓冲与重放 | [SharedFlow](async-events.md) | 原生能力组合实践与测试 |
| task.shutdown | 如何取消任务并安全释放依赖资源 | [结构化并发与显式 shutdown](shutdown.md) | 规范与应用示例，无通用异步基类 |
| failure.domain | 预期失败与运行异常如何表达 | [领域类型与原生异常](failures.md) | 规范与异常示例，无通用错误框架 |

每个条目区分代码实现、可运行示例、上游契约与尚未实测的建议；链接到具体源码和测试，而非把提供方介绍当成本项目验证。当前核心公共 API 为预发布；平台结果统一见 [兼容性记录](../providers/compatibility.md)。

先确认 [逻辑层架构](../architecture/index.md)：所有权树决定关闭责任，借用依赖图不转移该责任，事件订阅与协程任务有各自的生命期。`isClosed` 或一次 `cancel()` 都不能当成异步退出完成信号。第三方库的采用条件见 [提供方索引](../providers/index.md)。
