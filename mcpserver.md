# Business MCP Server 架构文档

## 概述

`business-mcp-server` 是一个 **MCP 服务端**实现，提供订单管理和反馈管理的工具接口，供 AgentScope 中的 Agent 作为 MCP 客户端调用。

## MCP 服务端身份确认

### 1. 依赖配置 (pom.xml:40-51)

```xml
<!-- MCP SDK Core -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>${mcp-sdk.version}</version>
</dependency>

<!-- MCP Spring WebFlux Transport -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
    <version>${mcp-sdk.version}</version>
</dependency>
```

使用的是 MCP SDK 的服务端组件。

### 2. MCP Server 实例创建 (McpServerConfig.java:67-76)

```java
@Bean
public McpSyncServer mcpSyncServer(WebFluxSseServerTransportProvider transportProvider) {
    McpServer.SyncSpecification spec =
        McpServer.sync(transportProvider)
            .serverInfo("business-mcp-server", "0.0.1")
            .capabilities(ServerCapabilities.builder().tools(true).build());

    registerOrderTools(spec);
    registerFeedbackTools(spec);

    return spec.build();
}
```

创建了 `McpSyncServer` 实例，这是 MCP SDK 提供的服务端实现。

### 3. SSE 传输层配置 (McpServerConfig.java:55-63)

```java
@Bean
public WebFluxSseServerTransportProvider webFluxSseServerTransportProvider() {
    return WebFluxSseServerTransportProvider.builder()
        .messageEndpoint("/mcp/message")
        .build();
}

@Bean
public RouterFunction<?> mcpRouterFunction(WebFluxSseServerTransportProvider transportProvider) {
    return transportProvider.getRouterFunction();
}
```

使用 SSE (Server-Sent Events) 作为服务端传输协议，端点路径为 `/mcp/message`。

### 4. Nacos 服务注册 (McpServerRegistrar.java:145)

```java
aiService.registerMcpServerEndpoint(serverName, serverAddr, serverPort);
```

将 MCP Server 注册到 Nacos 服务发现中心。

## 架构分层详解

### Nacos 的作用：服务发现与注册中心

Nacos **不处理实际的 MCP 请求**，仅负责：

- **存储服务元数据**
  - 服务名称: `business-mcp-server`
  - 服务地址: IP + Port (例如: `192.168.1.100:10002`)
  - 工具列表: 这个 MCP Server 提供的所有工具定义
  - 协议类型: SSE (Server-Sent Events)
  - 端点路径: `/sse` 或 `/mcp/message`

- **服务注册** (McpServerRegistrar.java:94-156)
  ```java
  McpServerBasicInfo serverSpec = new McpServerBasicInfo();
  serverSpec.setName(serverName);
  serverSpec.setVersion(serverVersion);
  serverSpec.setDescription(serverDescription);
  serverSpec.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_SSE);

  // 注册工具定义
  McpToolSpecification toolSpec = buildToolSpecification();

  // 发布到 Nacos
  aiService.releaseMcpServer(serverSpec, toolSpec, endpointSpec);
  ```

- **服务发现**
  - 客户端通过服务名称查询服务地址
  - 返回可用的 MCP Server 列表及其工具清单

### MCP 服务的职责：提供 SSE 端点和工具执行

`business-mcp-server` 服务本身负责：

- **启动 HTTP 服务器**: Spring WebFlux 在配置的端口（默认 10002）启动
- **提供 SSE 端点**: `http://IP:10002/mcp/message`
- **处理 MCP 协议**: 通过 MCP SDK 的 `WebFluxSseServerTransportProvider` 处理
- **执行工具调用**: 调用实际的业务服务（OrderService、FeedbackService）

### 端点类型证据：引用而非直连

从 `McpServerRegistrar.java:114-122` 可以看到，Nacos 中存储的是**引用类型**端点：

```java
McpEndpointSpec endpointSpec = new McpEndpointSpec();
endpointSpec.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF);  // REF = 引用类型

Map<String, String> endpointSpecData = new HashMap<>();
endpointSpecData.put("serviceName", serverName);      // 服务名
endpointSpecData.put("groupName", groupName);         // Nacos 分组
endpointSpecData.put("namespaceId", namespace);       // 命名空间
endpointSpec.setData(endpointSpecData);
```

`MCP_ENDPOINT_TYPE_REF` 表示这是一个**引用**，指向真正的服务端点，而不是在 Nacos 中直接处理请求。

## 完整的调用流程

```
┌─────────────────┐
│  MCP Client     │
│  (Agent)        │
└────────┬────────┘
         │
         │ 1. 查询 Nacos: "有哪些 MCP Server?"
         ▼
┌─────────────────┐
│  Nacos          │
│  ┌───────────┐  │
│  │ business- │  │
│  │ mcp-server│  │  返回元数据:
│  │ 地址:xxx  │  │  - serverName
│  │ 工具列表  │  │  - serverAddr:port
│  └───────────┘  │  - tools
└────────▲────────┘
         │
         │ 2. 从 Nacos 获取地址后，直接连接 MCP Server
         ▼
┌─────────────────────────────────┐
│  business-mcp-server:10002      │
│  ┌───────────────────────────┐  │
│  │ Spring WebFlux Server     │  │
│  │  ┌─────────────────────┐  │  │
│  │  │ SSE Endpoint        │  │  │
│  │  │ /mcp/message        │◄─┼──┼── 3. 建立 SSE 连接
│  │  │                     │  │  │
│  │  │ MCP SDK             │  │  │
│  │  │ (处理工具调用)      │  │  │
│  │  └─────────────────────┘  │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

### 调用步骤详解

1. **服务发现阶段**
   - Agent 查询 Nacos: "有哪些 MCP Server 可用？"
   - Nacos 返回 `business-mcp-server` 的元数据
   - 元数据包含: 服务地址、端口、工具列表、协议类型

2. **连接建立阶段**
   - Agent 从 Nacos 获取到 `192.168.1.100:10002`
   - Agent 直接连接到 `http://192.168.1.100:10002/mcp/message`
   - 建立 SSE (Server-Sent Events) 长连接
   - **此阶段完全绕过 Nacos**

3. **工具调用阶段**
   - Agent 通过 SSE 连接发送工具调用请求
   - MCP SDK 接收请求，解析参数
   - 调用对应的 `McpToolHandlers` 方法
   - Handler 调用业务服务 (OrderService/FeedbackService)
   - 结果通过 SSE 返回给 Agent

## 关键点总结

| 组件 | 职责 | 不负责 |
|------|------|--------|
| **Nacos** | • 存储服务元数据<br>• 服务注册与发现<br>• 工具列表广播<br>• 健康检查 | • 实际的 SSE 连接处理<br>• 工具执行<br>• 请求路由 |
| **business-mcp-server** | • 提供 SSE 端点<br>• 处理 MCP 协议<br>• 执行工具调用<br>• 业务逻辑处理 | • 服务发现（依赖 Nacos）|

## 提供的工具列表

### 订单管理工具 (McpServerConfig.java:80-110)

1. `order_create_order_with_user` - 创建订单
2. `order_get_order` - 获取单个订单
3. `order_get_order_by_user` - 获取用户的特定订单
4. `order_check_stock` - 检查库存
5. `order_get_orders` - 获取所有订单
6. `order_get_orders_by_user` - 获取用户的所有订单
7. `order_query_orders` - 查询订单
8. `order_delete_order` - 删除订单
9. `order_update_remark` - 更新订单备注
10. `order_validate_product` - 验证产品

### 反馈管理工具 (McpServerConfig.java:113-126)

1. `feedback_create_feedback` - 创建反馈
2. `feedback_get_feedback_by_user` - 获取用户的反馈
3. `feedback_get_feedback_by_order` - 获取订单的反馈
4. `feedback_update_solution` - 更新反馈解决方案

## 核心配置参数

### 服务配置 (application.yml)

```yaml
server:
  port: 10002  # MCP Server 监听端口

agentscope:
  mcp:
    nacos:
      register:
        enabled: true                    # 是否启用 Nacos 注册
        server-name: business-mcp-server # 服务名称
        server-version: 0.0.1            # 服务版本
        server-description: Business MCP Server
        protocol: sse                    # 通信协议
        endpoint-path: /sse              # 端点路径
        namespace:                       # Nacos 命名空间
```

## 核心类说明

### McpServerConfig
- **职责**: MCP Server 核心配置
- **功能**:
  - 创建 SSE 传输层
  - 注册 MCP 工具
  - 配置服务器能力
- **位置**: `config/McpServerConfig.java`

### McpServerRegistrar
- **职责**: Nacos 注册逻辑
- **功能**:
  - 启动时自动注册到 Nacos
  - 发布工具元数据
  - 注册服务端点
- **位置**: `config/McpServerRegistrar.java`

### McpToolHandlers
- **职责**: 工具调用处理器
- **功能**:
  - 处理 MCP 工具调用请求
  - 参数验证和转换
  - 调用业务服务
- **位置**: `config/McpToolHandlers.java`

### McpToolDefinitions
- **职责**: 工具定义中心
- **功能**:
  - 统一定义所有工具的元数据
  - 提供 JSON Schema 定义
  - 工具描述信息
- **位置**: `config/McpToolDefinitions.java`

## 技术栈

- **MCP SDK**: `io.modelcontextprotocol.sdk:mcp` (v0.17.2)
- **Spring WebFlux**: 响应式 Web 框架
- **MyBatis**: 数据库持久化
- **MySQL**: 数据存储
- **Nacos**: 服务注册与发现
- **传输协议**: SSE (Server-Sent Events)

## MCP 客户端通过 Nacos 发现并调用 MCP Server

### 客户端服务示例

在 `boba-tea-shop` 示例中，有两个微服务作为 MCP 客户端：

1. **business-sub-agent**: 业务处理 Agent，需要调用 `business-mcp-server` 的工具
2. **consult-sub-agent**: 咨询 Agent（可选，不使用 MCP 工具）

### 客户端核心配置

#### 1. Nacos 服务配置 (NacosMcpServiceConfig.java:38-43)

```java
@Bean
public AiService aiService() throws NacosException {
    Properties properties = new Properties();
    properties.put(PropertyKeyConst.SERVER_ADDR, serverAddress);
    properties.put(PropertyKeyConst.NAMESPACE, namespace);
    return AiFactory.createAiService(properties);
}
```

**配置参数** (application.yml):
```yaml
agentscope:
  mcp:
    nacos:
      server-addr: localhost:8848  # Nacos 服务器地址
      namespace:                   # 命名空间
```

这个配置创建了 Nacos AI 服务连接，用于后续的 MCP Server 发现。

#### 2. Agent 配置 (AgentScopeRunner.java:60-81)

```java
@Bean
public AgentRunner agentRunner(AgentPromptConfig promptConfig, AiService aiService, Model model) {

    // 创建 NacosToolkit，支持从 Nacos 动态加载 MCP 工具
    Toolkit toolkit = new NacosToolkit();

    // 配置内存
    AutoContextMemory memory = new AutoContextMemory(autoContextConfig, model);

    // 构建 ReActAgent
    ReActAgent.Builder builder =
            ReActAgent.builder()
                    .name("business_agent")
                    .sysPrompt(promptConfig.getBusinessAgentInstruction())
                    .memory(memory)
                    .hooks(List.of(new MonitoringHook()))
                    .model(model)
                    .toolkit(toolkit);  // 绑定 NacosToolkit

    return new CustomAgentRunner(builder, aiService, toolkit);
}
```

关键点：
- **NacosToolkit**: 特殊的 Toolkit，可以从 Nacos 动态发现和加载 MCP Server 提供的工具
- **AiService 注入**: 传递给 CustomAgentRunner，用于 Nacos 通信

#### 3. MCP 客户端初始化 (AgentScopeRunner.java:121-144)

```java
private void initializeMcpOnce() {
    if (!mcpInitialized) {
        synchronized (this) {
            if (!mcpInitialized) {
                try {
                    // 1. 创建 Nacos MCP Server 管理器
                    NacosMcpServerManager mcpServerManager =
                            new NacosMcpServerManager(aiService);

                    // 2. 通过 Nacos 发现并连接到 MCP Server
                    NacosMcpClientWrapper mcpClientWrapper =
                            NacosMcpClientBuilder.create(
                                            "business-mcp-server",  // MCP Server 名称
                                            mcpServerManager)       // Nacos 管理器
                                    .build();

                    // 3. 将 MCP 客户端注册到 Toolkit
                    toolkit.registerMcpClient(mcpClientWrapper).block();

                    mcpInitialized = true;
                } catch (Exception e) {
                    logger.warn(
                            "Failed to initialize MCP client: "
                                    + e.getMessage()
                                    + " , will try later.");
                }
            }
        }
    }
}
```

**初始化流程详解**：

1. **NacosMcpServerManager**: 封装了与 Nacos 的交互逻辑
2. **NacosMcpClientBuilder**: 构建器模式，通过 Server 名称从 Nacos 查询并建立连接
3. **registerMcpClient()**: 将发现的 MCP 工具注册到 Agent 的 Toolkit 中
4. **延迟初始化**: 只在首次使用时初始化（懒加载），失败不影响 Agent 启动

#### 4. 延迟初始化调用 (AgentScopeRunner.java:109-119)

```java
private ReActAgent buildReActAgent(String userId) {
    // 在构建 Agent 时初始化 MCP 客户端
    initializeMcpOnce();

    Mem0LongTermMemory longTermMemory =
            Mem0LongTermMemory.builder()
                    .agentName("BusinessAgent")
                    .userId(userId)
                    .apiBaseUrl("https://api.mem0.ai")
                    .apiKey(System.getenv("MEM0_API_KEY"))
                    .build();
    return agentBuilder.longTermMemory(longTermMemory).build();
}
```

### 完整的客户端发现和调用流程

```
┌──────────────────────────────────────────────────────────────┐
│  business-sub-agent (MCP 客户端)                             │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ CustomAgentRunner                                      │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │ NacosToolkit                                     │  │  │
│  │  │                                                   │  │  │
│  │  │ 1. initializeMcpOnce() 被调用                    │  │  │
│  │  │    ┌─────────────────────────────────────────┐   │  │  │
│  │  │    │ NacosMcpServerManager(aiService)        │   │  │  │
│  │  │    │   - 封装 Nacos 交互                     │   │  │  │
│  │  │    └─────────────────────────────────────────┘   │  │  │
│  │  │    ┌─────────────────────────────────────────┐   │  │  │
│  │  │    │ NacosMcpClientBuilder                   │   │  │  │
│  │  │    │   .create("business-mcp-server",        │   │  │  │
│  │  │    │           mcpServerManager)             │   │  │  │
│  │  │    │   - 从 Nacos 查询服务                   │   │  │  │
│  │  │    │   - 获取服务地址和工具列表              │   │  │  │
│  │  │    │   - 建立 SSE 连接                       │   │  │  │
│  │  │    └─────────────────────────────────────────┘   │  │  │
│  │  │                                                   │  │  │
│  │  │ 2. toolkit.registerMcpClient(mcpClientWrapper)   │  │  │
│  │  │    - 将发现的工具注册到 Toolkit                  │  │  │
│  │  │    - Agent 可以像本地工具一样调用                │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
                            │
                            │ 查询: "business-mcp-server"
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  Nacos (服务发现中心)                                         │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 存储信息:                                               │  │
│  │ - Server Name: business-mcp-server                     │  │
│  │ - Server Addr: 192.168.1.100:10002                    │  │
│  │ - Protocol: SSE                                        │  │
│  │ - Tools: [order_create_*, feedback_*, ...]            │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
                            │
                            │ 返回: 服务地址 + 工具列表
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  SSE 连接建立                                                │
│  http://192.168.1.100:10002/mcp/message                     │
└──────────────────────────────────────────────────────────────┘
```

### 核心类说明（客户端）

#### NacosMcpServerManager
- **职责**: 管理 Nacos 上的 MCP Server 发现和连接
- **功能**:
  - 从 Nacos 查询 MCP Server 列表
  - 获取服务地址和工具元数据
  - 管理 MCP Server 的生命周期

#### NacosMcpClientBuilder
- **职责**: 构建 MCP 客户端连接
- **功能**:
  - 通过 Server 名称从 Nacos 发现服务
  - 建立 SSE 传输层连接
  - 创建 `NacosMcpClientWrapper` 实例

#### NacosMcpClientWrapper
- **职责**: 封装 MCP 客户端功能
- **功能**:
  - 维护与 MCP Server 的 SSE 连接
  - 将工具调用请求发送到 Server
  - 接收并返回 Server 的执行结果

#### NacosToolkit
- **职责**: 支持 Nacos 发现的 Toolkit
- **功能**:
  - 继承自 `Toolkit`
  - 提供工具注册功能
  - 将 MCP 工具无缝集成到 Agent 的工具系统

### 工具调用的执行流程

当 Agent 需要调用 MCP 工具时：

```
1. Agent 决策
   └─> ReActAgent 通过 LLM 决策需要调用工具

2. 工具查找
   └─> Toolkit.findTool("order_create_order_with_user")
       └─> 在 NacosToolkit 中找到对应的 MCP 工具

3. 工具调用
   └─> NacosMcpClientWrapper.callTool(name, arguments)
       └─> 通过 SSE 发送请求到 business-mcp-server

4. Server 执行
   └─> business-mcp-server 接收请求
       └─> McpToolHandlers.createOrderWithUser()
           └─> OrderService 执行业务逻辑

5. 结果返回
   └─> 执行结果通过 SSE 返回
       └─> Agent 收到结果，继续决策或返回用户
```

### 关键优势

#### 1. **动态发现**
- Agent 启动时不需要硬编码 MCP Server 地址
- 通过 Nacos 自动发现可用的 MCP Server
- 支持动态增删 MCP Server

#### 2. **松耦合**
- Agent 只依赖 Server 名称，不依赖具体地址
- MCP Server 可以独立部署和升级
- 支持多环境（dev/test/prod）自动切换

#### 3. **容错性**
- MCP Server 不可用时，Agent 仍可正常启动（见 `logger.warn`）
- 支持重试机制
- 不影响本地工具的使用

#### 4. **统一抽象**
- MCP 工具对 Agent 来说与本地工具无异
- 通过 `Toolkit` 统一接口调用
- 简化了 Agent 的工具管理

### 配置对比

#### MCP Server 配置 (business-mcp-server)

```yaml
server:
  port: 10002

agentscope:
  mcp:
    nacos:
      register:
        enabled: true
        server-name: business-mcp-server
        server-version: 0.0.1
        server-description: Business MCP Server
        protocol: sse
        endpoint-path: /sse
        namespace: public
```

#### MCP Client 配置 (business-sub-agent)

```yaml
agentscope:
  mcp:
    nacos:
      server-addr: localhost:8848
      namespace: public
  dashscope:
    api-key: ${DASHSCOPE_API_KEY}
    model-name: qwen-max
```

### 注意事项

1. **Server 名称必须一致**
   - Server 注册时使用: `server-name: business-mcp-server`
   - Client 查询时使用: `"business-mcp-server"`
   - 建议通过配置中心统一管理

2. **命名空间必须匹配**
   - Server 和 Client 必须在同一个 Nacos 命名空间
   - 不同命名空间的服务无法互相发现

3. **网络可达性**
   - Client 必须能直接访问 Server 的 SSE 端点
   - 确保防火墙和网络策略允许 SSE 连接

4. **版本兼容性**
   - Nacos SDK 版本需要与服务端兼容
   - MCP SDK 版本建议与服务端保持一致

## 三种 MCP 客户端连接方式对比

AgentScope Java 支持三种 MCP 客户端连接方式，每种方式适用于不同的场景：

### 1. 直接连接方式 (McpToolExample)

#### 代码示例 (McpToolExample.java:151-155)

```java
McpClientWrapper client = McpClientBuilder.create("mcp")
    .stdioTransport(command, mcpArgs)      // 方式1: StdIO
    // .sseTransport(url)                   // 方式2: SSE
    // .streamableHttpTransport(url)        // 方式3: HTTP
    .buildAsync()
    .block();
```

#### 特点
- ✅ **简单直接**: 无需中间件，直接连接到 MCP Server
- ✅ **快速测试**: 适合本地开发和测试
- ✅ **灵活性高**: 支持 StdIO/SSE/HTTP 三种传输协议
- ❌ **硬编码地址**: 需要明确指定服务地址或启动命令
- ❌ **无服务发现**: 服务地址变更需要修改配置
- ❌ **不适合生产**: 缺乏负载均衡和服务治理

#### 适用场景
- 本地开发测试
- 连接本地 MCP Server（如 filesystem、git）
- 连接已知的第三方 MCP Server
- 快速原型验证

---

### 2. Nacos 服务发现方式 (business-sub-agent)

#### 代码示例 (AgentScopeRunner.java:126-132)

```java
NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);
NacosMcpClientWrapper mcpClientWrapper =
    NacosMcpClientBuilder.create("business-mcp-server", mcpServerManager)
        .build();
toolkit.registerMcpClient(mcpClientWrapper).block();
```

#### 特点
- ✅ **服务发现**: 通过服务名自动发现服务地址
- ✅ **松耦合**: 客户端只需知道服务名称
- ✅ **动态更新**: 服务地址变更自动感知
- ✅ **负载均衡**: 支持多实例部署
- ✅ **适合生产**: 完整的服务治理能力
- ❌ **依赖 Nacos**: 需要 Nacos 服务注册中心
- ❌ **复杂度较高**: 需要维护 Nacos 集群

#### 适用场景
- 微服务架构内部服务调用
- 需要动态服务发现的场景
- 生产环境部署
- 需要负载均衡和高可用的场景

---

### 3. Higress AI Gateway 方式 (HigressToolExample)

#### 代码示例 (HigressToolExample.java:41-49)

```java
HigressMcpClientWrapper higressClient =
    HigressMcpClientBuilder.create("higress")
        .streamableHttpEndpoint(HIGRESS_ENDPOINT)
        .toolSearch("your agent description", 5)  // 语义工具搜索
        .buildAsync()
        .block();

Toolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(higressClient).block();
```

#### 特点
- ✅ **统一网关**: 通过单一网关访问多个 MCP Server
- ✅ **语义检索**: 自动选择最相关的工具
- ✅ **企业级**: 认证、限流、监控等企业特性
- ✅ **工具聚合**: 从多个 MCP Server 聚合工具
- ✅ **灵活性**: 可集成第三方和自建服务
- ❌ **依赖网关**: 需要 Higress 网关
- ❌ **额外成本**: 需要维护网关服务

#### 适用场景
- 企业级统一工具管理
- 需要集成多个 MCP Server（包括第三方服务）
- 需要语义工具搜索优化
- 需要统一认证和治理

---

## 三种方式对比表

| 特性 | 直接连接 | Nacos 发现 | Higress 网关 |
|------|---------|-----------|-------------|
| **连接方式** | 直连 URL/进程 | 通过服务名发现 | 通过网关聚合 |
| **配置复杂度** | 低（仅需 URL） | 中（需 Nacos） | 中（需网关） |
| **服务发现** | ❌ | ✅ | ✅ |
| **负载均衡** | ❌ | ✅ | ✅ |
| **语义检索** | ❌ | ❌ | ✅ |
| **第三方集成** | ✅ | ❌* | ✅ |
| **生产就绪** | ⚠️ | ✅ | ✅ |
| **中间件依赖** | 无 | Nacos | Higress |
| **典型用途** | 本地开发、测试 | 微服务内部 | 企业统一入口 |

*注：第三方服务也可注册到 Nacos，但需要额外配置

---

## 第三方 MCP 服务的接入方式

### 场景：接入高德地图 MCP 服务

假设高德地图提供了 MCP Server，有以下几种接入方式：

#### 方式1: 直接连接（推荐用于简单场景）

```java
// 如果高德提供了公开的 MCP Server URL
McpClientWrapper amapClient = McpClientBuilder.create("amap-mcp")
    .sseTransport("https://mcp.amap.com/sse")
    .header("X-API-Key", "your_amap_api_key")
    .buildAsync()
    .block();

toolkit.registerMcpClient(amapClient).block();
```

**优点**:
- 配置简单，快速接入
- 不需要额外基础设施
- 适合单第三方服务集成

**缺点**:
- 需要硬编码 URL
- 缺乏统一管理
- 无语义检索优化

---

#### 方式2: 通过 Higress 网关（推荐用于企业场景）

```
┌─────────────────────────────────────────────────────┐
│  Agent (Client)                                      │
└──────────────┬──────────────────────────────────────┘
               │
               │ 统一连接到 Higress
               ▼
┌─────────────────────────────────────────────────────┐
│  Higress AI Gateway                                  │
│  ┌──────────────────────────────────────────────┐   │
│  │  MCP Server Aggregator                       │   │
│  │    - 语义检索                                 │   │
│  │    - 工具聚合                                 │   │
│  │    - 统一认证                                 │   │
│  └──────────────────────────────────────────────┘   │
└─────────┬───────────────┬───────────────┬───────────┘
          │               │               │
          ▼               ▼               ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │ 高德地图  │    │ 天气服务  │    │ 自建服务  │
    │ MCP Server│   │ MCP Server│   │ MCP Server│
    └──────────┘    └──────────┘    └──────────┘
```

```java
// 配置 Higress 客户端，聚合多个第三方服务
HigressMcpClientWrapper higressClient =
    HigressMcpClientBuilder.create("higress")
        .streamableHttpEndpoint("https://your-higress.com/mcp")
        .toolSearch("地图和位置相关服务", 5)  // 语义检索
        .buildAsync()
        .block();

HigressToolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(higressClient).block();
```

**优点**:
- 统一管理所有第三方服务
- 语义检索自动选择最合适的工具
- 统一认证和限流
- 便于监控和治理

**缺点**:
- 需要部署和维护 Higress 网关
- 增加了一层网络跳转

---

#### 方式3: 注册到 Nacos（不推荐用于第三方服务）

```java
// 理论上可以配置，但不推荐
// 需要第三方服务提供方配合注册到你的 Nacos
```

**为什么不推荐**:
- 第三方服务通常不会注册到你的 Nacos
- 需要额外的网络打通和安全配置
- 增加了不必要的复杂度

---

## 架构选择建议

### 选择决策树

```
开始
  │
  ├─ 是否连接本地/已知 MCP Server？
  │   ├─ 是 → 使用直接连接方式
  │   │        - 本地开发测试
  │   │        - 单第三方服务快速集成
  │   │
  │   └─ 否 → 继续
  │
  ├─ 是否为企业内部微服务？
  │   ├─ 是 → 使用 Nacos 发现方式
  │   │        - 生产环境部署
  │   │        - 动态服务发现
  │   │        - 高可用和负载均衡
  │   │
  │   └─ 否 → 继续
  │
  ├─ 需要集成多个第三方 MCP Server？
  │   ├─ 是 → 使用 Higress 网关方式
  │   │        - 统一入口
  │   │        - 语义检索
  │   │        - 企业级治理
  │   │
  │   └─ 否 → 使用直接连接方式
  │            - 简单直接
  │            - 快速集成
```

### 典型使用场景

| 场景 | 推荐方式 | 理由 |
|------|---------|------|
| 本地开发测试 | 直接连接 | 快速、简单 |
| 文件系统操作 | 直接连接 | 本地进程通信 |
| 微服务内部调用 | Nacos | 服务发现、负载均衡 |
| 企业统一工具平台 | Higress | 统一管理、语义检索 |
| 单个第三方服务 | 直接连接 | 简单高效 |
| 多个第三方服务 | Higress | 聚合管理、语义优化 |

---

## 混合使用场景

在实际应用中，可以混合使用多种方式：

```java
Toolkit toolkit = new Toolkit();

// 1. 直接连接本地 MCP Server（文件系统）
McpClientWrapper localClient = McpClientBuilder.create("local-fs")
    .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
    .buildAsync()
    .block();
toolkit.registerMcpClient(localClient).block();

// 2. 通过 Nacos 连接内部服务
NacosMcpClientWrapper internalClient = NacosMcpClientBuilder
    .create("business-mcp-server", mcpServerManager)
    .build();
toolkit.registerMcpClient(internalClient).block();

// 3. 通过 Higress 连接第三方服务
HigressMcpClientWrapper thirdPartyClient = HigressMcpClientBuilder
    .create("higress")
    .streamableHttpEndpoint("https://higress.example.com/mcp")
    .toolSearch("third-party tools", 10)
    .buildAsync()
    .block();
toolkit.registerMcpClient(thirdPartyClient).block();

// Agent 同时可以使用所有来源的工具
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .model(model)
    .toolkit(toolkit)  // 包含所有 MCP 工具
    .memory(new InMemoryMemory())
    .build();
```

---

## 关于 Higress 的说明

### Higress 是什么？

Higress 是一个云原生 API 网关，基于阿里巴巴内部实践，提供了 AI Gateway 能力：

- **MCP Server 聚合**: 将多个 MCP Server 聚合为统一入口
- **语义工具检索**: 基于语义相似度自动选择最相关的工具
- **企业级特性**: 认证、授权、限流、监控
- **插件生态**: 丰富的插件扩展能力

### 是否必须使用 Higress？

**答案：不是必须的**

| 使用 Higress | 不使用 Higress |
|-------------|--------------|
| ✅ 统一管理多个 MCP Server | ❌ 需要分别管理每个连接 |
| ✅ 语义检索优化工具选择 | ❌ 需要手动配置工具过滤 |
| ✅ 企业级治理能力 | ❌ 缺乏统一治理 |
| ✅ 便于监控和日志 | ❌ 需要自己实现监控 |
| ❌ 额外的部署成本 | ✅ 更简单的架构 |
| ❌ 增加网络跳转 | ✅ 更低延迟 |

### 何时使用 Higress？

**建议使用 Higress 的场景**:
- 集成 5+ 个 MCP Server（包括第三方服务）
- 需要语义检索优化工具调用
- 企业生产环境，需要统一治理
- 需要统一的认证和授权

**不需要 Higress 的场景**:
- 只连接 1-2 个已知的 MCP Server
- 本地开发测试
- 简单的微服务内部调用（用 Nacos 即可）

---

## 总结

### 三种方式的核心区别

1. **直接连接**: 简单直接，适合本地和简单场景
2. **Nacos 发现**: 服务治理，适合微服务架构
3. **Higress 网关**: 企业聚合，适合多服务集成

### 架构演进建议

```
阶段1: 本地开发
  └─> 直接连接方式

阶段2: 单体应用转微服务
  └─> Nacos 发现方式

阶段3: 多服务集成（包括第三方）
  └─> Higress 网关方式（可选）
```

### 第三方服务接入建议

- **少量第三方服务**: 直接连接方式
- **多个第三方服务**: Higress 网关方式
- **不推荐**: 将第三方服务注册到 Nacos

---

## 完整的架构分层

```
┌─────────────────────────────────────────────────────────────┐
│  AgentScope Application                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  ReActAgent                                          │  │
│  │   - 决策引擎                                         │  │
│  │   - 工具调用                                         │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
│                           ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Toolkit (统一工具接口)                               │  │
│  │   ├─ Direct MCP Clients                              │  │
│  │   ├─ Nacos MCP Clients                               │  │
│  │   └─ Higress MCP Clients                             │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Direct     │      │   Nacos     │      │  Higress    │
│  Connection │      │  Discovery  │      │  Gateway    │
└──────┬──────┘      └──────┬──────┘      └──────┬──────┘
       │                    │                    │
       ▼                    ▼                    ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Local/3rd  │      │  Internal   │      │  Aggregated │
│  MCP Server │      │  MCP Server │      │  MCP Servers│
└─────────────┘      └─────────────┘      └─────────────┘
```

这种分层架构使得 AgentScope 可以灵活地通过多种方式访问 MCP 工具，适应不同的应用场景和架构需求。

---

## Higress vs Nacos：在 MCP 服务管理中的角色对比

### 官方定位：互补而非竞争

根据阿里巴巴官方文档《[MCP 网关实战：基于 Higress + Nacos 的零代码工具扩展方案](https://nacos.io/blog/nacos-gvr7dx_awbbpb_ahggmtqmxwndm22k/)》明确指出：

> **"在整个 MCP 服务中，Higress 担任 MCP Proxy 的角色，Nacos 担任 MCP Registry 的角色。"**

它们是**互补关系**，各自负责不同层面的职责。

---

### Higress：MCP Proxy（流量层）

#### 核心职责
- **MCP 网关代理**: 作为 MCP 协议的网关层，处理所有 MCP 流量
- **SSE 会话管理**: 基于 Redis 实现粘性会话，解决多实例高可用场景下的 session 维护
- **流量路由**: 将 Agent 的工具调用请求路由到后端 MCP Server
- **协议转换**: 支持 REST API 到 MCP Server 的转换（零代码配置）

#### 关键能力
| 能力 | 说明 |
|------|------|
| **粘性会话** | 使用 Redis 解决 SSE 通信的会话保持问题 |
| **负载均衡** | 多副本部署，实现高可用 |
| **运维监控** | 完善的监控体系和可视化控制台 |
| **鉴权能力** | 支持 API 认证、租户隔离、安全防护 |
| **WASM 插件** | 支持 MCP 相关的 WASM 插件扩展 |
| **工具聚合** | 将多个 MCP Server 聚合为统一入口 |
| **语义检索** | 基于向量数据库的语义工具搜索（部分版本） |

#### 在 MCP 架构中的位置
```
Agent/Cursor
    ↓ (MCP 协议)
Higress (MCP Proxy)
    ↓ (HTTP/REST)
后端服务 (无需支持 MCP)
```

**Higress 的独特价值**: 让存量 REST API "零代码"升级为 MCP Server

---

### Nacos：MCP Registry（元数据层）

#### 核心职责
- **服务注册**: MCP Server 的注册和发现
- **元数据管理**: MCP 工具的元数据（名称、描述、参数 schema 等）管理
- **动态配置**: 实时更新工具定义，无需重启服务
- **版本管理**: 支持多版本 MCP Server 管理

#### 关键能力 (Nacos 3.0+)
| 能力 | 说明 |
|------|------|
| **MCP Registry API** | 原生支持 MCP Server 注册和发现（[Nacos 3.0](https://nacos.io/blog/release-301/)） |
| **工具元数据** | 存储 MCP 工具的定义、Prompt、参数 schema |
| **命名空间隔离** | 多租户隔离，不同租户看到不同的工具集 |
| **灰度发布** | MCP 工具的灰度管理和 A/B 测试 |
| **健康检查** | 自动检查 MCP Server 的健康状态 |
| **服务发现** | 让 Higress 动态发现后端 MCP Server |
| **零信任安全** | 支持安全认证和授权（[Nacos 3.0](https://nacos.io/blog/nacos-gvr7dx_awbbpb_gg16sv97bgirkixe/)） |

#### 在 MCP 架构中的位置
```
后端服务 → 注册到 → Nacos (MCP Registry)
                      ↓
                Higress 订阅 Nacos
                      ↓
                Higress 动态获取工具定义
```

**Nacos 的独特价值**: 动态更新 MCP 工具元数据，"秒级"生效

---

### 功能对比矩阵

| 维度 | Higress (MCP Proxy) | Nacos (MCP Registry) |
|------|---------------------|---------------------|
| **核心角色** | 流量代理和网关 | 服务注册和元数据中心 |
| **所在层级** | 流量层（Data Plane） | 元数据层（Control Plane） |
| **协议处理** | ✅ MCP 协议（SSE/HTTP） | ❌ 不处理协议 |
| **会话管理** | ✅ 粘性会话（基于 Redis） | ❌ 无会话概念 |
| **服务发现** | ⚠️ 依赖 Nacos/其他 | ✅ 原生支持 |
| **元数据管理** | ⚠️ 依赖 Nacos | ✅ 核心能力 |
| **动态配置** | ⚠️ 依赖 Nacos 订阅 | ✅ 实时推送配置 |
| **负载均衡** | ✅ 支持 | ❌ 不负责 |
| **API 认证** | ✅ 支持 | ✅ 支持（服务端） |
| **零代码转换** | ✅ REST → MCP | ❌ 不支持 |
| **可视化控制台** | ✅ 完整的控制台 | ✅ Nacos Console |
| **运维监控** | ✅ 完善的监控体系 | ⚠️ 基础监控 |
| **命名空间** | ❌ 不支持 | ✅ 多租户隔离 |

---

### 功能重合点分析

虽然两者有不同定位，但在以下方面存在**功能重合**：

#### 1. 服务发现能力

**Nacos 的服务发现**:
- 存储 MCP Server 的注册信息（IP、端口、健康状态）
- 提供 API 查询服务列表

**Higress 的服务发现**:
- 可以集成多种服务发现机制（Nacos、Kubernetes、DNS）
- 负责从注册中心查询服务地址

**重合点**: 都能"发现"服务，但层面不同
- Nacos: 服务的"注册表"
- Higress: 服务的"使用者"

#### 2. 配置管理能力

**Nacos 的配置管理**:
- 存储 MCP 工具的元数据定义
- 动态推送配置变更

**Higress 的配置管理**:
- 存储 MCP Bridge 配置（连接到 Nacos 的配置）
- 路由规则配置

**重合点**: 都能管理配置，但内容不同
- Nacos: MCP 工具的业务配置
- Higress: 网关的路由和连接配置

#### 3. MCP Server 管理

**Nacos 的 MCP 管理**:
- 注册 MCP Server 信息
- 管理工具元数据
- 版本管理

**Higress 的 MCP 管理**:
- 托管 MCP Server（作为代理）
- 聚合多个 MCP Server
- REST API 转换为 MCP

**重合点**: 都能"管理"MCP Server，但角度不同
- Nacos: 元数据层面的管理（"有哪些工具"）
- Higress: 流量层面的管理（"如何调用工具"）

---

### 典型工作流程

Higress 和 Nacos 如何协同工作：

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 后端服务注册到 Nacos                                      │
│    - 注册服务信息 (IP:Port)                                  │
│    - 配置 MCP 工具元数据 (工具定义、参数 schema)             │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Higress 订阅 Nacos                                        │
│    - 订阅服务实例变化 (gRPC 8848/9848 端口)                  │
│    - 订阅 MCP 工具元数据变化                                 │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. Agent 连接 Higress                                        │
│    - 建立 SSE 连接                                          │
│    - 获取工具列表 (Higress 从 Nacos 读取)                    │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. Agent 调用工具                                            │
│    - Agent → Higress (MCP 协议)                             │
│    - Higress → 后端服务 (HTTP/REST)                         │
│    - 粘性会话保证多次调用到同一实例 (Redis)                   │
└─────────────────────────────────────────────────────────────┘
```

**关键点**:
- Nacos 负责静态配置（工具定义）
- Higress 负责动态流量（实际调用）
- 配置变更只需在 Nacos 修改，Higress 自动感知

---

### 何时选择 Higress？何时选择 Nacos？

#### 选择 Higress 的场景
✅ 需要 SSE 会话管理（多实例 MCP Server）
✅ 需要负载均衡和高可用
✅ 需要 REST API 到 MCP 的转换
✅ 需要统一网关入口
✅ 需要运维监控和可视化
✅ 需要聚合多个 MCP Server（包括第三方）

#### 选择 Nacos 的场景
✅ 需要服务注册和发现
✅ 需要动态更新 MCP 工具定义
✅ 需要多租户隔离（命名空间）
✅ 需要工具元数据管理
✅ 需要版本管理和灰度发布

#### 最佳实践：Higress + Nacos 组合
✅ **企业生产环境推荐**
- Nacos: 注册和配置中心
- Higress: 流量网关和代理
- 两者通过 gRPC 通信（8848/9848 端口）

---

### 架构对比：三种方案

#### 方案1: 仅使用 Nacos
```
Agent → 直接连接 → MCP Server
         ↑
    从 Nacos 查询地址
```
- **优点**: 简单，无额外网关层
- **缺点**: Agent 需要处理服务发现、会话管理
- **适用**: 简单场景，Agent 自己有能力管理连接

#### 方案2: 仅使用 Higress（直连 MCP Server）
```
Agent → Higress → MCP Server
        (配置硬编码地址)
```
- **优点**: 网关统一管理流量
- **缺点**: 配置变更需要修改 Higress，不灵活
- **适用**: MCP Server 地址固定，不常变更

#### 方案3: Higress + Nacos（推荐）
```
Agent → Higress → MCP Server
         ↑           ↑
    订阅配置     注册到
         ↓           ↓
       Nacos (MCP Registry)
```
- **优点**:
  - 动态配置（Nacos 秒级生效）
  - 流量管理（Higress 会话和负载均衡）
  - 零代码扩展（新服务注册即可）
- **缺点**: 架构稍复杂
- **适用**: 企业生产环境，需要动态性和高可用

---

### 官方资源参考

根据搜索到的官方资料：

- **Nacos 3.0 官方发布**: [MCP Registry、安全零信任、链接更多生态](https://nacos.io/blog/nacos-gvr7dx_awbbpb_gg16sv97bgirkixe/)
- **MCP 网关实战**: [基于 Higress + Nacos 的零代码工具扩展方案](https://nacos.io/blog/nacos-gvr7dx_awbbpb_ahggmtqmxwndm22k/)
- **Higress MCP 快速开始**: [官方文档](https://higress.cn/ai/mcp-quick-start/)
- **Nacos MCP Router**: [使用手册](https://nacos.io/en/docs/latest/ecology/use-nacos-mcp-router/)

---

### 总结：角色定位

| 组件 | 角色 | 一句话总结 |
|------|------|-----------|
| **Higress** | MCP Proxy | "流量警察" - 管理实际调用 |
| **Nacos** | MCP Registry | "服务通讯录" - 管理服务信息 |

**核心差异**:
- **Nacos**: 静态元数据层（Control Plane）- "有什么"
- **Higress**: 动态流量层（Data Plane）- "怎么用"

**功能重合但不冲突**:
- 都涉及"服务发现"，但 Nacos 是"提供"发现，Higress 是"使用"发现
- 都涉及"配置管理"，但 Nacos 是"业务配置"，Higress 是"路由配置"
- 都涉及"MCP 管理"，但 Nacos 是"定义管理"，Higress 是"调用管理"

**最佳实践**:
Higress 和 Nacos 是**黄金搭档**，一起提供完整的 MCP 服务治理能力。
