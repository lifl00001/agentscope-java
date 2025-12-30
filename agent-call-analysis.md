# Agent.call() 深度分析

## 1. agent.call(userMsg).block() 的完整调用链路 (Call Tree)

### 1.1 入口层 (Entry Level)

```
用户代码: agent.call(userMsg).block()
    ↓
Agent接口: call(Msg msg) [默认方法]
    ↓ 转换为List
Agent接口: call(List<Msg> msgs)
    ↓
AgentBase.call(List<Msg> msgs) [第165-186行]
```

### 1.2 核心流程层 (Core Flow Level)

```
AgentBase.call(List<Msg> msgs)
├─ 1. 检查运行状态 (running.compareAndSet)
├─ 2. 重置中断标志 (resetInterruptFlag)
├─ 3. TracerRegistry.get().callAgent() [链路追踪包装]
│   └─ 执行核心逻辑:
│       ├─ notifyPreCall(msgs) [前置Hook通知]
│       ├─ doCall(msgs) [调用子类实现]
│       ├─ notifyPostCall(response) [后置Hook通知]
│       └─ onErrorResume(createErrorHandler) [错误处理]
└─ 4. doFinally(running.set(false)) [重置运行状态]
```

### 1.3 ReActAgent 实现层 (ReActAgent Implementation Level)

```
ReActAgent.doCall(List<Msg> msgs) [第168-173行]
├─ 1. 消息存储到Memory
│   └─ msgs.forEach(memory::addMessage)
│       └─ AutoContextMemory.addMessage(Msg)
│           ├─ workingMemoryStorage.add(message)
│           └─ originalMemoryStorage.add(message)
│
└─ 2. 执行ReAct循环
    └─ executeReActLoop(null)
        └─ executeIteration(0, handler)
```

### 1.4 ReAct 迭代循环层 (ReAct Iteration Loop)

```
executeIteration(int iter, StructuredOutputHandler handler) [第212-221行]
├─ 检查是否达到最大迭代次数
│   └─ if (iter >= maxIters) → summarizing(handler)
│
├─ 正常流程:
│   ├─ checkInterruptedAsync() [检查中断]
│   ├─ reasoning(handler) [推理阶段]
│   │   └─ ReasoningPipeline.execute()
│   ├─ checkInterruptedAsync() [再次检查中断]
│   └─ actingOrFinish(iter, handler) [执行或结束]
│       ├─ 检查是否完成: isFinished()
│       ├─ 如果未完成: acting() [执行阶段]
│       │   └─ ActingPipeline.execute()
│       └─ finishActingOrContinue(iter, handler)
│           └─ executeIteration(iter + 1, handler) [下一次迭代]
```

### 1.5 推理阶段详细流程 (Reasoning Phase Detail)

```
ReasoningPipeline.execute() [第398-402行]
├─ prepareAndStream()
│   ├─ 1. 准备消息列表
│   │   └─ messagePreparer.prepareMessageList(handler)
│   │       ├─ addSystemPromptIfNeeded() [添加系统提示]
│   │       └─ messages.addAll(memory.getMessages()) [添加历史消息]
│   │           └─ AutoContextMemory.getMessages()
│   │               └─ [可能触发自动压缩策略]
│   │
│   ├─ 2. 准备生成选项
│   │   └─ buildGenerateOptions()
│   │
│   ├─ 3. 获取工具Schema
│   │   └─ toolkit.getToolSchemas()
│   │
│   ├─ 4. 前置Hook通知
│   │   └─ hookNotifier.notifyPreReasoning(agent, messageList)
│   │
│   └─ 5. 流式调用模型
│       └─ model.stream(modifiedMsgs, toolSchemas, options)
│           ├─ DashScopeChatModel.stream()
│           ├─ 处理每个chunk
│           │   └─ processChunkWithInterruptCheck(chunk)
│           │       ├─ checkInterruptedAsync()
│           │       └─ processAndNotifyChunk(chunk)
│           │           ├─ context.processChunk(chunk) [累积内容]
│           │           └─ hookNotifier.notifyStreamingMsg(msg, context)
│           │               └─ 触发ReasoningChunkEvent
│           └─ 完成后: finalizeReasoning()
│
└─ finalizeReasoning(wasInterrupted)
    └─ context.buildFinalMessage()
        └─ processFinalMessage(reasoningMsg, wasInterrupted)
            ├─ hookNotifier.notifyPostReasoning(reasoningMsg)
            ├─ memory.addMessage(modifiedMsg) [保存推理结果]
            └─ notifyPreActingHooks(toolBlocks)
```

### 1.6 执行阶段详细流程 (Acting Phase Detail)

```
ActingPipeline.execute() [第481-495行]
├─ 1. 提取工具调用
│   └─ extractRecentToolCalls()
│       └─ MessageUtils.extractRecentToolCalls(memory.getMessages(), getName())
│
├─ 2. 执行工具
│   └─ toolkit.callTools(toolCalls, toolExecutionConfig, agent, toolExecutionContext)
│       ├─ 对每个tool call:
│       │   ├─ 查找Tool实例
│       │   ├─ 验证参数
│       │   ├─ 执行工具逻辑 (Tool.execute())
│       │   └─ 包装结果为ToolResultBlock
│       └─ 返回: List<ToolResultBlock>
│
├─ 3. 处理工具结果
│   └─ processToolResults(toolCalls, responses)
│       └─ 对每个结果:
│           └─ processSingleToolResult(toolCall, result)
│               ├─ hookNotifier.notifyPostActing(toolCall, result)
│               └─ memory.addMessage(toolMsg) [保存工具结果]
│
└─ 4. 检查中断
    └─ checkInterruptedAsync()
```

### 1.7 完整调用链路图 (Complete Call Tree)

```
用户代码
 └─ agent.call(userMsg).block()
     └─ Agent.call(Msg) → call(List<Msg>)
         └─ AgentBase.call(List<Msg>)
             └─ TracerRegistry.callAgent()
                 ├─ notifyPreCall() [前置Hook]
                 ├─ ReActAgent.doCall(List<Msg>)
                 │   ├─ memory.addMessage() [存储用户消息]
                 │   └─ executeReActLoop()
                 │       └─ executeIteration(0)
                 │           ├─ reasoning()
                 │           │   └─ ReasoningPipeline.execute()
                 │           │       ├─ prepareMessageList()
                 │           │       │   ├─ addSystemPrompt
                 │           │       │   └─ memory.getMessages()
                 │           │       │       └─ [自动压缩]
                 │           │       ├─ model.stream()
                 │           │       │   └─ [LLM推理]
                 │           │       └─ memory.addMessage()
                 │           │           [保存推理结果]
                 │           │
                 │           └─ actingOrFinish()
                 │               ├─ isFinished() [检查]
                 │               ├─ acting()
                 │               │   └─ ActingPipeline.execute()
                 │               │       ├─ extractRecentToolCalls()
                 │               │       ├─ toolkit.callTools()
                 │               │       └─ memory.addMessage()
                 │               │           [保存工具结果]
                 │               │
                 │               └─ executeIteration(1)
                 │                   [下一次迭代，循环...]
                 │
                 ├─ notifyPostCall() [后置Hook]
                 └─ [错误处理]
```

---

## 2. Msg userMsg 和 memory 如何作用到大模型上下文

### 2.1 消息流转过程

#### 步骤1: 用户消息接收
```java
// AutoMemoryExample.java 第121-125行
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text(query).build())
    .build();

Msg response = agent.call(userMsg).block();
```

#### 步骤2: 消息添加到Memory
```java
// ReActAgent.java 第168-173行
@Override
protected Mono<Msg> doCall(List<Msg> msgs) {
    if (msgs != null) {
        msgs.forEach(memory::addMessage);  // ← 关键点1: 用户消息存储
    }
    return executeReActLoop(null);
}
```

```java
// AutoContextMemory.java 第136-140行
@Override
public void addMessage(Msg message) {
    workingMemoryStorage.add(message);      // 工作存储(用于对话)
    originalMemoryStorage.add(message);     // 原始存储(完整历史)
}
```

#### 步骤3: 准备发送给模型的消息列表
```java
// ReActAgent.java MessagePreparer类 第763-770行
List<Msg> prepareMessageList(StructuredOutputHandler handler) {
    List<Msg> messages = new ArrayList<>();
    
    // 1. 添加系统提示
    addSystemPromptIfNeeded(messages);
    
    // 2. 添加历史消息 ← 关键点2: 从Memory获取所有历史
    messages.addAll(memory.getMessages());
    
    return messages;
}
```

#### 步骤4: Memory.getMessages() 的智能处理
```java
// AutoContextMemory.java 第142-250行
@Override
public List<Msg> getMessages() {
    List<Msg> currentContextMessages = new ArrayList<>(workingMemoryStorage);

    // 检查是否需要压缩
    boolean msgCountReached = currentContextMessages.size() >= autoContextConfig.msgThreshold;
    int calculateToken = TokenCounterUtil.calculateToken(currentContextMessages);
    int thresholdToken = (int) (autoContextConfig.maxToken * autoContextConfig.tokenRatio);
    boolean tokenCounterReached = calculateToken >= thresholdToken;

    if (!msgCountReached && !tokenCounterReached) {
        return new ArrayList<>(workingMemoryStorage);  // 未达阈值，直接返回
    }

    // 触发6种渐进式压缩策略:
    // 策略1: 压缩历史工具调用
    // 策略2: 卸载大型消息(带lastKeep保护)
    // 策略3: 卸载大型消息(无lastKeep保护)
    // 策略4: 摘要历史对话轮次
    // 策略5: 摘要当前轮次大型消息
    // 策略6: 压缩当前轮次消息
    
    return new ArrayList<>(workingMemoryStorage);  // 返回压缩后的消息
}
```

#### 步骤5: 构建完整上下文发送给LLM
```java
// ReActAgent.java ReasoningPipeline 第404-417行
private Mono<Void> prepareAndStream() {
    List<Msg> messageList = messagePreparer.prepareMessageList(handler);
    // messageList 结构:
    // [0] System: "You are a helpful AI assistant..."  (sysPrompt)
    // [1] User: "帮我写文件..."  (历史消息1)
    // [2] Assistant: "好的..."  (历史消息2)
    // [3] Tool: "文件已写入..."  (工具结果)
    // [4] User: "再读取一下"  (当前用户消息 - userMsg)
    
    GenerateOptions options = buildGenerateOptions();
    List<ToolSchema> toolSchemas = toolkit.getToolSchemas();

    return hookNotifier.notifyPreReasoning(ReActAgent.this, messageList)
        .flatMapMany(modifiedMsgs -> model.stream(modifiedMsgs, toolSchemas, options))
        // ↑ 这里将完整上下文发送给大模型
        .concatMap(this::processChunkWithInterruptCheck)
        .then();
}
```

### 2.2 上下文构建的完整数据流

```
用户输入 (userMsg)
    ↓
ReActAgent.doCall()
    ├─ memory.addMessage(userMsg)
    │   └─ AutoContextMemory
    │       ├─ workingMemoryStorage.add(userMsg)
    │       └─ originalMemoryStorage.add(userMsg)
    │
    └─ executeReActLoop()
        └─ reasoning()
            └─ ReasoningPipeline.prepareAndStream()
                └─ messagePreparer.prepareMessageList()
                    ├─ 步骤1: 创建空列表
                    │
                    ├─ 步骤2: 添加系统提示
                    │   └─ Msg(SYSTEM, sysPrompt)
                    │
                    ├─ 步骤3: 获取历史消息
                    │   └─ memory.getMessages()
                    │       └─ AutoContextMemory.getMessages()
                    │           ├─ 检查压缩阈值
                    │           ├─ 应用压缩策略(如需要)
                    │           └─ 返回: [msg1, msg2, ..., userMsg]
                    │
                    └─ 步骤4: 构建最终上下文
                        └─ [System, History..., userMsg]
                            ↓
                        model.stream(messages, toolSchemas, options)
                            ↓
                        发送到大模型API
```

### 2.3 AutoContextMemory 的压缩机制

#### 触发条件
```java
// 条件1: 消息数量达到阈值
msgCountReached = messages.size() >= msgThreshold (默认30条)

// 条件2: Token数量达到阈值  
tokenCounterReached = tokens >= (maxToken * tokenRatio)
                              // 默认: model_max_token * 0.4
```

#### 6种渐进式压缩策略

**策略1: 压缩历史工具调用**
```
工具调用序列: [ToolUse1, ToolResult1, ToolUse2, ToolResult2]
    ↓ 压缩
摘要消息: "调用了tool1(参数x)和tool2(参数y), 结果是..."
```

**策略2-3: 卸载大型消息**
```
原消息: Msg(content: 10000字符的长文本)
    ↓ 卸载
UUID = 生成唯一标识
offloadContext.put(UUID, [原消息])
新消息: Msg(content: "内容已卸载, UUID: xxx, 可用context_reload工具加载")
```

**策略4: 摘要历史对话**
```
历史对话:
  User: "今天天气怎么样?"
  Assistant: "今天晴天，温度25度"
  User: "适合出门吗?"
  Assistant: "非常适合出门"
    ↓ 使用LLM摘要
摘要消息: "用户询问天气和出门建议，助手回复晴天25度适合出门"
```

**策略5: 摘要当前轮次大型消息**
```
当前轮次某个消息特别大(超过阈值)
    ↓ 使用LLM生成摘要
保留关键信息，减少Token消耗
```

**策略6: 压缩当前轮次消息**
```
当前轮次所有消息:
  [ToolUse1, ToolResult1, ToolUse2, ToolResult2, ...]
    ↓ 合并压缩(可配置压缩比例)
压缩后: "执行了多个工具，主要完成了..."
```

### 2.4 Memory 对上下文的影响总结

| 方面 | 作用机制 |
|------|---------|
| **消息存储** | 用户消息和所有对话历史都存储在Memory中 |
| **上下文构建** | 每次推理时从Memory.getMessages()获取完整历史 |
| **自动压缩** | AutoContextMemory在getMessages()时自动压缩超长上下文 |
| **双存储机制** | workingMemory(压缩后用于对话) + originalMemory(完整历史) |
| **Token管理** | 通过压缩策略控制发送给LLM的Token数量 |
| **历史保留** | 所有原始消息永久保存在originalMemoryStorage |

---

## 3. SessionManager 如何与 Memory 结合使用

### 3.1 SessionManager 核心设计

```java
// SessionManager.java 第58-66行
public class SessionManager {
    private final String sessionId;                    // 会话ID
    private final List<StateModule> components;        // 状态组件列表
    private Session session;                           // 会话存储实现
}
```

### 3.2 使用流程

#### 初始化阶段
```java
// AutoMemoryExample.java 第86-94行
String sessionId = "session000005";
Path sessionPath = Paths.get(System.getProperty("user.home"), 
                            ".agentscope", "examples", "sessions");

SessionManager sessionManager = SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(sessionPath))
    .addComponent(agent)      // ← 添加Agent组件
    .addComponent(memory);    // ← 添加Memory组件
```

**关键点**: `addComponent()` 方法将Agent和Memory注册为需要持久化的组件

```java
// SessionManager.java 第109-115行
public SessionManager addComponent(StateModule component) {
    if (component == null) {
        throw new IllegalArgumentException("Component cannot be null");
    }
    components.add(component);  // 存储到组件列表
    return this;
}
```

#### 加载会话状态
```java
// AutoMemoryExample.java 第95-98行
if (sessionManager.sessionExists()) {
    sessionManager.loadIfExists();  // 加载已存在的会话
}
```

```java
// SessionManager.java 第125-131行
public void loadIfExists() {
    Session session = checkAndGetSession();
    if (session.sessionExists(sessionId)) {
        Map<String, StateModule> componentMap = buildComponentMap();
        session.loadSessionState(sessionId, componentMap);
        // ↑ 这里会调用每个组件的 loadState() 方法
    }
}
```

#### 保存会话状态
```java
// AutoMemoryExample.java 第133行
sessionManager.saveSession();  // 每次对话后保存
```

```java
// SessionManager.java 第156-160行
public void saveSession() {
    Session session = checkAndGetSession();
    Map<String, StateModule> componentMap = buildComponentMap();
    session.saveSessionState(sessionId, componentMap);
    // ↑ 这里会调用每个组件的 saveState() 方法
}
```

### 3.3 组件名称映射机制

```java
// SessionManager.java 第250-258行
private Map<String, StateModule> buildComponentMap() {
    Map<String, StateModule> componentMap = new LinkedHashMap<>();
    for (StateModule component : components) {
        String name = getComponentName(component);
        componentMap.put(name, component);
    }
    return componentMap;
}
```

```java
// SessionManager.java 第259-272行
private String getComponentName(StateModule component) {
    // 1. 优先使用组件自定义名称
    String componentName = component.getComponentName();
    if (componentName != null && !componentName.trim().isEmpty()) {
        return componentName;
    }

    // 2. 使用类名(首字母小写)
    // 例如: ReActAgent → "reActAgent"
    //       AutoContextMemory → "autoContextMemory"
    String className = component.getClass().getSimpleName();
    return Character.toLowerCase(className.charAt(0)) + className.substring(1);
}
```

### 3.4 Memory 状态持久化机制

#### AutoContextMemory 状态注册
```java
// AutoContextMemory.java 第115-134行
public AutoContextMemory(AutoContextConfig autoContextConfig, Model model) {
    this.model = model;
    this.autoContextConfig = autoContextConfig;
    workingMemoryStorage = new ArrayList<>();
    originalMemoryStorage = new ArrayList<>();
    offloadContext = new HashMap<>();
    compressionEvents = new ArrayList<>();
    
    // 注册需要持久化的状态字段
    registerState("workingMemoryStorage", 
                  MsgUtils::serializeMsgList, 
                  MsgUtils::deserializeToMsgList);
    registerState("originalMemoryStorage", 
                  MsgUtils::serializeMsgList, 
                  MsgUtils::deserializeToMsgList);
    registerState("offloadContext", 
                  MsgUtils::serializeOffloadContext, 
                  MsgUtils::deserializeOffloadContext);
    registerState("compressionEvents", 
                  MsgUtils::serializeCompressionEvents, 
                  MsgUtils::deserializeCompressionEvents);
}
```

#### 状态保存流程
```
SessionManager.saveSession()
    ↓
session.saveSessionState(sessionId, componentMap)
    ↓
JsonSession.saveSessionState()
    ↓
对每个组件:
    component.saveState()
        ↓
    StateModuleBase.saveState()
        ↓
    对每个注册的字段:
        serializer.apply(fieldValue)  // 序列化
            ↓
        Map<fieldName, serializedJson>
            ↓
    写入JSON文件:
    {
      "reActAgent": { ... },
      "autoContextMemory": {
        "workingMemoryStorage": [...],
        "originalMemoryStorage": [...],
        "offloadContext": {...},
        "compressionEvents": [...]
      }
    }
```

#### 状态加载流程
```
SessionManager.loadIfExists()
    ↓
session.loadSessionState(sessionId, componentMap)
    ↓
JsonSession.loadSessionState()
    ↓
从JSON文件读取:
    {
      "autoContextMemory": {
        "workingMemoryStorage": [...],
        "originalMemoryStorage": [...]
      }
    }
    ↓
对每个组件:
    component.loadState(stateData)
        ↓
    StateModuleBase.loadState()
        ↓
    对每个注册的字段:
        deserializer.apply(serializedJson)  // 反序列化
            ↓
        通过反射设置字段值
            ↓
    AutoContextMemory状态恢复完成:
        - workingMemoryStorage 恢复
        - originalMemoryStorage 恢复  
        - offloadContext 恢复
        - compressionEvents 恢复
```

### 3.5 完整的会话持久化流程图

```
会话开始
    ↓
SessionManager.forSessionId("session000005")
    ↓
.withSession(new JsonSession(path))
    ↓
.addComponent(agent)
.addComponent(memory)
    ↓
if (sessionExists()) {
    loadIfExists()
    ↓
    从JSON恢复:
    - Memory的所有历史消息
    - Agent的状态
    - 压缩事件记录
}
    ↓
用户对话循环:
    while (true) {
        用户输入
            ↓
        agent.call(userMsg)
            ├─ memory.addMessage(userMsg)
            ├─ [推理和执行]
            └─ memory.addMessage(response)
            ↓
        显示响应
            ↓
        sessionManager.saveSession()  ← 保存状态
            ↓
        写入JSON:
        {
          "autoContextMemory": {
            "workingMemoryStorage": [
              所有压缩后的消息
            ],
            "originalMemoryStorage": [
              所有原始消息(完整历史)
            ],
            "offloadContext": {
              "uuid1": [卸载的消息],
              "uuid2": [卸载的消息]
            }
          }
        }
    }
```

### 3.6 SessionManager 与 Memory 的协同机制

| 方面 | SessionManager作用 | Memory作用 |
|------|------------------|-----------|
| **状态管理** | 管理多个组件的状态持久化 | 管理对话历史的存储和压缩 |
| **持久化** | 协调保存/加载操作 | 提供状态序列化/反序列化 |
| **会话隔离** | 通过sessionId区分不同会话 | 存储特定会话的消息历史 |
| **恢复机制** | 恢复所有组件状态 | 恢复完整的对话上下文 |
| **组件注册** | 管理组件列表和名称映射 | 注册需要持久化的字段 |

### 3.7 关键设计优势

1. **解耦设计**: SessionManager不依赖具体的Memory实现，通过StateModule接口交互
2. **灵活存储**: 支持多种Session实现(JsonSession, DatabaseSession等)
3. **自动管理**: 组件只需实现StateModule接口，SessionManager自动处理序列化
4. **双存储保护**: AutoContextMemory同时保存压缩版本和原始历史
5. **状态完整性**: 保存时包含所有必要状态(消息、压缩事件、卸载内容)

---

## 4. agent.call() 如何实现 Reasoning 和 Acting

### 4.1 ReAct 模式核心思想

ReAct = **Rea**soning (推理) + **Act**ing (执行)

这是一个迭代循环模式:
```
思考 → 行动 → 观察结果 → 再思考 → 再行动 → ...
```

### 4.2 完整的 Reasoning-Acting 循环

```java
// ReActAgent.java 第212-221行
private Mono<Msg> executeIteration(int iter, StructuredOutputHandler handler) {
    if (iter >= maxIters) {
        return summarizing(handler);  // 达到最大迭代，生成摘要
    }

    return checkInterruptedAsync()           // 1. 检查中断
        .then(reasoning(handler))            // 2. 推理阶段
        .then(Mono.defer(this::checkInterruptedAsync))  // 3. 再次检查中断
        .then(Mono.defer(() -> actingOrFinish(iter, handler)));  // 4. 执行或结束
}
```

### 4.3 Reasoning 阶段详解

#### 4.3.1 核心流程
```java
// ReActAgent.java 第248-250行
private Mono<Void> reasoning(StructuredOutputHandler handler) {
    return new ReasoningPipeline(handler).execute();
}
```

#### 4.3.2 ReasoningPipeline 执行过程
```java
// ReActAgent.java 第388-473行
private class ReasoningPipeline {
    private final StructuredOutputHandler handler;
    private final ReasoningContext context;  // 累积推理内容

    Mono<Void> execute() {
        return prepareAndStream()
            .onErrorResume(this::handleError)
            .then(Mono.defer(this::finalizeReasoningStep));
    }
}
```

#### 4.3.3 准备和流式处理
```java
private Mono<Void> prepareAndStream() {
    // 步骤1: 准备消息列表
    List<Msg> messageList = messagePreparer.prepareMessageList(handler);
    // 结构: [System, History..., CurrentUserMsg]

    // 步骤2: 准备生成选项
    GenerateOptions options = buildGenerateOptions();

    // 步骤3: 获取工具Schema
    List<ToolSchema> toolSchemas = toolkit.getToolSchemas();

    // 步骤4: 流式调用模型
    return hookNotifier.notifyPreReasoning(ReActAgent.this, messageList)
        .flatMapMany(modifiedMsgs -> 
            model.stream(modifiedMsgs, toolSchemas, options))
        .concatMap(this::processChunkWithInterruptCheck)
        .then();
}
```

#### 4.3.4 处理模型返回的每个Chunk
```java
private Flux<Void> processChunkWithInterruptCheck(ChatResponse chunk) {
    return checkInterruptedAsync()
        .thenReturn(chunk)
        .flatMapMany(this::processAndNotifyChunk);
}

private Flux<Void> processAndNotifyChunk(ChatResponse chunk) {
    // 累积chunk内容
    List<Msg> msgs = context.processChunk(chunk);
    
    // 通知Hook(可用于流式输出)
    return Flux.fromIterable(msgs)
        .concatMap(msg -> hookNotifier.notifyStreamingMsg(msg, context));
}
```

#### 4.3.5 完成推理阶段
```java
private Mono<Void> finalizeReasoning(boolean wasInterrupted) {
    // 构建最终的推理消息
    return Mono.fromCallable(context::buildFinalMessage)
        .flatMap(reasoningMsg -> processFinalMessage(reasoningMsg, wasInterrupted));
}

private Mono<Void> processFinalMessage(Msg reasoningMsg, boolean wasInterrupted) {
    if (reasoningMsg == null) {
        return Mono.empty();
    }

    // 提取工具调用块
    List<ToolUseBlock> toolBlocks = reasoningMsg.getContentBlocks(ToolUseBlock.class);

    return hookNotifier.notifyPostReasoning(reasoningMsg)
        .flatMap(modifiedMsg -> {
            // 关键: 将推理结果保存到Memory
            memory.addMessage(modifiedMsg);
            
            // 通知即将执行的工具
            return notifyPreActingHooks(toolBlocks);
        });
}
```

#### 4.3.6 Reasoning 示例

**场景**: 用户说"帮我创建一个hello.txt文件,内容是Hello World"

**输入到模型**:
```
System: "You are a helpful AI assistant..."
User: "帮我创建一个hello.txt文件,内容是Hello World"
```

**模型推理输出** (流式返回):
```json
{
  "role": "assistant",
  "content": [
    {
      "type": "text",
      "text": "我来帮你创建文件"
    },
    {
      "type": "tool_use",
      "name": "WriteFileTool",
      "input": {
        "filePath": "hello.txt",
        "content": "Hello World"
      },
      "id": "call_123"
    }
  ]
}
```

**推理结果保存到Memory**:
```java
Msg reasoningMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .name("Assistant")
    .content([
        TextBlock("我来帮你创建文件"),
        ToolUseBlock("WriteFileTool", {"filePath": "hello.txt", ...}, "call_123")
    ])
    .build();

memory.addMessage(reasoningMsg);  // ← 推理结果入库
```

### 4.4 Acting 阶段详解

#### 4.4.1 决定是执行还是结束
```java
// ReActAgent.java 第223-236行
private Mono<Msg> actingOrFinish(int iter, StructuredOutputHandler handler) {
    // 提取最新的工具调用
    List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

    // 检查是否完成(没有工具调用或工具不存在)
    if (isFinished()) {
        return getLastAssistantMessage();  // 返回最后的助手消息
    }

    // 执行Acting阶段
    return acting()
        .then(Mono.defer(() -> finishActingOrContinue(iter, handler)));
}
```

#### 4.4.2 判断是否完成
```java
// ReActAgent.java 第285-294行
private boolean isFinished() {
    List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

    // 没有工具调用 → 完成
    if (recentToolCalls.isEmpty()) {
        return true;
    }

    // 所有工具调用都找不到对应的Tool → 完成
    return recentToolCalls.stream()
        .noneMatch(toolCall -> toolkit.getTool(toolCall.getName()) != null);
}
```

#### 4.4.3 ActingPipeline 执行过程
```java
// ReActAgent.java 第479-515行
private class ActingPipeline {
    
    Mono<Void> execute() {
        // 步骤1: 提取工具调用
        List<ToolUseBlock> toolCalls = extractRecentToolCalls();
        if (toolCalls.isEmpty()) {
            return Mono.empty();
        }

        // 设置chunk回调(用于流式工具输出)
        toolkit.setChunkCallback((toolUse, chunk) -> 
            hookNotifier.notifyActingChunk(toolUse, chunk).subscribe());

        // 步骤2: 执行所有工具
        return toolkit.callTools(toolCalls, toolExecutionConfig, 
                                ReActAgent.this, toolExecutionContext)
            .flatMapMany(responses -> processToolResults(toolCalls, responses))
            .then()
            .then(checkInterruptedAsync());
    }
}
```

#### 4.4.4 处理工具执行结果
```java
private Flux<Void> processToolResults(
        List<ToolUseBlock> toolCalls, 
        List<ToolResultBlock> responses) {
    return Flux.range(0, toolCalls.size())
        .concatMap(i -> processSingleToolResult(toolCalls.get(i), responses.get(i)));
}

private Mono<Void> processSingleToolResult(ToolUseBlock toolCall, ToolResultBlock result) {
    return hookNotifier.notifyPostActing(toolCall, result)
        .doOnNext(processedResult -> {
            // 关键: 构建工具结果消息并保存到Memory
            Msg toolMsg = ToolResultMessageBuilder.buildToolResultMsg(
                processedResult, toolCall, getName());
            memory.addMessage(toolMsg);  // ← 工具结果入库
        })
        .then();
}
```

#### 4.4.5 Acting 示例

**场景**: 执行上一步推理中的WriteFileTool

**提取的工具调用**:
```java
ToolUseBlock toolUse = ToolUseBlock.builder()
    .name("WriteFileTool")
    .input(Map.of(
        "filePath", "hello.txt",
        "content", "Hello World"
    ))
    .id("call_123")
    .build();
```

**执行工具**:
```java
// Toolkit.callTools() 内部
Tool tool = getTool("WriteFileTool");  // 找到WriteFileTool实例
ToolResultBlock result = tool.execute(toolUse.getInput());
// WriteFileTool实际执行: 创建文件并写入内容
```

**工具结果**:
```java
ToolResultBlock result = ToolResultBlock.builder()
    .toolName("WriteFileTool")
    .toolCallId("call_123")
    .content("文件hello.txt已成功创建,内容已写入")
    .isError(false)
    .build();
```

**保存到Memory**:
```java
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .name("WriteFileTool")
    .content(result)
    .build();

memory.addMessage(toolResultMsg);  // ← 工具结果入库
```

### 4.5 继续下一次迭代

```java
// ReActAgent.java 第238-243行
private Mono<Msg> finishActingOrContinue(int iter, StructuredOutputHandler handler) {
    if (handler != null && handler.isCompleted()) {
        return getLastAssistantMessage();  // 结构化输出完成
    }
    return executeIteration(iter + 1, handler);  // 继续下一次迭代
}
```

### 4.6 完整的 ReAct 循环示例

**场景**: 用户询问"帮我创建hello.txt并读取内容"

#### 迭代0 (Reasoning)
```
Memory状态: [User: "帮我创建hello.txt并读取内容"]
    ↓
发送到模型: [System, User消息]
    ↓
模型推理: "需要先创建文件"
    ↓
模型输出: ToolUse(WriteFileTool, {file: "hello.txt", content: "Hello"})
    ↓
Memory状态: [User消息, Assistant+ToolUse消息]
```

#### 迭代0 (Acting)
```
提取工具调用: WriteFileTool
    ↓
执行工具: 创建文件hello.txt
    ↓
工具结果: "文件已创建"
    ↓
Memory状态: [User, Assistant+ToolUse, ToolResult]
```

#### 迭代1 (Reasoning)
```
Memory状态: [User, Assistant+ToolUse, ToolResult]
    ↓
发送到模型: [System, User, Assistant+ToolUse, ToolResult]
    ↓
模型推理: "文件已创建,现在读取内容"
    ↓
模型输出: ToolUse(ReadFileTool, {file: "hello.txt"})
    ↓
Memory状态: [..., Assistant+ToolUse(ReadFile)]
```

#### 迭代1 (Acting)
```
提取工具调用: ReadFileTool
    ↓
执行工具: 读取hello.txt
    ↓
工具结果: "Hello"
    ↓
Memory状态: [..., ToolResult("Hello")]
```

#### 迭代2 (Reasoning)
```
Memory状态: [完整的对话历史]
    ↓
发送到模型: [System, 所有历史消息]
    ↓
模型推理: "任务已完成"
    ↓
模型输出: 纯文本响应(无ToolUse)
    ↓
检测到无工具调用 → isFinished() = true
    ↓
返回最终响应给用户
```

### 4.7 Reasoning vs Acting 对比表

| 维度 | Reasoning (推理阶段) | Acting (执行阶段) |
|------|-------------------|-----------------|
| **目的** | 思考下一步该做什么 | 执行计划中的工具调用 |
| **输入** | 完整的对话历史 | 推理阶段输出的工具调用 |
| **处理方** | LLM模型 | 本地工具实现 |
| **输出** | 文本响应 + 工具调用计划 | 工具执行结果 |
| **保存到Memory** | Assistant消息(含ToolUse) | Tool消息(含ToolResult) |
| **是否消耗Token** | 是(调用LLM API) | 否(本地执行) |
| **可能的内容** | 文本、思考过程、工具调用 | 成功/失败、返回值、错误信息 |

### 4.8 关键设计亮点

1. **Pipeline模式**: ReasoningPipeline和ActingPipeline封装各阶段逻辑
2. **流式处理**: 支持流式输出,用户可实时看到推理过程
3. **中断检查**: 在关键点检查中断,支持用户随时停止
4. **Hook机制**: 在各阶段前后触发Hook,允许扩展和监控
5. **自动循环**: 自动判断是否需要继续迭代,直到任务完成
6. **错误恢复**: 工具执行失败后,模型可以在下次推理中处理错误
7. **Memory集成**: 每个阶段的结果都保存到Memory,构建完整的对话历史

### 4.9 ReAct 循环的终止条件

```java
// 条件1: 达到最大迭代次数
if (iter >= maxIters) {
    return summarizing(handler);
}

// 条件2: 推理阶段没有输出工具调用
if (recentToolCalls.isEmpty()) {
    return getLastAssistantMessage();
}

// 条件3: 工具调用找不到对应的Tool
if (recentToolCalls.stream().noneMatch(tc -> toolkit.getTool(tc.getName()) != null)) {
    return getLastAssistantMessage();
}

// 条件4: 结构化输出已完成(如果使用了structured output)
if (handler != null && handler.isCompleted()) {
    return getLastAssistantMessage();
}
```

---

## 总结

1. **call()调用链路**: 从用户代码 → Agent接口 → AgentBase → ReActAgent → ReAct循环
2. **上下文构建**: userMsg通过memory.addMessage()存储,在reasoning时通过memory.getMessages()获取,AutoContextMemory自动压缩过长上下文
3. **会话持久化**: SessionManager管理多组件状态,Memory提供序列化接口,支持完整的会话恢复
4. **ReAct循环**: 交替执行Reasoning(LLM推理)和Acting(工具执行),每个阶段结果都保存到Memory,直到任务完成

这个设计实现了:
- ✅ 完整的上下文管理
- ✅ 自动的Token压缩
- ✅ 可靠的状态持久化
- ✅ 灵活的推理-执行循环
- ✅ 强大的扩展能力(Hook机制)
