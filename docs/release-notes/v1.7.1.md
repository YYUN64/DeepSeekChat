## 修复（v1.7.1）

- **工具调用不再是「幻觉」**：工具调用与结果现在会作为真正的 `assistant(tool_calls)` + `tool` 消息持久化进会话并随请求发给模型（之前只存在 UI 卡片，下一轮模型看不到，于是误判成自己编的）。停止/超时也会补一条「执行已中断（用户停止）」，保证 `tool_calls`/`tool` 成对（缺了会 400）
- **「停止」真的中断服务器命令**：点停止时先发 MCP 标准的 `notifications/cancelled`（2 秒超时、失败不阻塞 UI），服务端对 `run_command` 先 `SIGINT`、2 秒不退再 `SIGKILL`，返回已捕获的部分输出和 `_meta.cancelled`；卡片显示「已取消 · 1.2s」
- 服务端新增 `tools/cancel`（`params.id`）作为通知的兼容替代；`run_command` 注册/清理有锁，取消后不再按超时处理
- 上下文估算与裁剪把 `tool` 消息算进去，裁剪只从 user 消息边界切，避免拆散 tool 对
- 版本 1.7.1 (26)

完整功能列表与用法见 [README](https://github.com/YYUN64/DeepSeekChat#readme)。
