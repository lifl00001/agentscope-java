# Nacos MCP 示例代码创建总结

## 已创建文件

### 1. NacosMcpExample.java
**路径**: `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/NacosMcpExample.java`

**功能**:
- 连接到 Nacos 服务器 (47.122.78.28:8848)
- 使用认证信息 (用户名: nacos, 密码: lifl1234)
- 动态发现已注册的 MCP Server
- 创建 ReActAgent 并集成 MCP 工具
- 提供交互式对话界面

**核心代码结构**:
```java
// 1. 创建 Nacos 连接
AiService aiService = createNacosAiService();

// 2. 创建 MCP Server 管理器
NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);

// 3. 构建 MCP 客户端
NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
    .create(serverName, mcpServerManager)
    .build();

// 4. 注册工具
Toolkit toolkit = new NacosToolkit();
toolkit.registerMcpClient(mcpClient).block();

// 5. 创建 Agent
ReActAgent agent = createAgent(apiKey, toolkit);
```

### 2. NACOS_MCP_EXAMPLE.md
**路径**: `agentscope-examples/quickstart/NACOS_MCP_EXAMPLE.md`

**内容**:
- 完整的使用指南
- 前置条件和环境配置
- 运行步骤说明
- 故障排查指南
- 进阶配置示例

### 3. pom.xml 更新
**路径**: `agentscope-examples/quickstart/pom.xml`

**变更**: 添加了 Nacos MCP 依赖
```xml
<!-- Nacos MCP Extension -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos</artifactId>
</dependency>
```

## 配置的 Nacos 连接信息

```java
private static final String NACOS_SERVER_ADDR = "47.122.78.28:8848";
private static final String NACOS_USERNAME = "nacos";
private static final String NACOS_PASSWORD = "lifl1234";
private static final String NACOS_NAMESPACE = ""; // 默认命名空间
```

## 使用步骤

### 1. 编译项目

```bash
cd agentscope-examples/quickstart
mvn clean compile
```

### 2. 设置环境变量

```bash
export DASHSCOPE_API_KEY=your_api_key
```

### 3. 运行示例

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.NacosMcpExample"
```

### 4. 交互流程

1. 自动连接到 Nacos
2. 提示输入 MCP Server 名称（默认: business-mcp-server）
3. 显示所有可用工具列表
4. 开始对话测试工具调用

## 示例对话场景

### 场景1: 订单管理
```
You: 帮我创建一个订单，用户ID是user123，产品ID是prod001，数量是2

Agent: [调用 order_create_order_with_user 工具]
     订单创建成功！
```

### 场景2: 查询订单
```
You: 查询用户user456的所有订单

Agent: [调用 order_get_orders_by_user 工具]
     找到3个订单...
```

### 场景3: 库存检查
```
You: 检查产品prod007的库存

Agent: [调用 order_check_stock 工具]
     当前库存: 150件
```

## 代码特性

### ✅ 完整的错误处理
- Nacos 连接失败
- MCP Server 未找到
- 工具调用异常

### ✅ 用户友好
- 清晰的提示信息
- 进度状态显示
- 错误原因说明

### ✅ 灵活配置
- 可自定义 Nacos 地址
- 可切换命名空间
- 支持多种 MCP Server

### ✅ 实用功能
- 自动工具列表展示
- 交互式对话
- 默认值快捷方式

## 与其他示例的对比

| 示例 | 连接方式 | 适用场景 |
|------|---------|---------|
| `McpToolExample` | 直接连接 | 本地/已知 MCP Server |
| `NacosMcpExample` | Nacos 发现 | 微服务架构，动态服务 |
| `HigressToolExample` | Higress 网关 | 企业统一入口，多服务聚合 |

## 技术架构

```
┌──────────────────────┐
│  NacosMcpExample     │
│  (Main Entry)        │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  AiService           │
│  (Nacos Connection)  │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  NacosMcpServerManager│
│  (Service Discovery) │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  NacosMcpClientWrapper│
│  (MCP Connection)     │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  NacosToolkit        │
│  (Tool Registry)     │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  ReActAgent          │
│  (With MCP Tools)    │
└──────────────────────┘
```

## 依赖关系

```
NacosMcpExample
├── agentscope-core
│   └── ReActAgent, Toolkit
├── agentscope-extensions-nacos
│   ├── NacosMcpServerManager
│   ├── NacosMcpClientBuilder
│   ├── NacosMcpClientWrapper
│   └── NacosToolkit
└── nacos-client
    └── AiService, AiFactory
```

## 后续改进建议

1. **添加配置文件支持**
   - 从 `application.yml` 读取 Nacos 配置
   - 支持多环境配置（dev/test/prod）

2. **增强错误恢复**
   - 自动重试机制
   - 备用 Nacos 服务器

3. **添加监控指标**
   - 工具调用统计
   - 性能监控

4. **支持更多功能**
   - 多 MCP Server 聚合
   - 工具权限控制
   - 调用日志记录

## 相关文档

- [MCP Server 架构文档](../../../mcpserver.md) - 完整的 MCP 架构说明
- [Nacos 官方文档](https://nacos.io/) - Nacos 使用指南
- [MCP 协议规范](https://modelcontextprotocol.io/) - MCP 标准规范
