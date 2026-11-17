个人自用的 DeepSeek 安卓客户端，直连官方 API。

## 修复（v1.2.5）

- 修复展开「思考过程」面板后文字持续上下抽搐：跟随滚动改为每次内容更新只做一次 `scrollBy` 直接夹到底部（去掉了先 `scrollToItem(last)` 归位、再滚回底部的两步滚动）；是否跟随按真实滚动状态判断，手动往上翻后不会再把视图拽回，自己滚回底部才恢复；流式文本按约 80ms 节流后再推给 UI，减少整页重组
- 修复退出会话后思考过程消失：`stop()` 取消协程后，收尾的落盘在挂起点被 `CancellationException` 跳过，导致「界面上有、磁盘上没有」——现在收尾落盘放在 `NonCancellable` 里；另外 Activity 退到后台 / 退出前会把正在流式输出的正文与思考写一份检查点，进程被系统回收后再进来仍能看到「已深度思考」
- 新增 `ChatStore` JVM 往返测试，覆盖 `reasoning` / `error` / `usage` 序列化不丢

## 功能

- 图片上传（`deepseek-flash` 原生视觉，自动缩放 / EXIF 转正 / 请求体积上限）
- 修复：MCP 工具调用时，模型「先说一句再调用工具」的场合，已经显示的正文会被清空重来（现在只追加，不再重置）
- 联网（默认开）：服务端 `deepseek_web_search` / `deepseek_read_url`，自动搜索或读取消息里的链接，回答下方附「参考来源」；搜索/抓取按次计费，可在设置里关闭
- 时间感知：每条用户消息前带一条 DeepSeek `latest_reminder`（`日期,星期,时区,App,中文`），模型每轮都知道今天几号；由消息创建时间推导，历史回传一致，不破坏上下文缓存
- 远程 MCP（默认关）：设置里填服务器 IPv4 + 端口，App 作为 MCP 客户端握手、拉工具列表并交给模型调用（最多 4 轮），可用「测试连接」自检；开启时「联网」服务端工具自动让位（DeepSeek 不允许同时声明）。配套服务器已拆分为独立仓库 [pocket-mcp](https://github.com/YYUN64/pocket-mcp)：单文件、纯标准库、8 个工具，Linux 一条 `sudo ./install.sh` 装成非 root 用户 + systemd 沙箱，Windows 双击 `run-windows.bat` 即可
- 推理等级：关闭 / 低 / 高 / 极致（对齐 DeepSeek Harness 的 off/low/high/max）
- 会话历史：本地 JSON 存储，左侧抽屉切换，冷启动默认开新会话
- 上下文长度 / 最大回复长度可调（16K ~ 1M / 4K ~ 384K）
- 上下文占用显示：以服务端返回的 `usage` 为锚点（精确），新增消息才用估算（带 `≈` 标记）
- 流式输出、思考过程折叠面板、Markdown 渲染、深色 / 浅色 / 跟随系统
- 设置存本机、`allowBackup=false`，API Key 不上传任何第三方

## 安装

- `DeepSeekLite-<tag>.apk`：release 包，R8 压缩，冷启动约 0.36 s（推荐）
- `DeepSeekLite-<tag>-debug.apk`：调试包，体积大、启动约 2 s，仅用于排查

包名 `io.github.yyun64.deepseekchat`，可与官方 DeepSeek App 共存。
release 包使用 debug keystore 签名，仅供个人侧载，不适合上架分发。
