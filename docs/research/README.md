# Research Notes / 研究笔记

> 本目录存放 AgentScope-Java 团队整理的外部研究分析文档，与项目正式文档（`v1/`、`v2/`）分开管理。
> 这些文档不进入 Jupyter Book 的 TOC，主要作为团队选型与生态调研的内部参考。

## 文档列表

| 文档 | 简介 | 关键词 |
|---|---|---|
| [Pi Coding Agent 项目分析](./pi-coding-agent-项目分析.md) | earendil-works/pi 项目的深度分析：架构、扩展机制、对比 Claude Code/Aider | coding-agent, TypeScript, terminal, extensible |
| [Pi Coding Agent 安装使用指南](./pi-coding-agent-安装使用指南.md) | Pi 项目的安装、认证、CLI 参数、交互命令、扩展机制、典型用法示例 | coding-agent, installation, cli, usage |
| [2026 开源 Agent 框架生态全景](./2026开源Agent框架生态全景.md) | 当前 GitHub 上排名靠前的开源 Agent 框架盘点，分通用、编码、多 Agent 三大类 | ecosystem, ranking, github-stars, 2026 |
| [企业级 Agent 框架选型指南](./企业级Agent框架选型指南.md) | 企业级场景下的框架选型对比，重点是 AgentScope、LangGraph、Spring AI 等 | enterprise, selection, agentscope, langgraph, spring-ai |
| [Pi 复杂 Skill 开发方案 — cinno-daily-filter](./pi-coding-agent-复杂skill开发方案-cinno-daily-filter.md) | 用 pi 开发"流程长、参数可调、可运行"复杂 skill 的完整方案，含三档实现路径 | pi, complex-skill, design, cinno |

### PoC 骨架

| 目录 | 简介 |
|---|---|
| [poc-cinno-daily-filter/](./poc-cinno-daily-filter/) | cinno-daily-filter 在 Pi 上的最小可运行骨架（3 个 Skill + Extension + 配置 + 示例数据） |

## 阅读建议

- **想了解编码 Agent 这个细分领域**：从 [Pi 项目分析](./pi-coding-agent-项目分析.md) 入手，Pi 是个架构清晰、易读的范本
- **想直接上手用 Pi**：看 [Pi 安装使用指南](./pi-coding-agent-安装使用指南.md)，含最小可工作示例
- **想在 Pi 上做复杂 Skill 二次开发**：先看 [开发方案](./pi-coding-agent-复杂skill开发方案-cinno-daily-filter.md) 理解架构，再跑 [PoC](./poc-cinno-daily-filter/)
- **想做开源 Agent 框架调研**：直接看 [2026 框架生态全景](./2026开源Agent框架生态全景.md)
- **要给企业项目选型**：从 [企业级选型指南](./企业级Agent框架选型指南.md) 的"按场景推荐"章节入手
- **想了解 AgentScope 的定位**：[企业级选型指南](./企业级Agent框架选型指南.md) 的第一节 + 对比表

## 维护说明

- 这些是**研究快照**，数据采集时间在每篇文档顶部标注，可能随时间过时
- 文档使用中文，遵循项目根目录已有研究文档（如 `docs/harness-架构对比分析-业界范式vs项目实现.md`）的风格
- 不进入 Sphinx/Jupyter Book 构建（无 `_toc.yml` 注册）
- 修改正式文档请去 `docs/v1/` 或 `docs/v2/`
