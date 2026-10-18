# Kotlin 标准库

默认用于 AutoCloseable/use、接口与组合、不可变快照的建模、sealed 领域类型、Throwable 的 cause/suppressed 及 Result。

- [AutoCloseable](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-auto-closeable/) 与 [use](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/use.html) 已提供 common API。
- [AutoCloseable 工厂](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-auto-closeable.html) 本身不保证重复 close 只执行一次，不能直接当作本项目幂等所有权容器。
- [Result](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-result/) 的失败端是 Throwable；[runCatching](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/run-catching.html) 会捕获 Throwable，不能无条件用来包协程取消。

Kotlin 2.4.21 是本项目当前构建版本，不表示已经验证所有较旧编译器。使用方无需再引入本库对上述 API 的换名转发。API 设计遵循 [简洁性](https://kotlinlang.org/docs/api-guidelines-simplicity.html) 与 [兼容性](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html) 原则。

证据：[核心源码](../libraries/core/src/commonMain/)、[兼容性记录](compatibility.md)。核验日期：2026-10-19。
