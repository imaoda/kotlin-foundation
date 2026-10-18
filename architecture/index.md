# 面向对象的逻辑层

本项目约定怎样用普通 Kotlin 对象组织长期运行的逻辑：对象拥有明确的状态、行为和生命周期；子组件组合成更大的能力；依赖通过构造参数传入。这里的组件是逻辑对象，不是 UI、系统服务或宿主框架组件。

先从 [能力入口](../guide/index.md) 找到当前问题。下列规则约束自研组件和示例，也用于判断第三方能力怎样接入。

## 对象什么时候成为组件

有独立生命周期、拥有订阅或其他同步资源的逻辑对象，可以继承 [ManagedComponent](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/components/ManagedComponent.kt)。它提供 `own`、`onClose`、`ensureOpen` 和最终的 `close`。值对象、算法和无状态服务不必继承它；已有父类的对象可以组合 [CloseableGroup](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/resources/CloseableGroup.kt)。

业务方法负责检查自身前提、更新状态和发出事实通知。基类只统一资源归属和终止语义，不自动生成业务方法、错误恢复、调度器或任务作用域。

## 所有权必须可追踪

每项资源有一个负责关闭它的所有者。`own` 或 `add` 表示转移关闭责任；借用的仓库、共享服务和上级对象保持普通引用，不能登记为自己的资源。容器只能检查容器内部的重复登记，不能证明整个对象图没有双重所有权。

[文档工作区示例](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspace.kt) 中，工作区拥有各文档，文档拥有连接、事件源与当前插件，插件拥有订阅；工作区和文档都借用同一个仓库。关闭一个文档不影响另一个文档，也不关闭仓库。动态移除子组件通过 `removeAndClose` 同时解除容器引用和清理资源，避免把已结束对象一直保留到根对象退出。

资源按登记的逆序关闭。关闭开始即进入终态，重复或清理回调中的重入关闭不会再次释放；终态不代表任意线程上的清理都已完成。业务接口通过 `ensureOpen` 拒绝关闭后的调用。同步容器的清理异常规则见 [失败边界](failure-boundaries.md)。

构造函数只分配纯内存字段、保存借用依赖。获得外部连接或注册外部订阅放入受保护的初始化阶段；基类不能挽救一个尚未构造成功、调用方也拿不到引用的实例。见 [初始化与所有权实践](../practices/initialization-and-ownership.md)。

## 三种关系分别建模

| 关系 | 责任 | 不能推出的结论 |
| --- | --- | --- |
| 对象所有权树 | 父对象结束时关闭自己拥有的资源 | 子对象失败必然使所有同级对象失败 |
| 协程任务树 | 任务的等待、完成和取消传播 | 每个对象都必须有一个 Job |
| 故障边界 | 哪些工作共同失败，哪些故障允许隔离 | 捕获或上报后组件就自动恢复可用 |

`ManagedComponent.close()` 只处理同步资源，不会等待协程。在应用确实需要异步关闭时，使用显式 `suspend shutdown()` 并由外部所有者执行；不要通过 `onClose { scope.cancel() }` 宣称已完成任务退出和资源释放。[AsyncWorkSession](../examples/src/commonMain/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSession.kt) 展示一种可取消、可由另一个活跃所有者继续完成的关闭流程，它是应用示例，不是新增的通用框架。

## 状态、事件与执行模型

状态保留当前事实，示例直接使用只读 `StateFlow` 与不可变 `DocumentState`；同步事件逐次通知已经发生的改变。两者可以同时存在，无需统一包装。必须参与操作成功或失败的工作通过显式方法调用表达，不靠监听器抛异常否决已经发生的修改。

v0.1 的核心操作要求外部串行访问，支持契约规定的同线程重入，不提供线程安全或隐式线程切换。示例的协程调度上下文也必须保持串行执行。`ensureOpen` 是生命周期前提检查，不是锁，也不能让任意业务回调自动具备重入安全性。

## 可执行依据

- [文档工作区测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/DocumentWorkspaceTest.kt)：多文档、借用共享服务、初始化回滚、动态插件、通知失败。
- [异步会话测试](../examples/src/commonTest/kotlin/io/github/imaoda/foundation/examples/AsyncWorkSessionTest.kt)：关联任务、隔离失败、取消传播、关闭顺序和重复关闭。
- [核心源码](../libraries/core/src/commonMain/kotlin/io/github/imaoda/foundation/) 与对应 `commonTest` 定义每个原语的精确边界。声明目标平台不等于完成验证；实测记录以仓库验证说明为准。
