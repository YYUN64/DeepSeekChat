个人自用的 DeepSeek 安卓客户端，直连官方 API。

## 功能

- 图片上传（`deepseek-flash` 原生视觉，自动缩放 / EXIF 转正 / 请求体积上限）
- 推理等级：关闭 / 低 / 高 / 极致（对齐 DeepSeek Harness 的 off/low/high/max）
- 会话历史：本地 JSON 存储，左侧抽屉切换，冷启动默认开新会话
- 上下文长度 / 最大回复长度可调（16K ~ 1M / 4K ~ 384K）
- 流式输出、思考过程折叠面板、Markdown 渲染、深色 / 浅色 / 跟随系统
- 设置存本机、`allowBackup=false`，API Key 不上传任何第三方

## 安装

- `DeepSeekLite-<tag>.apk`：release 包，R8 压缩，冷启动约 0.36 s（推荐）
- `DeepSeekLite-<tag>-debug.apk`：调试包，体积大、启动约 2 s，仅用于排查

包名 `io.github.yyun64.deepseekchat`，可与官方 DeepSeek App 共存。
release 包使用 debug keystore 签名，仅供个人侧载，不适合上架分发。
