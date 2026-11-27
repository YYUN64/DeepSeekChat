## 修复（v1.8.2）

- **V4 Pro 下每次发送都 400 的问题**：该模型不支持 `latest_reminder` 消息（API 返回 `The latest_reminder message is unavailable for model deepseek-v4-pro`），现在按模型能力跳过注入——V4 Pro 正常对话，Flash 仍保留日期提醒
- 同时把「V4 Pro 不支持服务端联网工具」也收进 `Models` 的能力判断（1.8.0 已生效，这次补齐单测）
- 版本 1.8.2 (30)

完整功能列表与用法见 [README](https://github.com/YYUN64/DeepSeekChat#readme)。
