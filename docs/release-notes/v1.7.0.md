## 修复（v1.7.0）

- **工具面板**：「测试连接」成功后，设置页 MCP 区显示工具清单（名称 + 一行说明，可折叠），每个工具一个开关（默认全开）；关掉的工具**不再发给模型**（下一轮请求立即生效），选择保存在本机。`run_command` 带「可执行任意命令」提示
- **工具卡片显示耗时与状态**：服务端 `tools/call` 结果新增 `_meta`（`dispatched` / `code` / `durationMs`，超时带 `timeout` + `timeoutSeconds`）；卡片右侧显示「完成 · 123ms」「失败 · 退出码 2 · 45ms」「超时（5s）」，不再只有完成/失败两个词
- **服务端信息（只读）**：MCP 区底部显示 `服务端：pocket-mcp 1.0.0 · 8 个工具`（来自 initialize 的 `serverInfo` 与 `tools/list` 数量），未连接时显示「未连接」
- 版本 1.7.0 (25)

完整功能列表与用法见 [README](https://github.com/YYUN64/DeepSeekChat#readme)。
