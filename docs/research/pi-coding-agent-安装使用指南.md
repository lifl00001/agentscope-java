# Pi Coding Agent 安装使用指南

> 项目地址：<https://github.com/earendil-works/pi>
> 文档整理时间：2026 年 6 月
> 适用版本：Pi 最新发布版（参考 `pi --version`）

---

## 一、安装

### 方式 1：npm 全局安装（推荐）

```bash
npm install -g --ignore-scripts @earendil-works/pi-coding-agent
```

> `--ignore-scripts` 禁用依赖的 lifecycle scripts，Pi 本身**不需要** install scripts 来工作，这是供应链安全的考虑。

### 方式 2：安装脚本（macOS / Linux）

```bash
curl -fsSL https://pi.dev/install.sh | sh
```

### 方式 3：从源码运行（开发或想用最新未发布版本）

```bash
git clone https://github.com/earendil-works/pi.git
cd pi
npm install --ignore-scripts
npm run build

# 从源码直接跑（可在任意目录执行）
./pi-test.sh
```

### 方式 4：Bun 二进制（独立可执行文件）

发布的 release 包含 Bun 打包的单文件二进制，从 [GitHub Releases](https://github.com/earendil-works/pi/releases) 下载对应平台版本。

## 二、认证

Pi 支持两种认证方式。

### 方式 A：API Key（直接设置环境变量）

```bash
# 选一个你有的 provider
export ANTHROPIC_API_KEY=sk-ant-...
# 或
export OPENAI_API_KEY=sk-...
# 或
export DASHSCOPE_API_KEY=sk-...     # 阿里通义
export GOOGLE_API_KEY=...           # Gemini
# 等等
```

启动：

```bash
pi
```

### 方式 B：订阅登录（OAuth）

直接启动后在 Pi 内登录，复用你现有的订阅（不会消耗 API 额度）：

```bash
pi
/login        # 选择 provider
```

支持订阅登录的 provider：

- **Anthropic Claude Pro / Max**
- **OpenAI ChatGPT Plus / Pro（Codex）**
- **GitHub Copilot**

API key 支持的 provider（30+）：Anthropic、OpenAI、Azure OpenAI、DeepSeek、Google Gemini、Google Vertex、Amazon Bedrock、Mistral、Groq、Cerebras、Cloudflare AI Gateway / Workers AI、xAI、OpenRouter、Vercel AI Gateway、ZAI、OpenCode Zen / Go、Hugging Face、Fireworks、Together AI、Kimi For Coding、MiniMax、Xiaomi MiMo（含三种 Token Plan：中国 / 阿姆斯特丹 / 新加坡）等。

## 三、基本使用

### 1. 默认交互模式（最常用）

```bash
pi
```

进入 TUI 界面，直接对话。默认给模型 4 个工具：`read`、`write`、`edit`、`bash`。

带初始提示启动：

```bash
pi "List all .ts files in src/"
```

### 2. Print 模式（一次性输出，不进 TUI）

```bash
pi -p "Summarize this codebase"
```

支持管道输入：

```bash
cat README.md | pi -p "Summarize this text"
```

### 3. 文件参数（@ 前缀）

```bash
pi @prompt.md "Answer this"
pi -p @screenshot.png "What's in this image?"
pi @code.ts @test.ts "Review these files"
```

### 4. JSON 模式（流式事件 JSON Lines，便于程序消费）

```bash
pi --mode json "Generate a unit test for src/foo.ts"
```

### 5. RPC 模式（嵌入到非 Node 应用，stdin/stdout JSONL 协议）

```bash
pi --mode rpc
```

> 注意：RPC 用严格的 LF 分隔 JSONL framing，客户端必须只按 `\n` 分割，**不能用 Node 的 readline**（它会按 Unicode 分隔符分割）。

### 6. SDK 模式（嵌入到自己的 Node/TS 应用）

```typescript
import { AuthStorage, createAgentSession, ModelRegistry, SessionManager }
  from "@earendil-works/pi-coding-agent";

const authStorage = AuthStorage.create();
const modelRegistry = ModelRegistry.create(authStorage);

const { session } = await createAgentSession({
  sessionManager: SessionManager.inMemory(),
  authStorage,
  modelRegistry,
});

await session.prompt("What files are in the current directory?");
```

## 四、常用 CLI 参数

### 模型相关

```bash
pi --provider openai --model gpt-4o "Help me refactor"
pi --model openai/gpt-4o "..."              # provider/model 简写
pi --model sonnet:high "Solve this"         # thinking 级别简写
pi --thinking high "..."                    # off|minimal|low|medium|high|xhigh
pi --models "claude-*,gpt-4o"               # 限定 Ctrl+P 循环切换的模型
pi --list-models [search]                   # 列出可用模型
```

### 会话相关

```bash
pi -c                              # 继续最近一次会话
pi -r                              # 浏览并选择历史会话
pi --session <path|id>             # 用指定 session 文件或 ID
pi --fork <path|id>                # 从指定 session 分叉新会话
pi --no-session                    # 临时模式，不保存
pi --name "release audit"          # 给会话命名
pi --session-dir <dir>             # 自定义 session 存储目录
```

会话文件存在 `~/.pi/agent/sessions/`，按工作目录组织，**JSONL 树状结构**支持原地分叉。

### 工具相关

```bash
pi --tools read,grep,find,ls -p "Review the code"     # 只允许某些工具（只读模式）
pi --exclude-tools ask_question                        # 禁用某个工具
pi --no-builtin-tools                                  # 禁用所有内置工具（保留扩展）
pi --no-tools                                          # 禁用所有工具
```

内置工具：`read`、`bash`、`edit`、`write`、`grep`、`find`、`ls`。

### 扩展资源

```bash
pi -e ./my-ext.ts                  # 加载本地扩展
pi -e npm:@foo/pi-tools            # 加载 npm 包扩展
pi -e git:github.com/user/repo     # 加载 git 仓库扩展
pi --skill ./my-skill              # 加载 skill
pi --prompt-template ./review.md   # 加载 prompt template
pi --theme ./my-theme.json         # 加载主题
pi --no-extensions                 # 禁用扩展发现
pi --no-context-files              # 禁用 AGENTS.md/CLAUDE.md 自动加载
```

### 其他

```bash
pi --system-prompt "..."           # 替换默认 system prompt
pi --append-system-prompt "..."    # 追加到 system prompt
pi --export session.jsonl out.html # 导出 session 为 HTML
pi --verbose                       # 详细启动日志
pi -v / pi --version
pi -h / pi --help
```

### 环境变量

| 变量 | 作用 |
|---|---|
| `PI_CODING_AGENT_DIR` | 覆盖配置目录（默认 `~/.pi/agent`） |
| `PI_CODING_AGENT_SESSION_DIR` | 覆盖 session 存储目录 |
| `PI_PACKAGE_DIR` | 覆盖 package 目录 |
| `PI_OFFLINE=1` | 禁用所有启动网络操作（更新检查、telemetry） |
| `PI_SKIP_VERSION_CHECK=1` | 跳过版本更新检查 |
| `PI_TELEMETRY=0` | 关闭 install/update telemetry |
| `PI_CACHE_RETENTION=long` | 启用扩展 prompt cache（Anthropic 1h / OpenAI 24h） |
| `VISUAL` / `EDITOR` | Ctrl+G 调用外部编辑器 |

## 五、交互模式常用操作

### 命令（输入 `/`）

| 命令 | 作用 |
|---|---|
| `/login` `/logout` | OAuth 登录/登出 |
| `/model` | 切换模型 |
| `/scoped-models` | 启用/禁用 Ctrl+P 循环切换的模型 |
| `/settings` | 设置 thinking level、主题、transport 等 |
| `/resume` | 选历史会话 |
| `/new` | 新会话 |
| `/name <name>` | 命名会话 |
| `/session` | 显示会话信息 |
| `/tree` | 在会话树中跳转任意节点继续 |
| `/fork` | 从历史 user message 起新会话 |
| `/clone` | 复制当前分支到新会话 |
| `/compact [prompt]` | 手动压缩上下文 |
| `/copy` | 复制最近助手消息到剪贴板 |
| `/export [file]` | 导出会话为 HTML |
| `/share` | 上传为 GitHub gist + 可分享的 HTML 链接 |
| `/reload` | 重新加载扩展、skills、prompts、keybindings |
| `/hotkeys` | 显示所有快捷键 |
| `/changelog` | 显示版本历史 |
| `/quit` | 退出 |

### 编辑器

| 操作 | 方式 |
|---|---|
| 引用文件 | 输入 `@` 模糊搜索 |
| 路径补全 | Tab |
| 多行 | Shift+Enter（Windows Terminal 是 Ctrl+Enter） |
| 粘贴图片 | Ctrl+V（Windows 是 Alt+V），或拖入终端 |
| 跑 bash | `!command` 发输出给 LLM，`!!command` 不发 |
| 提交（队列） | Enter 排 steering / Alt+Enter 排 follow-up |
| 取消 | Escape |
| 调外部编辑器 | Ctrl+G |

### 关键快捷键

| 键 | 作用 |
|---|---|
| Ctrl+C | 清空编辑器 |
| Ctrl+C ×2 | 退出 Pi |
| Escape | 取消/abort |
| Escape ×2 | 打开 `/tree` |
| Ctrl+L | 模型选择器 |
| Ctrl+P / Shift+Ctrl+P | 循环切换 scoped 模型 |
| Shift+Tab | 循环 thinking level |
| Ctrl+O | 折叠/展开工具输出 |
| Ctrl+T | 折叠/展开 thinking blocks |

## 六、扩展与定制

### 配置目录

```
~/.pi/agent/                # 全局
├── settings.json
├── AGENTS.md              # 全局 context（同 CLAUDE.md）
├── SYSTEM.md              # 完全替换 system prompt
├── APPEND_SYSTEM.md       # 追加到 system prompt
├── prompts/               # 全局 prompt templates
├── skills/                # 全局 skills
├── extensions/            # 全局 extensions
├── themes/                # 全局 themes
├── sessions/              # 会话存储
├── keybindings.json
└── models.json            # 自定义 provider/model

.pi/                       # 项目级（覆盖全局）
├── settings.json
├── SYSTEM.md
├── prompts/
├── skills/
├── extensions/
└── themes/
```

### Context Files（类似 Claude Code 的 CLAUDE.md）

Pi 启动时按顺序加载 `AGENTS.md`（或 `CLAUDE.md`）：

1. `~/.pi/agent/AGENTS.md`（全局）
2. 父目录逐层向上查找
3. 当前目录

所有匹配文件**拼接**起来送给模型。

### Pi Packages（分发扩展）

```bash
pi install npm:@foo/pi-tools                          # 从 npm 装
pi install npm:@foo/pi-tools@1.2.3                    # 锁版本
pi install git:github.com/user/repo                   # 从 git 装
pi install git:github.com/user/repo@v1                # 锁 tag/commit
pi install git:git@github.com:user/repo               # SSH
pi install https://github.com/user/repo               # HTTPS
pi install ssh://git@github.com/user/repo             # SSH 协议
pi install ... -l                                      # 项目本地装（.pi/ 而非 ~/.pi/）

pi list                                                # 列出已装
pi update                                              # 更新 pi + 所有包
pi update --self                                       # 只更新 pi
pi update --extensions                                 # 只更新包
pi remove npm:@foo/pi-tools
pi config                                              # 启用/禁用资源
```

### 自己造一个 Pi Package

在 `package.json` 加 `pi` 字段：

```json
{
  "name": "my-pi-package",
  "keywords": ["pi-package"],
  "pi": {
    "extensions": ["./extensions"],
    "skills": ["./skills"],
    "prompts": ["./prompts"],
    "themes": ["./themes"]
  }
}
```

没有 `pi` manifest 时 Pi 会自动发现约定目录（`extensions/`、`skills/`、`prompts/`、`themes/`）。

## 七、Windows 平台特别注意

- 多行编辑用 **Ctrl+Enter**（Windows Terminal 默认 Alt+Enter 是全屏切换，需要重映射）
- 粘贴图片用 **Alt+V**（不是 Ctrl+V）
- 推荐用 WSL2 或 Git Bash 体验更接近 Linux

详细配置见 Pi 仓库的 [packages/coding-agent/docs/windows.md](https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/windows.md)。

## 八、典型使用场景示例

```bash
# 1. 启动交互式编码会话
cd ~/my-project
pi

# 2. 一次性提问（不进 TUI）
pi -p "Explain what src/index.ts does"

# 3. 用特定模型 + 高 thinking 解决难题
pi --model sonnet:high "Refactor this authentication module for better testability"

# 4. 只读模式审代码（无 bash / write）
pi --tools read,grep,find,ls -p "Review this PR for security issues"

# 5. 接管终端 pair programming
pi --name "feature-X" "Implement user authentication with JWT"

# 6. 用国产模型（阿里通义）
export DASHSCOPE_API_KEY=sk-...
pi --provider dashscope --model qwen3-coder-plus

# 7. 从上次的会话继续
pi -c

# 8. 把会话分享给别人看
# 在交互模式里输入 /share，得到一个 GitHub gist 链接
```

## 九、最小可工作示例（5 分钟跑通）

```bash
# 1. 安装
npm install -g --ignore-scripts @earendil-works/pi-coding-agent

# 2. 配 API key
export ANTHROPIC_API_KEY=sk-ant-your-key-here

# 3. 进项目目录
cd /path/to/your/project

# 4. 启动
pi

# 5. 在 TUI 里输入：
#    "List the top-level files and explain what this project does"
```

如果一切正常，Pi 会启动、显示 header、读到上下文文件（如果有 AGENTS.md）、然后接受你的输入并执行。

## 十、参考

- 项目仓库：<https://github.com/earendil-works/pi>
- 官网：<https://pi.dev>
- 文档：<https://pi.dev/docs/latest>
- npm 包：<https://www.npmjs.com/package/@earendil-works/pi-coding-agent>
- 配套分析：见同目录的 [pi-coding-agent-项目分析.md](./pi-coding-agent-项目分析.md)
