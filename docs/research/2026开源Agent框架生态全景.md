# 2026 开源 Agent 框架生态全景

> 数据采集时间：2026 年 6 月
> 数据来源：GitHub / PyPI / npm 公开榜单，Firecrawl、CodeWiz、JetBrains、Morph 等行业综述

整个领域分成 **三大类**：通用 Agent 框架、编码 Agent CLI、多 Agent / 应用层平台。

---

## 一、通用 Agent 框架（按 GitHub Stars 排序）

| # | 框架 | Stars | 主语言 | 维护方 | 定位 |
|---|---|---|---|---|---|
| 1 | **Dify** | ~144k | Python/TS | LangGenius | 低代码可视化 Agent + LLM 应用平台 |
| 2 | **AutoGen** | ~58.7k | Python | Microsoft Research | 多 Agent 对话框架（已并入 Microsoft Agent Framework） |
| 3 | **CrewAI** | ~52.8k | Python | CrewAI Inc. | 角色扮演式多 Agent 编排 |
| 4 | **LangGraph** | ~33.9k | Python | LangChain | 状态机式有状态 Agent（生产首选） |
| 5 | **Semantic Kernel** | ~28.1k | C#/Python/Java | Microsoft | 企业 .NET + Azure 集成 |
| 6 | **Smolagents** | ~27.7k | Python | Hugging Face | 极简单 Agent（Code Agent 范式） |
| 7 | **OpenAI Agents SDK** | ~26.9k | Python | OpenAI | 官方轻量 SDK，100+ LLM 兼容 |
| 8 | **Haystack** | ~25.5k | Python | deepset | RAG-first 文档处理管线 |
| 9 | **Mastra** | ~24.8k | TypeScript | Mastra（YC W24） | TypeScript 生态首选 |
| 10 | **Google ADK** | ~20k | Python | Google | Gemini / Vertex 紧密集成 |

### 几个值得单独说的

**LangGraph（生产首选）**

- 月下载量 34.5M，是企业采用率最高的框架
- Klarna 客服 bot 替代了 853 个人力，省了 $60M
- 用 Uber、LinkedIn、BlackRock、Cisco、JPMorgan 等 400+ 公司
- 缺点：基于 LangChain，复杂场景下抽象层有点累赘

**AutoGen（已进入维护模式）**

- 2025 年 10 月微软宣布 AutoGen + Semantic Kernel 合并成 Microsoft Agent Framework，2026 Q1 GA
- AutoGen 现在只接收 bug fix 和安全补丁，新项目建议直接用 MAF

**CrewAI（多 Agent 入门最快）**

- 角色化（Role-playing）API，原型特别快
- 生产用反馈两极：抽象太厚，调试时看不到真正发给 LLM 的内容
- 2026 年 1 月才补上 streaming tool call

**Mastra（TypeScript 唯一选择）**

- 由 Gatsby 团队打造，YC + $13M seed（Paul Graham、Guillermo Rauch 投资）
- Replit Agent 3、Marsh McLennan（7.5 万员工）、SoftBank Satto 都在用
- 81 个 LLM provider、2436+ 模型（基于 Vercel AI SDK）

**Smolagents（HF 的"反框架"派）**

- 让 LLM 直接写 Python 代码完成工具调用，不走 JSON function calling
- 50 行配置就能跑起来一个 agent，单 Agent 自动化最快

## 二、编码 Agent CLI（垂直领域，2026 大爆发）

这一类是 Claude Code / Cursor / Codex 的开源替代。

| # | 项目 | Stars | 语言 | 定位 |
|---|---|---|---|---|
| 1 | **OpenClaw** | ~250k | TS | 通用 AI 助手（不只 coding），创 GitHub 历史增速记录 |
| 2 | **OpenCode (SST)** | ~160k | TS | Claude Code 的最直接开源克隆 |
| 3 | **OpenHands** | ~117k | Python | 前 OpenDevin，SWE-bench 分数最高 |
| 4 | **Claw Code** | ~48k | Python+Rust | Claude Code 架构的 clean-room 重写 |
| 5 | **Aider** | ~42k | Python | git-first 终端 pair programmer，最成熟 |
| 6 | **Continue.dev** | ~26k | TS | VS Code / JetBrains IDE 扩展 |
| 7 | **Cline / Roo Code / Kilo Code** | — | TS | VS Code 扩展家族（Cline 5M+ 安装） |
| 8 | **Pi (earendil-works/pi)** | — | TS | 极简可扩展派代表 |
| 9 | **Crush** | — | Rust | Charm 出品，TUI 派 |
| 10 | **Goose** | — | Rust | Block 出品 |

### 几个亮点

**OpenClaw（现象级）**

- 60 天内打破了 React 用 10 年创下的 star 记录，250k+ stars
- 实际定位偏"通用生活助手"，coding 只是其中一块
- 内部 prompt 体系被广泛研究（"Karpathy 四原则"）

**OpenCode（SST）**

- 真正的 Claude Code 开源等价物，TUI 体验最像
- 7.5M 月活用户，160k stars，900+ contributors
- 75+ LLM provider，BYOK

**OpenHands（前 OpenDevin）**

- SWE-bench Verified 分数在开源 coding agent 里最高
- 学术界 / benchmark 圈的标准参考实现

**Aider（最老牌）**

- 2023 年就有了，git 工作流深度集成
- 编辑、修改、commit 全自动，仓库层面 pair programming 标杆

## 三、其他重要分类

### 多 Agent 应用 / 自治 Agent

- **AutoGPT**（经典老牌）：早期自治 Agent 鼻祖
- **Paperclip**（55k+ stars）：Agent 公司化编排框架
- **Agency** / **agency-agents**（107k+ stars）：Agent 集合平台

### 中文圈热门

- **ByteDance DeerFlow**：字节开源，2026 年 2 月登顶 GitHub Trending
- **Py（字节）**：多模态 Agent
- **Xiaomi MiMo**：小米开源 LLM，已在 Pi/Aider 等框架中作为 provider 接入
- **AgentScope**：阿里通义实验室，2.0 大版本刚发布（详见企业级选型指南）

### 企业级 / 商业开源

- **Microsoft Agent Framework**（AutoGen + Semantic Kernel 合并版）
- **Amazon Strands Agents**（AWS 出品）
- **Salesforce Agentforce**（CRM 集成）

### MCP 生态（虽然 Pi 故意不支持，但已成事实标准）

- **MCP 服务器列表**（modelcontextprotocol/servers）
- 各框架（LangGraph、Claude Code、Cursor 等）纷纷原生支持

## 四、选型决策树

```
你的需求是什么？
│
├─ 单 Agent + 想最快跑起来 ────────► Smolagents
│
├─ 多 Agent 编排
│   ├─ 角色化 / 业务流 ──────────► CrewAI
│   ├─ 复杂状态机 / 生产级 ────────► LangGraph
│   └─ 对话式协作 / 研究 ─────────► AutoGen 或 Microsoft Agent Framework
│
├─ 编码 Agent
│   ├─ 想要 Claude Code 体验但开源 ─► OpenCode 或 Pi
│   ├─ IDE 内嵌 ────────────────► Cline / Continue.dev
│   ├─ 终端 pair programming ─────► Aider
│   └─ 学术研究 / SWE-bench ───────► OpenHands
│
├─ TypeScript 技术栈 ─────────────► Mastra（通用） / Pi（编码）
├─ .NET / Azure 企业 ─────────────► Semantic Kernel
├─ RAG / 文档处理 ────────────────► Haystack
├─ 低代码 / 可视化 ───────────────► Dify
└─ Google 生态 ─────────────────► Google ADK
```

## 五、关键趋势（2026 年看）

1. **"反 LangChain"运动持续**：Mastra、Smolagents、Pydantic AI、Agent SDK 等都在走"少抽象"路线
2. **AutoGen + Semantic Kernel 合并**为 Microsoft Agent Framework，是 2026 最大架构调整
3. **Coding Agent 进入"BYOK + 多 Provider"时代**：OpenCode / Pi / Aider 都默认支持 30+ 模型
4. **OpenClaw 现象**：单一开源项目 250k stars，证明 AI Agent 已破圈到非开发者人群
5. **MCP 成为事实标准**，但仍有"反 MCP"派（Pi、Smolagents）坚持 CLI + Skill 路线
6. **企业采用率**：Gartner 预测 2026 年底 40% 企业应用将含 task-specific AI agent（2025 年仅 5%）

## 六、参考来源

- Firecrawl：《The best open source frameworks for building AI agents in 2026》<https://www.firecrawl.dev/blog/best-open-source-agent-frameworks>
- CodeWiz：《Java AI agent frameworks in 2026》<https://codewiz.info/blog/java-ai-agent-frameworks-2026/>
- Morph：《Best AI Coding Agents 2026》<https://www.morphllm.com/best-ai-coding-agents-2026>
- JetBrains Blog：《Top Agentic Frameworks for Building Applications 2026》<https://blog.jetbrains.com/pycharm/2026/06/top-agentic-frameworks-for-building-applications-2026/>
- OSS Insight：《Trending AI Repositories on GitHub》<https://ossinsight.io/trending/ai>
