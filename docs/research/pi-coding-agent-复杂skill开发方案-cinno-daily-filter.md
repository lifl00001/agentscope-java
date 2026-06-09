# 用 Pi 复杂 Skill 开发方案 — 以 cinno-daily-filter 为例

> 目标：在 [Pi Coding Agent](https://github.com/earendil-works/pi) 上实现"流程长、参数可调、可运行"的复杂 Skill
> 示例项目：CINNO 半导体日报筛选（cinno-daily-filter）
> 文档版本：2026-06

---

## 一、背景与诉求

`cinno-daily-filter` 是一个典型的复杂 Skill：

- **流程长**：预处理 → 聚类去重 → 分类筛选 → 选稿 → 输出 21 条日报
- **多 Skill 协作**：依赖 `cinno-preprocess`、`cinno-cluster-dedup`
- **参数可调**：目标条数、类别权重、去重阈值、排除词等
- **需要可运行**：用户调整参数后能一键触发

用户诉求三要素：

1. 用 pi 做开发底座
2. 把部分 skill 内容开放给用户可调整
3. 然后可以运行这个 skill

## 二、pi 能力对照表

| 诉求 | pi 是否支持 | 实现机制 |
|---|---|---|
| 流程长 / 复杂 | ✅ | Skill 是 Markdown 文档，描述完整 SOP；可调用 bash / read / write / 自定义工具；可嵌套调用其他 skill |
| 用户可调整参数 | ✅ | 三种粒度：①改 Markdown 配置；②Prompt Template 变量；③Extension 提供交互 UI |
| 可运行触发 | ✅ | `/skill:name` 触发；或 Extension 注册命令；或 RPC/SDK 程序化触发 |
| 结构化输出（如 21 条日报） | ✅ | 模型 + system prompt 约束 + 写文件；或 SDK 模式拿到 `message_end` 事件做解析 |
| 多 skill 协作 | ✅ | 一个 skill 文档里可写 "Step N: 调用 cinno-cluster-dedup skill" |
| 可视化调试 / Trace | ✅ | TUI 显示所有 tool call；`--mode json` 拿全量事件流；Extension 可加自定义 status line |

**pi 不能直接做到的**（但 Extension 可以补）：

- ❌ 没有内置表单 UI（要自己用 Extension 写）
- ❌ 没有 Web 控制台（要 `--mode rpc` + 自建前端，或 `pi-share-hf` 分享 session）
- ❌ 没有 GUI 工作流编辑器（不像 Dify）

## 三、三档实现路径

按"用户可调程度"递增，对应不同的开发成本。

### 方案 1：轻量级 — Skill + 配置文件（开发 5 分钟）

把可调参数抽到一个 JSON 配置文件，skill 读它。

```
.pi/skills/cinno-daily-filter/
├── SKILL.md              # 流程描述
└── config.json           # 用户可调参数
```

`config.json`（用户改这个）：

```json
{
  "target_count": 21,
  "categories": ["半导体设备", "晶圆代工", "存储", "封装测试"],
  "dedup_threshold": 0.85,
  "exclude_keywords": ["广告", "招聘"]
}
```

`SKILL.md`：

```markdown
---
name: cinno-daily-filter
description: CINNO 半导体日报筛选
---

# CINNO 半导体日报筛选

## 触发条件
当用户要求生成 CINNO 半导体日报时使用此 skill。

## 步骤
1. 读取 `.pi/skills/cinno-daily-filter/config.json` 获取用户配置
2. 读取当日源数据 (normalized_articles.jsonl)
3. 调用 cinno-cluster-dedup skill 做聚类去重
   - 使用 config.dedup_threshold 作为阈值
4. 主 Agent 对每个簇代表做分类：
   - 仅保留 config.categories 内的类别
   - 排除含 config.exclude_keywords 的条目
5. 选稿：按类别权重产出 config.target_count 条
6. 写入 output/cinno-daily-{date}.md
```

用户调整 → 改 config.json → `/skill:cinno-daily-filter` 运行。

### 方案 2：中等 — Skill + Prompt Template（推荐）

参数通过 Prompt Template 变量暴露，用户在 TUI 里 `/template` 选择并填变量。

```
.pi/
├── skills/cinno-daily-filter/SKILL.md
├── prompts/cinno-daily.md             # 模板，带 {{变量}}
└── prompts/cinno-daily-config.md      # 配置向导
```

`prompts/cinno-daily.md`：

```markdown
请按以下参数生成 CINNO 半导体日报：

- 目标条数：{{count}}
- 类别范围：{{categories}}
- 去重阈值：{{threshold}}

按 cinno-daily-filter skill 的流程执行。
```

用户在 pi 里：

```
/cinno-daily
# pi 弹出变量输入，填好后再触发 skill
```

Prompt Template 还支持默认值和必填，比纯 JSON 友好。

### 方案 3：重量级 — Skill + Extension（最强）

用 TypeScript Extension 注册自定义命令、交互 UI、专用工具，提供"按钮式"操作。

```typescript
// .pi/extensions/cinno/index.ts
export default function (pi: ExtensionAPI) {
  // 1. 注册配置 UI（替换编辑器，弹表单）
  pi.registerCommand("cinno:config", {
    description: "配置日报参数",
    execute: async () => {
      const config = await pi.ui.form({
        title: "CINNO 日报配置",
        fields: [
          { name: "count", label: "目标条数", type: "number", default: 21 },
          { name: "categories", label: "类别", type: "multiselect",
            options: ["半导体设备", "晶圆代工", "存储", "封装测试"] },
          { name: "threshold", label: "去重阈值", type: "slider",
            min: 0.5, max: 1.0, step: 0.05, default: 0.85 }
        ]
      });
      await fs.writeFile(".pi/cinno-config.json", JSON.stringify(config));
      pi.notify("配置已保存");
    }
  });

  // 2. 注册专用工具（让 LLM 调用）
  pi.registerTool({
    name: "cinno_filter",
    description: "按配置筛选 CINNO 半导体新闻",
    parameters: Type.Object({
      date: Type.String({ description: "日期 YYYY-MM-DD" })
    }),
    execute: async (_, params) => {
      const config = JSON.parse(await fs.readFile(".pi/cinno-config.json"));
      const articles = await loadArticles(params.date);
      const deduped = await clusterDedup(articles, config.threshold);
      const filtered = classify(deduped, config);
      const selected = selectTop(filtered, config.count);
      return { content: [{ type: "text", text: JSON.stringify(selected) }] };
    }
  });

  // 3. 注册一键运行命令
  pi.registerCommand("cinno:run", {
    description: "生成今日 CINNO 日报",
    execute: async () => {
      pi.prompt("调用 cinno_filter 工具，生成今日日报");
    }
  });

  // 4. 监听 tool_call 事件做审计 / 持久化
  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName === "cinno_filter") {
      await auditLog(event);
    }
  });
}
```

用户在 TUI 里：

```
/cinno:config    # 弹表单调整参数
/cinno:run       # 一键运行
```

也可以让 LLM 自然语言触发："生成今日 CINNO 日报" → 模型自动调 `cinno_filter` 工具。

## 四、cinno-daily-filter 推荐架构

针对这个具体场景，推荐 **方案 3 的分层子集**：

```
your-project/
├── .pi/
│   ├── skills/
│   │   ├── cinno-preprocess/SKILL.md          # 预处理
│   │   ├── cinno-cluster-dedup/SKILL.md       # 聚类去重
│   │   └── cinno-daily-filter/SKILL.md        # 主筛选
│   ├── prompts/
│   │   └── cinno-daily.md                     # 模板（暴露 count/categories 等）
│   └── extensions/
│       └── cinno-tools/index.ts               # 注册工具 + 配置命令
├── data/
│   ├── normalized_articles.jsonl              # 输入
│   └── cinno-config.json                      # 用户可调配置
└── output/
    └── cinno-daily-{date}.md                  # 输出
```

### 关键设计点

**1. 多 skill 协作通过"文档引用"实现**

`cinno-daily-filter/SKILL.md` 里直接写：

```markdown
## 步骤 3：聚类去重
调用 `/skill:cinno-cluster-dedup` 处理上一步的输出。
阈值取自 `data/cinno-config.json` 的 dedup_threshold 字段。
```

pi 的 LLM 看到这种描述会自动调用对应 skill 或工具。**不需要硬编码调用链**——这正是 pi 哲学：模型自主，而不是框架编排。

**2. 用户可调的"分层"**

| 调整对象 | 调整方式 | 适合谁 |
|---|---|---|
| 选稿数量、类别权重 | `/cinno:config` 表单 | 终端用户（无需懂代码） |
| Prompt 措辞、分类标准 | 编辑 SKILL.md | 分析师（懂 Markdown 即可） |
| 算法逻辑（去重算法、分类规则） | 改 Extension 工具代码 | 开发者 |

**3. 复杂流程的"分阶段"处理**

pi 的"工具并行执行 + steering"很合适长流程：

```
跑 cinno_filter 工具
├─ LLM 先输出"我打算分类这 100 条新闻"
├─ 用户在 TUI 里用 Enter 排 steering："这次重点关注存储芯片"
├─ 工具执行完后，下一轮 LLM 看 steering 调整选稿
└─ 输出 21 条
```

用户可以在长流程运行中**实时干预**，这是 pi 的 steering 机制天然支持的。

**4. 调试与回放**

```bash
/export cinno-daily-2026-06-09.html   # 跑完后导出 HTML
/share                                 # 上传 gist 分享
/tree                                  # 跳到中间某步重新分叉
```

完整 session 留痕，方便优化流程。

## 五、和原生 Claude Code + skill 方案的差异

| 维度 | 直接用 Claude Code skill | 用 pi 重做 |
|---|---|---|
| 用户可调参数 | 改 SKILL.md 文本 | 表单 / 模板 / 配置文件三种粒度 |
| 多 Provider（国产模型） | 受限（主要 Anthropic） | ✅ DashScope/Qwen 等原生支持 |
| 自定义工具 | 需 MCP server | Extension 一个 .ts 文件搞定 |
| 交互 UI | 基本只能靠 prompt | Extension 可写自定义 UI |
| 离线 / 私有部署 | ❌ | ✅ 完全开源，可内网部署 |
| 学习成本 | 低（生态成熟） | 中（要理解 Extension） |
| 国内合规 | ❌ | ✅ |

## 六、落地路径

| 阶段 | 时间 | 产出 |
|---|---|---|
| **Step 1：验证流程** | 半天 | 方案 1，SOP 改写成 SKILL.md，config.json 可调，pi 跑通 |
| **Step 2：暴露参数** | 1–2 天 | 方案 2，加 Prompt Template，`/cinno-daily` 触发，国产模型跑通 |
| **Step 3：交互化** | 按需 | 方案 3，Extension 提供表单 UI 和专用工具，一键运行 |
| **Step 4：生产化** | 按需 | `--mode rpc` + 自建 Web 前端，或打包成 Pi Package 分发 |

## 七、PoC 骨架

完整的 PoC 文件结构见同目录的 [`poc-cinno-daily-filter/`](./poc-cinno-daily-filter/)，包含：

- 三个 SKILL.md（预处理、去重、主筛选）
- Prompt Template（cinno-daily.md）
- Extension TypeScript 代码骨架（cinno-tools）
- 配置文件示例（cinno-config.json）
- 示例输入（sample-articles.jsonl）与预期输出
- 运行说明 README

把 `poc-cinno-daily-filter/pi-structure/` 的内容复制到目标项目的 `.pi/` 目录即可运行。

## 八、一句话结论

**pi 比 Claude Code 更适合做这类"复杂 skill + 用户可调"的二次开发**——Extension 机制允许把"可调参数"做成真正的 UI 控件，而不仅是 prompt 占位符；同时 Skill 是纯 Markdown，分析师也能直接改流程。

## 九、参考

- Pi 项目仓库：<https://github.com/earendil-works/pi>
- Pi Extension 文档：<https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/extensions.md>
- Pi Skill 文档：<https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/skills.md>
- 配套分析：[pi-coding-agent-项目分析.md](./pi-coding-agent-项目分析.md)
- 配套使用指南：[pi-coding-agent-安装使用指南.md](./pi-coding-agent-安装使用指南.md)
