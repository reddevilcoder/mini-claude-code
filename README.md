# **亲手造一个编程 Agent（非Toy可入简历的项目）**

[https://www.codecrush.cn/course/355707199399395328](https://www.codecrush.cn/course/355707199399395328)   **大模型应用开发基础**

[https://www.codecrush.cn/course/355709732859674624](https://www.codecrush.cn/course/355709732859674624)  **大模型应用开发进阶（Agent）**

[https://www.codecrush.cn/course/355711073371815936](https://www.codecrush.cn/course/355711073371815936) **大模型应用开发实战（mini Claude Code项目）**

欢迎来到《大模型应用开发》的项目实战部分。

这门实战课只有一个目标：带你从零开始，亲手写出一个能在命令行里运行的编程 Agent。它参照 Claude Code 来做，名字叫 **mini Claude Code**，命令行工具名是 `mcc`。

写完之后，你会得到一个真的能用的编程 Agent。

你在一个项目目录下输入 `mcc` 把它启动起来，再用自然语言告诉它"给下单接口加上参数校验"，或者"这个测试为什么失败了"。它会自己去读代码、改文件、跑测试，一步步把任务做完。

通过这个项目，你会把编程 Agent （特别是Claude Code）的底层实现原理弄得清清楚楚、明明白白！毕竟代码之下，没有秘密！

## **为什么我们选这个项目**

做实战项目可以选的方向有很多。我们为什么选一个编程 Agent？

因为 Agent 在编程领域，是目前落地最成功的场景。

Claude Code 里面有不少 harness 上的工程做法，学习价值很高。你以后做别的 Agent 项目时，完全可以把这些做法借鉴过去。

其实，市面上有很多Agent开发框架或者说harness 框架，比如 Pi、AgentScope等，大多是同一套 harness 理论的不同实现，彼此差别不大。你学透我们这个项目，基本上也就学透了所有这类框架的底层原理。

除此之外，mini Claude Code 里用到的理论知识，和《大模型应用开发进阶》课里讲的 Agent 开发技术，绝大部分是一一对应的。

如果你在学《大模型应用开发进阶》的时候，有哪个知识点没有弄得特别清楚，你就可以在 mini Claude Code 里去找对应的代码，前面也讲到，代码之下，没有秘密，通过阅读代码，消灭任何知识盲点！这比你查资料强百倍，毕竟代码才是一手知识来源。

反过来，你把 mini Claude Code 做一遍，也会把《大模型应用开发进阶》里的那些理论理解得更透彻、更细节。

如果要用一句话来说这个项目的价值，那就是：

学完它，Agent 开发的核心技术你就掌握了、Harness工程你也搞得清清楚楚了。Claude Code 这一类工具底层是怎么实现的，你也能够比任何人都清楚。

## **非Toy项目，可写入简历**

我也多方对比研究了市面上的一些大模型项目课、Agent项目课，做得非常Toy（玩具），究其原因还是因为场景的局限性。

前面提到编程才是Agent落地的最成功的场景，实现一个编程Agent也就意味着会涉及到非常多的技术。

而且，我们实现了Claude Code大多数功能，分析代码、实现复杂功能、修改bug、测试等等，都不在话下。

**甚至mini Claude Code的本身的一些代码，都是mini Claude Code自己开发的！！！**

可见它并不是一个只会修改一两行代码的Toy项目！

当年，我上大学的时候，自己手搓了一个搜索引擎，靠对搜索引擎的无盲点了解，收获了N多大厂Offer。

我相信，这个项目也可以让你对编程Agent、甚至通用Agent开发对技术有一个无盲点的了解，也可以写入到简历里，成为你的项目亮点！

## **关于项目的一些补充说明**

这里我们说明一下：RAG、MCP、多 Agent 协作，mini Claude Code 里没有做。这部分内容，我们会放到另一个项目里讲。

除此之外，我们的项目是使用Java + Spring AI开发的。Spring AI 只负责最底层的模型调用。Agent 的控制循环、工具分发、权限审批、上下文组装与压缩，这些核心逻辑全部由我们自己来写。

为什么我们不把这些都交给框架？因为一旦交出去，你就看不见框架到底替你做了什么，也就学不到东西。你自己写一遍，才能真正弄懂 Agent 开发里那些容易被框架盖住的细节。

虽然这是一个Java项目，但是，编程语言完全不是障碍，你不需要对Java有深入的了解，只需要能读懂就可以了。对于大多数程序员，这都不是一个问题！

而且，我们配有70+篇项目讲解文章，你光看文章都会有很多收获。

当然，你也可以参照文章和代码，把Java实现的min Claude Code 翻译成其他语言，在这个过程中，也会有很多收获。

## **这门课适合谁，怎么学**

这门课适合已经读过我们另外两门前置课的同学，也就是《大模型应用开发基础》和《大模型应用开发进阶》。
## **产品化增强（M0 / M9）**

**Headless 模式与 Eval（M0）**

```bash
mcc -p "修复 pricing.py 的 off-by-one" -d ./proj --output json --permission auto
python3 evals/run_evals.py --label baseline --repeat 3   # 需 OPENAI_API_KEY，会产生费用
```

- `--output json` 输出 `status / result / steps / tool_calls / usage{prompt_tokens, completion_tokens, cost_usd}`；退出码 0 完成、2 步数上限、130 取消、1 出错。
- `--permission auto` 在开启沙箱时为严格模式：沙箱不可用直接拒绝执行（fail closed），不会回落到裸跑。

**流式、取消、重试与成本（M9）**

- 流式输出：思维链与正文逐字上屏；增量只用于展示，流结束聚合成一条完整消息后才写入会话。
- 即时取消：Ctrl-C 通过 `CancelToken.onCancel` 回调直接断开 HTTP 流，半截消息不进历史。
- 重试：仅对 408/409/429/5xx/网络错误，指数退避 + 抖动，且**只在尚未输出任何内容时**重试；SDK 自带重试已关闭。
- 成本：`/cost` 展示真实 usage 与按配置单价折算的费用；`/context` 额外显示上次请求服务端报告的真实输入 Token。

```yaml
stream: true
retry: { maxAttempts: 3, initialBackoffMs: 1000 }
pricing:
  deepseek-flash: { input: 0.27, output: 1.10, cacheRead: 0.07 }   # 美元/百万 Token，以官网为准
```

**检查点、diff 预览与回退（M10）**

- 每轮用户输入带 `turnId`（写入 JSONL）；Edit/Write 在真正写盘前把原文件备份到 `~/.mcc/.../checkpoints/<sessionId>/<turnId>/`，写后记录哈希。
- 审批卡片展示统一 diff（`Tool.preview()` 先算出改动，再决定是否写入）。
- `/rewind`：选择某一轮，回退「文件 + 对话 / 仅对话 / 仅文件」。对话回退以 JSONL 为准并追加 `REWIND` 事件（不改写日志，压缩后依然正确，`/resume` 也能正确重放）；文件回退检测外部修改冲突，可跳过或强制覆盖。
- 局限：Bash 的副作用无法撤销，回退前会列出这些轮次执行过的命令作为提醒。

**MCP 客户端（M11）**

手写 JSON-RPC 2.0 over stdio（`Transport` 接口隔离传输层，可扩展 HTTP），支持 initialize 握手、`tools/list` 分页、`tools/call`、超时与 Ctrl-C 时发送 `notifications/cancelled`。在 `~/.mcc/mcp.json` 配置：

```json
{ "mcpServers": {
    "github": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"],
                "env": { "GITHUB_TOKEN": "${GITHUB_TOKEN}" }, "timeoutMs": 60000 } } }
```

- 只认全局白名单配置（不读项目目录，防止恶意仓库拉起进程）；密钥类 env 必须写成 `${VAR}` 引用。
- 工具暴露为 `mcp__<server>__<tool>`，名字清洗为 `^[a-zA-Z0-9_-]{1,64}$` 并处理冲突。
- 默认非只读、串行执行、走审批（可按单个工具“本会话放行”），Plan Mode 下被拦截；`readOnlyHint` 默认不可信，需在该 Server 上显式 `"trustReadOnlyHint": true`。
- 大结果走 ToolResultCompactor 落盘；`/mcp` 查看状态，`/mcp reconnect [name]` 重连；stderr 写入 `~/.mcc/logs/mcp-<name>.log`。
- ⚠ MCP Server 是本机进程，**不在 Bash 沙箱内运行**，只配置你信任的 Server。`--bare` 模式不加载 MCP。

**子代理（M12）**

- `Task`（`subagent_type`: explore / plan）：只读工具、上下文隔离、只回传结论；只读且并发安全，模型一次发多个 Task 会并行调研，不弹审批，Plan Mode 可用。
- `GeneralTask`：通用子代理，可读写与执行命令；派生需审批（可本会话放行），内部每个写操作仍走主审批钩子与主 CancelToken，写入记入主会话当前轮检查点（`/rewind` 可撤销）。
- 深度固定为 1（子代理工具表不含 Task/GeneralTask）；子代理不流式输出；继承主代理“已读”副本，但自己的读不回流；用量累计到主会话 `/cost`。
