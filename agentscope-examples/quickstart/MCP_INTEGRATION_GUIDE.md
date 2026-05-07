# MCP 服务调用方式详解 - 三种场景对比

## 问题概述

您提出的三个核心问题：

1. **API 转 MCP 是否通过 Higress？**
2. **Nacos 注册的 MCP 服务如何调用？NacosToolkit 怎么用？**
3. **三方服务 MCP 如何接入？直接调用还是通过 Nacos？**

---

## 核心答案速览

| 场景 | 推荐方式 | Toolkit 类型 | 核心区别 |
|------|---------|--------------|---------|
| **API 转 MCP** | Higress 网关 | `HigressToolkit` | Higress 自动转换 REST API → MCP |
| **Nacos 注册的 MCP** | Nacos 服务发现 | `Toolkit` 或 `NacosToolkit` | NacosToolkit = Toolkit（语义化命名） |
| **三方 MCP 服务** | 直接连接 | `Toolkit` | 直接连接三方 MCP Server URL |

---

## 问题1: API 转 MCP 是通过 Higress 吗？

### 答案：✅ 是的，这是推荐方式！

### Higress 的核心价值：零代码 API → MCP 转换

```
┌─────────────────────────────────────────────────────────────┐
│  存量 REST API（不需要修改代码）                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ POST /api/orders                                    │   │
│  │ GET  /api/products                                   │   │
│  │ PUT  /api/feedback                                   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  Nacos (MCP Registry) - 配置工具元数据                      │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 工具名称: order_create_order                          │   │
│  │ 请求模板: POST /api/orders                           │   │
│  │ 参数映射: requestTemplate                            │   │
│  │ 响应模板: responseTemplate                          │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  Higress (MCP Proxy) - 自动转换协议                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 接收 MCP 请求 → 调用 REST API → 返回 MCP 响应         │   │
│  │ - SSE 会话管理                                        │   │
│  │ - 参数映射                                            │   │
│  │ - 响应转换                                            │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  Agent (通过 HigressToolkit 调用)                            │
│  toolkit.registerMcpClient(higressClient)                 │
└─────────────────────────────────────────────────────────────┘
```

### 代码示例

```java
// 1. 在 Nacos 配置工具元数据（API → MCP 映射）
// 通过 Nacos 控制台或 API 配置

// 2. 创建 Higress MCP 客户端
HigressMcpClientWrapper higressClient =
    HigressMcpClientBuilder.create("higress")
        .streamableHttpEndpoint("http://higress-gateway/mcp-servers/union-tools-search")
        .buildAsync()
        .block();

// 3. 使用 HigressToolkit 注册
HigressToolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(higressClient).block();

// 4. Agent 直接调用（就像调用普通 MCP 工具）
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .toolkit(toolkit)  // 包含 Higress 聚合的所有 MCP 工具
    .model(model)
    .build();
```

### HigressToolkit vs 普通 Toolkit

```java
// HigressToolkit 继承自 Toolkit，增加了 Higress 特有功能
public class HigressToolkit extends Toolkit {

    // 缓存 Higress 客户端引用
    private HigressMcpClientWrapper higressMcpClient;

    @Override
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return super.registerMcpClient(mcpClientWrapper)
            .doOnSuccess(unused -> cacheHigressClient(mcpClientWrapper));
    }

    public HigressMcpClientWrapper getHigressMcpClient() {
        return higressMcpClient;
    }
}
```

**关键点**:
- `HigressToolkit` 只是 `Toolkit` 的轻量级包装
- 主要额外功能：缓存 Higress 客户端引用
- 实际工具注册逻辑完全相同

---

## 问题2: Nacos 注册的 MCP 服务如何调用？

### 答案：直接用 McpClientWrapper，NacosToolkit 可选！

### 重要发现：NacosToolkit = Toolkit

查看源码发现，**`NacosToolkit` 很可能就是 `Toolkit` 的别名或轻量包装**：

```java
// agentscope-core/tool/Toolkit.java 已经内置 MCP 支持
public class Toolkit {
    private final McpClientManager mcpClientManager;

    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper);
    }
}
```

### 两种调用方式（功能完全相同）

#### 方式A: 直接使用 Toolkit + McpClientWrapper（推荐）

```java
// 1. 创建 Nacos 连接
AiService aiService = AiFactory.createAiService(properties);

// 2. 通过 Nacos 发现 MCP Server
NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);
NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
    .create("business-mcp-server", mcpServerManager)
    .build();

// 3. 使用普通 Toolkit 注册
Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(mcpClient).block();

// 4. 创建 Agent
ReActAgent agent = ReActAgent.builder()
    .toolkit(toolkit)
    .model(model)
    .build();
```

#### 方式B: 使用 NacosToolkit（语义化）

```java
// 功能完全相同，只是命名更清晰
Toolkit toolkit = new NacosToolkit();  // 表明会使用 Nacos
toolkit.registerMcpClient(mcpClient).block();
```

### 完整示例：NacosMcpExample.java

```java
public class NacosMcpExample {

    // Nacos 配置
    private static final String NACOS_SERVER_ADDR = "47.122.78.28:8848";
    private static final String NACOS_USERNAME = "nacos";
    private static final String NACOS_PASSWORD = "lifl1234";

    public static void main(String[] args) throws Exception {
        // 1. 连接 Nacos
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, NACOS_SERVER_ADDR);
        properties.put(PropertyKeyConst.USERNAME, NACOS_USERNAME);
        properties.put(PropertyKeyConst.PASSWORD, NACOS_PASSWORD);

        AiService aiService = AiFactory.createAiService(properties);

        // 2. 从 Nacos 发现 MCP Server
        NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);
        NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
            .create("business-mcp-server", mcpServerManager)
            .build();

        // 3. 注册到 Toolkit
        Toolkit toolkit = new Toolkit();  // 或 new NacosToolkit()
        toolkit.registerMcpClient(mcpClient).block();

        // 4. 显示可用工具
        System.out.println("可用工具:");
        toolkit.getToolNames().forEach(tool -> System.out.println("  - " + tool));

        // 5. 创建 Agent 并使用
        ReActAgent agent = ReActAgent.builder()
            .toolkit(toolkit)
            .model(model)
            .build();

        ExampleUtils.startChat(agent);
    }
}
```

### Nacos 发现流程

```
1. Agent 启动
   ↓
2. 创建 NacosMcpServerManager
   ↓
3. 调用 NacosMcpClientBuilder.create("service-name", manager)
   ↓
4. Builder 内部逻辑：
   - 从 Nacos 查询服务信息（IP:Port）
   - 获取工具列表和元数据
   - 建立 SSE 连接到 MCP Server
   - 创建 NacosMcpClientWrapper
   ↓
5. 注册到 Toolkit
   ↓
6. Agent 可以像调用本地工具一样调用 MCP 工具
```

---

## 问题3: 三方 MCP 服务如何接入？

### 答案：根据场景选择，推荐直接连接！

### 三种接入方式对比

#### 方式1: 直接连接三方 MCP Server（推荐用于简单场景）

```java
// 适合：单个三方服务，公开的 MCP Server URL
McpClientWrapper amapClient = McpClientBuilder.create("amap-mcp")
    .sseTransport("https://mcp.amap.com/sse")
    .header("X-API-Key", "your_api_key")
    .buildAsync()
    .block();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(amapClient).block();
```

**优点**:
- ✅ 配置简单，快速接入
- ✅ 不需要额外基础设施
- ✅ 直接控制，易于调试

**缺点**:
- ❌ 硬编码 URL
- ❌ 服务变更需要重新部署
- ❌ 缺乏统一管理

---

#### 方式2: 通过 Higress 网关聚合（推荐用于企业场景）

```
Agent → Higress → [高德地图, 天气服务, 自建服务]
```

```java
// 聚合多个三方服务
HigressMcpClientWrapper higressClient = HigressMcpClientBuilder
    .create("higress")
    .streamableHttpEndpoint("https://your-higress.com/mcp")
    .toolSearch("地图和位置服务", 5)  // 语义检索
    .buildAsync()
    .block();

HigressToolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(higressClient).block();
```

**优点**:
- ✅ 统一入口，统一管理
- ✅ 语义检索自动选择工具
- ✅ 统一认证、限流、监控

**缺点**:
- ❌ 需要部署 Higress 网关
- ❌ 增加网络跳转

---

#### 方式3: 注册到 Nacos（不推荐）

**为什么不推荐？**

1. **三方服务不会主动注册到你的 Nacos**
   - 高德地图等第三方服务不知道你的 Nacos 地址
   - 它们有独立的服务发现机制

2. **需要额外配置**
   - 您需要在 Nacos 中手动创建服务
   - 配置服务地址和工具元数据
   - 维护成本高

3. **没有实际收益**
   - 三方服务地址通常稳定
   - 不需要动态发现
   - 反而增加了复杂度

**如果有特殊需求（如统一管理），可以这样配置：**

```java
// 1. 手动在 Nacos 中注册三方 MCP Server
// 通过 Nacos API 或控制台

// 2. 使用 Nacos 方式调用
AiService aiService = AiFactory.createAiService(properties);
NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
    .create("third-party-amap", aiService)
    .build();
```

---

## 完整对比表

| 场景 | 推荐方式 | Toolkit | 客户端类型 | 配置复杂度 | 服务发现 |
|------|---------|---------|-----------|-----------|---------|
| **API 转 MCP** | Higress | `HigressToolkit` | `HigressMcpClientWrapper` | 中 | Higress 自动 |
| **内部微服务 MCP** | Nacos 发现 | `Toolkit` | `NacosMcpClientWrapper` | 中 | Nacos 自动 |
| **三方 MCP (单服务)** | 直接连接 | `Toolkit` | `McpClientWrapper` | 低 | 无 |
| **三方 MCP (多服务)** | Higress 聚合 | `HigressToolkit` | `HigressMcpClientWrapper` | 中 | Higress 自动 |

---

## 实战建议

### 场景1: 存量 REST API 转 MCP（企业内部）

**推荐架构**: Nacos + Higress

```java
// 1. 后端服务注册到 Nacos
// 2. 在 Nacos 配置 API → MCP 映射
// 3. Higress 从 Nacos 订阅配置
// 4. Agent 通过 HigressToolkit 调用
```

**优势**:
- ✅ 零代码转换
- ✅ 动态配置
- ✅ 统一管理

---

### 场景2: 微服务内部 MCP Server

**推荐架构**: Nacos 服务发现

```java
// 使用普通 Toolkit 即可
Toolkit toolkit = new Toolkit();
NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
    .create("service-name", mcpServerManager)
    .build();
toolkit.registerMcpClient(mcpClient).block();
```

**优势**:
- ✅ 动态服务发现
- ✅ 负载均衡
- ✅ 配置简单

---

### 场景3: 接入高德地图等三方服务

**推荐架构**: 直接连接

```java
// 直接连接，简单高效
McpClientWrapper amapClient = McpClientBuilder.create("amap")
    .sseTransport("https://mcp.amap.com/sse")
    .buildAsync()
    .block();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(amapClient).block();
```

**优势**:
- ✅ 配置简单
- ✅ 快速接入
- ✅ 易于调试

**如果需要聚合多个三方服务**，再考虑 Higress：
```java
// 通过 Higress 聚合多个三方服务
HigressMcpClientWrapper client = HigressMcpClientBuilder
    .create("higress")
    .streamableHttpEndpoint("https://gateway/mcp")
    .buildAsync()
    .block();
```

---

## 关键要点总结

### 1. API 转 MCP = Higress 的核心能力

- ✅ Higress 提供零代码转换
- ✅ 在 Nacos 配置 API → MCP 映射
- ✅ Higress 自动处理协议转换
- ✅ 存量 API 无需修改代码

### 2. Nacos Toolkit = 普通 Toolkit

- ✅ `Toolkit` 已经内置 MCP 支持
- ✅ `NacosToolkit` 可能只是语义化命名
- ✅ 核心是 `NacosMcpClientWrapper` 负责服务发现
- ✅ `Toolkit.registerMcpClient()` 统一注册

### 3. 三方服务接入 = 直接连接优先

- ✅ 单个三方服务：直接连接最简单
- ✅ 多个三方服务：考虑 Higress 聚合
- ❌ 不推荐：注册到 Nacos（增加复杂度，无实际收益）

---

## 快速决策树

```
需要接入 MCP 工具
    │
    ├─ 存量 REST API？
    │   └─ 是 → 使用 Higress (Nacos + Higress)
    │
    ├─ 内部微服务 MCP Server？
    │   └─ 是 → 使用 Nacos 发现 (Toolkit + NacosMcpClientWrapper)
    │
    ├─ 三方 MCP 服务？
    │   ├─ 单个服务 → 直接连接 (Toolkit + McpClientWrapper)
    │   └─ 多个服务 → Higress 聚合 (HigressToolkit + HigressMcpClientWrapper)
    │
    └─ 本地工具？
        └─ 使用 @Tool 注解 + Toolkit.registerTool()
```

---

## 示例代码位置

- **Nacos 发现示例**: `NacosMcpExample.java`
- **Higress 聚合示例**: `HigressToolExample.java`
- **直接连接示例**: `McpToolExample.java`
- **完整架构文档**: `mcpserver.md`
