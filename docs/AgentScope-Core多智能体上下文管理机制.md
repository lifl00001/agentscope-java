# AgentScope Core 多智能体上下文管理机制

## 一、基础架构：三层状态体系

```
┌───────────────────────────────────────────────────────┐
│  Session（会话存储层）                                   │
│  ├── InMemorySession  → ConcurrentHashMap（进程内）      │
│  └── JsonSession       → 文件系统（增量追加，可持久化）    │
├───────────────────────────────────────────────────────┤
│  StateModule（状态模块接口）                              │
│  ├── saveTo(session, sessionKey)                       │
│  ├── loadFrom(session, sessionKey)                     │
│  └── loadIfExists(session, sessionKey) → boolean       │
├───────────────────────────────────────────────────────┤
│  Memory（对话记忆接口，extends StateModule）               │
│  └── InMemoryMemory → CopyOnWriteArrayList<Msg>         │
└───────────────────────────────────────────────────────┘
```

### 1.1 Session 接口

Session 是最底层的持久化存储接口，负责保存和恢复组件状态。

**核心 API：**

```java
public interface Session {
    // 保存单个状态值（全量替换）
    void save(SessionKey sessionKey, String key, State value);
    // 保存列表值（JsonSession 增量追加，InMemorySession 全量替换）
    void save(SessionKey sessionKey, String key, List<? extends State> values);
    // 读取
    <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type);
    <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType);
    // 判断会话是否存在
    boolean exists(SessionKey sessionKey);
    // 删除
    void delete(SessionKey sessionKey);
}
```

**两种实现：**

| 实现 | 存储位置 | 列表保存策略 | 持久化 | 适用场景 |
|---|---|---|---|---|
| `InMemorySession` | `ConcurrentHashMap` | 全量替换 | 否（JVM 退出丢失） | 单进程、开发测试 |
| `JsonSession` | 文件系统 | 增量追加（按行数对比） | 是（文件持久化） | 生产环境、跨重启 |

### 1.2 StateModule 接口

所有有状态的组件都实现 `StateModule` 接口，提供状态序列化/反序列化能力。

```java
public interface StateModule {
    default void saveTo(Session session, SessionKey sessionKey) {}    // 默认空实现
    default void loadFrom(Session session, SessionKey sessionKey) {}  // 默认空实现
    default boolean loadIfExists(Session session, SessionKey sessionKey) {
        if (session.exists(sessionKey)) {
            loadFrom(session, sessionKey);
            return true;
        }
        return false;
    }
}
```

### 1.3 Memory 接口

Memory 是对话历史的存储接口，继承自 StateModule，额外提供消息的增删查操作。

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

`InMemoryMemory` 内部使用 `CopyOnWriteArrayList<Msg>`，线程安全。

### 1.4 ReActAgent 的状态组成

每个 `ReActAgent` 内部管理以下有状态组件：

| 组件 | 类型 | 说明 |
|---|---|---|
| `memory` | `Memory` | 对话历史（Msg 列表） |
| `toolkit` | `Toolkit` | 工具集 + 激活的工具组（ToolGroup） |
| `planNotebook` | `PlanNotebook` | 计划任务状态（可选） |
| `hooks` | `List<Hook>` | 拦截器列表（不持久化） |
| `interruptFlag` | `AtomicBoolean` | 中断标志（不持久化） |

通过 `StatePersistence` 控制哪些组件需要持久化：

```java
ReActAgent.builder()
    .statePersistence(StatePersistence.builder()
        .memoryManaged(true)         // 默认 true，持久化 Memory
        .toolkitManaged(true)        // 默认 true，持久化 Toolkit 激活组
        .planNotebookManaged(true)   // 默认 true，持久化 PlanNotebook
        .build())
    .build();
```

ReActAgent 的 `saveTo` / `loadFrom` 实现：

```java
// 保存时
public void saveTo(Session session, SessionKey sessionKey) {
    session.save(sessionKey, "agent_meta",
        new AgentMetaState(getAgentId(), getName(), getDescription(), sysPrompt));
    if (statePersistence.memoryManaged()) {
        memory.saveTo(session, sessionKey);  // 保存 memory_messages
    }
    if (statePersistence.toolkitManaged() && toolkit != null) {
        session.save(sessionKey, "toolkit_activeGroups",
            new ToolkitState(toolkit.getActiveGroups()));
    }
    if (statePersistence.planNotebookManaged() && planNotebook != null) {
        planNotebook.saveTo(session, sessionKey);
    }
}
```

---

## 二、SubAgentTool 模式 — 隔离上下文 + 会话持久化

### 2.1 核心组件

```java
// agentscope-core 中的核心类
io.agentscope.core.tool.subagent.SubAgentTool     // AgentTool 实现
io.agentscope.core.tool.subagent.SubAgentConfig   // 配置
io.agentscope.core.tool.subagent.SubAgentProvider // 工厂接口
```

### 2.2 架构图

```
父智能体 (Supervisor)
  └── Toolkit
        └── SubAgentTool (工具名: call_expert)
              ├── SubAgentProvider<Agent> — 工厂接口，每次调用 provide() 创建新实例
              └── SubAgentConfig
                    ├── session: InMemorySession / JsonSession  // 会话存储
                    ├── forwardEvents: true/false                // 事件转发
                    └── streamOptions: StreamOptions             // 流式配置
```

### 2.3 注册方式

```java
// 方式一：默认配置（推荐，每次创建新实例）
toolkit.registration().subAgent(() -> ReActAgent.builder()
    .name("ResearchAgent")
    .model(model)
    .sysPrompt("You are a research expert...")
    .toolkit(researchToolkit)
    .memory(new InMemoryMemory())
    .build()
).apply();

// 方式二：自定义配置
toolkit.registration().subAgent(
    () -> researchAgent,
    SubAgentConfig.builder()
        .toolName("ask_expert")
        .description("Ask the expert a question")
        .forwardEvents(true)
        .session(new JsonSession(Path.of("sessions")))
        .build()
).apply();

// 方式三：工具分组
toolkit.registration().subAgent(() -> worker1).group("workers").apply();
```

### 2.4 工具参数

SubAgentTool 暴露两个参数给 LLM：

| 参数 | 必填 | 说明 |
|---|---|---|
| `message` | 是 | 发给子智能体的消息内容 |
| `session_id` | 否 | 省略则开始新会话；提供则继续已有会话 |

工具名自动从 Agent 名称派生：`"ResearchAgent"` → `"call_researchagent"`

### 2.5 上下文流转过程

#### 第一次调用（新会话）

```
1. 父智能体 LLM 决定调用 call_expert 工具
   参数: { "message": "分析项目依赖", "session_id": null }

2. SubAgentTool.callAsync() 执行：
   ├── session_id == null → isNewSession = true → 生成 UUID
   ├── agent = agentProvider.provide()
   │     ← 创建全新 Agent 实例（独立 Memory、Toolkit、sysPrompt）
   ├── isNewSession → 跳过 loadState
   ├── 构建 Msg → agent.call(userMsg)
   │     子智能体内部：
   │     ├── memory.addMessage(userMsg)    // 消息加入子智能体的 Memory
   │     ├── prepareMessages() → [sysPrompt + memory]  // 发给 LLM
   │     └── 返回结果
   ├── 执行完毕 → agent.saveTo(session, sessionId)
   │     保存: agent_meta + memory_messages + toolkit_activeGroups
   └── 返回 ToolResultBlock:
         "session_id: xxx\n\n分析结果..."
```

#### 第二次调用（继续会话）

```
3. 父智能体 LLM 再次调用 call_expert
   参数: { "message": "再深入看看版本冲突", "session_id": "xxx" }

4. SubAgentTool.callAsync() 执行：
   ├── session_id != null → isNewSession = false
   ├── agent = agentProvider.provide()   ← 又创建一个全新实例
   ├── agent.loadFrom(session, sessionId)
   │     恢复: memory_messages（包含上次的完整对话历史）
   ├── 构建 Msg → agent.call(userMsg)
   │     子智能体内部：
   │     ├── prepareMessages() → [sysPrompt + 上次对话 + 本次消息]
   │     └── 子智能体可以看到之前的完整对话上下文
   ├── 执行完毕 → agent.saveTo(session, sessionId)
   └── 返回 ToolResultBlock
```

### 2.6 父子智能体的 Memory 对比

**父智能体 Memory：**

```
[SYSTEM]    父智能体系统提示
[USER]      用户原始输入："帮我分析这个项目"
[ASSISTANT] 父智能体回复（包含 ToolUseBlock: call_expert）
[TOOL_RESULT] session_id: xxx\n\n子智能体分析结果   ← 只是纯文本
[ASSISTANT] 父智能体综合回答用户
```

**子智能体 Memory（通过 Session 隔离）：**

```
[SYSTEM]    子智能体系统提示（"你是依赖分析专家..."）
[USER]      "分析项目依赖"               ← 只有父智能体传来的 message
[ASSISTANT] 子智能体分析过程
[TOOL_RESULT] glob_search 结果
[ASSISTANT] 子智能体最终回复
─────── 同一 session_id 的第二次调用恢复后 ───────
[USER]      "再深入看看版本冲突"           ← 第二次调用的 message
[ASSISTANT] 子智能体继续分析（能看到之前的对话）
```

### 2.7 关键设计决策

| 决策 | 实现方式 | 原因 |
|---|---|---|
| 每次调用新建 Agent | `SubAgentProvider.provide()` | ReActAgent 不是线程安全的（AgentBase 明确声明） |
| 上下文完全隔离 | 新实例有独立 Memory | 父子智能体看不到彼此的对话历史 |
| 会话通过 Session 恢复 | `saveTo` / `loadFrom` | 同一 session_id 的多次调用可延续对话 |
| 结果以纯文本返回父智能体 | `ToolResultBlock.text(...)` | 只有摘要文本回到父上下文，不会污染父 Memory |
| 事件可转发 | `forwardEvents=true` + `ToolEmitter` | 子智能体的事件可通过 Hook 传给父智能体 |

---

## 三、Supervisor 模式 — 实例共享 vs 工厂模式

### 3.1 共享实例写法（当前示例）

```java
// 当前 Supervisor 示例中的写法
ReActAgent calendarAgent = ReActAgent.builder()
    .name("schedule_event").model(model)
    .toolkit(calendarToolkit).memory(new InMemoryMemory()).build();

toolkit.registration().subAgent(() -> calendarAgent).apply();
// calendarAgent 是同一个实例，每次 provide() 返回同一个对象
```

**特点：**
- 子智能体的 Memory **跨调用累积**，对话历史不断增长
- `AgentBase.running` 标志保证同一时刻只有一个调用在执行
- 适合单线程顺序调用的简单场景

**风险：**
- 并发调用会抛 `IllegalStateException: Agent is still running`
- Memory 无限增长可能导致上下文溢出
- 无法通过 Session 做跨实例的状态持久化

### 3.2 工厂模式写法（推荐）

```java
// 推荐写法：每次调用创建新实例
toolkit.registration().subAgent(() -> ReActAgent.builder()
    .name("schedule_event")
    .model(model)
    .sysPrompt(CALENDAR_AGENT_PROMPT)
    .toolkit(calendarToolkit)  // Toolkit 会在 build() 时被 copy()
    .memory(new InMemoryMemory())
    .build()
).apply();
```

**优势：**
- 每次调用独立 Memory，天然隔离
- 避免上下文溢出
- 可配合 `session_id` + Session 实现多轮记忆
- 线程安全（不同调用使用不同实例）

### 3.3 Supervisor 与 SubAgent 的区别

| 维度 | Supervisor | SubAgent（SubAgentTool + TaskTool） |
|---|---|---|
| 工具数量 | 每个子智能体一个独立工具 | 单一 Task 工具，通过 `subagent_type` 分发 |
| 工具名 | `call_schedule_event`, `call_manage_email` | `Task`（参数：subagent_type） |
| 会话管理 | 支持 `session_id` 多轮 | TaskTool 无状态；SubAgentTool 支持 |
| 事件转发 | 支持 | SubAgentTool 支持；TaskTool 不支持 |
| 后台执行 | 不支持 | TaskTool 支持 `run_in_background=true` |
| Markdown 定义 | 不支持 | TaskTool 支持 AgentSpecLoader |

---

## 四、Toolkit 深拷贝机制

### 4.1 构建时拷贝

```java
// ReActAgent.Builder.build() 中
public ReActAgent build() {
    Toolkit agentToolkit = this.toolkit.copy();  // 深拷贝
    // ... 后续使用 agentToolkit
    return new ReActAgent(this, agentToolkit);
}
```

`Toolkit.copy()` 做了以下操作：
- 复制所有注册的工具（toolRegistry）
- 复制所有工具组状态（groupManager）
- 保留用户定义的 chunk callback

### 4.2 设计意义

多个 Agent 可以共享同一个 Toolkit 配置，构建时各自拿到独立副本，互不干扰：

```java
Toolkit baseToolkit = new Toolkit();
baseToolkit.registerTool(searchTool);
baseToolkit.registerTool(fetchTool);

// 三个 Agent 各自拿到独立副本
ReActAgent agent1 = ReActAgent.builder().toolkit(baseToolkit).build();
ReActAgent agent2 = ReActAgent.builder().toolkit(baseToolkit).build();
ReActAgent agent3 = ReActAgent.builder().toolkit(baseToolkit).build();
// agent1 激活/停用工具组不会影响 agent2、agent3
```

---

## 五、Memory 在多智能体中的行为

### 5.1 Memory 是 Agent 级别的私有状态

Memory 在构建时绑定到 Agent，运行时不可替换：

```java
// ReActAgent 构造函数中
this.memory = builder.memory;

// 运行时替换会抛异常
public void setMemory(Memory memory) {
    throw new UnsupportedOperationException(
        "Memory cannot be replaced after agent construction.");
}
```

### 5.2 Memory 的读写时机

```java
// doCall() — 输入消息加入 Memory
private Mono<Msg> doCall(List<Msg> msgs) {
    addToMemory(msgs);           // ← 用户消息加入 Memory
    return executeIteration(0);
}

// reasoning() — 从 Memory 读取发送给 LLM
private List<Msg> prepareMessages() {
    List<Msg> messages = new ArrayList<>();
    if (sysPrompt != null) {
        messages.add(systemPromptMsg);
    }
    messages.addAll(memory.getMessages());  // ← Memory 全部历史 → LLM
    return messages;
}

// reasoning() — LLM 回复加入 Memory
.flatMap(event -> {
    Msg msg = event.getReasoningMessage();
    if (msg != null) {
        memory.addMessage(msg);  // ← ASSISTANT 消息加入 Memory
    }
    // ...
})

// acting() — 工具结果加入 Memory
private Mono<PostActingEvent> notifyPostActingHook(...) {
    return notifyHooks(event)
        .doOnNext(e -> memory.addMessage(e.getToolResultMsg()));  // ← 工具结果加入 Memory
}
```

### 5.3 observe 机制

MsgHub 广播时，每个订阅者的 `observe()` 被调用：

```java
// AgentBase 默认实现（空）
protected Mono<Void> doObserve(Msg msg) {
    return Mono.empty();
}

// ReActAgent 覆写：将被观察的消息加入自己的 Memory
@Override
protected Mono<Void> doObserve(Msg msg) {
    if (msg != null) {
        memory.addMessage(msg);
    }
    return Mono.empty();
}
```

MsgHub 广播流程：
```
AgentA.call() → 产生回复 msg
  └── broadcastToSubscribers(msg)
        ├── AgentB.observe(msg) → AgentB.memory.addMessage(msg)
        ├── AgentC.observe(msg) → AgentC.memory.addMessage(msg)
        └── AgentD.observe(msg) → AgentD.memory.addMessage(msg)
```

每个订阅者将广播消息加入各自的 Memory，互不影响。

---

## 六、各模式的上下文管理总结

| 模式 | 上下文隔离方式 | Memory 管理 | 状态持久化 | 通信机制 |
|---|---|---|---|---|
| **SubAgentTool**（工厂模式） | `SubAgentProvider` 每次新建实例 | 每个子智能体独立 Memory | Session（InMemory / Json） | ToolResultBlock 纯文本 |
| **Supervisor**（共享实例） | 同一实例，Memory 累积 | 共享 Memory，跨调用增长 | 无 | ToolResultBlock 纯文本 |
| **Supervisor**（工厂模式） | 每次新建实例 | 每次独立 Memory | Session（同 SubAgentTool） | ToolResultBlock 纯文本 |
| **Pipeline** | 每个 AgentScopeAgent 独立 | 通过 `outputKey` 传递结果 | 无（StateGraph 管理 OverAllState） | 图状态中的 key-value |
| **MsgHub** | observe() 广播到各自 Memory | 各自 Memory 独立累积 | 无 | Msg 广播 |
| **Handoffs** | StateGraph + active_agent 状态 | 图状态中的 messages（AppendStrategy） | 无 | 图状态变量驱动路由 |
| **Skills** | 单 Agent，共享上下文 | 同一个 Memory，技能内容按需加载 | 无 | read_skill / use_skill 工具 |

---

## 七、核心设计原则

### 7.1 上下文隔离靠实例隔离

AgentScope 不做"虚拟上下文"或"上下文切换"，而是通过创建新 Agent 实例实现隔离。每个实例有自己的 Memory、Toolkit、sysPrompt。

```java
// 不是这样（虚拟上下文）：
agent.switchContext("session_1");
agent.call(msg);

// 而是这样（实例隔离）：
Agent instance = agentProvider.provide();  // 新实例
instance.loadFrom(session, "session_1");  // 恢复状态
instance.call(msg);                       // 执行
instance.saveTo(session, "session_1");    // 保存状态
```

### 7.2 Memory 是私有状态，不是共享依赖

- 构建时绑定，运行时不可替换
- 每个 Agent 实例有独立的 `CopyOnWriteArrayList<Msg>`
- `prepareMessages()` 只读取自己的 Memory 发给 LLM

### 7.3 Session 是跨实例的状态桥梁

`StateModule` 接口让 Agent 可以将内部状态序列化到外部存储，下次从新实例恢复：

```
实例 A (调用 1)          Session 存储          实例 B (调用 2)
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│ memory: [...] │ ──→  │ agent_meta   │  ──→ │ memory: [...] │
│ toolkit: {...}│ ──→  │ memory_msgs  │  ──→ │ toolkit: {...}│
│ plan: {...}   │ ──→  │ toolkit_grps │  ──→ │ plan: {...}   │
└──────────────┘      └──────────────┘      └──────────────┘
```

### 7.4 父子智能体只通过纯文本通信

子智能体的完整上下文不会泄漏到父智能体的 Memory 中。父智能体只看到工具返回的文本结果（`ToolResultBlock.text()`），确保上下文不会膨胀或污染。

### 7.5 线程安全通过实例隔离保证

`SubAgentProvider` 的设计初衷："ReActAgent 不是线程安全的，所以每次调用创建新实例"。`AgentBase` 通过 `AtomicBoolean running` 保证同一实例不会被并发调用。

---

## 八、最佳实践

### 8.1 子智能体注册推荐写法

```java
// 推荐：工厂模式 + Session 持久化
toolkit.registration().subAgent(
    () -> ReActAgent.builder()
        .name("dependency-analyzer")
        .model(model)
        .sysPrompt(DEPENDENCY_ANALYZER_PROMPT)
        .toolkit(depToolkit)       // Toolkit 会自动 copy
        .memory(new InMemoryMemory())
        .build(),
    SubAgentConfig.builder()
        .session(new JsonSession(Path.of("sessions")))  // 持久化会话
        .forwardEvents(true)                            // 转发事件
        .build()
).apply();
```

### 8.2 避免共享子智能体实例

```java
// 不推荐：共享实例
ReActAgent sharedAgent = ReActAgent.builder()...build();
toolkit.registration().subAgent(() -> sharedAgent).apply();
// 问题：Memory 累积、无法并发、无法持久化
```

### 8.3 控制子智能体的 Memory 大小

子智能体的 Memory 持续增长可能导致上下文溢出。建议：
- 使用工厂模式（每次新建实例，Memory 从零开始）
- 需要多轮记忆时通过 `session_id` + Session 持久化
- 配置合理的 `maxIters` 限制迭代次数
- 考虑使用 `LongTermMemory` 做语义压缩

### 8.4 根据场景选择 Session 实现

| 场景 | Session 实现 |
|---|---|
| 开发测试、短期会话 | `InMemorySession`（默认） |
| 生产环境、需要跨重启 | `JsonSession(Path.of("sessions"))` |
| 分布式环境 | 自定义 Session 实现（如 Redis） |
