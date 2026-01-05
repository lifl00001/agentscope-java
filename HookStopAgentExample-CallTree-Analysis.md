# HookStopAgentExample Main方法调用树和恢复机制分析

本文档详细分析 `HookStopAgentExample.main()` 方法的完整调用树，并深入探讨 Hook Stop 机制的停止方式和恢复方式。

## 一、完整调用树 (Call Tree)

### 1. Main 方法入口
```
HookStopAgentExample.main(String[] args)
│
├─→ ExampleUtils.printWelcome()                    # 打印欢迎信息
│
├─→ ExampleUtils.getDashScopeApiKey()              # 获取 API Key
│
├─→ new Toolkit()                                  # 创建工具包
│   └─→ Toolkit.registerTool(new SensitiveTools()) # 注册敏感工具
│
├─→ new ToolConfirmationHook()                     # 创建确认Hook
│
├─→ ReActAgent.builder()                           # 构建Agent
│   ├─→ .name("SafeAgent")
│   ├─→ .sysPrompt(...)
│   ├─→ .model(DashScopeChatModel.builder()...)
│   ├─→ .toolkit(toolkit)
│   ├─→ .memory(new InMemoryMemory())
│   ├─→ .hooks(List.of(confirmationHook))
│   └─→ .build()
│       └─→ ReActAgent.<init>()                    # 初始化Agent实例
│
└─→ startChatWithConfirmation(agent)               # 启动交互循环
    └─→ [进入交互循环] (详见2.0)
```

### 2. 交互循环调用树

#### 2.1 正常调用流程（无停止）
```
startChatWithConfirmation(agent)
│
├─→ Scanner.nextLine()                              # 读取用户输入
│
├─→ Msg.builder()...build()                         # 构建用户消息
│
└─→ agent.call(userMsg).block()                     # 调用Agent
    │
    └─→ AgentBase.call(Msg msg)                     # Agent基类调用入口
        ├─→ TracerRegistry.get().callAgent(...)     # 跟踪记录
        ├─→ notifyPreCall(msgs)                     # PreCallEvent通知
        ├─→ doCall(msgs)                            # 执行调用 → [3.0]
        ├─→ notifyPostCall(result)                  # PostCallEvent通知
        └─→ .doFinally(signalType -> running.set(false))
```

#### 2.2 Hook Stop 后的处理流程
```
[Agent返回带ToolUseBlock的消息]
│
└─→ while (hasPendingToolUse(response))             # 检测到待执行工具
    │
    ├─→ displayPendingToolCalls(response)           # 显示待执行工具
    │
    ├─→ Scanner.nextLine()                          # 读取用户确认
    │
    ├─→ [用户输入 "yes" 或 "y"]
    │   └─→ agent.call().block()                    # 无参调用恢复 → [5.1]
    │
    ├─→ [用户输入 "no" 或 "n"]
    │   ├─→ createCancelledToolResults(response, agent.getName())
    │   │   └─→ Msg.builder()...build()             # 创建取消结果消息
    │   └─→ agent.call(cancelResult).block()        # 传入取消结果 → [5.2]
    │
    └─→ [循环直到没有待执行工具]
```

### 3. ReActAgent 核心执行流程

#### 3.0 doCall 方法
```
ReActAgent.doCall(List<Msg> msgs)
│
├─→ memory.addMessage(msgs)                         # 将用户消息添加到记忆
│
├─→ findLastAssistantMsg()                          # 查找最后一条助手消息
│
├─→ hasPendingToolUse(lastAssistant)                # 检查是否有待执行工具
│   ├─→ [有待执行工具]
│   │   └─→ acting(0)                               # 直接进入Acting阶段 → [4.0]
│   │
│   └─→ [无待执行工具]
│       └─→ executeIteration(0)                     # 开始新的迭代 → [3.1]
```

#### 3.1 执行迭代（Reasoning阶段）
```
executeIteration(int iter)
└─→ reasoning(iter, false)
    │
    ├─→ checkInterruptedAsync()                     # 检查中断
    │
    ├─→ notifyPreReasoningEvent(prepareMessages())  # PreReasoningEvent
    │
    ├─→ model.stream(...)                           # LLM流式推理
    │   └─→ .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
    │
    ├─→ .doOnNext(chunk -> context.processChunk(chunk))
    │   └─→ notifyReasoningChunk(msg, context)      # ReasoningChunkEvent
    │
    ├─→ context.buildFinalMessage()                 # 构建最终消息
    │
    └─→ notifyPostReasoning(msg)                    # PostReasoningEvent → [3.2]
        └─→ [Hook触发点] → [3.3]
```

#### 3.2 PostReasoning Hook处理
```
notifyPostReasoning(Msg msg)
│
└─→ notifyHooks(new PostReasoningEvent(...))
    │
    └─→ ToolConfirmationHook.onEvent(event)         # Hook处理
        │
        ├─→ [检测敏感工具]
        │   ├─→ reasoningMsg.getContentBlocks(ToolUseBlock.class)
        │   └─→ toolCalls.stream().anyMatch(tool -> SENSITIVE_TOOLS.contains(...))
        │
        └─→ [发现敏感工具]
            └─→ postReasoning.stopAgent()           # 请求停止 ★★★
                └─→ this.stopRequested = true
```

#### 3.3 PostReasoning后续处理
```
notifyPostReasoning(msg).flatMap(event -> {
    │
    ├─→ memory.addMessage(msg)                      # 添加推理消息到记忆
    │
    ├─→ [检查停止请求]
    │   └─→ if (event.isStopRequested())            # ★★★ 停止检测点
    │       └─→ return Mono.just(msg)               # 返回带ToolUseBlock的消息
    │                                                # [流程终止，返回用户]
    │
    ├─→ [检查gotoReasoning请求]
    │   └─→ if (event.isGotoReasoningRequested())
    │       └─→ reasoning(iter + 1, true)           # 重新推理
    │
    ├─→ [检查完成条件]
    │   └─→ if (isFinished(msg))
    │       └─→ return Mono.just(msg)               # 完成，返回消息
    │
    └─→ [继续Acting]
        └─→ acting(iter)                            # 进入Acting阶段 → [4.0]
})
```

### 4. Acting 阶段（工具执行）

#### 4.0 Acting 方法
```
acting(int iter)
│
├─→ extractRecentToolCalls()                        # 提取待执行工具
│   └─→ MessageUtils.extractRecentToolCalls(memory.getMessages(), getName())
│
├─→ [无工具调用]
│   └─→ executeIteration(iter + 1)                  # 继续下一轮迭代
│
└─→ [有工具调用]
    │
    ├─→ notifyPreActingHooks(allToolCalls)          # PreActingEvent
    │
    ├─→ executeToolCalls(toolCalls)                 # 执行工具
    │   └─→ toolkit.callTools(...)
    │       └─→ SensitiveTools.deleteFile() / sendEmail() / searchWeb()
    │
    └─→ [处理结果]
        ├─→ [分离成功和挂起的结果]
        │   ├─→ successPairs = filter(!isSuspended())
        │   └─→ pendingPairs = filter(isSuspended())
        │
        ├─→ [处理成功结果]
        │   └─→ Flux.fromIterable(successPairs)
        │       └─→ notifyPostActingHook(entry)     # PostActingEvent → [4.1]
        │
        └─→ [检查停止或挂起]
            ├─→ if (event.isStopRequested())        # ★★★ Acting阶段停止点
            │   └─→ return event.getToolResultMsg() # 返回工具结果消息
            │
            ├─→ if (!pendingPairs.isEmpty())
            │   └─→ buildSuspendedMsg(pendingPairs) # 构建挂起消息
            │
            └─→ executeIteration(iter + 1)          # 继续下一轮迭代
```

#### 4.1 PostActing Hook处理
```
notifyPostActingHook(Map.Entry<ToolUseBlock, ToolResultBlock> entry)
│
├─→ ToolResultMessageBuilder.buildToolResultMsg(...)  # 构建工具结果消息
│
├─→ new PostActingEvent(this, toolkit, toolUse, result)
│
├─→ notifyHooks(event)                              # 通知所有Hooks
│   └─→ [Hook可以调用 event.stopAgent()]          # ★★★ 另一个停止点
│
└─→ .doOnNext(e -> memory.addMessage(e.getToolResultMsg()))  # 添加到记忆
```

### 5. 恢复机制调用流程

#### 5.1 确认继续执行（无参调用）
```
agent.call().block()                                # 无参数调用
│
└─→ AgentBase.call()
    └─→ ReActAgent.doCall(List.of())                # 空消息列表
        │
        ├─→ memory.addMessage(List.of())            # 无消息添加
        │
        ├─→ findLastAssistantMsg()                  # 查找最后助手消息
        │   └─→ [返回之前停止时的消息，包含ToolUseBlock]
        │
        ├─→ hasPendingToolUse(lastAssistant)        # 检测到待执行工具
        │   └─→ return true ★★★                     # 关键：检测到恢复点
        │
        └─→ acting(0)                               # 直接进入Acting阶段
            │                                        # 从中断的地方恢复执行
            ├─→ extractRecentToolCalls()            # 提取之前的工具调用
            │   └─→ [从记忆中找到未执行的ToolUseBlock]
            │
            └─→ executeToolCalls(...)               # 执行工具
                └─→ [正常执行流程] → [4.0]
```

#### 5.2 取消执行（传入取消结果）
```
agent.call(cancelResult).block()                    # 传入取消结果消息
│
└─→ AgentBase.call(Msg msg)
    └─→ ReActAgent.doCall(List.of(cancelResult))
        │
        ├─→ memory.addMessage(cancelResult)         # 添加取消结果到记忆
        │   └─→ [包含 ToolResultBlock: "Operation cancelled by user"]
        │
        ├─→ findLastAssistantMsg()                  # 查找最后助手消息
        │
        ├─→ hasPendingToolUse(lastAssistant)        # 检查待执行工具
        │   └─→ [检查记忆中的ToolResultBlock]
        │       └─→ return false ★★★                # 所有工具已有结果
        │
        └─→ executeIteration(0)                     # 开始新迭代
            └─→ reasoning(0, false)                 # 重新推理
                │                                    # Agent会看到取消消息
                └─→ [Agent根据取消结果生成新回复]
```

## 二、Hook Stop 停止方式分析

### 停止方式1：PostReasoningEvent.stopAgent()

**触发时机**：LLM推理完成后，工具执行前

**触发位置**：
```java
// ReActAgent.reasoning() 方法中
.flatMap(this::notifyPostReasoning)
.flatMap(event -> {
    // HITL stop
    if (event.isStopRequested()) {  // ← 检查点
        return Mono.just(msg);       // ← 返回包含ToolUseBlock的消息
    }
    // ...继续执行
})
```

**停止条件**：
- Hook在 `PostReasoningEvent` 中检测到敏感操作
- 调用 `postReasoning.stopAgent()` 设置停止标志
- 推理消息包含 `ToolUseBlock`

**停止结果**：
- Agent返回包含 `ToolUseBlock` 的 `Msg`
- 消息已添加到记忆中
- 工具**未执行**
- 流程完全终止，控制权返回调用者

**代码示例**：
```java
// ToolConfirmationHook.java
public <T extends HookEvent> Mono<T> onEvent(T event) {
    if (event instanceof PostReasoningEvent postReasoning) {
        List<ToolUseBlock> toolCalls = postReasoning.getReasoningMessage()
            .getContentBlocks(ToolUseBlock.class);
        
        boolean hasSensitiveTool = toolCalls.stream()
            .anyMatch(tool -> SENSITIVE_TOOLS.contains(tool.getName()));
        
        if (hasSensitiveTool) {
            postReasoning.stopAgent();  // ★ 停止点
        }
    }
    return Mono.just(event);
}
```

### 停止方式2：PostActingEvent.stopAgent()

**触发时机**：工具执行完成后，下一轮推理前

**触发位置**：
```java
// ReActAgent.acting() 方法中
.concatMap(this::notifyPostActingHook)
.last()
.flatMap(event -> {
    // HITL stop
    if (event.isStopRequested()) {  // ← 检查点
        return Mono.just(event.getToolResultMsg());  // ← 返回工具结果消息
    }
    // ...继续执行
})
```

**停止条件**：
- Hook在 `PostActingEvent` 中检测到需要审查的工具结果
- 调用 `postActing.stopAgent()` 设置停止标志

**停止结果**：
- Agent返回包含 `ToolResultBlock` 的 `Msg`
- 工具**已执行**
- 工具结果已添加到记忆中
- 流程终止，控制权返回调用者

**使用场景**：
- 工具执行结果需要人工审查
- 结构化输出完成后停止（StructuredOutputHook）
- 工具执行后的质量检查

### 停止方式对比

| 特性 | PostReasoningEvent.stopAgent() | PostActingEvent.stopAgent() |
|------|-------------------------------|----------------------------|
| **停止时机** | 推理后，工具执行前 | 工具执行后 |
| **工具是否执行** | ❌ 未执行 | ✅ 已执行 |
| **返回消息类型** | 包含 ToolUseBlock | 包含 ToolResultBlock |
| **记忆状态** | 包含推理消息 | 包含推理消息+工具结果 |
| **典型场景** | 敏感操作确认 | 结果审查、结构化输出 |
| **恢复方式** | 继续执行工具 或 提供新输入 | 提供新输入继续对话 |

## 三、Hook Stop 恢复方式分析

### 恢复方式1：无参调用 `agent.call()`

**适用场景**：确认继续执行

**恢复机制**：
```java
// ReActAgent.doCall() 方法
protected Mono<Msg> doCall(List<Msg> msgs) {
    // 1. 添加消息（无参调用时为空列表）
    if (msgs != null) {
        msgs.forEach(memory::addMessage);
    }
    
    // 2. 检查是否有待执行工具
    Msg lastAssistant = findLastAssistantMsg();
    if (lastAssistant != null && hasPendingToolUse(lastAssistant)) {
        // ★★★ 关键恢复点：直接进入acting阶段
        return acting(0);
    }
    
    // 3. 否则开始新迭代
    return executeIteration(0);
}
```

**恢复过程详解**：

1. **记忆状态检查**
   ```java
   private boolean hasPendingToolUse(Msg msg) {
       // 获取助手消息中的ToolUseBlock
       List<ToolUseBlock> toolUses = msg.getContentBlocks(ToolUseBlock.class);
       
       // 获取记忆中所有ToolResultBlock的ID
       Set<String> toolResultIds = memory.getMessages().stream()
           .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
           .map(ToolResultBlock::getId)
           .collect(Collectors.toSet());
       
       // 检查是否有未匹配的ToolUse
       return toolUses.stream()
           .anyMatch(tu -> !toolResultIds.contains(tu.getId()));
   }
   ```

2. **工具提取**
   ```java
   private List<ToolUseBlock> extractRecentToolCalls() {
       return MessageUtils.extractRecentToolCalls(
           memory.getMessages(), 
           getName()
       );
   }
   ```

3. **执行恢复**
   - 从记忆中提取未执行的 `ToolUseBlock`
   - 直接进入 `acting()` 阶段
   - 执行工具调用
   - 正常流程继续

**示例代码**：
```java
// HookStopAgentExample.java
if (confirmation.equals("yes") || confirmation.equals("y")) {
    System.out.println("Resuming execution...\n");
    response = agent.call().block();  // ★ 无参调用恢复
}
```

### 恢复方式2：提供取消结果消息

**适用场景**：取消操作，提供替代结果

**恢复机制**：
```java
// ReActAgent.doCall() 方法
protected Mono<Msg> doCall(List<Msg> msgs) {
    // 1. 添加取消结果消息到记忆
    if (msgs != null) {
        msgs.forEach(memory::addMessage);  // ← 包含 ToolResultBlock
    }
    
    // 2. 检查待执行工具
    Msg lastAssistant = findLastAssistantMsg();
    if (lastAssistant != null && hasPendingToolUse(lastAssistant)) {
        // ★ 此时 hasPendingToolUse() 返回 false
        // 因为记忆中已有匹配的 ToolResultBlock
        return acting(0);  // 不会执行（无待执行工具）
    }
    
    // 3. 开始新的推理迭代
    return executeIteration(0);  // ← 走这个分支
}
```

**创建取消结果**：
```java
static Msg createCancelledToolResults(Msg toolUseMsg, String agentName) {
    List<ToolUseBlock> toolCalls = toolUseMsg.getContentBlocks(ToolUseBlock.class);
    
    // 为每个待执行工具创建取消结果
    List<ToolResultBlock> results = toolCalls.stream()
        .map(tool -> ToolResultBlock.of(
            tool.getId(),           // ★ 关键：ID匹配
            tool.getName(),
            TextBlock.builder()
                .text("Operation cancelled by user. Please try a different approach.")
                .build()
        ))
        .toList();
    
    return Msg.builder()
        .name(agentName)
        .role(MsgRole.TOOL)
        .content(results.toArray(new ToolResultBlock[0]))
        .build();
}
```

**恢复效果**：
- Agent看到工具"执行"结果（实际是取消消息）
- Agent会根据取消信息重新推理
- 可能提供替代方案或询问用户

**示例代码**：
```java
// HookStopAgentExample.java
else if (confirmation.equals("no") || confirmation.equals("n")) {
    System.out.println("Operation cancelled by user.\n");
    Msg cancelResult = createCancelledToolResults(response, agent.getName());
    response = agent.call(cancelResult).block();  // ★ 传入取消结果
}
```

### 恢复方式3：提供新的用户消息

**适用场景**：改变意图，提供新指令

**恢复机制**：
```java
// 用户提供新消息
Msg newUserMsg = Msg.builder()
    .name("user")
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("新的指令").build())
    .build();

response = agent.call(newUserMsg).block();
```

**处理过程**：
1. 新消息添加到记忆
2. 检查待执行工具（仍存在）
3. 但工具执行可能失败或被跳过
4. Agent会结合新消息和现有上下文重新推理

**记忆状态**：
```
[ASSISTANT] "我准备删除文件 temp.txt" + ToolUseBlock(delete_file)
[USER] "不，改为搜索文件信息"  ← 新消息
[继续执行...]
```

### 恢复方式对比

| 恢复方式 | 方法调用 | 记忆变化 | 执行路径 | 适用场景 |
|---------|---------|---------|---------|---------|
| **无参调用** | `agent.call()` | 无变化 | 直接进入acting | 确认继续执行 |
| **取消结果** | `agent.call(cancelMsg)` | 添加ToolResultBlock | 进入新reasoning | 取消操作 |
| **新用户消息** | `agent.call(newMsg)` | 添加USER消息 | 可能acting或reasoning | 改变意图 |

## 四、从中断点精确恢复的关键机制

### 关键1：记忆中的状态标记

**ToolUseBlock 和 ToolResultBlock 的ID匹配**：
```java
// ToolUseBlock (在推理消息中)
{
    "id": "call_123",
    "name": "delete_file",
    "input": {"filename": "temp.txt"}
}

// ToolResultBlock (在工具结果消息中)
{
    "id": "call_123",  // ← 相同ID表示已执行
    "name": "delete_file",
    "output": [...]
}
```

**状态判断逻辑**：
```java
private boolean hasPendingToolUse(Msg msg) {
    List<ToolUseBlock> toolUses = msg.getContentBlocks(ToolUseBlock.class);
    Set<String> toolResultIds = memory.getMessages().stream()
        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
        .map(ToolResultBlock::getId)
        .collect(Collectors.toSet());
    
    // ★ 通过ID匹配判断哪些工具尚未执行
    return toolUses.stream()
        .anyMatch(tu -> !toolResultIds.contains(tu.getId()));
}
```

### 关键2：Acting阶段的工具提取

**extractRecentToolCalls() 的作用**：
```java
private List<ToolUseBlock> extractRecentToolCalls() {
    return MessageUtils.extractRecentToolCalls(
        memory.getMessages(), 
        getName()
    );
}

// MessageUtils.extractRecentToolCalls() 实现（简化）
public static List<ToolUseBlock> extractRecentToolCalls(
        List<Msg> messages, String agentName) {
    
    // 1. 找到最后一条助手消息
    // 2. 提取其中的 ToolUseBlock
    // 3. 过滤掉已有结果的工具
    // 4. 返回待执行的工具列表
}
```

### 关键3：Mono响应式链的状态保持

**记忆作为状态容器**：
```java
// 停止时
.flatMap(event -> {
    if (msg != null) {
        memory.addMessage(msg);  // ★ 状态已保存
    }
    if (event.isStopRequested()) {
        return Mono.just(msg);    // ★ 返回，但状态在记忆中
    }
    // ...
})

// 恢复时
protected Mono<Msg> doCall(List<Msg> msgs) {
    // ★ 从记忆中读取状态
    Msg lastAssistant = findLastAssistantMsg();
    if (lastAssistant != null && hasPendingToolUse(lastAssistant)) {
        return acting(0);  // ★ 精确恢复到acting阶段
    }
    // ...
}
```

## 五、完整执行时序图

### 场景1：Hook Stop → 确认继续执行

```
用户: "删除文件 temp.txt"
  ↓
[1] ReActAgent.call(userMsg)
  ↓
[2] doCall() → executeIteration(0) → reasoning(0)
  ↓
[3] Model推理 → 生成带 ToolUseBlock 的消息
  ↓
[4] notifyPostReasoning(msg)
  ↓
[5] ToolConfirmationHook.onEvent()
  ├─ 检测到敏感工具: delete_file
  └─ postReasoning.stopAgent()  ★ 停止
  ↓
[6] memory.addMessage(msg)  // 消息已保存
  ↓
[7] return Mono.just(msg)   // 返回带 ToolUseBlock 的消息
  ↓
[8] 用户看到: "⚠️ Agent paused for confirmation"
  ↓
[9] 用户输入: "yes"
  ↓
[10] agent.call().block()  // 无参调用
  ↓
[11] doCall(List.of())
  ├─ findLastAssistantMsg() → 找到步骤[6]保存的消息
  ├─ hasPendingToolUse() → true (ToolUse ID未匹配到Result)
  └─ acting(0)  ★ 精确恢复
  ↓
[12] extractRecentToolCalls() → 提取 delete_file 调用
  ↓
[13] executeToolCalls() → 执行 delete_file
  ↓
[14] notifyPostActingHook() → 添加 ToolResultBlock 到记忆
  ↓
[15] executeIteration(1) → 继续下一轮推理
  ↓
[16] 返回最终结果
```

### 场景2：Hook Stop → 取消执行

```
用户: "删除文件 temp.txt"
  ↓
[1-7] 同场景1，Agent停止并返回
  ↓
[8] 用户看到: "⚠️ Agent paused for confirmation"
  ↓
[9] 用户输入: "no"
  ↓
[10] createCancelledToolResults()
  └─ 创建 ToolResultBlock (id=call_123, output="cancelled")
  ↓
[11] agent.call(cancelMsg).block()
  ↓
[12] doCall(List.of(cancelMsg))
  ├─ memory.addMessage(cancelMsg)  // 添加取消结果
  ├─ findLastAssistantMsg() → 找到带 ToolUseBlock 的消息
  ├─ hasPendingToolUse() → false  ★ ID已匹配
  └─ executeIteration(0)  // 开始新推理
  ↓
[13] reasoning(0)
  └─ Agent看到记忆:
      ASSISTANT: "我准备删除..." + ToolUse(call_123)
      TOOL: ToolResult(call_123, "cancelled")
  ↓
[14] Agent重新推理，生成回复:
  "好的，我不会删除文件。还有什么我可以帮助的吗？"
  ↓
[15] 返回最终结果
```

## 六、总结

### Hook Stop 的核心优势

1. **精确控制**：可以在推理后或工具执行后任意点停止
2. **状态保持**：通过记忆系统完整保存执行状态
3. **灵活恢复**：支持继续、取消、修改等多种恢复方式
4. **类型安全**：通过ID匹配确保工具调用和结果的对应关系

### 停止方式总结

| 停止位置 | API | 时机 | 工具状态 | 返回内容 |
|---------|-----|------|---------|---------|
| **PostReasoningEvent** | `stopAgent()` | 推理后，工具执行前 | 未执行 | ToolUseBlock |
| **PostActingEvent** | `stopAgent()` | 工具执行后 | 已执行 | ToolResultBlock |

### 恢复方式总结

| 恢复方式 | 方法 | 效果 | 记忆变化 |
|---------|------|------|---------|
| **继续执行** | `agent.call()` | 执行原计划的工具 | 无 |
| **取消操作** | `agent.call(cancelMsg)` | 提供取消结果，重新推理 | +ToolResultBlock |
| **新指令** | `agent.call(newMsg)` | 改变意图，新推理 | +USER消息 |

### 关键实现机制

1. **记忆系统**：作为状态容器保存执行上下文
2. **ID匹配**：通过ToolUseBlock和ToolResultBlock的ID关联判断状态
3. **条件路由**：doCall()方法根据记忆状态选择执行路径
4. **Hook标志**：通过stopRequested标志传递停止信号

这种设计使得AgentScope能够实现真正的人机协作，在关键决策点暂停执行，等待人类确认后精确恢复，既保证了安全性又保持了执行的连续性。
