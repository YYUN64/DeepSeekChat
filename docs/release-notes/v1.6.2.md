## 修复（v1.6.2）

- **工具调用不再卡死**：MCP 工具请求读超时从 120 秒改为 **45 秒**；超时后工具卡片直接标成「执行超时（45 秒）：<工具名>」，原因写进本轮结果，对话继续（不会整轮挂住）
- **响应看门狗**：流式请求 60 秒没有收到任何数据就主动中止，提示「响应超时（60 秒没有收到数据），请重试」，不再出现界面静止不动
- **所有退出路径统一收尾**：正常结束、工具超时、达到轮数上限、异常、取消都会把 `streaming`/工具状态复位并停止前台服务，界面上一定有明确文案
- **服务端 `run_command` 加固**：`stdin=DEVNULL`、`TERM=dumb` / `PAGER=cat` / `SYSTEMD_PAGER=cat` / `SYSTEMD_COLORS=0` / `SYSTEMD_ASK_PASSWORD=false` / `DEBIAN_FRONTEND=noninteractive`，`timeout` 上限夹到 120 秒；日志打印命令摘要（≤200 字符）、耗时和退出码
- **流式渲染对齐官方 chat-demo 的部分**：流式过程中就渲染 Markdown（表格/代码块/公式边生成边出现，节流到 ~8Hz），代码块保留语言标识 + 一键复制，链接可点击（系统浏览器打开）
- 版本 1.6.2 (24)

完整功能列表与用法见 [README](https://github.com/YYUN64/DeepSeekChat#readme)。
