# Nacos MCP Example 使用指南

## 概述

`NacosMcpExample` 演示了如何通过 Nacos 服务发现来集成 MCP (Model Context Protocol) 工具。

## 功能特性

- ✅ 连接到远程 Nacos 服务器
- ✅ 从 Nacos 动态发现 MCP Server
- ✅ 自动注册 MCP 工具到 Agent
- ✅ 显示所有可用工具列表
- ✅ 交互式对话测试工具调用

## 前置条件

### 1. 环境变量设置

```bash
# 设置 DashScope API Key
export DASHSCOPE_API_KEY=your_dashscope_api_key
```

### 2. Nacos 服务器

确保 Nacos 服务器正在运行：
- **地址**: `47.122.78.28:8848`
- **用户名**: `nacos`
- **密码**: `lifl1234`

### 3. MCP Server 注册

确保至少有一个 MCP Server 已注册到 Nacos，例如：
- `business-mcp-server`
- 或其他自定义 MCP Server

## 运行示例

### 方式1: 使用 Maven

```bash
cd agentscope-examples/quickstart
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.NacosMcpExample"
```

### 方式2: 使用 IDE

1. 在 IDE 中打开 `NacosMcpExample.java`
2. 设置环境变量 `DASHSCOPE_API_KEY`
3. 运行 `main` 方法

## 使用流程

### 1. 启动示例

```bash
$ mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.NacosMcpExample"

========================================
  AgentScope Java - Examples
========================================

Nacos MCP Example
========================================

This example demonstrates MCP (Model Context Protocol) integration via Nacos.
MCP servers are discovered dynamically from Nacos service registry.
Nacos Server: 47.122.78.28:8848
```

### 2. 连接 Nacos

系统会自动连接到配置的 Nacos 服务器：

```
========================================
Connecting to Nacos...
========================================
Server: 47.122.78.28:8848
Username: nacos
========================================

✓ Successfully connected to Nacos
```

### 3. 输入 MCP Server 名称

```
========================================
MCP Server Configuration
========================================
Enter MCP server name (registered in Nacos)
Press Enter for default 'business-mcp-server':
```

输入已在 Nacos 注册的 MCP Server 名称，或按回车使用默认值。

### 4. 查看可用工具

成功连接后，会显示所有可用的 MCP 工具：

```
========================================
Initializing MCP client from Nacos...
========================================
✓ Successfully connected to MCP server: business-mcp-server
✓ Discovered and registered MCP tools

========================================
Available Tools:
========================================
  - feedback_create_feedback
  - feedback_get_feedback_by_order
  - feedback_get_feedback_by_user
  - feedback_update_solution
  - order_check_stock
  - order_create_order_with_user
  - order_delete_order
  - order_get_order
  - order_get_order_by_user
  - order_get_orders
  - order_get_orders_by_user
  - order_query_orders
  - order_update_remark
  - order_validate_product
========================================

Agent is ready! You can now ask questions that require MCP tools.
Type 'exit' to quit.
```

### 5. 开始对话

现在可以与 Agent 对话，它会自动调用 MCP 工具：

```
You: 帮我创建一个订单，用户ID是user123，产品ID是prod001，数量是2

Assistant: 我来帮您创建订单。

[调用工具: order_create_order_with_user]
参数: {
  "userId": "user123",
  "productId": "prod001",
  "quantity": 2
}

订单创建成功！订单号：ORDER-20250104-001
```

## 代码结构

### 主要组件

1. **NacosAiService**: Nacos 服务连接
   ```java
   AiService aiService = createNacosAiService();
   ```

2. **NacosMcpServerManager**: MCP Server 管理器
   ```java
   NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);
   ```

3. **NacosMcpClientBuilder**: MCP 客户端构建器
   ```java
   NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
       .create(serverName, mcpServerManager)
       .build();
   ```

4. **NacosToolkit**: 支持服务发现的工具包
   ```java
   Toolkit toolkit = new NacosToolkit();
   toolkit.registerMcpClient(mcpClient).block();
   ```

### 配置参数

在 `NacosMcpExample.java` 中修改：

```java
// Nacos 服务器配置
private static final String NACOS_SERVER_ADDR = "47.122.78.28:8848";
private static final String NACOS_USERNAME = "nacos";
private static final String NACOS_PASSWORD = "lifl1234";
private static final String NACOS_NAMESPACE = ""; // 空字符串表示默认命名空间
```

## 故障排查

### 问题1: 连接 Nacos 失败

**错误信息**:
```
✗ Failed to connect to Nacos: xxx
```

**解决方案**:
1. 检查 Nacos 服务器是否运行
2. 验证网络连接
3. 确认用户名和密码正确
4. 检查防火墙设置

### 问题2: MCP Server 未找到

**错误信息**:
```
✗ Failed to initialize MCP client: MCP server not found
```

**解决方案**:
1. 登录 Nacos 控制台 (http://47.122.78.28:8848/nacos)
2. 检查服务列表中是否有 MCP Server
3. 确认服务名称拼写正确
4. 检查服务是否健康

### 问题3: 工具调用失败

**错误信息**:
```
Error calling tool: xxx
```

**解决方案**:
1. 检查 MCP Server 是否正常运行
2. 验证工具参数是否正确
3. 查看 MCP Server 日志
4. 确认网络可达性

## 进阶配置

### 使用特定命名空间

如果 MCP Server 注册在非默认命名空间：

```java
private static final String NACOS_NAMESPACE = "your-namespace-id";
```

### 添加认证头

如果 MCP Server 需要认证：

```java
NacosMcpClientWrapper mcpClient = NacosMcpClientBuilder
    .create(serverName, mcpServerManager)
    .header("Authorization", "Bearer your-token")
    .build();
```

### 工具过滤

只注册特定工具：

```java
toolkit.registration()
    .mcpClient(mcpClient)
    .enableTools(List.of("order_create_order", "order_get_order"))
    .apply();
```

## 相关资源

- [MCP 协议文档](https://modelcontextprotocol.io/)
- [Nacos 官方文档](https://nacos.io/)
- [AgentScope Java 文档](../../docs/zh/)
- [MCP Server 架构文档](../../mcpserver.md)

## 下一步

- 查看 `McpToolExample.java` 了解直接连接方式
- 查看 `HigressToolExample.java` 了解网关方式
- 阅读 `mcpserver.md` 了解完整架构对比
