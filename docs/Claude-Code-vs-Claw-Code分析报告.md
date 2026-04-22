# Claude Code vs Claw Code 全面对比分析报告

> 分析日期：2026-04-20
> 对比仓库：
> - https://github.com/anthropics/claude-code (Anthropic 官方)
> - https://github.com/ultraworkers/claw-code (UltraWorkers 社区)

---

## 一、项目基本信息

| 维度 | Claude Code (Anthropic) | Claw Code (UltraWorkers) |
|---|---|---|
| **维护方** | Anthropic 官方 | 社区 (UltraWorkers)，非官方，不 affiliated with Anthropic |
| **语言/技术栈** | Node.js (npm 包) | Rust (主要) + Python (参考层) |
| **定位** | 商业化产品，官方 CLI 编码工具 | 开源 Rust 重写，强调自主编码代理协作 |
| **安装** | `curl`/`brew`/`winget` 一键安装 | 仅支持从源码构建 (`cargo build`) |
| **核心模型** | Anthropic Claude 系列 | 多模型：Claude、OpenAI、xAI、Ollama、DashScope 等 |

---

## 二、核心理念差异

### Claude Code — 官方产品，面向开发者日常使用

- 终端/IDE/GitHub 三端集成
- 人机协作，用户在终端中自然语言交互
- 强调安全性、企业级管理 (MDM)、权限控制

### Claw Code — 社区实验项目，面向自主代理协作

- 核心哲学："人类设定方向，claws 执行劳动"
- Discord 作为主要人机交互界面，而非终端
- 三层系统：OmX (工作流) + clawhip (事件路由) + OmO (多代理协调)
- 目标是证明代码仓库可以由代理团队自主构建和维护

---

## 三、功能对比

| 功能 | Claude Code | Claw Code |
|---|---|---|
| **多模型支持** | 仅 Claude 系列 | Claude + OpenAI + xAI + Ollama + DashScope 等 |
| **模型路由** | 不适用 | 前缀路由 (`openai/gpt-4.1-mini`, `qwen/qwen-max`) |
| **插件系统** | 官方插件 (code-review, security, commit 等) | 社区插件 + clawhip 生态 |
| **Hook 系统** | 完善 (stdin/stdout JSON 协议，条件匹配) | 有 (PreToolUse, PostToolUse 等) |
| **企业/MDM** | 完整支持 (企业管理设置不可被用户覆盖) | 无 |
| **权限模式** | 多级 (strict/lax/custom) | read-only / workspace-write / full-auto |
| **REPL** | 完整交互式 REPL | 有 REPL，支持 `/doctor`, `/help`, `/status` 等 |
| **会话管理** | 成熟 | 有 (`--resume latest`)，但被列为"启动脆弱" |
| **配置层级** | MDM > User > Project > Local | User > Project > Local |
| **MCP 支持** | 原生支持 | 支持 |
| **IDE 集成** | VS Code, JetBrains 扩展 | 无 |

---

## 四、项目成熟度

| 维度 | Claude Code | Claw Code |
|---|---|---|
| **稳定性** | 生产级，商用产品 | 实验性，快速迭代中 |
| **文档** | 官方文档站 + 完整 README | USAGE.md + PHILOSOPHY.md + ROADMAP.md，但仍在完善 |
| **测试** | 不公开源码，内部测试 | `cargo test --workspace`，有 mock parity harness |
| **Windows 支持** | 官方支持 (`winget`, PowerShell) | 有，但列出了多个已知痛点 |
| **生态** | Discord 社区 + 官方支持 | Discord + clawhip/oh-my-codex/oh-my-openagent 生态 |

---

## 五、Claw Code 的独特之处

1. **源码完全开放** — Rust 实现完全可见，Claude Code 的核心是闭源的 npm 包
2. **多模型优先** — 天然支持多家 LLM 提供商，不绑定单一厂商
3. **代理自主协作** — 专为多代理并行工作设计（Architect/Executor/Reviewer 模式）
4. **clawable 设计** — 追求"无需人类盯着终端"的完全自主运行
5. **Parity 追踪** — 有 `PARITY.md` 专门追踪与上游 Claude Code 的功能对齐状态

### Claw Code 的不足

1. **安装门槛高** — 必须从源码构建，无预编译二进制分发
2. **稳定性差** — 会话启动脆弱、事件系统不完善、恢复循环需手动干预
3. **crates.io 上有废弃存根** — `cargo install claw-code` 会安装错误的包
4. **生态依赖 Discord** — 人机交互深度绑定 Discord，不适合企业环境

---

## 六、多智能体实现深度对比

### 6.1 整体架构差异

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Claude Code (Anthropic)                      │
│                                                                     │
│   用户 ──→ 斜杠命令(Command) ──→ Task 工具 ──→ 子 Agent (并行)      │
│              ↓                          ↓                           │
│           技能(Skill)              结构化结果返回父会话               │
│              ↓                          ↓                           │
│           钩子(Hook)              命令综合呈现给用户                  │
│                                                                     │
│   特点: 命令编排，Agent 无自主决策权，结果由命令汇总                   │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        Claw Code (UltraWorkers)                     │
│                                                                     │
│   Discord 人 ──→ OmX(工作流) ──→ OmO(多代理协调)                    │
│                    ↓                ↓                               │
│              clawhip(事件路由)  Team/Task/Worker/Cron 注册表         │
│                    ↓                ↓                               │
│              git/tmux/PR 监控  Worker 状态机(6态)                    │
│                                     ↓                               │
│                              Worker 进程(独立终端)                   │
│                                                                     │
│   特点: 代理自主运行，人类不盯终端，事件驱动编排                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Claude Code 的多智能体实现

#### 核心机制：命令编排 + 子 Agent 委托

Claude Code 的多智能体基于**插件系统**实现，采用"命令编排、Agent 执行"的模式：

| 组件 | 职责 | 触发方式 |
|---|---|---|
| **斜杠命令 (Commands)** | 定义多阶段工作流，编排 Agent 启动 | 用户输入 `/command` |
| **专用 Agent (Agents)** | 接收聚焦任务，独立执行分析 | 命令通过 Task 工具调用 |
| **技能 (Skills)** | 被动知识注入，按上下文匹配 | 命令中提及技能名 |
| **钩子 (Hooks)** | 事件驱动的自动化拦截 | 工具调用、Agent 停止等事件 |

Agent 本质是 **Markdown 文件**，通过 YAML front matter 定义模型、工具、颜色和触发描述：

```markdown
---
model: sonnet
tools: ["Read", "Grep", "Glob"]
color: blue
description: >
  Explore the codebase structure and identify relevant files
  for the feature request. Triggered when...
---
You are a code explorer agent. Analyze the following...
```

#### 典型案例：feature-dev 插件的 7 阶段流水线

```
/feature-dev "添加用户认证功能"
    │
    ├─ 阶段1: 发现 — haiku Agent 检查 PR 资格
    ├─ 阶段2: 代码库探索 — 并行启动 code-explorer Agent
    ├─ 阶段3: 澄清问题 — 向用户确认需求
    ├─ 阶段4: 架构设计 — 并行启动 code-architect Agent (opus)
    ├─ 阶段5: 实现 — 主 Agent 编码
    ├─ 阶段6: 质量审查 — 并行启动 code-reviewer Agent
    └─ 阶段7: 总结 — 综合所有 Agent 结果呈现给用户
```

**关键设计原则**：

- **分析与决策分离** — Agent 生成数据，命令负责综合呈现
- **最小权限** — 每个 Agent 只获得所需工具
- **模型分层** — 简单任务用 haiku，复杂分析用 opus
- **并行覆盖** — 多个 Agent 在同一阶段并行运行以扩大覆盖面

#### 典型案例：pr-review-toolkit 的中心辐射模式

```
/review-pr
    │
    ├─ 注释审查 Agent (sonnet)
    ├─ 测试审查 Agent (sonnet)
    ├─ 错误处理审查 Agent (opus)
    ├─ 类型设计审查 Agent (sonnet)
    ├─ 代码质量 Agent (sonnet)
    └─ 简化建议 Agent (sonnet)
```

6 个 Agent 各自独立评估，命令根据变更文件性质决定启动哪些 Agent，支持顺序或并行执行。

#### Agent 间通信方式

- **单向委托**：命令 → Task 工具 → 子 Agent → 结果返回命令
- **Agent 之间不直接通信**
- 父会话始终保留控制权和最终决策权

### 6.3 Claw Code 的多智能体实现

#### 核心机制：四层注册表编排

Claw Code 采用完全不同的架构 — **运行时级别的进程编排**：

```
┌─────────────────────────────────────────────────┐
│            四层注册表控制面                        │
│                                                   │
│  TeamRegistry ──→ TaskRegistry ──→ WorkerRegistry │
│       ↓                                     ↑     │
│  CronRegistry ──→ 定时触发 ─────────────────┘     │
│                                                   │
│  Team: 团队定义、成员管理                          │
│  Task: 任务 CRUD、状态跟踪、消息历史               │
│  Worker: 进程状态机、就绪检测、提示词分发          │
│  Cron: 周期性任务调度                              │
└─────────────────────────────────────────────────┘
```

全部注册表通过 `OnceLock` 初始化为进程级单例，保证唯一性。

#### Worker 状态机（6 态）

```
Spawning ──→ TrustRequired ──→ ReadyForPrompt ──→ Running ──→ Finished
                 │                   │                │
                 ↓                   ↓                ↓
              Failed             Failed           Failed
```

| 状态 | 含义 | 能否接收提示词 |
|---|---|---|
| `Spawning` | 进程启动中，信任门未评估 | 否 |
| `TrustRequired` | 阻塞于信任确认对话框 | 否 |
| `ReadyForPrompt` | Agent 空闲，接受输入 | 是 |
| `Running` | 提示词已分发，Agent 处理中 | 否 (执行中守卫) |
| `Finished` | 正常完成或手动终止 | — |
| `Failed` | 启动失败/执行错误 | — |

**就绪检测机制**：

1. 检查终端缓冲区最后一行是否包含 Agent 风格提示符 (`>`, `›`, `❯`)
2. 显式排除 shell 提示符 (`$`, `%`, `#`)，防止误判
3. 状态持久化到 `.claw/worker-state.json`

#### Worker → Task 绑定流程

```rust
// 1. 创建 Worker 进程
WorkerRegistry.create(cwd, trusted_roots, auto_recover) → worker

// 2. 轮询观察屏幕文本，检测就绪状态
WorkerRegistry.observe(worker_id, screen_text) → worker

// 3. Worker 就绪后，分发任务提示词
WorkerRegistry.send_prompt(worker_id, Some(task.prompt)) → worker

// 4. 观察完成状态，回传结果
WorkerRegistry.observe_completion(worker_id, finish, tokens) → worker
```

**关键设计**：Worker 注册表不引用 Task ID — 解耦让编排器灵活绑定。

#### 三层外部协作系统

Claw Code 的多智能体不仅限于内部运行时，还有完整的外部协作栈：

| 层 | 工具 | 职责 |
|---|---|---|
| **工作流层** | oh-my-codex (OmX) | 将短指令转为结构化执行：规划关键词、执行模式、验证循环 |
| **事件路由层** | clawhip | 监控 git commits、tmux sessions、GitHub issues/PRs、Agent 生命周期 |
| **协调层** | oh-my-openagent (OmO) | 多 Agent 间的规划、交接、分歧解决、验证循环 |

> "When Architect, Executor, and Reviewer disagree, OmO provides the structure for that loop to converge instead of collapse."

#### 人类接口：Discord 而非终端

```
人类 (Discord/手机) ──→ 短指令 ──→ OmX 分解任务
                                    ↓
                              OmO 协调 Agent
                                    ↓
                              clawhip 监控事件
                                    ↓
                              Worker 执行 + 恢复
                                    ↓
                              推送结果到 Discord
```

### 6.4 多智能体关键差异对比

| 维度 | Claude Code | Claw Code |
|---|---|---|
| **编排模型** | 命令驱动（Markdown 工作流） | 运行时注册表驱动（Rust 状态机） |
| **Agent 粒度** | 子进程（Task 工具启动） | 独立 Worker 进程（完整生命周期） |
| **Agent 定义** | Markdown 文件 + YAML front matter | Rust 代码 + 注册表 + 配置 |
| **Agent 通信** | 单向：命令→Agent→命令 | 通过 TaskRegistry.append_output() 回传 |
| **并行机制** | 命令在同一阶段启动多个 Agent | WorkerRegistry 管理多个并行 Worker |
| **容错** | Agent 失败后命令处理 | 6 态状态机 + auto_recover + 4 种失败分类 |
| **就绪检测** | 不适用（即时启动） | 终端屏幕文本分析 + Agent 提示符检测 |
| **人类参与** | 用户在终端交互决策 | Discord 异步指令，Agent 自主恢复 |
| **外部生态** | 插件市场 + MCP | clawhip + OmX + OmO 三层外部协作 |
| **调度** | 无内置调度 | CronRegistry 周期性任务触发 |
| **状态持久化** | 会话级 | 文件快照（worker-state.json） |
| **Agent 自主权** | 低 — 命令控制一切 | 高 — Worker 可自主恢复和重试 |

### 6.5 设计哲学对比

**Claude Code**："人机协作，命令为王"

- Agent 是工具，人类是决策者
- 分析与决策严格分离
- 每一步都在人类视野中
- 适合需要精确控制的开发场景

**Claw Code**："人类定方向，Agent 做劳动"

- Agent 是劳动者，人类是方向制定者
- 事件驱动，无需盯着终端
- 自主恢复、自主协调
- 适合大规模自主开发实验

---

## 七、Claw Code 的起源：基于 Claude Code 源码泄露的洁净室重写

### 7.1 泄露事件

**2026年3月31日** — Anthropic 在发布 Claude Code 的 npm 包时，因缺少 `.npmignore` 文件，意外将一个 **59.8 MB 的 source map 文件** 发布到了公共 npm registry，暴露了约 **512,000 行专有 TypeScript 源码**。

### 7.2 Claw Code 与泄露源码的关系

Claw Code **不是** Claude Code 泄露源码的直接拷贝。它是一个**洁净室重写 (clean-room rewrite)**：

1. **Sigrid Jin**（claw-code 创建者）没有直接复制泄露的 TypeScript 代码
2. 他声称仅凭**对 Claude Code 架构的记忆**，通过 OhMyCodex 代理编排工具，在约 **2 小时内完成了 Python 重写**，随后在一天内完成 Rust 重写
3. 仓库的 README 明确声明：
   > "This repository does **not** claim ownership of the original Claude Code source material. This repository is **not affiliated with, endorsed by, or maintained by Anthropic**."

### 7.3 但它深度参考了泄露的源码

尽管声称是"洁净室"，多个证据表明泄露源码被广泛参考：

- **PARITY.md** 详细追踪了与"upstream"（上游 = Claude Code）的功能对齐状态，包括 9 条一致性通道
- **`UpstreamPaths` 解析器**会在文件系统中搜索 Claude Code 的 TypeScript 源码树（检查 `CLAUDE_CODE_UPSTREAM` 环境变量、`reference-source/claw-code`、`vendor/claw-code` 等路径）
- **Mock parity harness** 直接从上游 TS 源码中提取命令和工具定义来验证行为一致性
- **工具名称、CLI 参数、REPL 命令**与 Claude Code 高度一致（如 `claw -p` 兼容 Claude Code 的 `-p` 参数）

### 7.4 事件链路

```
Anthropic npm 发布事故
    │
    ├─ 59.8MB source map 泄露 512,000 行 TypeScript
    │       │
    │       ├─ DMCA 下架通知 → 镜像站点被删除
    │       │
    │       ├─ 安全研究员镜像 (Njengah/claude-code-source-code-leak)
    │       │
    │       └─ Sigrid Jin → 洁净室重写 → claw-code (ultraworkers)
    │               │
    │               └─ 185K+ stars，GitHub 历史上最快达到 100K stars 的仓库
    │
    └─ Anthropic 官方反应
            │
            ├─ 公开了 plugins/ 目录（官方插件生态）
            └─ 继续闭源核心运行时
```

### 7.5 关键证据总结

| 证据 | 含义 |
|---|---|
| PARITY.md 9-lane checkpoint | 系统性地对齐 Claude Code 的每个功能模块 |
| `UpstreamPaths` 解析器 | 运行时能定位并读取 Claude Code 的 TS 源码 |
| Mock parity harness | 用上游 TS 源码的行为作为测试基准 |
| `claw -p` 兼容模式 | CLI 参数层面保持兼容 |
| Cybernews 称其为"山寨货" | 媒体直接定性 |

### 7.6 结论

Claw Code **不是泄露源码的直接翻版**，而是一个**基于泄露源码深度理解的洁净室重写**。它的创建者声称没有直接复制代码，但：

- 泄露事件提供了完整的架构蓝图
- PARITY.md 和 compat-harness 证明了对上游行为的系统性追踪
- 2 小时完成 Python 重写的速度，只有在对原有架构极其熟悉的情况下才可能

本质上：**泄露让整个社区看到了 Claude Code 的"设计图纸"，Claw Code 是按照这份图纸用不同材料（Python/Rust）重新建造的房子**。是否真的是"洁净室"在法律上可能有争议，但在技术上它确实是独立编写的代码。

---

## 八、参考资源

### 官方合法资源

| 资源 | 链接 | 说明 |
|---|---|---|
| Claude Code 官方仓库 | https://github.com/anthropics/claude-code | 官方插件、配置示例、MDM 支持 |
| Claude Code 官方文档 | https://code.claude.com/docs | API 用法、Hook 协议、插件开发指南 |
| Claw Code 仓库 | https://github.com/ultraworkers/claw-code | 开源 Rust 重写，185K+ stars |

### 泄露事件相关报道

| 来源 | 链接 |
|---|---|
| Claude Code Source Snapshot (GitHub Mirror) | https://github.com/Njengah/claude-code-source-code-leak |
| Reddit 讨论 | https://www.reddit.com/r/LocalLLaMA/comments/1s8xj2e/claude_codes_source_just_leaked_i_extracted_its/ |
| Kilo Blog - 事件时间线 | https://blog.kilo.ai/p/claude-code-source-leak-a-timeline |
| Medium - 512,000 行源码泄露分析 | https://medium.com/the-ai-studio/claude-codes-512-000-lines-of-source-code-leaked-49941dfb13a7 |
| Wavespeed - What Is Claw Code? | https://wavespeed.ai/blog/posts/what-is-claw-code/ |
| Dev.to - 意外还是公关? | https://dev.to/varshithvhegde/the-great-claude-code-leak-of-2026-accident-incompetence-or-the-best-pr-stunt-in-ai-history-3igm |
| Layer5 - 工程深度分析 | https://layer5.io/blog/engineering/the-claude-code-source-leak-512000-lines-a-missing-npmignore-and-the-fastest-growing-repo-in-github-history |
| Cybernews | https://cybernews.com/tech/claude-code-leak-spawns-fastest-github-repo |
| OpenAI Tools Hub 评测 | https://www.openaitoolshub.org/en/blog/claw-code-open-source-review |

---

## 九、对 AgentScope Java 的启示

结合 agentscope-java 项目，两个项目提供了不同的多智能体编排思路：

1. **Claude Code 的 Pipeline 模式**对应 agentscope-java 中的 `SequentialPipeline` / `FanoutPipeline` — 阶段化、结构化
2. **Claw Code 的注册表+状态机模式**更接近一个完整的 Agent 编排运行时 — TaskRegistry/WorkerRegistry 的设计可以借鉴用于增强 agentscope-java 的调度能力
3. **Claw Code 的 Worker 就绪检测和容错机制**值得参考 — 当前 agentscope-java 的 Agent 是线程安全的但缺乏进程级的状态追踪
4. **Claude Code 的 Hook 系统**与 agentscope-java 的 Hook 系统理念相似，但 Claude Code 的 stdin/stdout JSON 协议更标准化
