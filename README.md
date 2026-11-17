# DeepSeek Chat（Android 客户端）

一个用 **DeepSeek 官方 API** 驱动的 Android 聊天客户端，界面和交互参照官方 App：极简聊天页 + 历史会话抽屉 + 设置页。

只做四件事：**图片上传、思考深度、会话历史、上下文长度**，其余能省就省。

> 这是第三方客户端，不是官方 App。你的 API Key 只保存在本机。

---

## 功能

| 能力 | 说明 |
| --- | --- |
| 图片上传 | 从相册选图，自动缩放（最长边 1600px）并按 EXIF 旋转，以 base64 内联发送；使用 `deepseek-flash` 的原生视觉能力（支持 JPEG/PNG/GIF/WebP） |
| 思考深度 | 开关「深度思考」+ 四档强度（低/中/高/极致 → `reasoning_effort` 的 `low/medium/high/max`）；思考过程以可折叠面板展示，流式输出 |
| 会话历史 | 每个会话一个 JSON 文件，自动命名、重命名、删除、清空；重启后自动恢复最近会话 |
| 上下文长度 | 16K / 32K / 64K / 128K / 256K / 512K / 1M 可选，超出预算自动丢弃最早的对话；顶栏实时显示估算用量 |

另外还有：流式打字机输出（SSE）、Markdown 渲染（代码块高亮底色 + 一键复制）、停止生成、重新生成、复制回答、深色模式、系统提示词、自定义 Base URL（可接任意 OpenAI 兼容网关）、token 用量展示。

## 环境与构建

- Kotlin 2.0.21 + Jetpack Compose（Material 3），AGP 8.5.2，Gradle 8.7，JDK 17
- minSdk 26（Android 8.0），targetSdk 34
- 无 Room / Hilt / KSP，依赖极少，构建快

### 方式一：GitHub Actions 直接拿 APK（不用装 Android Studio）

1. 把本目录推到 GitHub 仓库（`.github/workflows/android.yml` 已包含）。
2. 打开仓库的 **Actions → Build APK → Run workflow**。
3. 跑完在 Artifacts 里下载 `DeepSeekChat-debug`，解压得到 `app-debug.apk`，传到手机上安装（需允许「安装未知来源应用」）。

### 方式二：Android Studio

1. Android Studio（Ladybug 或更新）→ **Open** 本目录。
2. 首次同步会自动下载 Gradle 8.7 与依赖（`settings.gradle.kts` 里已配置阿里云镜像，国内网络更稳）。
3. 连接手机，点 ▶ 运行；或 **Build → Build App Bundle(s)/APK(s) → Build APK(s)**。

> 仓库已包含 Gradle wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`），Windows、macOS、Linux 都可直接用 wrapper 构建，无需另外安装 Gradle。

### 方式三：命令行

```bash
# 需要 JDK 17 + Android SDK（platform 34、build-tools 34.0.0）
# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要能访问 Google Maven / Maven Central（`settings.gradle.kts` 已优先使用阿里云镜像）。`sdk.dir` 由 `local.properties` 指定，Android Studio 会自动生成。

## 使用

1. 打开 App → 右上角齿轮 → 填入 API Key（在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请）。
2. 返回聊天页，直接发消息即可。点顶栏标题可切换本轮对话的模型 / 深度思考 / 思考强度。
3. 输入框左侧的图片按钮 → 选图 → 发送。
4. 左滑或点左上角菜单打开会话历史。

### 参数映射

| App 里的选项 | 请求里的字段 |
| --- | --- |
| 模型 Flash / V4 Pro | `model` = `deepseek-flash` / `deepseek-v4-pro` |
| 深度思考开关 | `thinking` = `{"type": "enabled"}` / `{"type": "disabled"}` |
| 思考强度 低/中/高/极致 | `reasoning_effort` = `low` / `medium` / `high` / `max` |
| 上下文长度 | 客户端裁剪历史消息的预算（模型本身支持 1M） |
| 最大回复长度 | `max_tokens`（1 ~ 393216） |
| 系统提示词 | `messages` 开头的 `system` 消息 |
| 图片 | 用户消息 `content` 数组里的 `image_url`（`data:image/jpeg;base64,...`，`detail: high`） |

注意：`deepseek-v4-pro` 不支持图片，带图片的消息会自动改用 `deepseek-flash` 发送（气泡上会标注实际使用的模型）。`deepseek-flash` 支持 1M 上下文、Vision 与思考模式。

## 代码结构

```
app/src/main/java/com/deepseek/chat/
├── MainActivity.kt              # 入口，挂 Compose
├── data/
│   ├── Models.kt                # 消息/会话数据模型、模型与强度枚举
│   ├── Settings.kt              # SharedPreferences 设置存储
│   ├── ChatStore.kt             # 会话 JSON 持久化
│   ├── TokenCounter.kt          # token 估算与上下文裁剪
│   ├── ImageStore.kt            # 图片缩放/旋转/压缩/base64
│   └── DeepSeekClient.kt        # SSE 流式请求 + 错误处理
└── ui/
    ├── ChatViewModel.kt         # 状态与业务编排
    ├── App.kt                   # 抽屉 + 页面切换 + 相册选择
    ├── ChatScreen.kt            # 聊天页（顶栏、列表、空状态、对话设置）
    ├── MessageItem.kt           # 用户气泡 / 助手消息 / 思考面板
    ├── Composer.kt              # 输入框、图片条、发送/停止
    ├── Markdown.kt              # 轻量 Markdown 解析与渲染
    ├── HistoryDrawer.kt         # 会话历史抽屉
    ├── SettingsScreen.kt        # 设置页
    ├── Components.kt            # 通用小组件
    └── theme/Theme.kt           # 配色（浅色/深色）
```

## 说明

- 数据全部在本机：设置存在 SharedPreferences，会话在 `files/chat/conversations/*.json`，图片在 `files/images/`。
- 卸载 App 会一并删除这些数据；`allowBackup=false`，不会进云备份。
- 思考模式下 `temperature` / `top_p` 基本无效（DeepSeek 的限制），所以没有做这两个滑杆。
- 上下文用量是客户端估算（中文按 1 字 ≈ 1 token，其它字符 4 字符 ≈ 1 token，图片按 1024 token 计），和真实计费数字会有偏差，以回答下方服务端返回的 usage 为准。
- `tools/` 下是开发期的自检脚本（括号配平、import 一致性、Markdown/SSE 解析逻辑的等价实现测试），跑 `node tools/check-brackets.js app/src/main/java` 即可，不参与 App 构建。
