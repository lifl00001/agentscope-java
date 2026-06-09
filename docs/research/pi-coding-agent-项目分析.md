# Pi Coding Agent 项目分析（earendil-works/pi）

> 分析对象：[github.com/earendil-works/pi](https://github.com/earendil-works/pi)
> 作者：Earendil Works（核心开发者 Mario Zechner / badlogicgames）
> 许可证：MIT
> 分析时间：2026 年 6 月

---

## 一、项目定位

Pi 是一个用 **TypeScript** 编写的**极简终端编码 Agent 框架**（terminal coding agent harness），定位类似 Claude Code、Aider、Codex CLI，但走"极简内核 + 强可扩展性"路线。

设计哲学（来自作者博文）：

> "Pi is aggressively extensible so it doesn't have to dictate your workflow."（激进地可扩展，从而不必为用户的工作流做决定）

作者明确**砍掉**了同类工具的许多"标配"功能：

- ❌ No MCP（不支持 Model Context Protocol）
- ❌ No sub-agents（无内置子 Agent）
- ❌ No permission popups（无确认弹窗，建议容器隔离）
- ❌ No plan mode（无内置计划模式）
- ❌ No built-in to-dos（无内置 TODO，避免误导模型）
- ❌ No background bash（无后台进程，建议用 tmux）

这些能力都可由 **Extensions** 自行实现或安装第三方包。

## 二、Monorepo 结构

项目采用 npm workspaces 的 monorepo，包含 4 个核心包，自底向上分层：

| 包 | 作用 |
|---|---|
| `@earendil-works/pi-ai` | 统一多家 LLM 提供商 API（OpenAI、Anthropic、Google、Bedrock、Vertex、Mistral、Cloudflare、xAI、OpenRouter、Vercel、DeepSeek、Groq、Cerebras、Hugging Face、Fireworks、Together、Kimi、MiniMax、Xiaomi MiMo、ZAI 等），含 OAuth、token/费用追踪、prompt cache、跨 provider 切换、图像生成 |
| `@earendil-works/pi-agent-core` | Agent 运行时：状态管理 + 工具调用 + 事件流，含上下文压缩（compaction）、session 持久化（JSONL）、steering / follow-up 队列、并行工具执行 |
| `@earendil-works/pi-coding-agent` | 交互式 CLI（终端编码 Agent 产品本体），含 4 个内置工具：`read` / `write` / `edit` / `bash`（外加 `grep` / `find` / `ls`），支持 interactive / print / json / rpc / sdk 五种运行模式 |
| `@earendil-works/pi-tui` | 终端 UI 库（差分渲染），是 Interactive Mode 的渲染底座 |

## 三、核心架构

### 1. Agent 循环（`packages/agent/src/agent-loop.ts`）

标准 ReAct 风格循环，但实现完全是**事件驱动流式**的：

```
prompt → agent_start → turn_start → user message
       → assistant streaming → tool_execution_* → toolResult
       → turn_end → agent_end
```

事件类型设计很细：`agent_start/end`、`turn_start/end`、`message_start/update/end`、`tool_execution_start/update/end`。所有事件订阅器 await in registration order，可作为强 barrier。

### 2. 消息模型（很有意思的设计）

**`AgentMessage` ≠ LLM Message**：

- `AgentMessage` 是富类型，可通过 declaration merging 扩展自定义消息类型（如 `notification`、UI 状态等）
- 通过 `transformContext()` 在送给 LLM 前做剪枝/压缩
- 通过 `convertToLlm()` 过滤 UI-only 消息，把自定义类型转成 LLM 能理解的 `user/assistant/toolResult`

```
AgentMessage[] → transformContext() → convertToLlm() → Message[] → LLM
```

### 3. Session = JSONL + Tree

Session 文件是 JSONL 行 + parentId 字段构成的树：

- 单文件原地分叉，无需复制
- `/tree` 命令在任意历史节点继续，切换分支
- `/fork` / `/clone` / `--fork <id>` 多种分叉入口
- Compaction 时只压缩内存中的上下文，磁盘 JSONL 永不丢失原始历史

### 4. 上下文压缩（Compaction）

自动 + 手动双模式：

- 触发：context overflow 时**自动恢复并重试**，或接近上限时**主动压缩**
- 损失有损：老消息摘要，新消息保留
- 可通过 Extension 自定义压缩策略

### 5. Steering / Follow-up 队列（亮点）

解决"Agent 在跑工具，用户想插话"的场景：

- **Steering**：当前 turn 工具跑完后立即注入，**打断** Agent 接下来要做的事
- **Follow-up**：等 Agent 完全停下后才注入，**追加**新任务
- 默认 `one-at-a-time`，可配置为 `all`（一次发完所有队列）

## 四、扩展机制（最重要的卖点）

Pi 把"可扩展性"作为一等公民，提供 4 种扩展方式：

### 1. Prompt Templates（最轻）

Markdown 文件 + Mustache 变量，放 `~/.pi/agent/prompts/`，用 `/name` 调用。

### 2. Skills（Agent Skills 标准）

遵循 [agentskills.io](https://agentskills.io) 规范的 `SKILL.md`，按需加载。作者认为这是 MCP 的替代方案——把工具做成有 README 的 CLI，让 Agent 通过 skill 调用，而不是协议层集成。

### 3. Extensions（最重）

TypeScript 模块，可注册：

- 自定义工具 / 命令 / 快捷键
- 事件处理器（`tool_call`、`message` 等钩子）
- 自定义编辑器、UI 组件、status line、overlay
- 完全自定义 compaction、permission gate、sub-agent、plan mode

### 4. Themes

CSS-like 主题文件，**热重载**。

### 5. Pi Packages

通过 `pi install npm:...` / `pi install git:...` 分发以上四种扩展，类似 npm 生态。

## 五、工程实践亮点

### 供应链安全（写得相当扎实）

- 所有直接依赖**精确锁版本**（pinned exact）
- `.npmrc`：`save-exact=true` + `min-release-age=2`（拒绝当天发布的依赖）
- `package-lock.json` 是 ground truth，pre-commit 拦截误提交
- 发布包带 `npm-shrinkwrap.json` 锁定传递依赖
- 依赖 lifecycle script 走**显式 allowlist**
- CI 跑 `npm audit --omit=dev` + `npm audit signatures`
- 默认 `--ignore-scripts` 安装

### TypeScript 严格度

- 仅使用 erasable TypeScript（Node strip-only mode）：禁止 `enum`、`namespace`、`parameter properties`、`import =`、`export =`
- 全部顶层 import，禁止 `await import()` 内联导入
- 禁用 `any`

### 多种运行模式

- `interactive`：默认 TUI
- `print` / `json`：一次性输出
- `rpc`：stdin/stdout JSONL 协议，供非 Node 集成
- `sdk`：嵌入到其他应用（[openclaw/openclaw](https://github.com/openclaw/openclaw) 是真实案例）

### 30+ 家 Provider 接入

除主流四家（OpenAI / Anthropic / Google / Bedrock）外，还接了 Codex、GitHub Copilot、Vertex、xAI、OpenRouter、ZAI、Kimi For Coding、MiniMax、小米 MiMo（中国/阿姆斯特丹/新加坡三种 Token Plan）等。**OAuth** 模块支持 Anthropic、OpenAI Codex、GitHub Copilot 的设备码/PKCE 流。

## 六、和 Claude Code / Aider 对比

| 维度 | Pi | Claude Code | Aider |
|---|---|---|---|
| 语言 | TypeScript | TypeScript | Python |
| 内置工具数 | 7（极少） | 多 | 多 |
| MCP 支持 | ❌ 故意不做 | ✅ | 部分 |
| 子 Agent | ❌ 交给扩展 | ✅ | ❌ |
| 计划模式 | ❌ 交给扩展 | ✅ | ❌ |
| 扩展机制 | TS Extension + Skill + Package | Skill + Hook + MCP | .aider.conf |
| Session 树状分叉 | ✅ JSONL tree | ❌ | ❌ |
| 多 Provider | 30+ | 4 | 6 |
| 自我更新 | `pi update --self` | ✅ | pipx |
| License | MIT | 专有 | Apache 2.0 |

Pi 的差异化点很清楚：**比 Claude Code 更开放（MIT 开源 + 任意 Provider），比 Aider 更现代化（TypeScript + 丰富 TUI + 树状 Session）**。

## 七、潜在使用场景

1. 想要 Claude Code 体验但不想被锁定到 Anthropic：Pi 是最接近的开源等价物
2. 构建自定义编码 Agent 产品：用 `pi-coding-agent` 的 SDK 模式嵌入
3. 多 Provider 路由 / 成本切换：`pi-ai` 单独可用
4. 研究 Agent Loop + Compaction + Steering：代码组织清晰，适合学习

## 八、值得读的源码入口

- `packages/agent/src/agent-loop.ts` — 主循环
- `packages/agent/src/agent.ts` — Agent 类、事件订阅、state
- `packages/agent/src/harness/compaction/` — 上下文压缩实现
- `packages/agent/src/harness/session/jsonl-repo.ts` — JSONL tree session
- `packages/coding-agent/src/core/tools/` — 7 个内置工具实现
- `packages/coding-agent/src/core/extensions/` — 扩展加载与生命周期
- `packages/ai/src/providers/` — 30+ Provider 适配层

## 九、一句话总结

Pi 是一个**架构清晰、哲学鲜明（极简 + 强扩展）、工程素养高（供应链安全做得很扎实）** 的开源编码 Agent，定位上更像"TypeScript 版的开源 Claude Code"，适合作为学习现代 Agent 架构的范本，也可作为自建 Agent 产品的底座。

## 参考

- 项目仓库：<https://github.com/earendil-works/pi>
- 官网：<https://pi.dev>
- 作者博文：<https://mariozechner.at/posts/2025-11-30-pi-coding-agent/>
- "what if you don't need MCP"：<https://mariozechner.at/posts/2025-11-02-what-if-you-dont-need-mcp/>
