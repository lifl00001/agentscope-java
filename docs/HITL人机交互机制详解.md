# AgentScope Java HITL (Human-in-the-Loop) 人机交互机制详解

## 概述

AgentScope Java 提供了两种互补的 HITL 暂停机制，允许在 Agent 执行过程中插入人工审核或交互环节：

| 机制 | 触发方式 | 暂停时机 | 返回的 GenerateReason | 典型场景 |
|---|---|---|---|---|
| **Hook-based** | Hook 中调用 `stopAgent()` | 推理后 / 行动后 | `REASONING_STOP_REQUESTED` / `ACTING_STOP_REQUESTED` | 敏感操作确认、结果审核 |
| **Tool-based** | 工具方法抛出 `ToolSuspendException` | 工具执行时 | `TOOL_SUSPENDED` | 用户输入采集、外部工具执行 |

---

## 一、Hook-based 机制（基于钩子的暂停）

### 1.1 原理

Hook-based 机制通过在 Agent 的 Hook 事件链中调用 `stopAgent()` 方法，在推理阶段或行动阶段之后中断 Agent 的执行循环。

ReActAgent 的执行循环分为 **Reasoning（推理）** 和 **Acting（行动）** 两个阶段。Hook 可以在这两个阶段结束时介入，检查 Agent 的输出，并决定是否暂停。

```
用户消息 → [Reasoning: LLM 生成回复]
                ↓
         PostReasoningEvent  ← Hook 在此检查，可调用 stopAgent()
                ↓
         [Acting: 执行工具]
                ↓
         PostActingEvent     ← Hook 在此检查，可调用 stopAgent()
                ↓
         下一轮 Reasoning ...
```

### 1.2 两个暂停时机

#### 推理后暂停（PostReasoningEvent.stopAgent()）

**暂停时机**：LLM 已决定要调用哪些工具，但 **尚未执行**。

**适用场景**：工具确认 —— 用户可以看到工具名称和参数，决定是否允许执行。

**源码位置**：`PostReasoningEvent.java:99`

```java
public void stopAgent() {
    this.stopRequested = true;
}
```

**ReActAgent 中的处理**（`ReActAgent.java:518-523`）：

```java
// HITL stop
if (event.isStopRequested()) {
    return Mono.just(
        msg.withGenerateReason(GenerateReason.REASONING_STOP_REQUESTED));
}
```

**返回内容**：包含 `ToolUseBlock`（待执行的工具调用，含工具名和参数），`GenerateReason` 为 `REASONING_STOP_REQUESTED`。

#### 行动后暂停（PostActingEvent.stopAgent()）

**暂停时机**：工具已执行完毕，但在 **进入下一轮推理之前**。

**适用场景**：结果审核 —— 用户可以查看工具执行结果，决定是否让 Agent 继续推理。

**源码位置**：`PostActingEvent.java:98`

**ReActAgent 中的处理**（`ReActAgent.java:611-618`）：

```java
if (event.isStopRequested()) {
    return Mono.just(
        event.getToolResultMsg()
            .withGenerateReason(GenerateReason.ACTING_STOP_REQUESTED));
}
```

**返回内容**：包含 `ToolResultBlock`（工具执行结果），`GenerateReason` 为 `ACTING_STOP_REQUESTED`。

### 1.3 恢复方式

暂停后，调用方根据用户的选择来恢复 Agent：

| 用户操作 | 恢复方式 | 说明 |
|---|---|---|
| **确认执行** | `agent.call()`（无参） | Agent 继续执行待处理的工具调用 |
| **拒绝执行** | `agent.call(toolResultMsg)` | 构造一个包含取消信息的 `ToolResultBlock` 消息，以 `MsgRole.TOOL` 角色发送，Agent 收到后继续推理 |

**拒绝时构造取消消息示例**：

```java
Msg cancelResult = Msg.builder()
    .role(MsgRole.TOOL)
    .content(pending.stream()
        .map(t -> ToolResultBlock.of(
            t.getId(),
            t.getName(),
            TextBlock.builder().text("操作已取消").build()))
        .toArray(ToolResultBlock[]::new))
    .build();
response = agent.call(cancelResult).block();
```

### 1.4 完整示例：敏感工具确认 Hook

以下示例展示如何在 Agent 调用 `delete_file`、`send_email` 等敏感工具前暂停，等待用户确认。

```java
Hook confirmationHook = new Hook() {
    private static final List<String> SENSITIVE_TOOLS =
        List.of("delete_file", "send_email");

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            Msg reasoningMsg = e.getReasoningMessage();
            List<ToolUseBlock> toolCalls =
                reasoningMsg.getContentBlocks(ToolUseBlock.class);

            boolean hasSensitive = toolCalls.stream()
                .anyMatch(t -> SENSITIVE_TOOLS.contains(t.getName()));

            if (hasSensitive) {
                e.stopAgent();
            }
        }
        return Mono.just(event);
    }
};

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hook(confirmationHook)
    .build();
```

### 1.5 两个示例中的实现对比

#### advanced/hitl — ToolConfirmationHook（静态配置）

```java
public class ToolConfirmationHook implements Hook {
    private final Set<String> toolsRequiringConfirmation;

    public ToolConfirmationHook(Set<String> toolsRequiringConfirmation) {
        this.toolsRequiringConfirmation = toolsRequiringConfirmation;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            Msg reasoning = post.getReasoningMessage();
            if (reasoning != null && hasToolRequiringConfirmation(reasoning)) {
                post.stopAgent();
            }
        }
        return Mono.just(event);
    }
}
```

- 敏感工具列表在构造时固定，不可运行时修改
- 适合工具集已知的场景

#### hitl-chat — ToolConfirmationHook（动态配置）

```java
public class ToolConfirmationHook implements Hook {
    private final Set<String> dangerousTools;

    // 运行时可动态增删
    public void addDangerousTool(String toolName) { ... }
    public void removeDangerousTool(String toolName) { ... }
    public void setDangerousTools(Set<String> toolNames) { ... }
}
```

- 敏感工具列表可通过 REST API 动态增删（`GET/POST /api/settings/dangerous-tools`）
- 适合工具集动态变化的场景（如 MCP 动态加载工具后，可运行时将某些标记为危险）

### 1.6 Hook-based 的特点总结

| 特点 | 说明 |
|---|---|
| **控制粒度** | 外部控制 —— 由 Hook 逻辑决定是否暂停，与工具内部无关 |
| **暂停时机灵活** | 可在推理后（看参数）或行动后（看结果）暂停 |
| **可修改事件** | Hook 可通过 `setReasoningMessage()`、`setToolResult()` 修改 Agent 的输出 |
| **决策逻辑集中** | 所有暂停策略集中在一个 Hook 中管理 |
| **不侵入工具代码** | 工具本身无需任何改动 |

---

## 二、Tool-based 机制（基于工具的暂停）

### 2.1 原理

Tool-based 机制通过在工具方法内部抛出 `ToolSuspendException`，由框架自动将异常转换为"挂起状态"，实现暂停。

**执行流程**：

```
Agent 决定调用 ask_user 工具
        ↓
ToolExecutor 调用 tool.callAsync()
        ↓
工具方法抛出 ToolSuspendException
        ↓
ToolExecutor.onErrorResume() 捕获异常（ToolExecutor.java:244-253）
        ↓
调用 ToolResultBlock.suspended(toolUse, exception) 生成挂起的 ToolResultBlock
   - metadata 中包含 agentscope_suspended=true
   - output 中包含异常的 reason 文本
        ↓
ReActAgent.acting() 将结果分为 success 和 pending 两组（ReActAgent.java:588-595）
        ↓
pending 组不为空 → 调用 buildSuspendedMsg()（ReActAgent.java:642-654）
   - 构造包含 ToolUseBlock + pending ToolResultBlock 的消息
   - GenerateReason = TOOL_SUSPENDED
        ↓
Agent 返回给调用方，等待用户输入
```

### 2.2 核心类

#### ToolSuspendException

```java
public class ToolSuspendException extends RuntimeException {
    private final String reason;

    public ToolSuspendException() {
        this(null);
    }

    public ToolSuspendException(String reason) {
        super(reason != null ? reason : "Tool execution suspended");
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
```

- 继承自 `RuntimeException`，不强制捕获
- `reason` 字段会被传递到 `ToolResultBlock` 中，作为挂起原因的文本描述

#### ToolResultBlock.suspended() — 框架转换逻辑

```java
public static ToolResultBlock suspended(ToolUseBlock toolUse, ToolSuspendException exception) {
    String content = exception.getReason() != null
        ? exception.getReason()
        : "[Awaiting external execution]";
    return new ToolResultBlock(
        toolUse.getId(),
        toolUse.getName(),
        List.of(TextBlock.builder().text(content).build()),
        Map.of(METADATA_SUSPENDED, true)  // "agentscope_suspended" -> true
    );
}
```

关键标识：`metadata` 中 `"agentscope_suspended" = true`，通过 `isSuspended()` 方法判断。

#### ToolExecutor 中的异常捕获

```java
// ToolExecutor.java:243-253
return tool.callAsync(executionParam)
    .onErrorResume(ToolSuspendException.class, e -> {
        logger.debug("Tool '{}' suspended: {}",
            toolCall.getName(),
            e.getReason() != null ? e.getReason() : "no reason");
        return Mono.just(ToolResultBlock.suspended(toolCall, e));
    })
    .onErrorResume(e -> {
        // 其他异常视为工具执行失败
        return Mono.just(ToolResultBlock.error("Tool execution failed: " + errorMsg));
    });
```

注意：`ToolSuspendException` 被视为**正常挂起**（debug 级别日志），而其他异常被视为**执行失败**。

#### SchemaOnlyTool — 外部工具的声明式注册

```java
public class SchemaOnlyTool implements AgentTool {
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.error(new ToolSuspendException());
    }
}
```

用于只提供 Schema 定义、没有执行逻辑的工具。通过 `toolkit.registerSchema(schema)` 注册。

### 2.3 恢复方式

暂停后，用户需要手动构造 `ToolResultBlock` 并以 `MsgRole.TOOL` 角色发送给 Agent：

```java
// 用户提供了答案
ToolResultBlock userResult = ToolResultBlock.of(
    toolUseBlock.getId(),    // 必须匹配挂起的 ToolUseBlock 的 ID
    toolUseBlock.getName(),  // 必须匹配挂起的 ToolUseBlock 的 Name
    TextBlock.builder().text(userResponse).build()
);

Msg resultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(userResult)
    .build();

// 恢复 Agent
response = agent.call(resultMsg).block();
```

**关键**：`ToolResultBlock` 的 `id` 和 `name` 必须与挂起的 `ToolUseBlock` 一一匹配，框架通过 `ToolValidator.validateToolResultMatch()` 进行校验。

### 2.4 完整调用过程分析（以 ask_user 为例）

以下用 `advanced/hitl` 示例中的健身教练场景，逐步追踪 Tool-based 机制从发起到恢复的完整过程。

#### 场景设定

用户说："帮我安排下周的健身计划"，Agent 需要先收集用户的健身目标、身体信息等，再生成计划。

#### Agent 初始化

```java
// HitlInteractionExample.java:125-128
toolkit = new Toolkit();
toolkit.registerTool(new UserInteractionTool());   // 注册 ask_user 工具
toolkit.registerTool(new AddCalendarEventTool());   // 注册 add_calendar_event 工具

// HitlInteractionExample.java:311-320
ReActAgent agent = ReActAgent.builder()
    .name("FitnessCoach")
    .sysPrompt(SYS_PROMPT)    // 告诉 LLM 用 ask_user 收集信息，不要直接提问
    .model(model)
    .toolkit(toolkit)
    .hook(new ToolConfirmationHook(Set.of("add_calendar_event")))  // Hook-based
    .build();
```

此时 LLM 知道自己有两个工具可用：
- `ask_user`：description 中写明"当信息不完整时向用户提问，支持 text/select/form 等 UI 类型"
- `add_calendar_event`：添加日历事件

#### 第 1 步：用户发送消息

```http
POST /api/chat
{ "sessionId": "session-1", "message": "帮我安排下周的健身计划" }
```

```java
// HitlInteractionExample.java:174-183
Msg userMsg = Msg.builder()
    .name("User")
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("帮我安排下周的健身计划").build())
    .build();

// 调用 agent.stream(userMsg)，触发 ReActAgent.doCall()
```

#### 第 2 步：ReActAgent.doCall() 入口

```java
// ReActAgent.java:264-271
protected Mono<Msg> doCall(List<Msg> msgs) {
    Set<String> pendingIds = getPendingToolUseIds();  // 检查是否有挂起的工具调用

    // 首次调用，没有挂起的工具 → 正常处理
    if (pendingIds.isEmpty()) {
        addToMemory(msgs);       // [USER] "帮我安排下周的健身计划" 写入 Memory
        return executeIteration(0);  // 开始第 0 轮迭代
    }
    ...
}
```

**此时 Memory**：
```
[USER] "帮我安排下周的健身计划"
```

#### 第 3 步：Reasoning（LLM 推理）

```
executeIteration(0) → reasoning(0)
```

框架将 Memory 中的对话历史 + system prompt + 工具列表发送给 LLM。

**LLM 看到的输入**（简化）：
```
[System] You are a professional fitness coach... NEVER ask questions in plain text.
         ALWAYS use the ask_user tool. Collect missing information one at a time.
[User]   帮我安排下周的健身计划

可用工具:
  ask_user(question, ui_type, options, fields, ...) — 向用户提问收集信息
  add_calendar_event(...) — 添加日历事件
```

**LLM 的输出**（模型决定调用 ask_user）：
```json
{
  "tool_calls": [{
    "id": "call_abc123",
    "type": "function",
    "function": {
      "name": "ask_user",
      "arguments": {
        "question": "你的健身目标是什么？",
        "ui_type": "select",
        "options": ["Fat Loss", "Muscle Gain", "General Fitness", "Flexibility"]
      }
    }
  }]
}
```

> **关键：LLM 为什么会调用 ask_user？**
>
> 不是因为异常（异常还没发生），而是 LLM 根据三个信号做出了决策：
> 1. **System Prompt** 明确要求："NEVER ask questions in plain text. ALWAYS use the ask_user tool."
> 2. **@Tool(description=...)** 告诉 LLM 这个工具用于 "when the request is ambiguous or missing required details"
> 3. **对话上下文**中确实缺少健身目标、身体信息等必要数据
>
> LLM 像人类一样理解这些自然语言描述，自主判断出"我需要先问用户要信息"。

#### 第 4 步：PostReasoningEvent（推理后处理）

```java
// ReActAgent.java:513-515
Msg msg = event.getReasoningMessage();   // 包含 ToolUseBlock(ask_user) 的消息
if (msg != null) {
    memory.addMessage(msg);  // ✅ 写入 Memory
}

// ReActAgent.java:518-519
if (event.isStopRequested()) { ... }
// ToolConfirmationHook 检查 ToolUseBlock 的 name 是 "ask_user"，
// 不在 TOOLS_REQUIRING_CONFIRMATION 中，不调用 stopAgent()
```

**此时 Memory**：
```
[USER]     "帮我安排下周的健身计划"
[ASSISTANT] ToolUseBlock { id: "call_abc123", name: "ask_user",
              input: { question: "你的健身目标是什么？", ui_type: "select",
                       options: ["Fat Loss", ...] } }
```

Agent 继续进入 Acting 阶段。

#### 第 5 步：Acting（工具执行）

```
reasoning(0) → acting(0)
```

```java
// ReActAgent.java:571
List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();
// 找到 call_abc123（Memory 中有 ToolUseBlock 但没有对应的 ToolResultBlock）

// ReActAgent.java:583-584
return notifyPreActingHooks(pendingToolCalls)
    .flatMap(this::executeToolCalls)
```

**executeToolCalls → ToolExecutor → UserInteractionTool.askUser()**：

```java
// UserInteractionTool.java:75-114
public String askUser(String question, String uiType, ...) {
    String reason = question != null ? question : "Waiting for user input";
    throw new ToolSuspendException(reason);
    // 抛出 ToolSuspendException("你的健身目标是什么？")
    // ← 注意：这个异常永远不会到达 LLM，也不会到达前端
}
```

**ToolExecutor 捕获异常**（`ToolExecutor.java:243-253`）：

```java
return tool.callAsync(executionParam)
    .onErrorResume(ToolSuspendException.class, e -> {
        // 异常在这里被吞掉，转为一个特殊的 ToolResultBlock
        return Mono.just(ToolResultBlock.suspended(toolCall, e));
        // 生成: { id: "call_abc123", name: "ask_user",
        //         output: "你的健身目标是什么？",
        //         metadata: { "agentscope_suspended": true } }
    })
```

#### 第 6 步：分离 success / pending 结果

```java
// ReActAgent.java:588-601
results = [ (ToolUseBlock(call_abc123), ToolResultBlock(suspended)) ]

// 分组：
successPairs = []                    // 空 — suspended 的不算 success
pendingPairs  = [(call_abc123, suspended_result)]  // 全部是 pending

if (successPairs.isEmpty()) {
    if (!pendingPairs.isEmpty()) {
        return Mono.just(buildSuspendedMsg(pendingPairs));
        // ⚠️ 不经过 notifyPostActingHook，所以不执行 memory.addMessage()
    }
}
```

> **关键**：只有 `successPairs` 才会走 `notifyPostActingHook` → `memory.addMessage()` 路径。
> `pendingPairs` 直接被 `buildSuspendedMsg()` 打包返回给调用方，**不会写入 Memory**。

**buildSuspendedMsg 的输出**（`ReActAgent.java:642-654`）：

```java
// 构造的消息包含 ToolUseBlock + suspended ToolResultBlock
return Msg.builder()
    .name("FitnessCoach")
    .role(MsgRole.ASSISTANT)
    .content(toolUseBlock, suspendedToolResultBlock)
    .generateReason(GenerateReason.TOOL_SUSPENDED)  // ← 标记原因
    .build();
```

**此时 Memory**（未变化）：
```
[USER]     "帮我安排下周的健身计划"
[ASSISTANT] ToolUseBlock { id: "call_abc123", name: "ask_user", input: {...} }
            ← 没有 ToolResultBlock。Memory 中存在一个"未完成"的工具调用。
```

#### 第 7 步：前端收到 SSE 事件

`HitlInteractionExample.convertEvent()` 处理流式事件：

```java
// HitlInteractionExample.java:390-399
case AGENT_RESULT -> {
    GenerateReason reason = msg.getGenerateReason();
    if (reason == GenerateReason.TOOL_SUSPENDED) {
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        for (ToolUseBlock tool : toolCalls) {
            if (UserInteractionTool.TOOL_NAME.equals(tool.getName())) {
                events.add(userInteractionEvent(tool));
                // 从 ToolUseBlock.getInput() 中提取 UI 规格
            }
        }
    }
}
```

**前端收到的 SSE 数据**：
```json
{
  "type": "USER_INTERACTION",
  "toolId": "call_abc123",
  "question": "你的健身目标是什么？",
  "uiType": "select",
  "options": ["Fat Loss", "Muscle Gain", "General Fitness", "Flexibility"]
}
```

> **注意**：前端是从 `ToolUseBlock.getInput()` 中提取的 UI 规格，不是从 `ToolSuspendException` 的 reason 中。
> `ToolUseBlock` 是 LLM 在第 3 步推理时输出的，其 input 参数（question、ui_type、options）是 LLM 根据工具 description 中的指引自动生成的。

前端根据 `uiType: "select"` 和 `options` 渲染一个单选按钮组。

#### 第 8 步：用户选择并提交

用户点击 "Muscle Gain"，前端发送：

```http
POST /api/chat/respond
{ "sessionId": "session-1", "toolId": "call_abc123", "response": "Muscle Gain" }
```

#### 第 9 步：构造 ToolResultBlock 并恢复 Agent

```java
// HitlInteractionExample.java:222-231
ToolResultBlock result = ToolResultBlock.of(
    "call_abc123",              // id 匹配挂起的 ToolUseBlock
    "ask_user",                 // name 匹配
    TextBlock.builder().text("User responded: Muscle Gain").build()
);

Msg responseMsg = Msg.builder()
    .role(MsgRole.TOOL)         // ← 关键：角色是 TOOL
    .content(result)
    .build();

Flux<Map<String, Object>> events = agent.stream(responseMsg).flatMap(this::convertEvent);
// 触发 ReActAgent.doCall() 第二次调用
```

#### 第 10 步：ReActAgent.doCall() 第二次入口

```java
// ReActAgent.java:264-298
protected Mono<Msg> doCall(List<Msg> msgs) {
    Set<String> pendingIds = getPendingToolUseIds();
    // pendingIds = {"call_abc123"} ← Memory 中有 ToolUseBlock 但没有 ToolResultBlock

    // 第一个 if 不走（pendingIds 不为空）

    // 检查用户是否提供了 tool results
    List<ToolResultBlock> providedResults = msgs.stream()
        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
        .toList();
    // providedResults = [ToolResultBlock(id=call_abc123, output="User responded: Muscle Gain")]

    if (!providedResults.isEmpty()) {
        validateAndAddToolResults(msgs, pendingIds);  // 校验 id 匹配，写入 Memory
        // ✅ 新的 ToolResultBlock 被写入 Memory

        return hasPendingToolUse() ? acting(0) : executeIteration(0);
        // call_abc123 现在有结果了 → hasPendingToolUse() = false
        // → executeIteration(0)  → 开始新一轮推理
    }
}
```

**此时 Memory**：
```
[USER]     "帮我安排下周的健身计划"
[ASSISTANT] ToolUseBlock { id: "call_abc123", name: "ask_user",
              input: { question: "你的健身目标是什么？", ... } }
[TOOL]     ToolResultBlock { id: "call_abc123", name: "ask_user",
              output: "User responded: Muscle Gain" }
```

> **这就是 LLM 在第 11 步看到的完整上下文**。LLM 看到一个完整的"工具调用 → 结果"对。

#### 第 11 步：LLM 再次推理（第 2 轮）

```
executeIteration(0) → reasoning(0)
```

**LLM 看到的输入**（简化）：
```
[System]   You are a professional fitness coach... ALWAYS use the ask_user tool...
[User]     帮我安排下周的健身计划
[Assistant] (调用 ask_user, question="你的健身目标是什么？")
[Tool]     User responded: Muscle Gain
```

LLM 理解用户选择了 "Muscle Gain"，但还缺少身体信息，于是**再次调用 ask_user**：

```json
{
  "tool_calls": [{
    "id": "call_def456",
    "function": {
      "name": "ask_user",
      "arguments": {
        "question": "请提供你的身体信息",
        "ui_type": "form",
        "fields": [
          { "name": "age", "label": "年龄", "type": "number" },
          { "name": "height", "label": "身高(cm)", "type": "number" },
          { "name": "weight", "label": "体重(kg)", "type": "number" }
        ]
      }
    }
  }]
}
```

然后重复第 4-10 步的流程，LLM 会逐个收集完所有信息后，最终生成健身计划并调用 `add_calendar_event`（此时会触发 Hook-based 的 ToolConfirmationHook，进入另一个 HITL 流程）。

#### 全流程时序图

```
 用户          前端              ReActAgent              LLM              ToolExecutor
  │             │                   │                      │                    │
  │  "帮我安排"  │                   │                      │                    │
  │────────────>│  POST /api/chat   │                      │                    │
  │             │──────────────────>│  doCall()             │                    │
  │             │                   │  addToMemory(USER)    │                    │
  │             │                   │  reasoning(0)────────>│                    │
  │             │                   │                      │  "调用 ask_user"    │
  │             │                   │<──────────────────────│                    │
  │             │                   │  memory.addMessage(   │                    │
  │             │                   │    ASSISTANT+ToolUse) │                    │
  │             │                   │  acting(0)───────────────────────────────>│
  │             │                   │                      │  ask_user() 抛出    │
  │             │                   │                      │  ToolSuspendException
  │             │                   │                      │<───────────────────│
  │             │                   │                      │  suspended Result   │
  │             │                   │<─────────────────────────────────────────│
  │             │                   │  buildSuspendedMsg()  │                    │
  │             │  SSE: USER_INTERACTION                   │                    │
  │             │<──────────────────│  (TOOL_SUSPENDED)     │                    │
  │             │                   │                      │                    │
  │  渲染 select │                   │                      │                    │
  │  点击按钮    │                   │                      │                    │
  │────────────>│                   │                      │                    │
  │             │  POST /api/chat/respond                  │                    │
  │             │──────────────────>│  doCall()             │                    │
  │             │                   │  validateAndAdd(      │                    │
  │             │                   │    TOOL ResultBlock)  │                    │
  │             │                   │  executeIteration(0)  │                    │
  │             │                   │  reasoning(0)────────>│                    │
  │             │                   │                      │  看到完整对话历史    │
  │             │                   │                      │  "再问身体信息"      │
  │             │                   │<──────────────────────│                    │
  │             │       ... 重复收集信息 ...                │                    │
  │             │                   │                      │                    │
  │             │                   │  最终生成健身计划      │                    │
  │  收到计划    │                   │                      │                    │
  │<────────────│<──────────────────│                      │                    │
```

#### 关键数据隔离：谁看到什么

| 数据 | LLM 是否可见 | 前端是否可见 | Memory 中是否存在 |
|---|---|---|---|
| `ToolSuspendException` 异常对象 | 否 | 否 | 否 |
| 异常的 reason 文本 | 否 | 否（不单独传递） | 否 |
| suspended ToolResultBlock | 否 | 否（包含在 AGENT_RESULT 中但前端不读取） | **否** |
| ToolUseBlock（LLM 的工具调用意图） | **是**（推理时就是它生成的） | **是**（从 AGENT_RESULT 中提取 input） | **是**（reasoning 后写入） |
| 用户恢复时的新 ToolResultBlock | **是**（下一轮推理时读取） | 否 | **是**（doCall 中写入） |

#### 总结：Tool-based 的本质

Tool-based 机制的本质是一个 **"LLM 自主发起、框架暂停、调用方恢复"** 的三方协作：

1. **LLM 自主发起**：LLM 根据 description + system prompt + 对话上下文，判断需要用户输入，决定调用 `ask_user` 工具。这个决策完全由 LLM 做出，不需要任何外部信号。

2. **框架暂停**：`ask_user` 方法抛出 `ToolSuspendException`，框架在 `ToolExecutor` 中捕获并转为 suspended `ToolResultBlock`，`ReActAgent` 将其打包为 `TOOL_SUSPENDED` 消息返回给调用方。**suspended 结果不写入 Memory**。

3. **调用方恢复**：调用方（前端/API）从 `ToolUseBlock.getInput()` 中提取 UI 规格，渲染组件，收集用户输入后构造新的 `ToolResultBlock` 发回。新结果写入 Memory，LLM 在下一轮推理中看到完整的"调用→结果"对。

### 2.5 Tool-based 的特点总结

| 特点 | 说明 |
|---|---|
| **控制粒度** | 内部控制 — 由工具自身决定何时暂停 |
| **LLM 主动触发** | LLM 根据 `@Tool(description=...)` + system prompt + 对话上下文，自主决定是否调用该工具 |
| **异常不可见** | `ToolSuspendException` 及其 reason 不会暴露给 LLM，只在框架内部流转 |
| **暂停结果不入 Memory** | suspended ToolResultBlock 不写入 Memory，避免 LLM 看到异常信息 |
| **UI 规格由 LLM 生成** | ToolUseBlock 的 input 参数（question、ui_type、options 等）是 LLM 根据 description 指引自动生成的 |
| **工具与暂停耦合** | 工具代码中必须显式抛出异常 |
| **适合外部执行** | `SchemaOnlyTool` 提供声明式注册方式 |

---

## 三、Tool-based 的适用场景

### 3.1 场景一：用户输入采集（ask_user 模式）

**何时使用**：Agent 在对话过程中需要向用户收集信息，且 LLM 能自主判断何时需要询问。

**与 Hook-based 的区别**：Hook-based 的暂停是由外部规则驱动的（"检测到敏感工具就暂停"），而 Tool-based 的暂停是由 LLM 自主决策驱动的（"我觉得需要问用户一个问题"）。

**典型场景**：
- 订单信息不完整，需要用户补充地址
- 健身教练需要了解用户的身体指标
- 预订系统需要用户选择时间、人数等

**核心优势**：LLM 可以通过 `ui_type` 参数告诉前端渲染什么类型的 UI 组件，实现智能化的表单交互。

### 3.2 场景二：外部工具执行（SchemaOnlyTool 模式）

**何时使用**：某些工具的执行需要在 Agent 框架之外完成（如调用本地桌面应用、调用需要人工操作的硬件设备等）。

**典型场景**：
- 通过 MCP 注册了工具 Schema，但执行逻辑在外部系统
- 需要调用本地安装的桌面软件
- 需要人工操作物理设备后上报结果

```java
// 只注册 Schema，不提供执行逻辑
ToolSchema schema = ToolSchema.builder()
    .name("print_document")
    .description("Print a document on the local printer")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of(
            "content", Map.of("type", "string"),
            "copies", Map.of("type", "integer")
        ),
        "required", List.of("content")
    ))
    .build();

toolkit.registerSchema(schema);
// LLM 决定调用 print_document 时，框架自动暂停等待外部结果
```

### 3.3 场景对比：何时选 Hook-based vs Tool-based

| 维度 | Hook-based | Tool-based |
|---|---|---|
| **决策者** | 开发者（预设规则） | LLM（自主判断） |
| **暂停触发** | 条件匹配（工具名、参数等） | 工具被调用时自动暂停 |
| **灵活性** | 可随时修改规则，不改动工具 | 暂停逻辑嵌入工具代码 |
| **信息展示** | 展示工具名 + 参数 | 可自定义 UI 规格 |
| **适合场景** | 安全审计、操作确认、合规检查 | 用户输入采集、外部工具执行 |
| **是否需要新工具** | 不需要 | 需要专门编写一个抛异常的工具 |

**简单判断规则**：
- 如果是"我（开发者）想在某类操作前拦截" → **Hook-based**
- 如果是"我希望 Agent 能主动向用户提问/请求外部操作" → **Tool-based**

---

## 四、两种机制的组合使用

`advanced/hitl` 示例展示了两种机制的组合使用：

```
用户: "帮我安排下周的健身计划"

ReActAgent 推理:
  1. 调用 ask_user(question="你的身高体重是多少？", ui_type="form", fields=[...])
     → ToolSuspendException → TOOL_SUSPENDED → 前端渲染表单
     → 用户填写 → 构造 ToolResultBlock → Agent 恢复推理

  2. 调用 add_calendar_event(date="周一 18:00", event="力量训练")
     → ToolConfirmationHook 检测到敏感工具 → stopAgent() → REASONING_STOP_REQUESTED
     → 前端渲染确认按钮 → 用户确认 → Agent 恢复执行

  3. 返回最终训练计划
```

组合使用时的 `GenerateReason` 判断逻辑：

```java
Msg response = agent.call(userMsg).block();
switch (response.getGenerateReason()) {
    case TOOL_SUSPENDED -> {
        // Tool-based 暂停：展示 UI，等待用户输入
        List<ToolUseBlock> toolCalls = response.getContentBlocks(ToolUseBlock.class);
        // 从 toolCall.getInput() 中提取 UI 规格
    }
    case REASONING_STOP_REQUESTED -> {
        // Hook-based 暂停：展示工具信息，等待用户确认
        List<ToolUseBlock> pending = response.getContentBlocks(ToolUseBlock.class);
        // 展示工具名和参数，提供确认/拒绝按钮
    }
    case ACTING_STOP_REQUESTED -> {
        // Hook-based 暂停：展示执行结果，等待用户审核
        // 展示 ToolResultBlock 内容
    }
    case MODEL_STOP -> {
        // Agent 正常完成
    }
}
```

---

## 五、框架安全机制

### 5.1 PendingToolRecoveryHook

默认注册（优先级 10），防止孤立的 `ToolUseBlock`（无匹配 `ToolResultBlock`）导致 Agent 崩溃。

当用户发送了新消息但没有为挂起的工具提供结果时，自动生成错误 `ToolResultBlock`：

```
可通过 ReActAgent.Builder.enablePendingToolRecovery(false) 禁用，
以便完全手动控制 HITL 流程。
```

### 5.2 ToolValidator

在恢复 Agent 时校验用户提供的 `ToolResultBlock` 与挂起的 `ToolUseBlock` 是否一一匹配（id 和 name 必须对应），防止部分或错误的结果导致 Agent 状态异常。

### 5.3 gotoReasoning() — 不暂停的替代方案

`PostReasoningEvent` 提供了 `gotoReasoning()` 方法，可以在不暂停 Agent 的情况下直接注入消息并继续推理循环：

```java
// 在 Hook 中注入提示消息，让 Agent 重新推理（不暂停）
event.gotoReasoning(Msg.builder()
    .role(MsgRole.SYSTEM)
    .textContent("请用中文回答")
    .build());
```

---

## 六、关键源码文件索引

| 文件 | 说明 |
|---|---|
| `agentscope-core/.../hook/PostReasoningEvent.java` | 推理后事件，提供 `stopAgent()` 和 `gotoReasoning()` |
| `agentscope-core/.../hook/PostActingEvent.java` | 行动后事件，提供 `stopAgent()` |
| `agentscope-core/.../tool/ToolSuspendException.java` | 工具挂起异常 |
| `agentscope-core/.../tool/ToolExecutor.java:244-253` | 异常捕获与转换逻辑 |
| `agentscope-core/.../tool/SchemaOnlyTool.java` | 外部工具声明式注册 |
| `agentscope-core/.../message/ToolResultBlock.java` | 工具结果块，`suspended()` 工厂方法和 `isSuspended()` 判断 |
| `agentscope-core/.../message/GenerateReason.java` | 暂停原因枚举 |
| `agentscope-core/.../ReActAgent.java:518-523` | Hook-based 推理后暂停处理 |
| `agentscope-core/.../ReActAgent.java:588-654` | Tool-based 挂起处理和 `buildSuspendedMsg()` |
| `agentscope-core/.../hook/PendingToolRecoveryHook.java` | 孤立工具调用安全兜底 |
| `agentscope-core/.../tool/ToolValidator.java` | 恢复时的结果匹配校验 |
| `agentscope-examples/advanced/.../hitl/` | 完整示例（两种机制组合） |
| `agentscope-examples/hitl-chat/` | 完整示例（仅 Hook-based + 动态配置 + 前端） |
