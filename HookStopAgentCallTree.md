# HookStopAgent Call Tree Analysis

## Main Method Call Tree

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
            ├── add user messages to memory
            ├── check for pending tool calls
            ├── executeIteration(0)
                └── reasoning(0, false)
                    ├── checkInterruptedAsync()
                    ├── notifyPreReasoningEvent()
                    ├── model.stream()
                    ├── process chunks via ReasoningContext
                    ├── notifyReasoningChunk()
                    ├── notifyPostReasoning()
                    ├── check stop requested (stopAgent called)
                    ├── if stop requested: return message with ToolUseBlocks
                    ├── else continue to acting phase
                    └── acting(0)
                        ├── extractRecentToolCalls()
                        ├── notifyPreActingHooks()
                        ├── executeToolCalls()
                        └── notifyPostActingHook()
```

## Hook Stop and Resume Analysis

### How HookStop Works

The hook stop mechanism is implemented through the `stopAgent()` method in hook events:

1. **PostReasoningEvent.stopAgent()**: When called in a PostReasoningEvent, the agent will stop before executing tools and return the message containing ToolUseBlocks.
2. **Agent Detection**: The agent checks `event.isStopRequested()` and returns the pending tool use message instead of proceeding to tool execution.
3. **State Preservation**: The agent preserves its state in memory and waits for further input.

### Types of Stops

1. **Tool Confirmation Stop**:
   - Triggered by ToolConfirmationHook in PostReasoningEvent
   - Occurs when sensitive tools (delete_file, send_email) are detected
   - Calls `postReasoning.stopAgent()` to pause execution
   - Returns message with ToolUseBlocks for user review

2. **Structured Output Stop**:
   - Triggered by StructuredOutputHook when structured output is requested
   - Calls `postReasoning.stopAgent()` to pause execution
   - Allows for type-safe output generation

3. **External Stop**:
   - Triggered by external interruption (agent.interrupt())
   - Stops execution at the next available interruption point

### Resume Mechanisms

1. **Resume with No Arguments** (`agent.call()`):
   - Continues execution from where it was stopped
   - Processes the pending tool calls that were identified during stop
   - Uses `acting()` method to execute the tools

2. **Resume with Tool Results** (`agent.call(toolResultMsg)`):
   - Provides manual tool results to the agent
   - Bypasses actual tool execution
   - Agent continues with the provided results

3. **Resume with New User Input** (`agent.call(userMsg)`):
   - Processes new user input while preserving agent state
   - Can override or supplement the previous execution flow

### Implementation Details

The ToolConfirmationHook implements the stop/resume logic by:

1. Monitoring PostReasoningEvent for tool use blocks
2. Checking if any tools are in the sensitive tools list (delete_file, send_email)
3. Calling `postReasoning.stopAgent()` to request stop when sensitive tools detected
4. The agent detects the stop request and returns the pending ToolUse message
5. The main application detects pending tool calls using `hasPendingToolUse()`
6. User is prompted for confirmation (yes/no)
7. On "yes": Resume with `agent.call()` to execute tools
8. On "no": Create cancelled tool results and call `agent.call(cancelResult)` to continue with error messages

### Key Methods for Stop/Resume

- `PostReasoningEvent.stopAgent()`: Requests the agent to stop before tool execution
- `PostReasoningEvent.isStopRequested()`: Checks if stop was requested
- `ReActAgent.doCall()`: Main entry point that handles both new calls and resume from stops
- `ReActAgent.hasPendingToolUse()`: Detects if there are pending tool calls from a previous stop
- `ReActAgent.acting()`: Executes tools when resuming from a stop
- `ReActAgent.reasoning()`: Handles reasoning phase and stop detection

This design enables a clean human-in-the-loop workflow where sensitive operations can be reviewed and confirmed before execution, while maintaining the agent's state and context.