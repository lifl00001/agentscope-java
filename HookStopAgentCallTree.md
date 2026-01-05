# HookStopAgentExample 的 Call Tree 和恢复机制分析

## Call Tree

```
main(String[] args)
├── ExampleUtils.printWelcome(String, String)
├── ExampleUtils.getDashScopeApiKey()
├── new Toolkit()
├── toolkit.registerTool(new SensitiveTools())
├── new ToolConfirmationHook()
├── ReActAgent.builder()
│   ├── .name("SafeAgent")
│   ├── .sysPrompt(String)
│   ├── .model(DashScopeChatModel.builder()...)
│   │   ├── DashScopeChatModel.builder()
│   │   │   ├── .apiKey(String)
│   │   │   ├── .modelName("qwen-plus")
│   │   │   ├── .stream(true)
│   │   │   ├── .formatter(new DashScopeChatFormatter())
│   │   │   └── .build()
│   ├── .toolkit(Toolkit)
│   ├── .memory(new InMemoryMemory())
│   ├── .hooks(List.of(confirmationHook))
│   └── .build()
└── startChatWithConfirmation(agent)
    └── agent.call(String)
        └── ReActAgent.doCall()
            ├── 将用户消息添加到内存
            ├── 检查待处理的工具调用
            ├── 检查stopRequested
            ├── 如果请求停止：返回包含 ToolUseBlocks 的消息
            ├── 否则继续执行到 acting 阶段
            └── acting(0)
                ├── extractRecentToolCalls()
                ├── notifyPreActingHooks()
                ├── executeToolCalls()
                └── notifyPostActingHook()
```

## Hook Stop 后的恢复机制分析

### 停止方式

1. **Hook 中的 `stopAgent()` 调用**
   - 在 [ToolConfirmationHook](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookStopAgentExample.java#L147-L164) 的 [onEvent](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/ExampleUtils.java#L23-L30) 方法中，当检测到敏感工具调用时，调用 [postReasoning.stopAgent()](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/hook/PostReasoningEvent.java#L98-L100)
   - 停止发生在 [PostReasoningEvent](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/hook/PostReasoningEvent.java#L50-L171) 事件中，即推理完成后、工具执行前

2. **停止的触发条件**
   - 检测到敏感工具调用（[delete_file](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookStopAgentExample.java#L167-L172)、[send_email](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookStopAgentExample.java#L174-L181)）
   - 通过检查 [Msg](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/message/Msg.java#L40-L162) 中的 [ToolUseBlock](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/message/ToolUseBlock.java#L35-L102) 类型内容块来判断

### 恢复方式

1. **确认继续执行**
   - 用户输入 "yes" 或 "y" 确认工具调用
   - 调用 [agent.call()](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java#L77-L85) 无参数方法
   - [ReActAgent](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L124-L1386) 检测到存在待处理的工具调用，直接进入 [acting](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L334-L390) 阶段执行工具

2. **取消执行**
   - 用户输入 "no" 或 "n" 取消工具调用
   - 通过 [createCancelledToolResults](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HookStopAgentExample.java#L125-L144) 方法创建取消的工具结果消息
   - 调用 [agent.call(cancelResult)](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java#L77-L85) 将取消结果传递给代理
   - 代理继续推理过程，但使用取消的工具结果

3. **内部实现机制**
   - [ReActAgent.doCall](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L206-L222) 方法首先检查是否存在待处理的工具调用
   - 如果存在待处理工具调用，直接进入 [acting](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L334-L390) 阶段而非开始新的推理循环
   - 这使得代理能够从停止点精确恢复执行

### 关键类和方法

1. **[PostReasoningEvent](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/hook/PostReasoningEvent.java#L50-L171)**
   - [stopAgent()](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/hook/PostReasoningEvent.java#L98-L100) 方法：请求代理停止执行并返回包含工具调用的消息
   - [isStopRequested()](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/hook/PostReasoningEvent.java#L106-L108) 方法：检查是否已请求停止

2. **[ReActAgent](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L124-L1386) 的恢复逻辑**
   - [doCall](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L206-L222) 方法检查是否存在待处理的工具调用
   - [acting](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L334-L390) 方法执行工具调用
   - [hasPendingToolUse](file:///F:/workspace/springaialibaba/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L224-L242) 方法检查是否有待处理的工具使用

这种设计使得代理能够在关键决策点暂停执行，允许人类用户审查和确认敏感操作，然后再继续执行，实现了人机协作的智能代理系统。
