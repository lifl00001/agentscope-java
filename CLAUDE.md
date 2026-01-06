# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AgentScope Java is an agent-oriented programming framework for building LLM-powered applications. It implements the ReAct (Reasoning-Acting) paradigm with reactive programming based on Project Reactor.

**Key Architecture Principles:**
- **Reactive & Non-blocking**: All agent operations return `Mono<Msg>` or `Flux<Event>` for async execution
- **Multi-model Support**: Abstracted formatter layer supports OpenAI, DashScope (Alibaba), Anthropic, Gemini, and other LLM providers
- **Hook System**: Extensible interception points (PreReasoning, PostReasoning, PreActing, PostActing) for monitoring, HITL, and custom behavior
- **Tool Ecosystem**: Java methods can be exposed as tools with automatic schema generation; supports MCP (Model Context Protocol) integration
- **Multi-Agent**: A2A protocol enables distributed agent collaboration via service discovery (Nacos)

## Build & Test Commands

```bash
# Build entire project (includes format check, compile, test)
mvn clean verify

# Build without tests
mvn clean install

# Format code (required before committing)
mvn spotless:apply

# Check code format
mvn spotless:check

# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=YourTestClassName

# Run tests with coverage report
mvn verify
```

## Project Structure

```
agentscope-java/
├── agentscope-core/              # Core framework (agents, models, tools, formatters)
├── agentscope-extensions/        # Optional extensions and integrations
│   ├── agentscope-spring-boot-starters/  # Spring Boot auto-configuration
│   ├── agentscope-extensions-a2a/        # Agent-to-Agent protocol
│   ├── agentscope-extensions-rag-*/      # RAG integrations (Dify, Haystack, etc.)
│   ├── agentscope-extensions-session-*/  # Session persistence (MySQL, Redis)
│   └── agentscope-extensions-scheduler/  # Task scheduling (Quartz, XXL-Job)
├── agentscope-examples/          # Example applications
└── agentscope-distribution/      # Maven BOM and distribution packaging
```

## Core Concepts

### Agent Types

- **ReActAgent**: Main agent implementation combining reasoning (LLM calls) and acting (tool execution) in iterative loops
- **Agent Interface**: Combines `CallableAgent` (process messages), `StreamableAgent` (stream events), and `ObservableAgent` (observe without responding)
- **AgentBase**: Abstract base class providing common functionality for custom agents
- **UserAgent**: Special agent for human-in-the-loop interactions

### Message System

Messages are composed of `ContentBlock`s:
- **TextBlock**: Text content
- **ImageBlock**: Image content (URL or base64)
- **AudioBlock/VideoBlock**: Media content
- **ToolUseBlock**: Tool invocation from LLM
- **ToolResultBlock**: Tool execution result
- **ThinkingBlock**: LLM reasoning traces

All messages use `Msg` wrapper with role (USER/ASSISTANT/SYSTEM) and optional name.

### Tool System

Tools are Java methods exposed to LLMs via automatic schema generation:

```java
@Component
public class MyTools {
    @Tool(name = "search", description = "Search the web")
    public String search(@ToolParam("query") String query) {
        // implementation
    }
}
```

Key tool features:
- **ToolGroup**: Organize tools into groups for dynamic activation
- **MCP Integration**: Connect to external MCP servers for tool discovery
- **SubAgentTool**: Treat other agents as tools for composition
- **ToolResultConverter**: Customize how tool results are converted to messages

### Hook System

Hooks provide interception points throughout agent execution:

```java
agent.addHook(new PreReasoningEvent((event) -> {
    // Before LLM call
}));

agent.addHook(new PostReasoningEvent((event) -> {
    if (needHumanInput) {
        event.stopAgent(); // Human-in-the-loop
    }
}));
```

Hook types: `PreCallEvent`, `PreReasoningEvent`, `PostReasoningEvent`, `PreActingEvent`, `PostActingEvent`, `PostCallEvent`, `ReasoningChunkEvent`, `ActingChunkEvent`, `ErrorEvent`

### Memory System

- **Memory**: Base interface for conversation history
- **InMemoryMemory**: Thread-safe in-memory storage
- **LongTermMemory**: Persistent memory with semantic search (via embeddings)
- **StaticLongTermMemoryHook**: Automatically stores important messages to long-term memory

### Pipelines

Compose multiple agents into workflows:

- **SequentialPipeline**: Pass messages through agents sequentially
- **FanoutPipeline**: Broadcast message to multiple agents and aggregate results
- **MsgHub**: Central message routing hub for multi-agent systems

### Formatters

Formatters convert between AgentScope's internal `Msg` format and provider-specific APIs:

- `OpenAIChatFormatter`: OpenAI-compatible APIs (includes DeepSeek, GLM)
- `DashScopeChatFormatter`: Alibaba DashScope APIs
- `AnthropicChatFormatter`: Anthropic Claude APIs
- `GeminiChatFormatter`: Google Gemini APIs

Each formatter includes:
- **MessageConverter**: Convert Msg → provider format
- **ConversationMerger**: Manage conversation history compression
- **MultiAgentFormatter**: Handle multi-agent name formatting

## Extension Architecture

### Spring Boot Integration

Auto-configuration classes in `agentscope-spring-boot-starters/`:
- `AgentscopeAutoConfiguration`: Core agent and model setup
- `AgentscopeA2aAutoConfiguration`: A2A protocol support
- `AgentscopeAguiMvcAutoConfiguration`: Web UI for agent interactions

### A2A Protocol (Agent-to-Agent)

Enables distributed multi-agent collaboration:
- **Server**: Expose agents via JSON-RPC over HTTP
- **Client**: Discover and call remote agents via service registries (Nacos)
- **AgentCard**: Metadata describing agent capabilities for discovery

Located in `agentscope-extensions/agentscope-extensions-a2a/`

### RAG Extensions

Integrations with knowledge bases:
- `agentscope-extensions-rag-simple`: Basic embedding-based retrieval
- `agentscope-extensions-rag-dify`: Dify RAG platform
- `agentscope-extensions-rag-haystack`: Haystack RAG framework
- `agentscope-extensions-rag-bailian`: Alibaba Bailian

All implement `GenericRAGHook` for automatic knowledge retrieval.

## Development Guidelines

### Code Style

- **Spotless**: Auto-formats code using Google Java Format (AOSP style)
- **Indentation**: 4 spaces (no tabs)
- **Import ordering**: Enforced by Spotless
- Always run `mvn spotless:apply` before committing

### Testing

- **Test Framework**: JUnit 5 with Reactor Test
- **Coverage**: JaCoCo generates reports in `target/site/jacoco/index.html`
- **Mocking**: Mockito for external dependencies
- All public APIs should have tests

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):
- `feat(core): add new tool registry feature`
- `fix(agent): resolve memory leak in ReActAgent`
- `docs(readme): update installation instructions`
- `refactor(formatter): simplify message formatting logic`

### Adding New Model Support

1. Create formatter in `agentscope-core/src/main/java/io/agentscope/core/formatter/{provider}/`
2. Implement `Formatter` interface
3. Add model class in `agentscope-core/src/main/java/io/agentscope/core/model/`
4. Register formatter in `FormatterFactory`
5. Add tests and examples

### Adding New Tools

1. Annotate methods with `@Tool`
2. Add `@ToolParam` annotations for parameters
3. Return types are automatically converted to `ToolResultBlock`
4. For custom conversion, implement `ToolResultConverter`

## Common Patterns

### Creating a ReActAgent with Tools

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-max")
        .build())
    .tools(toolkit)  // Toolkit with registered tools
    .build();
```

### Streaming Agent Responses

```java
agent.call(Msg.builder().textContent("Hello").build())
    .flatMapMany(response -> agent.stream(response, StreamOptions.DEFAULT))
    .subscribe(event -> {
        // Handle streaming events
    });
```

### Multi-Agent Pipeline

```java
SequentialPipeline pipeline = Pipelines.sequential(
    translatorAgent,
    summarizerAgent,
    formatterAgent
);

pipeline.call(inputMsg).block();
```

## Key Dependencies

- **Project Reactor**: Reactive programming (required)
- **Spring Boot**: Optional (for auto-configuration)
- **Jackson**: JSON serialization
- **OkHttp**: HTTP client for LLM APIs
- **SLF4J**: Logging facade

## Environment Variables

Common environment variables for examples:
- `DASHSCOPE_API_KEY`: Alibaba DashScope API key
- `OPENAI_API_KEY`: OpenAI API key
- `ANTHROPIC_API_KEY`: Anthropic API key

## Important Notes

- **JDK 17+** required
- **Reactive programming**: All agent operations are non-blocking and async
- **Thread safety**: Agents are thread-safe and can be shared across threads
- **Resource cleanup**: Use `try-with-resources` for agents that implement `AutoCloseable`
- **Interruption**: Agents support cooperative interruption via `interrupt()` method
- **State persistence**: Some agents support state persistence across restarts (see `StatePersistence` interface)