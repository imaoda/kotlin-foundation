# kotlin-foundation contributor instructions

This is a Kotlin/KMP foundation for object-oriented **logic**, with an AI-readable capability guide. It contains architecture contracts, verified reuse guidance, and minimal missing mechanisms. UI and host-framework integration are outside scope.

- Discuss ambiguous goals and lasting API/dependency/behavior changes before implementation; execute agreed scope. Read-only investigations and uncontroversial reversible fixes need no extra approval.
- Work on main by default. Preserve other changes. After relevant validation, commit all version-controlled changes and push origin/main. Never publish packages or change repository visibility without an explicit request.
- Preserve the selected Kotlin 2.4.21, Gradle 9.6.0, AGP 9.4.1, JDK 25 toolchain. Do not downgrade tools or dependencies to obtain a green build.
- Core depends only on Kotlin stdlib. Reuse kotlinx.coroutines directly in examples. Do not add UI, DI, global event buses, custom Result/error-code frameworks, or coroutine ownership disguised as synchronous close.
- Public resource protocol is AutoCloseable. A ManagedComponent owns synchronous resources only. Borrowed dependencies must not be owned. Value objects and stateless functions need no component base.
- v0.1 core operations are synchronous and require externally serialized access, including reentrant callbacks. They are not generally thread-safe. No implicit dispatcher or thread switching.
- Core public APIs use explicit visibility and types, KDoc, and behavioral tests. Document initialization rollback, repeated close, reentrancy, exception/suppressed behavior, and terminal state.
- Keep guide/index.md as the consumer entry. This AGENTS.md governs maintenance only and must not be imported into a consuming project as its instructions.
- Documentation describes actual API and evidence. Link runnable Kotlin sources rather than maintaining duplicate code snippets. Mark unverified providers/platforms explicitly.
- Do not overwrite files being edited by another agent. Coordinate API changes with consumers before integration.
