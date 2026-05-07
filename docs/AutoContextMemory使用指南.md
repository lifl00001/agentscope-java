# AgentScope 核心：记忆、会话、状态、模型调用与 AutoContextMemory 完整指南

## 一、记忆、会话、状态的关系

### 1.1 一句话类比

> **记忆**是你脑子里的事，**状态**是把你脑子拍成一张快照，**会话**是那个存放快照的抽屉。

### 1.2 各自的定义

| 概念 | 是什么 | 解决什么问题 |
|------|--------|-------------|
| **Memory（记忆）** | Agent 的对话历史管理组件，负责存取消息列表 | "Agent 怎么记住对话内容" |
| **State（状态）** | 组件运行时数据的可序列化表示 | "组件的数据怎么变成能存到磁盘的格式" |
| **Session（会话）** | 状态的存储引擎（JSON 文件、数据库等） | "状态存到哪里、怎么按用户区分" |

### 1.3 三者的协作关系

```
用户 "张三" 发消息给 Agent
        |
        v
   +-------------+
   |   Memory     |  <- 记忆：在内存中管理对话消息列表
   |  [msg1,msg2] |
   +------+------+
          | saveTo() 把内存中的消息列表序列化
          v
   +-------------+
   |   State      |  <- 状态：Memory 的运行时数据被转成可存储的格式
   |  (快照数据)   |     (实现了 State 标记接口的对象)
   +------+------+
          | session.save() 写入存储
          v
   +---------------------+
   |   Session            |  <- 会话：存储引擎，按 SessionKey 区分不同用户
   |  sessions/张三/      |     (JsonSession -> 文件, MysqlSession -> 数据库)
   |    +- memory.jsonl   |
   |    +- plan.json      |
   +---------------------+
```

反方向（恢复）就是 `loadFrom()`，Session -> State -> Memory。

### 1.4 代码层面的调用链

```java
// 1. Memory 继承了 StateModule，说明 Memory 本身就有状态管理能力
public interface Memory extends StateModule { ... }

// 2. 实际使用时，三个概念一起工作
InMemoryMemory memory = new InMemoryMemory();   // <- Memory
ReActAgent agent = ReActAgent.builder().memory(memory).build();
Session session = new JsonSession(sessionPath);  // <- Session

agent.loadIfExists(session, "张三");   // Session 中读取 -> 还原到 Memory

// ... 对话 ...

agent.saveTo(session, "张三");         // Memory 中的数据 -> 序列化为 State -> 写入 Session
```

`agent.saveTo()` 会级联保存它内部的所有组件（Memory、PlanNotebook 等），每个组件各自决定把自己的什么数据作为 State 存入 Session。

### 1.5 长期记忆的特殊性

```
短期记忆 (Memory)              长期记忆 (LongTermMemory)
     |                              |
     | 需要配合 Session             | 独立持久化（Mem0/ReMe 外部服务）
     | 手动 saveTo/loadFrom         | 框架自动调用 record/retrieve
     | 存当前会话的完整对话          | 存跨会话的知识和偏好
     v                              v
  "这次对话聊了什么"             "这个用户喜欢什么、知道什么"
```

长期记忆不经过 Session/State 机制，它自己有独立的外部存储（向量数据库）。

### 1.6 State 接口 vs StateModule 接口的区别

#### `State` -- "什么东西可以被存"

```java
public interface State {}  // 空的标记接口
```

- **角色**：数据标记，告诉 Session "我这个对象可以被你存储"
- **实现者**：数据对象本身（record、class）
- **类比**：快递盒上贴的"可邮寄"标签

```java
// 一个可以被 Session 存储的数据对象
public record UserPreferences(String theme, String language) implements State {}

// Msg 本身也实现了 State，所以消息可以直接存
public class Msg implements State { ... }
```

#### `StateModule` -- "谁有能力存和取"

```java
public interface StateModule {
    void saveTo(Session session, SessionKey sessionKey);   // 把自己的状态存进去
    void loadFrom(Session session, SessionKey sessionKey); // 把状态取出来恢复
}
```

- **角色**：行为契约，定义组件如何序列化和反序列化自己
- **实现者**：有状态的组件（Agent、Memory、PlanNotebook）
- **类比**：一个人知道怎么把东西装进快递盒、怎么从快递盒取出来

#### 两者的协作关系

```java
public class MyComponent implements StateModule {
    private String data;

    // 定义"什么数据可以被存" -> State
    public record MyState(String data) implements State {}

    // 定义"怎么存、怎么取" -> StateModule
    @Override
    public void saveTo(Session session, SessionKey key) {
        session.save(key, "myComponent", new MyState(data));  // 创建 State 对象，交给 Session
    }

    @Override
    public void loadFrom(Session session, SessionKey key) {
        session.get(key, "myComponent", MyState.class)       // 从 Session 取出 State 对象
            .ifPresent(s -> this.data = s.data());            // 恢复到自己的字段
    }
}
```

#### 核心区别总结

| | `State` | `StateModule` |
|---|---------|---------------|
| **本质** | 标记接口（能被存的数据） | 行为接口（会存取的组件） |
| **实现者** | 数据对象（record/class） | 组件（Agent/Memory） |
| **方法** | 无 | `saveTo()`, `loadFrom()` |
| **数量关系** | 一个组件可以有多个 State | 一个组件实现一个 StateModule |
| **比喻** | 货物 | 会打包/拆包的仓库管理员 |

简单说：**`StateModule` 是动词（存/取），`State` 是名词（被存的数据）**。`StateModule` 在 `saveTo()` 中创建 `State` 对象交给 `Session`，在 `loadFrom()` 中从 `Session` 取回 `State` 对象恢复自己。

---

## 二、Agent 调用模型时，消息是如何传递的

### 2.1 核心问题

> 保存了 Agent 的会话内容后，调用模型的时候，是全量把所有的状态内容都给大模型吗？长期记忆、计划等信息都不给模型吗？

**答案：不是。发给模型的只有 Memory 中的消息 + Hook 注入的附加信息。`agent.saveTo()` 保存的其他组件状态（如 Agent 元数据、Toolkit 激活组）是用于持久化恢复的，不会直接发给模型。但某些组件会通过 Hook 机制间接将信息注入到消息中。**

### 2.2 saveTo 保存了什么 vs 模型收到了什么

#### agent.saveTo() 保存的全部组件

```java
// ReActAgent.saveTo() 的完整逻辑
public void saveTo(Session session, SessionKey sessionKey) {
    // 1. Agent 元数据（始终保存）
    session.save(sessionKey, "agent_meta",
        new AgentMetaState(getAgentId(), getName(), getDescription(), sysPrompt));

    // 2. Memory 消息（如果 memoryManaged）
    memory.saveTo(session, sessionKey);

    // 3. Toolkit 激活的工具组（如果 toolkitManaged）
    session.save(sessionKey, "toolkit_activeGroups", new ToolkitState(toolkit.getActiveGroups()));

    // 4. PlanNotebook 计划状态（如果 planNotebookManaged）
    planNotebook.saveTo(session, sessionKey);
}
```

| 保存的组件 | 保存的内容 | 是否直接发给模型 |
|-----------|-----------|---------------|
| Agent 元数据 | agentId, name, description, sysPrompt | **sysPrompt 会**（作为 SYSTEM 消息），其他不会 |
| Memory | 对话历史消息列表 | **会**（核心输入） |
| Toolkit activeGroups | 当前激活的工具组名称列表 | **不会**（仅影响工具注册，不作为消息） |
| PlanNotebook | 当前计划、子任务、历史计划 | **不会直接发**，但通过 Hook 注入计划提示 |
| LongTermMemory | **不参与 saveTo** | **不直接发**，但通过 Hook 注入召回的记忆 |
| SkillBox | 已加载的技能信息 | **不直接发**，但通过 Hook 注入技能提示 |
| RAG 知识库 | 外部知识文档 | **不直接发**，但通过 Hook 注入检索到的知识片段 |

#### 各组件与模型调用之间的关系总览

```
                        agent.saveTo() 保存的（持久化到磁盘）
                        ┌─────────────────────────────────────┐
                        │ Agent 元数据 (agent_meta)            │ ← 不直接发给模型
                        │ Memory 消息列表                      │ ← 直接发给模型
                        │ Toolkit 激活组 (toolkit_activeGroups) │ ← 不发给模型
                        │ PlanNotebook 计划状态                 │ ← 不直接发给模型
                        └─────────────────────────────────────┘

                        模型调用时实际收到的消息
                        ┌─────────────────────────────────────┐
                        │ [1] SYSTEM: sysPrompt                │ ← 来自 prepareMessages()
                        │ [2] MEMORY: memory.getMessages()     │ ← 来自 prepareMessages()
                        │ [3] Hook 注入的消息:                  │ ← 来自各种 Hook
                        │     ├── 长期记忆召回 (STATIC_CONTROL) │
                        │     ├── RAG 知识片段                  │
                        │     ├── PlanNotebook 计划提示         │
                        │     ├── AutoContext 压缩指令          │
                        │     └── SkillBox 技能提示             │
                        └─────────────────────────────────────┘
                              │
                              v
                        formatter.format() 转换为 API 请求
                              │
                              v
                        发送给 LLM
```

### 2.3 完整调用链

以 `agent.call(userMsg)` 为例，追踪从用户输入到模型 API 请求的完整过程：

```
agent.call(userMsg)
  |
  v
doCall(msgs)
  |-- addToMemory(msgs)              // 第一步：用户消息先存入 Memory
  |-- executeIteration(0)
       |
       v
     reasoning(0, false)
       |
       |-- prepareMessages()          // 第二步：组装基础消息列表
       |     |
       |     |-- [SYSTEM] sysPrompt（如果设置了系统提示词）
       |     +-- memory.getMessages() // 获取记忆消息（全量 or 压缩后）
       |
       v
     notifyPreReasoningEvent(msgs)    // 第三步：Hook 链拦截，各组件注入信息
       |
       |-- 创建 PreReasoningEvent(agent, modelName, options, msgs)
       |
       v
     Hook 链（按优先级顺序执行）:
       |
       |-- [优先级 0] AutoContextHook:
       |     |-- compressIfNeeded()           // 压缩记忆
       |     |-- 替换消息列表 = [压缩指令 SYSTEM] + [压缩后的记忆消息]
       |
       |-- [优先级 50] StaticLongTermMemoryHook (PreCallEvent):
       |     |-- longTermMemory.retrieve()    // 从 Mem0/ReMe 召回相关记忆
       |     |-- 注入 SYSTEM 消息: "<long_term_memory>用户喜欢..." (追加到消息列表)
       |
       |-- [优先级 50] GenericRAGHook (PreCallEvent):
       |     |-- knowledge.retrieve()          // 从知识库检索相关文档
       |     |-- 注入 USER 消息: "<retrieved_knowledge>..." (追加到消息列表)
       |
       |-- PlanHintHook (PreReasoningEvent):
       |     |-- planNotebook.getCurrentHint()  // 获取当前计划提示
       |     |-- 注入 USER 消息: "当前计划: ..." (追加到消息列表)
       |
       |-- SkillBox Hook (PreReasoningEvent):
       |     |-- 注入技能提示消息
       |
       v
     model.stream(event.getInputMessages(), tools, options)  // 第四步：发送给模型
       |
       |-- formatter.format(messages)     // Msg -> OpenAIMessage（或 DashScopeMessage）
       |     |
       |     |-- 对每个 Msg：
       |     |     SYSTEM    -> {"role":"system","content":"..."}
       |     |     USER      -> {"role":"user","content":"..."}
       |     |     ASSISTANT -> {"role":"assistant","content":"...","tool_calls":[...]}
       |     |     TOOL      -> {"role":"tool","tool_call_id":"...","content":"..."}
       |     |   (ThinkingBlock 被静默丢弃，不发给模型)
       |
       |-- 构建 HTTP 请求，发送到模型 API
       |
       v
     LLM 收到请求并返回响应

     回复完成后:
     PostCallEvent Hook:
       |-- StaticLongTermMemoryHook.handlePostCall()
       |     |-- longTermMemory.record(memory.getMessages())  // 异步存入长期记忆
```

### 2.4 各组件如何将信息注入到模型调用中

#### 1. Memory（直接传入）

Memory 是模型调用最核心的数据源，通过 `prepareMessages()` 直接放入消息列表。

```java
// ReActAgent.java
private List<Msg> prepareMessages() {
    List<Msg> messages = new ArrayList<>();
    if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
        messages.add(Msg.builder().role(MsgRole.SYSTEM)
            .content(TextBlock.builder().text(sysPrompt).build()).build());
    }
    messages.addAll(memory.getMessages());  // Memory 中的全部消息直接追加
    return messages;
}
```

#### 2. LongTermMemory — 通过 StaticLongTermMemoryHook 注入

长期记忆**不通过 Memory 传递**，而是通过 Hook 在 `PreCallEvent` 阶段注入：

```java
// StaticLongTermMemoryHook.java（简化）
private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
    // 1. 提取最后一条用户消息作为查询
    Msg queryMsg = inputMessages.get(queryMsgIndex);

    // 2. 从 Mem0/ReMe 召回相关记忆
    return longTermMemory.retrieve(queryMsg)
        .flatMap(memoryText -> {
            // 3. 包装为 SYSTEM 消息，追加到消息列表末尾
            Msg memoryMsg = Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("long_term_memory")
                .content(TextBlock.builder().text(wrap(memoryText)).build())
                .build();
            enhancedMessages.add(memoryMsg);  // 追加！不是替换
            event.setInputMessages(enhancedMessages);
            return Mono.just(event);
        });
}
```

**注入方式**：作为一条 `role=SYSTEM` 的消息，包裹在 `<long_term_memory>` 标签中，**追加**到消息列表末尾。

**回复后记录**：在 `PostCallEvent` 中，框架自动将 `memory.getMessages()` 全部消息异步存入长期记忆。

#### 3. PlanNotebook — 通过 PlanHintHook 注入

PlanNotebook 的计划状态**不通过 Memory 传递**，而是通过内部注册的 Hook 在 `PreReasoningEvent` 阶段注入：

```java
// ReActAgent.configurePlan() 中注册的 Hook（简化）
Hook planHintHook = new Hook() {
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent) {
            PreReasoningEvent e = (PreReasoningEvent) event;
            return planNotebook.getCurrentHint()  // 获取当前计划提示
                .map(hintMsg -> {
                    List<Msg> modifiedMsgs = new ArrayList<>(e.getInputMessages());
                    modifiedMsgs.add(hintMsg);    // 追加计划提示消息
                    e.setInputMessages(modifiedMsgs);
                    return (T) e;
                });
        }
        return Mono.just(event);
    }
};
```

`getCurrentHint()` 返回的内容类似：

```
<plan_hint>
Goal: 分析销售数据并生成报告
Current Progress: [数据查询, 数据分析]
Progress: 2/5 subtasks completed
Expected Outcome: 包含趋势分析和建议的完整报告
Next Step: 执行子任务 2 - 数据分析
</plan_hint>
```

**注入方式**：作为一条 `role=USER` 的消息，追加到消息列表末尾。

#### 4. RAG 知识库 — 通过 GenericRAGHook 注入

```java
// GenericRAGHook.java（简化）
private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
    // 1. 提取最后一条用户消息作为查询
    String query = extractQueryFromMessages(inputMessages);

    // 2. 从知识库检索相关文档
    return knowledge.retrieve(query, defaultConfig)
        .flatMap(retrievedDocs -> {
            // 3. 构建知识内容消息
            Msg enhancedMessage = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(knowledgeContent).build())
                .build();
            enhancedMessages.add(enhancedMessage);  // 追加
            event.setInputMessages(enhancedMessages);
            return Mono.just(event);
        });
}
```

**注入方式**：作为一条 `role=USER` 的消息，包裹在 `<retrieved_knowledge>` 标签中，追加到消息列表末尾。

#### 5. AutoContextMemory — 通过 AutoContextHook 替换

AutoContextMemory 的处理方式与上述不同——它是**替换**而非追加：

```java
// AutoContextHook.java（简化）
private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
    autoContextMemory.compressIfNeeded();  // 压缩工作存储

    // 完全替换消息列表（不是追加！）
    List<Msg> newInputMessages = new ArrayList<>();
    newInputMessages.add(/* 带压缩指令的 SYSTEM 消息 */);
    newInputMessages.addAll(autoContextMemory.getMessages());  // 压缩后的消息
    event.setInputMessages(newInputMessages);  // 替换！
    return Mono.just(event);
}
```

### 2.5 关键源码解读

#### InMemoryMemory.getMessages() — 全量返回

```java
// InMemoryMemory.java
@Override
public List<Msg> getMessages() {
    return messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
    // 没有任何过滤、截断、滑动窗口 — 返回所有消息
}
```

#### AutoContextMemory.getMessages() — 返回压缩后的工作存储

```java
// AutoContextMemory.java
@Override
public List<Msg> getMessages() {
    return new ArrayList<>(workingMemoryStorage);  // 只返回压缩后的消息
}
// 原始完整消息保存在 originalMemoryStorage 中，但不会被发送给 LLM
```

#### Formatter 的转换逻辑

```java
// OpenAIChatFormatter.java
protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
    List<OpenAIMessage> result = new ArrayList<>();
    for (Msg msg : msgs) {
        OpenAIMessage openAIMsg = messageConverter.convertToMessage(msg, hasMediaContent(msg));
        if (openAIMsg != null) result.add(openAIMsg);
    }
    return result;
}
```

### 2.6 具体示例：完整场景下发给模型的 API 请求

#### 场景：全部功能开启，3 轮对话 + 工具调用 + 计划 + 长期记忆 + RAG

假设：
- 使用 `InMemoryMemory`
- 开启了 `enablePlan()`，Agent 制定了计划
- 配置了 `LongTermMemory`（STATIC_CONTROL 模式），Mem0 中有用户偏好记忆
- 配置了 RAG 知识库

**经过 Hook 链处理后，最终发给模型的 JSON 请求：**

```json
{
  "model": "qwen-max",
  "messages": [
    {
      "role": "system",
      "content": "你是一个数据分析助手"
    },
    {
      "role": "user",
      "content": "帮我分析一下销售数据"
    },
    {
      "role": "assistant",
      "content": "好的，让我先查询数据库。",
      "tool_calls": [
        {"id": "call_1", "type": "function", "function": {"name": "query_db", "arguments": "{\"sql\":\"SELECT...\"}"}}
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_1",
      "content": "查询结果：100条记录..."
    },
    {
      "role": "assistant",
      "content": "数据已获取，共100条销售记录。"
    },
    {
      "role": "user",
      "content": "总结一下关键发现"
    },
    {
      "role": "system",
      "name": "long_term_memory",
      "content": "<long_term_memory>\n该用户是数据分析师，偏好使用图表展示数据，之前关注过Q3销售趋势。\n</long_term_memory>"
    },
    {
      "role": "user",
      "name": "user",
      "content": "<retrieved_knowledge>Use the following content from the knowledge base(s) if it is helpful:\n\n- Score: 0.892, Content: Q3销售额同比增长15%，主要来自华东地区...\n- Score: 0.756, Content: 产品A是畅销品，占总销售额的32%...\n</retrieved_knowledge>"
    },
    {
      "role": "user",
      "name": "user",
      "content": "<plan_hint>\nGoal: 分析销售数据并生成报告\nCurrent Progress: [数据查询, 数据分析]\nProgress: 2/5 subtasks completed\nNext Step: 执行子任务 2 - 数据分析\n</plan_hint>"
    }
  ],
  "tools": [
    {"type": "function", "function": {"name": "query_db", "description": "查询数据库", "parameters": {...}}},
    {"type": "function", "function": {"name": "create_plan", "description": "创建计划", "parameters": {...}}},
    {"type": "function", "function": {"name": "long_term_memory_record", "description": "记录长期记忆", "parameters": {...}}}
  ],
  "stream": true
}
```

**消息列表构成分析：**

```
[0] SYSTEM "你是一个数据分析助手"                              ← prepareMessages() — sysPrompt
[1] USER   "帮我分析一下销售数据"                              ← prepareMessages() — memory[0]
[2] ASSISTANT + tool_calls                                     ← prepareMessages() — memory[1]
[3] TOOL   "查询结果：100条记录..."                             ← prepareMessages() — memory[2]
[4] ASSISTANT "数据已获取..."                                  ← prepareMessages() — memory[3]
[5] USER   "总结一下关键发现"                                  ← prepareMessages() — memory[4]
                                                              ← === Hook 注入的分界线 ===
[6] SYSTEM "<long_term_memory>用户偏好...</long_term_memory>"  ← StaticLongTermMemoryHook 注入
[7] USER   "<retrieved_knowledge>Q3销售额...</retrieved_knowledge>" ← GenericRAGHook 注入
[8] USER   "<plan_hint>Goal: 分析销售...</plan_hint>"          ← PlanHintHook 注入
```

### 2.7 工具调用结果如何传递给模型

工具调用结果是**通过 Memory 直接传递**的，不需要额外的 Hook 注入。当 Agent 调用工具后，工具结果会作为 `TOOL` 角色的消息存入 Memory：

```
第一轮推理:
  LLM 返回: ASSISTANT 消息 + tool_calls: [get_weather("北京")]
  ↓ 存入 Memory
  Memory 中新增: {role: ASSISTANT, tool_calls: [{id: "call_1", name: "get_weather", ...}]}

工具执行:
  框架调用 get_weather("北京")，返回 "北京今天晴，25度"
  ↓ 存入 Memory
  Memory 中新增: {role: TOOL, tool_call_id: "call_1", content: "北京今天晴，25度"}

第二轮推理:
  prepareMessages() 从 Memory 取出全部消息，包括上面的 ASSISTANT 和 TOOL 消息
  ↓
  发送给 LLM 的 messages 数组:
  [
    ...之前的历史消息...,
    {"role": "assistant", "content": null, "tool_calls": [{"id":"call_1", ...}]},
    {"role": "tool", "tool_call_id": "call_1", "content": "北京今天晴，25度"}
  ]
  ↓
  LLM 看到工具结果，生成最终回复: "北京今天天气晴朗，气温25度。"
```

**关键点**：工具调用结果（`TOOL` 消息）和工具调用请求（`ASSISTANT` 消息中的 `tool_calls`）是**成对出现**的，它们都存储在 Memory 中，通过 `memory.getMessages()` 全量（或压缩后）传给模型。模型通过 `tool_call_id` 将两者关联起来。

### 2.8 SkillBox 如何传递给模型

SkillBox 的传递机制取决于使用模式：

**模式 1：技能提示注入（通过 Hook）**

SkillBox 会注册一个 Hook，在 `PreReasoningEvent` 时将已加载技能的描述注入为提示消息：

```
SkillBox Hook 在 PreReasoningEvent 中:
  → 读取当前激活的技能列表
  → 注入 USER 消息: "你当前拥有以下技能: [数据分析, 报表生成, SQL查询]..."
```

**模式 2：技能作为工具注册**

SkillBox 中的技能工具会被注册到 Toolkit 中，以 `tools` 字段的形式出现在 API 请求中：

```json
{
  "tools": [
    {"type": "function", "function": {"name": "data_analysis_skill", "description": "执行数据分析", "parameters": {...}}},
    {"type": "function", "function": {"name": "report_generation_skill", "description": "生成报表", "parameters": {...}}}
  ]
}
```

LLM 看到可用的技能工具后，会像调用普通工具一样调用它们。

### 2.9 RAG 知识库如何作为模型上下文传递

RAG 知识库通过 `GenericRAGHook` 在 `PreCallEvent` 阶段注入（详见 2.4 节第 4 点）。其工作流程：

```
用户输入 "北京有什么好玩的？"
  ↓
GenericRAGHook.handlePreCall():
  1. 提取用户查询: "北京有什么好玩的？"
  2. 调用 knowledge.retrieve("北京有什么好玩的？")
  3. 向量数据库返回相关文档:
     - Doc 1 (score=0.92): "故宫是北京最著名的景点..."
     - Doc 2 (score=0.85): "颐和园位于北京西郊..."
  4. 构建知识消息:
     <retrieved_knowledge>
     Use the following content from the knowledge base(s) if it is helpful:
     - Score: 0.920, Content: 故宫是北京最著名的景点...
     - Score: 0.850, Content: 颐和园位于北京西郊...
     </retrieved_knowledge>
  5. 注入为 USER 消息，追加到消息列表末尾
  ↓
LLM 收到的 messages:
  [SYSTEM "你是旅游助手", USER "北京有什么好玩的？", USER "<retrieved_knowledge>..."]
  ↓
LLM 结合知识库内容生成回答
```

**注意**：RAG 检索到的知识是**按需动态注入**的，不是全量注入。每次用户发消息时，Hook 会根据当前查询重新检索最相关的文档片段。

### 2.10 saveTo 保存但不直接发给模型的组件

以下是 `agent.saveTo()` 保存但**不直接参与模型调用**的组件：

#### Toolkit activeGroups

```java
// 保存：工具组的激活状态
session.save(sessionKey, "toolkit_activeGroups", new ToolkitState(toolkit.getActiveGroups()));
```

保存的是当前激活了哪些工具组（如 ["default", "database"]），用于下次加载时恢复工具配置。**这些信息不会作为消息发给模型**，而是决定了哪些工具会出现在 API 请求的 `tools` 字段中。

#### Agent 元数据

```java
session.save(sessionKey, "agent_meta",
    new AgentMetaState(getAgentId(), getName(), getDescription(), sysPrompt));
```

其中 `sysPrompt` 会在 `prepareMessages()` 中作为 SYSTEM 消息发给模型，但 `agentId`、`name`、`description` 不会。

#### LongTermMemory（特殊：不参与 saveTo）

LongTermMemory 有自己独立的持久化机制（Mem0/ReMe 外部服务），**不通过 Session 保存**，也不通过 Memory 传递。它通过 Hook 在每次推理前召回、推理后记录。

### 2.8 两种 Memory 的消息传递对比

```
InMemoryMemory 的消息传递:
  Memory [msg1, msg2, ..., msg50]  ──全量──>  prepareMessages()  ──全量──>  Hook 链注入  ──>  LLM
  (50条消息全部发送，无压缩)

AutoContextMemory 的消息传递:
  原始存储 [msg1, msg2, ..., msg50]  ──不发送──>  (保存在 originalMemoryStorage)
  工作存储 [summary1, msg48, msg49, msg50]  ──压缩后──>  AutoContextHook 替换  ──>  Hook 链注入  ──>  LLM
  (只有压缩后的消息发送，原始数据按需重载)
```

---

## 三、AutoContextMemory 使用指南

### 3.1 模块工程概览

#### 模块定位与工程作用

`agentscope-extensions-autocontext-memory` 是 AgentScope Java 框架的**独立扩展模块**，专门解决 LLM Agent 在长对话场景下的**上下文窗口溢出**问题。

**核心矛盾**：Agent 在复杂任务中（如多轮工具调用、计划执行、RAG 检索）会产生大量消息，而 LLM 的上下文窗口是有限的（如 128K tokens）。当消息累积超出窗口限制时，会导致 API 报错、信息丢失、成本激增。

**本模块的解决方案**：提供一个**可插拔的智能记忆管理器**，替代默认的 `InMemoryMemory`，通过渐进式压缩策略自动控制发送给 LLM 的 token 数量，同时保证原始数据的完整性和可追溯性。

#### 与 agentscope-core 的关系

> **`AutoContextMemory` 不在 `agentscope-core` 中。** 核心框架只提供记忆接口和一种默认实现，`AutoContextMemory` 全部代码都位于扩展模块中。

| 层 | 位置 | 内容 |
|---|------|------|
| **接口定义** | `agentscope-core` | `Memory` 接口（`addMessage`、`getMessages`、`deleteMessage`、`clear`） |
| **默认实现** | `agentscope-core` | `InMemoryMemory`——消息无限增长，无压缩能力 |
| **智能实现** | `agentscope-extensions-autocontext-memory` | `AutoContextMemory` + 6 种压缩策略 + 卸载/重载机制 + 审计日志 |

`agentscope-core` 中与记忆相关的全部类：

```
agentscope-core/src/main/java/io/agentscope/core/memory/
├── Memory.java                  ← 接口定义
├── InMemoryMemory.java          ← 唯一实现（无压缩）
├── LongTermMemory.java          ← 长期记忆接口（跨会话）
├── LongTermMemoryMode.java      ← 长期记忆模式枚举
├── LongTermMemoryTools.java     ← 长期记忆工具
└── StaticLongTermMemoryHook.java ← 长期记忆 Hook
```

**结论**：使用 `AutoContextMemory` 必须显式引入扩展依赖，不存在"core 自带"的说法。核心框架保持轻量，压缩能力作为可选扩展按需引入。

#### 在项目架构中的位置

```
agentscope-java/
├── agentscope-core/                              ← 核心框架（定义 Memory、StateModule、Hook 接口）
│   └── InMemoryMemory                            ← 默认记忆实现（无压缩，消息无限增长）
│
├── agentscope-extensions/
│   ├── agentscope-extensions-autocontext-memory/ ← ★ 本模块：智能上下文压缩记忆
│   ├── agentscope-extensions-rag-*/              ← RAG 知识库扩展
│   ├── agentscope-extensions-session-*/          ← 会话持久化扩展
│   └── agentscope-extensions-a2a/                ← Agent 间通信协议
│
└── agentscope-examples/                          ← 示例应用
```

**依赖关系**：本模块仅依赖 `agentscope-core`，不依赖 Spring Boot 或其他扩展模块，可以独立使用。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-autocontext-memory</artifactId>
</dependency>
```

#### 核心类结构与职责

本模块共包含 14 个生产类，按职责分为四层：

```
┌─────────────────────────────────────────────────────────────────┐
│                      对外接口层                                   │
│                                                                  │
│  AutoContextMemory   实现 Memory + StateModule + ContextOffLoader │
│  AutoContextHook     实现 Hook，自动触发压缩 + 注册工具             │
│  AutoContextConfig   Builder 模式配置，所有可调参数                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                      压缩引擎层                                   │
│                                                                  │
│  6 种渐进式压缩策略（由轻到重，命中即停）：                          │
│  ├── 策略1: TOOL_INVOCATION_COMPRESS         压缩历史工具调用      │
│  ├── 策略2: LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION  带保护的大消息卸载│
│  ├── 策略3: LARGE_MESSAGE_OFFLOAD             大消息卸载           │
│  ├── 策略4: PREVIOUS_ROUND_CONVERSATION_SUMMARY  历史对话摘要     │
│  ├── 策略5: CURRENT_ROUND_LARGE_MESSAGE_SUMMARY  当前轮大消息摘要  │
│  └── 策略6: CURRENT_ROUND_MESSAGE_COMPRESS     当前轮全量压缩      │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                      基础设施层                                   │
│                                                                  │
│  PromptProvider / Prompts   压缩提示词管理与回退机制                │
│  PromptConfig               自定义提示词配置                       │
│  TokenCounterUtil           基于字符数的 token 估算工具             │
│  MsgUtils                   消息序列化/分类/计划工具过滤            │
│  CompressionEvent           压缩操作审计记录（实现 State）          │
│  OffloadContextState        卸载上下文持久化载体（实现 State）       │
│  Pair                       通用索引范围记录                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                      工具层                                       │
│                                                                  │
│  ContextOffloadTool   @Tool(name="context_reload")               │
│                       LLM 可按需调用，重载被卸载的原始消息           │
└─────────────────────────────────────────────────────────────────┘
```

#### 三大核心设计原则

| 原则 | 说明 | 实现方式 |
|------|------|---------|
| **渐进式压缩** | 从最轻量的策略开始尝试，命中即停，避免过度压缩 | 6 种策略按顺序执行，成功一个就停止 |
| **当前轮优先** | 当前轮消息比历史消息更重要，优先压缩历史 | 策略 1-4 只处理历史消息，策略 5-6 才处理当前轮 |
| **可追溯性** | 原始数据永不丢失，可按需重载 | 双存储机制 + UUID 卸载/重载 + 压缩审计日志 |

#### 与核心框架的集成方式

本模块通过两个接口与核心框架无缝集成，无需修改 Agent 核心代码：

```
集成点 1: Memory 接口替换
  InMemoryMemory  ──替换为──>  AutoContextMemory
  Agent 无感知，调用 memory.getMessages() 即可获取压缩后的消息

集成点 2: Hook 机制自动触发
  AutoContextHook 注册到 Agent
  ├── PreCallEvent:     自动注册 ContextOffloadTool + 绑定 PlanNotebook
  └── PreReasoningEvent: 自动调用 compressIfNeeded() + 重写输入消息
```

**对 Agent 的影响**：仅需两行代码——将 Memory 替换为 `AutoContextMemory`，并注册 `AutoContextHook`。Agent 的其他功能（工具调用、计划、长期记忆、RAG）完全不受影响。

### 3.2 概述

`AutoContextMemory` 是 AgentScope 提供的**智能上下文记忆管理器**，用于解决长对话中上下文窗口溢出的问题。它会自动压缩、卸载和摘要对话历史，控制发送给 LLM 的 token 数量。

与 `InMemoryMemory`（消息无限增长）不同，`AutoContextMemory` 采用**双存储机制**：

| 存储层 | 说明 | 用途 |
|--------|------|------|
| **工作存储** (workingMemoryStorage) | 压缩后的消息 | 实际发送给 LLM 的上下文 |
| **原始存储** (originalMemoryStorage) | 完整的未压缩历史 | 审计、恢复、会话持久化 |

核心工作流程：

```
用户消息进入 -> 存入双存储 -> 每次推理前检查是否需要压缩
                                  |
                        +---------+-----------+
                        | 消息数 >= msgThreshold |  或  | token数 >= maxToken * tokenRatio |
                        +---------+-----------+
                                  |
                        按顺序尝试 6 种压缩策略（轻 -> 重）
                                  |
                        +---------+-----------+
                        | 原始消息被卸载到 offloadContext（内存 Map）|
                        | 压缩后的摘要替换工作存储中的原始消息      |
                        | 压缩摘要中嵌入 CONTEXT_OFFLOAD 标记       |
                        +-----------------------+
```

### 3.3 快速开始

#### 最小用法

```java
// 1. 创建配置
AutoContextConfig config = AutoContextConfig.builder()
    .tokenRatio(0.1)    // token 使用率达到 10% 就触发压缩（适合测试）
    .lastKeep(20)       // 保留最近 20 条消息不被压缩
    .build();

// 2. 创建记忆（需要传入 Model，因为部分策略需要 LLM 进行摘要）
AutoContextMemory memory = new AutoContextMemory(config, chatModel);

// 3. 构建 Agent 时注册 AutoContextHook（关键！）
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .memory(memory)
    .toolkit(toolkit)
    .hook(new AutoContextHook())   // 自动注册 ContextOffloadTool + 在推理前触发压缩
    .build();
```

#### 完整用法（含会话持久化 + 长期记忆）

```java
// 1. 创建模型
DashScopeChatModel chatModel = DashScopeChatModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-max")
    .build();

// 2. 创建 AutoContextMemory
AutoContextConfig config = AutoContextConfig.builder()
    .tokenRatio(0.4)
    .lastKeep(10)
    .build();
AutoContextMemory memory = new AutoContextMemory(config, chatModel);

// 3. 创建工具集
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ReadFileTool());
toolkit.registerTool(new WriteFileTool());

// 4. 构建 Agent（注册 Hook + 开启计划能力）
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .memory(memory)
    .toolkit(toolkit)
    .longTermMemory(longTermMemory)           // 可选：长期记忆
    .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
    .enablePlan()                             // 可选：开启计划感知压缩
    .hook(new AutoContextHook())
    .build();

// 5. 会话管理
Path sessionPath = Paths.get(System.getProperty("user.home"), ".agentscope", "sessions");
Session session = new JsonSession(sessionPath);

// 加载已有会话
agent.loadIfExists(session, "user_123");

// ... 对话交互 ...
Msg response = agent.call(userMsg).block();

// 保存会话
agent.saveTo(session, "user_123");
```

### 3.4 AutoContextHook 的作用

`AutoContextHook` 是 `AutoContextMemory` 自动工作的关键，它在两个时机介入：

| Hook 事件 | 行为 |
|-----------|------|
| `PreCallEvent`（首次调用） | 自动将 `ContextOffloadTool` 注册到 Agent 的 Toolkit 中；如果 Agent 有 `PlanNotebook`，自动附加到 `AutoContextMemory` |
| `PreReasoningEvent`（每次推理前） | 调用 `compressIfNeeded()` 执行压缩检查；将压缩后的消息 + 系统指令（关于 CONTEXT_OFFLOAD 标记的说明）重新组装为推理输入 |

> **注意**：如果不注册 `AutoContextHook`，压缩不会自动触发，`ContextOffloadTool` 也不会注册。

### 3.5 配置参数详解

```java
AutoContextConfig config = AutoContextConfig.builder()
    // === 触发阈值 ===
    .msgThreshold(100)                       // 消息数达到此值触发压缩（默认 100）
    .maxToken(128 * 1024)                    // 上下文窗口最大 token 数（默认 128K）
    .tokenRatio(0.75)                        // token 使用率超过此比例触发压缩（默认 0.75）

    // === 保护策略 ===
    .lastKeep(50)                            // 最近 N 条消息不被压缩（默认 50）

    // === 工具消息压缩 ===
    .minConsecutiveToolMessages(6)           // 连续工具消息至少达到此数量才压缩（默认 6）

    // === 大消息卸载 ===
    .largePayloadThreshold(5 * 1024)         // 超过此字符数视为大消息（默认 5KB）
    .offloadSinglePreview(200)               // 卸载后保留的预览字符数（默认 200）

    // === LLM 压缩控制 ===
    .minCompressionTokenThreshold(5000)      // 最小压缩 token 阈值，低于此值不调用 LLM（默认 5000）
    .currentRoundCompressionRatio(0.3)       // 当前轮压缩目标比例（默认 0.3，即压缩到 30%）

    // === 自定义提示词（可选） ===
    .customPrompt(PromptConfig.builder()
        .previousRoundToolCompressPrompt("...")   // 策略 1 的自定义提示词
        .previousRoundSummaryPrompt("...")        // 策略 4 的自定义提示词
        .currentRoundLargeMessagePrompt("...")    // 策略 5 的自定义提示词
        .currentRoundCompressPrompt("...")        // 策略 6 的自定义提示词
        .build())
    .build();
```

#### 参数选择建议

| 场景 | msgThreshold | tokenRatio | lastKeep | 说明 |
|------|-------------|------------|----------|------|
| 短对话/测试 | 20-30 | 0.1-0.3 | 10-20 | 快速触发，方便观察压缩行为 |
| 一般应用 | 50-100 | 0.5-0.7 | 20-30 | 平衡压缩频率和上下文完整性 |
| 长对话/复杂任务 | 100-200 | 0.7-0.8 | 40-60 | 减少压缩频率，保留更多上下文 |

### 3.6 六种压缩策略

`compressIfNeeded()` 在每次推理前被调用，按**从轻到重**的顺序尝试策略，**成功一个就停止**：

#### 策略 1：TOOL_INVOCATION_COMPRESS（工具调用压缩）

- **目标**：压缩历史中的连续工具调用（ToolUse + ToolResult 对）
- **条件**：连续工具消息数 > `minConsecutiveToolMessages`，且 token > `minCompressionTokenThreshold`
- **方式**：调用 LLM 将多轮工具调用摘要为一条 ASSISTANT 消息
- **特点**：会过滤掉计划相关工具调用（如 create_plan、update_plan_info 等）
- **LLM 调用**：是

#### 策略 2：LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION（带保护的大消息卸载）

- **目标**：卸载 `lastKeep` 之前的大消息
- **方式**：将大消息截断为预览文本，原始内容卸载到 offloadContext
- **安全机制**：跳过包含 ToolUseBlock 的 ASSISTANT 消息（避免导致孤立的 ToolResult）；保留 ToolResultBlock 的结构信息（id、name）
- **LLM 调用**：否

#### 策略 3：LARGE_MESSAGE_OFFLOAD（大消息卸载）

- **目标**：与策略 2 相同，但不受 `lastKeep` 保护，搜索更早的历史
- **方式**：同策略 2
- **LLM 调用**：否

#### 策略 4：PREVIOUS_ROUND_CONVERSATION_SUMMARY（前轮对话摘要）

- **目标**：将历史的多轮对话压缩为摘要
- **方式**：找到所有 USER-ASSISTANT 对，调用 LLM 将每轮对话改写为自包含的摘要
- **特点**：从后往前处理以保持索引正确；过滤计划相关工具调用
- **LLM 调用**：是

#### 策略 5：CURRENT_ROUND_LARGE_MESSAGE_SUMMARY（当前轮大消息摘要）

- **目标**：压缩当前轮（最新 USER 消息之后）的大消息
- **方式**：调用 LLM 对大消息进行智能摘要（比简单的截断预览更智能）
- **LLM 调用**：是

#### 策略 6：CURRENT_ROUND_MESSAGE_COMPRESS（当前轮消息压缩）

- **目标**：压缩当前轮的所有消息
- **方式**：调用 LLM 将所有消息压缩为一条带字符数要求的摘要
- **特点**：会计算原始字符数 * `currentRoundCompressionRatio` 作为压缩目标
- **LLM 调用**：是

#### 策略选择流程图

```
compressIfNeeded() 被调用
        |
        v
  消息数 >= msgThreshold 或 token >= maxToken * tokenRatio？
        |
    否 --+-- 是
    |        |
    返回     v
         策略 1: 有足够的连续工具消息？ --是--> 压缩工具调用，返回
           |
          否
           v
         策略 2: lastKeep 外有大消息？ --是--> 截断预览 + 卸载，返回
           |
          否
           v
         策略 3: 更早的历史中有大消息？ --是--> 截断预览 + 卸载，返回
           |
          否
           v
         策略 4: 有历史对话轮次？ --是--> LLM 摘要，返回
           |
          否
           v
         策略 5: 当前轮有大消息？ --是--> LLM 智能摘要，返回
           |
          否
           v
         策略 6: 当前轮有消息？ --是--> LLM 全量压缩，返回
           |
          否
           v
         返回（无法压缩）
```

### 3.7 上下文卸载与重载机制

#### 卸载（Offload）

当消息被压缩时，原始消息会被保存到 `offloadContext`（内存中的 `HashMap<String, List<Msg>>`），并在压缩后的摘要中嵌入标记：

```html
<!-- CONTEXT_OFFLOAD: uuid=550e8400-e29b-41d4-a716-446655440000 -->
```

#### 重载（Reload）

`ContextOffloadTool` 会被自动注册到 Agent 的 Toolkit 中，LLM 可以通过工具调用按需重载被卸载的上下文：

```
LLM 看到压缩摘要中的 CONTEXT_OFFLOAD 标记
        |
        v
LLM 调用 context_reload(working_context_offload_uuid="550e8400-...")
        |
        v
ContextOffloadTool 从 offloadContext 中取出原始消息返回给 LLM
```

#### 卸载存储的生命周期

```
压缩时  -> offload(uuid, messages)   存入内存 Map
重载时  -> reload(uuid)              从 Map 中取出
清除时  -> clear(uuid)               从 Map 中移除
会话保存 -> saveTo(session, key)     序列化为 OffloadContextState 持久化
会话加载 -> loadFrom(session, key)   反序列化恢复 offloadContext
```

### 3.8 计划感知压缩

当 Agent 开启了 `enablePlan()` 并注册了 `AutoContextHook` 时，`AutoContextMemory` 会自动感知 `PlanNotebook`：

1. **过滤计划工具调用**：压缩时会排除 `create_plan`、`update_plan_info`、`finish_subtask` 等计划相关工具，避免丢失计划状态
2. **注入计划提示**：压缩提示词末尾会追加当前计划信息（目标、进度、预期结果），让 LLM 在压缩时保留计划相关的关键信息

### 3.9 会话持久化

`AutoContextMemory` 实现了 `StateModule` 接口，支持完整的会话持久化。`saveTo()` 会保存四个部分：

| 数据 | 说明 |
|------|------|
| 工作消息 (workingMemoryStorage) | 压缩后的消息列表 |
| 原始消息 (originalMemoryStorage) | 完整的未压缩历史 |
| 卸载上下文 (offloadContext) | UUID -> 消息列表的映射 |
| 压缩事件 (compressionEvents) | 所有压缩操作的审计日志 |

```java
// 持久化
agent.saveTo(session, "user_123");

// 恢复（加载时会恢复全部四个部分）
agent.loadIfExists(session, "user_123");
```

### 3.10 实用 API

```java
// 获取压缩后的消息（发送给 LLM 的）
List<Msg> working = memory.getMessages();

// 获取完整的原始消息（审计用）
List<Msg> original = memory.getOriginalMemoryMsgs();

// 获取交互消息（USER 消息 + 最终 ASSISTANT 回复）
List<Msg> interactions = memory.getInteractionMsgs();

// 手动触发压缩检查
memory.compressIfNeeded();

// 查看压缩审计日志
List<CompressionEvent> events = memory.getCompressionEvents();
for (CompressionEvent event : events) {
    System.out.println(event.eventType() + ": " + event.compressedMessageCount() + " messages");
}

// 附加/分离 PlanNotebook（通常由 AutoContextHook 自动完成）
memory.attachPlanNote(planNotebook);
memory.detachPlanNote();
```

### 3.11 模块依赖

`AutoContextMemory` 位于独立的扩展模块中，使用时需要添加依赖：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-autocontext-memory</artifactId>
</dependency>
```

---

## 四、总结：InMemoryMemory vs AutoContextMemory

| 特性 | InMemoryMemory | AutoContextMemory |
|------|---------------|-------------------|
| 上下文管理 | 无，消息无限增长 | 自动压缩和卸载 |
| Token 控制 | 无 | 按配置阈值自动触发 |
| 双存储 | 无（单存储） | 工作存储 + 原始存储 |
| 发给 LLM 的内容 | **全量历史消息** | **压缩后的消息** |
| 大消息处理 | 不处理 | 自动卸载 + 预览 |
| LLM 调用 | 无 | 压缩时需要 LLM 进行摘要 |
| 按需重载 | 不支持 | 通过 ContextOffloadTool 按需加载原始数据 |
| 适用场景 | 短对话、简单场景 | 长对话、复杂 Agent 任务 |
| 会话持久化 | 支持 | 支持（保存更多数据） |

---

## 五、最佳实践

1. **始终注册 `AutoContextHook`**：否则压缩不会自动触发
2. **合理设置 `tokenRatio`**：根据模型的上下文窗口大小设置，建议在 0.4-0.8 之间
3. **`lastKeep` 不宜过小**：建议保留至少 10-20 条消息，确保最近上下文完整
4. **`minCompressionTokenThreshold` 避免浪费**：低于此阈值的压缩不值得调用 LLM
5. **复杂任务开启 `enablePlan()`**：计划感知压缩能更好地保留任务相关上下文
6. **长期对话搭配 `LongTermMemory`**：短期记忆负责当前会话上下文，长期记忆负责跨会话知识
7. **短对话用 `InMemoryMemory` 即可**：如果对话不会超过模型的上下文窗口，无需引入 AutoContextMemory 的额外复杂度
