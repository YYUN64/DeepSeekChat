# DeepSeek Chat（Android 客户端）

[![Build APK](https://github.com/YYUN64/DeepSeekChat/actions/workflows/android.yml/badge.svg)](https://github.com/YYUN64/DeepSeekChat/actions/workflows/android.yml)

一个用 **DeepSeek 官方 API** 驱动的 Android 聊天客户端，界面和交互参照官方 App：极简聊天页 + 历史会话抽屉 + 设置页。

只做四件事：**图片上传、思考深度、会话历史、上下文长度**，其余能省就省。

> 这是第三方客户端，不是官方 App。你的 API Key 只保存在本机。
>
> 应用名 **DeepSeek Lite**，包名 `io.github.yyun64.deepseekchat` —— 官方 App 的包名正是 `com.deepseek.chat`，两者可以同时安装、互不影响。

---

## 功能

| 能力 | 说明 |
| --- | --- |
| 图片上传 | 从相册选图，自动缩放（最长边 1600px）并按 EXIF 旋转，以 base64 内联发送；使用 `deepseek-flash` 的原生视觉能力（支持 JPEG/PNG/GIF/WebP） |
| 联网 | 声明服务端工具 `deepseek_web_search` + `deepseek_read_url`：模型自己决定是否搜索、是否读取消息里的链接，搜索和抓取都在 DeepSeek 服务端完成；回答下方展示「参考来源」。设置里可关闭（默认开），搜索/抓取按次计费 |
| MCP | 可选（默认关）：设置里填 MCP 服务器 IPv4 + 端口（`http://<ip>:<port>/mcp`）和 Token（可选，对应服务端 `MCP_TOKEN`），App 作为 MCP 客户端把工具交给模型调用，工具真实执行在 MCP 服务器上；工具调用轮数可选 2/4/8/不限（内部上限 64）；支持「测试连接」列出工具名。开启时自动关闭上面的服务端联网工具。配套服务器已拆分为独立仓库 [pocket-mcp](https://github.com/YYUN64/pocket-mcp)：纯标准库单文件 Python，Linux/Windows 均可部署，现已支持 Bearer token 鉴权 |
| 思考深度 | 四档「推理等级」：关闭 / 低 / 高 / 极致（与 DeepSeek Harness 的 off/low/high/max 一致）；思考过程以可折叠面板展示，流式输出 |
| 会话历史 | 每个会话一个 JSON 文件，自动命名、重命名、删除、清空；重启后自动恢复最近会话 |
| 上下文长度 | 16K / 32K / 64K / 128K / 256K / 512K / 1M 可选，超出预算自动丢弃最早的对话；顶栏实时显示估算用量 |

另外还有：流式打字机输出（SSE）、Markdown 渲染（表格、代码块底色 + 一键复制、**LaTeX 数学公式**）、停止生成、重新生成、复制回答、深色模式、系统提示词、自定义 Base URL（可接任意 OpenAI 兼容网关）、token 用量展示。

## 环境与构建

- Kotlin 2.0.21 + Jetpack Compose（Material 3），AGP 8.5.2，Gradle 8.7，JDK 17
- minSdk 26（Android 8.0），targetSdk 34
- 无 Room / Hilt / KSP，依赖极少，构建快

### 方式一：GitHub Actions 直接拿 APK（不用装 Android Studio）

1. 把本目录推到 GitHub 仓库（`.github/workflows/android.yml` 已包含）。
2. 每次 push 到 `main` 都会自动构建（debug + release 都会产出）；也可以在 **Actions → Build APK → Run workflow** 手动触发。
3. 跑完在 Artifacts 里下载 `DeepSeekChat-apk`，解压后按需安装 `app-release.apk`（推荐）或 `app-debug.apk`。

> CI 里用的是 Google 官方 Maven 仓库；阿里云镜像只在本地环境（`CI` 环境变量为空时）优先使用。

### 方式二：Android Studio

1. Android Studio（Ladybug 或更新）→ **Open** 本目录。
2. 首次同步会自动下载 Gradle 8.7 与依赖（`settings.gradle.kts` 里已配置阿里云镜像，国内网络更稳）。
3. 连接手机，点 ▶ 运行；或 **Build → Build App Bundle(s)/APK(s) → Build APK(s)**。

> 仓库已包含 Gradle wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`），Windows、macOS、Linux 都可直接用 wrapper 构建，无需另外安装 Gradle。

### 方式三：命令行

```bash
# 需要 JDK 17 + Android SDK（platform 34、build-tools 34.0.0）
# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew

./gradlew :app:assembleDebug     # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # 发布包：app/build/outputs/apk/release/app-release.apk
```

> **日常请用 release 包**：开了 R8 压缩/混淆，体积 1.5 MB、冷启动 ~0.36 s；debug 包因为要保留调试信息且不做 AOT，冷启动约 2 s。release 使用仓库内固定的 `app/deepseek-lite.jks`（密码与别名在 `keystore.properties`，随仓库提交）签名，**本机与 CI 产出的包签名完全一致，可以互相覆盖安装**。
>
> **签名迁移提醒**：从 1.2.9 起固定了签名。如果你之前装的是从 GitHub Release / CI 下载的包（GitHub runner 用自己机器的 debug keystore 签名），需要**卸载一次**再装新包；卸载会删除本机会话数据（`allowBackup=false`，没有云备份），重要会话请先自行备份。一直装本机构建包的设备可以直接覆盖安装。

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
| 推理等级 关闭/低/高/极致 | 关闭 → `thinking` = `{"type": "disabled"}`；低/高/极致 → `thinking` = `{"type": "enabled"}` + `reasoning_effort` = `low` / `high` / `max` |
| 上下文长度 | 客户端裁剪历史消息的预算（模型本身支持 1M） |
| 最大回复长度 | `max_tokens`（1 ~ 393216） |
| 系统提示词 | `messages` 开头的 `system` 消息 |
| 图片 | 用户消息 `content` 数组里的 `image_url`（`data:image/jpeg;base64,...`，`detail: high`） |
| 联网开关 | `tools` 里声明 `deepseek_web_search` / `deepseek_read_url`；带 tools 时历史 assistant 消息会回传 `reasoning_content` 与 `server_tool_calls` |

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
- 上下文用量：优先用服务端最近一次返回的 `usage` 做锚点（`prompt + completion` 即该轮完整 token），锚点之后新增的消息才用启发式估算补齐；还没有实测数据时整体走估算，此时界面显示 `≈`。启发式为中文按 1 字 ≈ 1 token、其它 4 字符 ≈ 1 token、每张图 1024 token，与真实计费有偏差，以回答下方的服务端 usage 为准。
- `tools/` 下是开发期的自检脚本（括号配平、import 一致性、Markdown/SSE 解析逻辑的等价实现测试），跑 `node tools/check-brackets.js app/src/main/java` 即可，不参与 App 构建。
- 数学公式由 [JLaTeXMath-Android](https://github.com/noties/jlatexmath-android)（`ru.noties:jlatexmath-android`，许可证 **GPL-2.0**）渲染成图片；内置提示词会引导模型用 `$$...$$` / `\[...\]` / `\(...\)` 输出公式，模型仍输出别的写法时按文本安全兜底。若要把这个 App 作为闭源商业应用分发，需要先处理该依赖的 GPL 许可（这个项目是自用侧载，不受影响）。
