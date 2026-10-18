# 开发与验证

使用仓库 Gradle Wrapper。`scripts/gradle` 在未设置 JAVA_HOME 的 macOS 上可使用已安装的 Android Studio JBR，不安装或降级任何工具。Android SDK 使用本机 local.properties 或 ANDROID_HOME；本机路径不提交。

## 工具链

Kotlin 2.4.21、Gradle 9.6.0、AGP 9.4.1、Gradle daemon JDK 25；JVM/Android 字节码目标 11，Android compileSdk 37 / minSdk 28；协程示例锁定 1.9.0。声明值集中在 [版本目录](../gradle/libs.versions.toml) 和 Wrapper。SDK/JDK安装位置与最低消费版本是不同概念。

目前保留用户已有工具链。AGP 9.4.1 和本机 Xcode 27.1 高于 Kotlin 2.4.21 官方表中的 AGP 9.3.1 / Xcode 26.4；实测通过也不能称为官方支持全覆盖。详见 [官方兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)。

## 常规验证

```sh
./scripts/gradle :check
```

根任务 `:check` 验证 JVM 核心/示例测试、Android 宿主消费与构建、ABI、文档及声明依赖边界。冒号限定根任务；不带冒号的 `check` 还会选择各子工程的检查任务，包括 Native 测试，需要相应平台环境。

Android 模块的宿主单元测试不等于设备运行。在已启动、已授权的 Android 设备或模拟器上执行 `./scripts/gradle :android-consumer:connectedDebugAndroidTest`；这只安装验证 APK、测试逻辑契约，不接入 UI。

Native 测试在已安装 iOS 运行时的 macOS 上执行，选定模拟器后传入设备 UDID：

```sh
./scripts/gradle -PiosDevice=SIMULATOR_UDID :core:iosSimulatorArm64Test :examples:iosSimulatorArm64Test
```

不会自动下载模拟器。iOS 真机架构可用 compileKotlinIosArm64 验证编译；编译不等于真机运行。Swift 导出不是本库当前承诺。

## ABI 与依赖

公共 API 变更先审查契约，再运行 `:core:updateKotlinAbi` 更新基线；不要为了通过检查直接覆盖基线。Kotlin 内置 ABI DSL 仍需显式 opt-in。ABI 检查不能代替行为兼容测试。

`checkBoundaries` 检查 core 的声明依赖仅允许标准库、示例和 Android 消费端单向依赖 core。它不扫描所有运行时传递依赖，解析证据另记。文档检查只验证本地链接及能力字段；上游链接需发布前人工核验。

## 本地发布消费

```sh
./scripts/gradle :core:publishAllPublicationsToVerificationRepository
./scripts/gradle -p verification/published-consumer jvmTest
./scripts/gradle -p verification/published-consumer -PiosDevice=SIMULATOR_UDID iosSimulatorArm64Test
```

产物写入忽略的 build/repository，不上传网络仓库。公共坐标为 `io.github.imaoda.foundation:core:0.1.0-SNAPSHOT`，发布元数据会选择对应平台产物。包未发布 Maven Central，不能仅复制坐标就期待远程解析成功。

[独立消费工程](../verification/published-consumer/) 不包含源码 project dependency 或 composite build，验证 JVM / iOS 平台选择和公共 API 可消费性。主工程中的 [Android 消费端](../verification/android-consumer/) 验证 Android source-project 依赖；它不替代 Android 发布物消费验证。JUnit / AndroidX Test 仅出现在验证模块中，不进入 core 依赖。

实际验证结果和精确范围见 [证据记录](../providers/compatibility.md)。测试、文档与依赖版本变更一起审查。新增平台必须先有可重复的测试路径；不因存在 target 声明就标记支持。
