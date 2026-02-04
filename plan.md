# 智能体平台开发计划

> 基于 agentscope-java + yudao-cloud 的企业级智能体平台开发计划

## 📋 项目概述

### 项目目标
构建一个企业级智能体管理平台，支持智能体的创建、配置、对话、监控等全生命周期管理，并具备对外提供 AI 服务的能力。

### 技术选型

| 层级 | 技术选型 | 版本 | 说明 |
|------|---------|------|------|
| **前端框架** | Vue 3 | 3.3+ | 渐进式 JavaScript 框架 |
| **前端 UI** | Element Plus | 2.4+ | Vue 3 组件库 |
| **前端构建** | Vite | 5.0+ | 下一代前端构建工具 |
| **后端框架** | Spring Boot | 3.2 | Java 企业级应用框架 |
| **微服务** | Spring Cloud Alibaba | 2022.0.0.0 | 微服务解决方案 |
| **注册中心** | Nacos | 2.3.2 | 服务注册与配置中心 |
| **智能体框架** | agentscope-java | 1.0.0 | Agent Java 框架 |
| **数据库** | MySQL | 8.0+ | 关系型数据库 |
| **缓存** | Redis | 7.0+ | 缓存与会话存储 |
| **消息队列** | Redis Stream | - | 轻量级消息队列 |
| **对象存储** | MinIO | - | 文件存储（可选） |

### 项目结构

```
agentscope-platform/
├── yudao-cloud/                      # yudao 后端项目
│   ├── yudao-gateway/                # API 网关
│   ├── yudao-module-system/          # 系统管理模块
│   ├── yudao-module-infra/           # 基础设施模块
│   ├── yudao-module-bpm/             # 工作流模块
│   ├── yudao-module-agent/           # 🆕 智能体模块
│   │   ├── yudao-module-agent-api/   # API 接口定义
│   │   └── yudao-module-agent-server/# 服务实现
│   └── yudao-server/                 # 启动器
│
├── yudao-ui-admin-vue3/              # yudao 前端项目
│   └── src/
│       ├── views/
│       │   ├── system/               # 系统管理（现有）
│       │   └── agent/                # 🆕 智能体管理
│       ├── components/
│       │   ├── AgentChat/            # 🆕 对话组件
│       │   └── AgentBuilder/         # 🆕 编排器组件
│       └── api/
│           └── agent/                # 🆕 智能体 API
│
├── docker-compose.yml                # Docker 编排文件
├── deploy/                           # 部署配置
│   ├── nginx/                        # Nginx 配置
│   └── scripts/                      # 部署脚本
│
└── docs/                             # 项目文档
    ├── api/                          # API 文档
    ├── design/                       # 设计文档
    └── user-guide/                   # 用户指南
```

---

## 🎯 总体开发阶段

| 阶段 | 时间 | 目标 | 产出 |
|------|------|------|------|
| **Phase 0：准备阶段** | 第 1 周 | 环境搭建、项目初始化 | 项目骨架、开发环境 |
| **Phase 1：基础框架** | 第 2-3 周 | 后端模块、前端路由 | 可运行的基础系统 |
| **Phase 2：核心功能** | 第 4-7 周 | 智能体 CRUD、对话功能 | MVP 版本 |
| **Phase 3：高级特性** | 第 8-11 周 | 工具管理、知识库、监控 | 完整功能版本 |
| **Phase 4：优化上线** | 第 12-13 周 | 性能优化、测试、部署 | 生产就绪版本 |

**总开发周期：约 3 个月（13 周）**

---

## 📅 详细开发计划

### Phase 0：准备阶段（第 1 周）

#### 目标
- 搭建开发环境
- 初始化项目结构
- 配置基础依赖

#### 任务清单

##### 1.1 环境准备
- [ ] 安装 JDK 17
- [ ] 安装 Node.js 20+
- [ ] 安装 Maven 3.8+
- [ ] 安装 Docker Desktop
- [ ] 安装 MySQL 8.0
- [ ] 安装 Redis 7.0
- [ ] 安装 Nacos 2.3.2

##### 1.2 后端项目初始化
- [ ] Fork/Clone yudao-cloud 项目（master-jdk17 分支）
- [ ] 创建 `yudao-module-agent` 模块
  ```bash
  mkdir -p yudao-module-agent/yudao-module-agent-api
  mkdir -p yudao-module-agent/yudao-module-agent-server
  ```
- [ ] 在根 pom.xml 中添加新模块
- [ ] 配置 agentscope-java 依赖
- [ ] 创建基础包结构

##### 1.3 前端项目初始化
- [ ] Fork/Clone yudao-ui-admin-vue3 项目
- [ ] 创建智能体模块路由
- [ ] 创建智能体相关页面骨架
- [ ] 复制 boba-tea-shop 对话组件
  ```bash
  # 从 boba-tea-shop 复制组件
  cp -r boba-tea-shop/frontend/src/components/ChatInterface.vue \
     yudao-ui-admin-vue3/src/components/AgentChat/
  ```

##### 1.4 数据库设计
- [ ] 设计数据库表结构
  - `agent`（智能体表）
  - `agent_tool`（工具关联表）
  - `chat_session`（会话表）
  - `chat_message`（消息表）
  - `knowledge_base`（知识库表）
  - `tool_registry`（工具注册表）
- [ ] 创建 SQL 建表脚本
- [ ] 初始化测试数据

##### 1.5 开发规范
- [ ] 制定代码规范
- [ ] 配置 Git 工作流
- [ ] 配置 IDE 代码格式化
- [ ] 编写 README.md

#### 交付物
- [x] 可运行的项目骨架
- [x] 完整的开发环境
- [x] 数据库初始化脚本
- [x] 开发规范文档

---

### Phase 1：基础框架（第 2-3 周）

#### 目标
- 完成后端基础服务
- 完成前端路由和布局
- 实现智能体 CRUD 接口

#### 任务清单

##### 2.1 后端基础开发

**Week 2:**

- [ ] **2.1.1 创建 API 模块**
  ```java
  // yudao-module-agent-api/src/main/java/io/yudao/module/agent/api/
  ├── dto/
  │   ├── AgentDTO.java           // 智能体 DTO
  │   ├── AgentCreateReqDTO.java  // 创建请求 DTO
  │   ├── AgentUpdateReqDTO.java  // 更新请求 DTO
  │   ├── ChatSendReqDTO.java     // 对话请求 DTO
  │   └── ChatMessageDTO.java     // 消息 DTO
  ├── enums/
  │   ├── AgentTypeEnum.java      // 智能体类型枚举
  │   └── AgentStatusEnum.java    // 智能体状态枚举
  └── api/
      └── AgentApi.java           // 智能体 API 接口
  ```

- [ ] **2.1.2 配置 AgentScope 依赖**
  ```xml
  <!-- yudao-dependencies/pom.xml -->
  <properties>
      <agentscope.version>1.0.0</agentscope.version>
  </properties>

  <dependencyManagement>
      <dependencies>
          <dependency>
              <groupId>io.agentscope</groupId>
              <artifactId>agentscope-dependencies-bom</artifactId>
              <version>${agentscope.version}</version>
              <type>pom</type>
              <scope>import</scope>
          </dependency>
      </dependencies>
  </dependencyManagement>
  ```

- [ ] **2.1.3 创建数据访问层**
  ```java
  // yudao-module-agent-server/src/main/java/io/yudao/module/agent/dal/
  ├── mysql/
  │   ├── AgentMapper.java        // MyBatis Mapper
  │   ├── ChatSessionMapper.java
  │   └── ChatMessageMapper.java
  └── dataobject/
      ├── AgentDO.java            // 数据对象
      ├── ChatSessionDO.java
      └── ChatMessageDO.java
  ```

- [ ] **2.1.4 创建 Service 层**
  ```java
  // yudao-module-agent-server/src/main/java/io/yudao/module/agent/service/
  ├── AgentService.java           // 智能体服务
  ├── ChatService.java            // 对话服务
  ├── ToolService.java            // 工具服务
  └── KnowledgeService.java       // 知识库服务
  ```

**Week 3:**

- [ ] **2.1.5 创建 Controller 层**
  ```java
  // yudao-module-agent-server/src/main/java/io/yudao/module/agent/controller/
  ├── admin/
  │   ├── AgentController.java        // 智能体管理
  │   ├── ToolController.java         // 工具管理
  │   └── KnowledgeController.java    // 知识库管理
  └── app/
      └── AppChatController.java      // 面向应用的对话接口
  ```

- [ ] **2.1.6 实现智能体配置**
  ```java
  // yudao-module-agent-server/src/main/java/io/yudao/module/agent/config/
  ├── AgentScopeConfiguration.java    // AgentScope 配置
  ├── ModelConfiguration.java         // 模型配置
  ├── NacosConfiguration.java         // Nacos 配置
  └── SupervisorAgentConfig.java      // 监督者智能体配置
  ```

- [ ] **2.1.7 实现智能体核心**
  ```java
  // yudao-module-agent-server/src/main/java/io/yudao/module/agent/agent/
  ├── supervisor/
  │   ├── SupervisorAgent.java        // 监督者智能体
  │   └── SupervisorAgentFactory.java
  ├── business/
  │   └── BusinessAgent.java          // 业务智能体
  └── consult/
      └── ConsultAgent.java           // 咨询智能体
  ```

- [ ] **2.1.8 单元测试**
  - [ ] AgentService 测试
  - [ ] ChatService 测试
  - [ ] Controller 接口测试

##### 2.2 前端基础开发

**Week 2-3:**

- [ ] **2.2.1 创建智能体模块路由**
  ```typescript
  // yudao-ui-admin-vue3/src/router/index.ts
  {
    path: '/agent',
    component: Layout,
    meta: { title: '智能体管理' },
    children: [
      {
        path: 'list',
        component: () => import('@/views/agent/agent/AgentList.vue'),
        meta: { title: '智能体列表' }
      },
      {
        path: 'chat',
        component: () => import('@/views/agent/chat/Chat.vue'),
        meta: { title: '智能对话' }
      }
    ]
  }
  ```

- [ ] **2.2.2 创建智能体列表页面**
  ```vue
  <!-- AgentList.vue -->
  - 搜索栏（名称、状态、模型）
  - 数据表格（ID、名称、描述、模型、状态、操作）
  - 分页组件
  - 新增/编辑对话框
  ```

- [ ] **2.2.3 创建智能体表单页面**
  ```vue
  <!-- AgentForm.vue -->
  - 基础信息（名称、描述、类型）
  - 模型配置（模型选择、参数配置）
  - Prompt 模板编辑器
  - 工具选择器
  - 记忆配置
  ```

- [ ] **2.2.4 创建 API 接口**
  ```typescript
  // yudao-ui-admin-vue3/src/api/agent/
  ├── agent.ts                   // 智能体 API
  ├── chat.ts                    // 对话 API
  ├── tool.ts                    // 工具 API
  └── knowledge.ts               // 知识库 API
  ```

- [ ] **2.2.5 集成对话组件**
  ```vue
  <!-- 从 boba-tea-shop 复制并适配 -->
  - ChatInterface.vue            // 对话界面（适配 Element Plus）
  - MarkdownRenderer.vue         // Markdown 渲染
  - MessageList.vue              // 消息列表
  - InputBox.vue                 // 输入框
  ```

#### 交付物
- [x] 智能体 CRUD 接口
- [x] 智能体列表页面
- [x] 智能体表单页面
- [x] 单元测试覆盖率 > 60%

---

### Phase 2：核心功能（第 4-7 周）

#### 目标
- 实现智能体对话功能
- 实现会话历史管理
- 集成 agentscope-java

#### 任务清单

##### 3.1 后端核心功能

**Week 4:**

- [ ] **3.1.1 实现对话服务（SSE 流式）**
  ```java
  @Service
  public class ChatService {
      public Flux<ServerSentEvent<String>> chatStream(
          String sessionId,
          String userId,
          String message
      ) {
          // 1. 构建消息
          Msg msg = Msg.builder()
              .role(Msg.Role.USER)
              .textContent(message)
              .build();

          // 2. 调用智能体
          Flux<Event> eventStream = supervisorAgent.stream(msg, sessionId, userId);

          // 3. 转换为 SSE 格式
          return eventStream.map(event -> ServerSentEvent.<String>builder()
              .data(event.toJson())
              .build());
      }
  }
  ```

- [ ] **3.1.2 实现会话管理**
  ```java
  public interface ChatSessionService {
      // 创建会话
      Long createSession(Long agentId, String userId);

      // 获取会话历史
      List<ChatMessageDO> getSessionHistory(String sessionId);

      // 删除会话
      void deleteSession(String sessionId);
  }
  ```

- [ ] **3.1.3 实现消息存储**
  ```java
  // 支持 MongoDB 存储对话历史
  @Service
  public class ChatMessageService {
      public void saveMessage(String sessionId, Msg msg);
      public List<Msg> getMessages(String sessionId, int limit);
  }
  ```

**Week 5:**

- [ ] **3.1.4 集成 SupervisorAgent**
  ```java
  @Configuration
  public class SupervisorAgentConfig {
      @Bean
      public SupervisorAgent supervisorAgent(
          Model model,
          A2aAgentTools tools,
          String sysPrompt
      ) {
          return new SupervisorAgent(model, tools, sysPrompt);
      }
  }
  ```

- [ ] **3.1.5 集成子智能体**
  ```java
  // Business Agent（业务处理）
  @Bean
  public ReActAgent businessAgent() {
      return ReActAgent.builder()
          .name("business_agent")
          .sysPrompt("你是一个业务处理专家...")
          .model(model)
          .tools(tools)
          .build();
  }

  // Consult Agent（知识咨询）
  @Bean
  public ReActAgent consultAgent() {
      return ReActAgent.builder()
          .name("consult_agent")
          .sysPrompt("你是一个知识咨询专家...")
          .model(model)
          .tools(knowledgeTools)
          .build();
  }
  ```

- [ ] **3.1.6 配置模型服务**
  ```yaml
  # application.yaml
  agentscope:
    model:
      provider: dashscope        # 模型提供商
      api-key: ${DASHSCOPE_API_KEY}
      model-name: qwen-max
      base-url: https://dashscope.aliyuncs.com
  ```

**Week 6:**

- [ ] **3.1.7 实现工具管理**
  ```java
  @Service
  public class ToolService {
      // 注册工具
      public void registerTool(ToolDTO toolDTO);

      // 获取工具列表
      public List<ToolDTO> getToolList();

      // 调用工具
      public Object invokeTool(String toolName, Map<String, Object> params);
  }
  ```

- [ ] **3.1.8 实现 A2A 协议集成**
  ```java
  @Configuration
  public class A2aConfiguration {
      @Bean
      public A2aAgentTools a2aAgentTools() {
          return new A2aAgentTools(nacosServiceDiscovery);
      }
  }
  ```

**Week 7:**

- [ ] **3.1.9 实现权限控制**
  ```java
  @PreAuthorize("@ss.hasPermission('agent:agent:create')")
  public CommonResult<Long> createAgent(@Valid @RequestBody AgentCreateReqDTO reqDTO);

  @PreAuthorize("@ss.hasPermission('agent:agent:update')")
  public CommonResult<Boolean> updateAgent(@Valid @RequestBody AgentUpdateReqDTO reqDTO);
  ```

- [ ] **3.1.10 接口文档和测试**
  - Swagger 接口文档
  - Postman 测试集合
  - 集成测试

##### 3.2 前端核心功能

**Week 4-5:**

- [ ] **3.2.1 对话页面实现**
  ```vue
  <!-- Chat.vue -->
  - 会话列表侧边栏
  - 对话主区域
  - SSE 流式响应处理
  - Markdown 渲染
  - 思考过程展示
  ```

- [ ] **3.2.2 会话历史页面**
  ```vue
  <!-- ChatHistory.vue -->
  - 会话列表
  - 消息列表
  - 时间轴展示
  - 搜索和筛选
  ```

- [ ] **3.2.3 SSE 集成**
  ```typescript
  // 前端 SSE 处理
  const handleSendMessage = async (message: string) => {
    const eventSource = new EventSource(
      `/agent/chat/stream?sessionId=${sessionId}&message=${encodeURIComponent(message)}`
    )

    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data)
      // 实时更新消息
      appendMessage(data)
    }

    eventSource.onerror = () => {
      eventSource.close()
    }
  }
  ```

**Week 6-7:**

- [ ] **3.2.4 智能体详情页**
  ```vue
  <!-- AgentDetail.vue -->
  - 智能体信息
  - 对话统计
  - 性能指标
  - 操作按钮（对话、编辑、删除）
  ```

- [ ] **3.2.5 Prompt 编辑器**
  ```vue
  <!-- PromptEditor.vue -->
  - Monaco Editor 集成
  - 语法高亮
  - 变量提示
  - 模板预览
  ```

- [ ] **3.2.6 工具选择器**
  ```vue
  <!-- ToolkitSelector.vue -->
  - 工具列表
  - 多选支持
  - 工具预览
  - 参数配置
  ```

#### 交付物
- [x] 可用的智能体对话功能
- [x] 会话历史管理
- [x] 工具注册和调用
- [x] 完整的权限控制

---

### Phase 3：高级特性（第 8-11 周）

#### 目标
- 实现知识库管理（RAG）
- 实现工具管理界面
- 实现监控和报表

#### 任务清单

##### 4.1 知识库管理

**Week 8:**

- [ ] **4.1.1 知识库表结构**
  ```sql
  CREATE TABLE knowledge_base (
      id BIGINT PRIMARY KEY,
      name VARCHAR(100),
      description VARCHAR(500),
      type VARCHAR(50),        -- local/remote
      status VARCHAR(20),
      config TEXT,             -- JSON 配置
      creator VARCHAR(64),
      create_time DATETIME,
      updater VARCHAR(64),
      update_time DATETIME
  );

  CREATE TABLE knowledge_document (
      id BIGINT PRIMARY KEY,
      kb_id BIGINT,
      name VARCHAR(200),
      type VARCHAR(50),        -- file/url/text
      content TEXT,
      status VARCHAR(20),
      metadata TEXT,           -- JSON 元数据
      create_time DATETIME
  );
  ```

- [ ] **4.1.2 后端知识库服务**
  ```java
  @Service
  public class KnowledgeService {
      // 创建知识库
      public Long createKnowledge(KnowledgeCreateReqDTO reqDTO);

      // 上传文档
      public void uploadDocument(Long kbId, MultipartFile file);

      // 文档解析
      public void parseDocument(Long docId);

      // 向量化
      public void vectorize(Long docId);

      // 检索
      public List<String> search(Long kbId, String query, int topK);
  }
  ```

- [ ] **4.1.3 集成百炼 RAG**
  ```java
  @Configuration
  public class RagConfiguration {
      @Bean
      public RagClient ragClient() {
          return RagClient.builder()
              .accessKeyId(${DASHSCOPE_ACCESS_KEY_ID})
              .accessKeySecret(${DASHSCOPE_ACCESS_KEY_SECRET})
              .workspaceId(${DASHSCOPE_WORKSPACE_ID})
              .indexId(${DASHSCOPE_INDEX_ID})
              .build();
      }
  }
  ```

- [ ] **4.1.4 前端知识库管理**
  ```vue
  <!-- KnowledgeBase.vue -->
  - 知识库列表
  - 文档上传
  - 文档解析状态
  - 向量化进度
  - 检索测试
  ```

**Week 9:**

- [ ] **4.1.5 文档上传组件**
  ```vue
  <!-- DocumentUpload.vue -->
  - 拖拽上传
  - 进度展示
  - 批量上传
  - 格式校验
  ```

- [ ] **4.1.6 向量存储配置**
  ```vue
  <!-- VectorStore.vue -->
  - 向量存储选择（本地/云端）
  - Embedding 模型选择
  - 参数配置
  - 连接测试
  ```

##### 4.2 工具管理

**Week 10:**

- [ ] **4.2.1 工具注册表**
  ```sql
  CREATE TABLE tool_registry (
      id BIGINT PRIMARY KEY,
      name VARCHAR(100),
      type VARCHAR(50),         -- java/mcp/http
      description VARCHAR(500),
      config TEXT,             -- JSON 配置
      status VARCHAR(20),
      creator VARCHAR(64),
      create_time DATETIME
  );
  ```

- [ ] **4.2.2 工具管理服务**
  ```java
  @Service
  public class ToolService {
      // 注册 Java 工具
      public void registerJavaTool(ToolDTO toolDTO);

      // 注册 MCP 工具
      public void registerMcpTool(McpToolDTO toolDTO);

      // 注册 HTTP 工具
      public void registerHttpTool(HttpToolDTO toolDTO);

      // 获取工具 Schema
      public String getToolSchema(String toolName);

      // 调用工具
      public Object invokeTool(String toolName, Map<String, Object> params);
  }
  ```

- [ ] **4.2.3 MCP 集成**
  ```java
  @Configuration
  public class McpConfiguration {
      @Bean
      public McpClient mcpClient() {
          return McpClient.builder()
              .serverUrl(${MCP_SERVER_URL})
              .build();
      }
  }
  ```

- [ ] **4.2.4 前端工具管理**
  ```vue
  <!-- ToolRegistry.vue -->
  - 工具列表
  - 工具注册
  - 工具测试
  - 调用日志
  ```

##### 4.3 监控和报表

**Week 11:**

- [ ] **4.3.1 监控指标收集**
  ```java
  @Component
  public class AgentMetrics {
      // 对话次数
      private AtomicInteger chatCount = new AtomicInteger(0);

      // Token 消耗
      private AtomicLong tokenUsage = new AtomicLong(0);

      // 平均响应时间
      private AtomicLong avgResponseTime = new AtomicLong(0);

      // 记录调用
      public void recordChat(long responseTime, int tokens);
  }
  ```

- [ ] **4.3.2 性能监控接口**
  ```java
  @RestController
  @RequestMapping("/agent/analytics")
  public class AnalyticsController {
      // 使用统计
      @GetMapping("/usage")
      public CommonResult<UsageStatisticsVO> getUsageStats();

      // 成本分析
      @GetMapping("/cost")
      public CommonResult<CostAnalysisVO> getCostAnalysis();

      // 性能报告
      @GetMapping("/performance")
      public CommonResult<PerformanceReportVO> getPerformanceReport();
  }
  ```

- [ ] **4.3.3 前端监控页面**
  ```vue
  <!-- UsageStatistics.vue -->
  - 对话次数趋势
  - Token 消耗统计
  - 用户活跃度

  <!-- CostAnalysis.vue -->
  - 模型成本占比
  - 成本趋势
  - 预算预警

  <!-- PerformanceReport.vue -->
  - 平均响应时间
  - 错误率
  - 性能瓶颈分析
  ```

#### 交付物
- [x] 知识库管理功能
- [x] 工具管理功能
- [x] 监控和报表功能

---

### Phase 4：优化上线（第 12-13 周）

#### 目标
- 性能优化
- 安全加固
- 测试和文档
- 生产部署

#### 任务清单

##### 5.1 性能优化

**Week 12:**

- [ ] **5.1.1 后端优化**
  - 数据库索引优化
  - Redis 缓存策略
  - SQL 慢查询优化
  - 连接池配置优化
  - 异步处理优化

- [ ] **5.1.2 前端优化**
  - 路由懒加载
  - 组件按需引入
  - 图片压缩和懒加载
  - 打包体积优化
  - 首屏加载优化

- [ ] **5.1.3 对话性能优化**
  - SSE 连接池管理
  - 消息压缩
  - 分页加载历史消息
  - 实时消息去重

##### 5.2 安全加固

**Week 12:**

- [ ] **5.2.1 认证授权**
  - JWT Token 管理
  - 刷新 Token 机制
  - 权限细粒度控制
  - API 密钥管理

- [ ] **5.2.2 数据安全**
  - 敏感数据加密
  - API 签名验证
  - XSS 防护
  - SQL 注入防护
  - CSRF 防护

- [ ] **5.2.3 流控防护**
  - 接口限流
  - 防刷机制
  - 黑白名单
  - DDoS 防护

##### 5.3 测试和文档

**Week 13:**

- [ ] **5.3.1 测试**
  - 单元测试（覆盖率 > 70%）
  - 集成测试
  - 压力测试
  - 安全测试
  - 用户验收测试

- [ ] **5.3.2 文档**
  - API 接口文档（Swagger）
  - 部署文档
  - 运维手册
  - 用户手册
  - 开发者文档

##### 5.4 生产部署

**Week 13:**

- [ ] **5.4.1 Docker 镜像**
  ```dockerfile
  # yudao-module-agent/Dockerfile
  FROM openjdk:17-jdk-slim
  COPY target/yudao-module-agent-server.jar app.jar
  ENTRYPOINT ["java", "-jar", "/app.jar"]
  ```

- [ ] **5.4.2 Docker Compose 编排**
  ```yaml
  version: '3.8'
  services:
    mysql:
      image: mysql:8.0
    redis:
      image: redis:7.0
    nacos:
      image: nacos/nacos-server:2.3.2
    yudao-gateway:
      image: yudao/gateway:latest
    yudao-module-agent:
      image: yudao/module-agent:latest
    yudao-ui:
      image: yudao/ui-admin:latest
  ```

- [ ] **5.4.3 Nginx 配置**
  ```nginx
  server {
      listen 80;
      server_name agent.example.com;

      location / {
          root /usr/share/nginx/html;
          try_files $uri $uri/ /index.html;
      }

      location /api/ {
          proxy_pass http://yudao-gateway:8080/;
          proxy_set_header Host $host;
          proxy_set_header X-Real-IP $remote_addr;
      }

      location /agent/chat/stream {
          proxy_pass http://yudao-gateway:8080/agent/chat/stream;
          proxy_buffering off;
          proxy_cache off;
          proxy_set_header Connection '';
          proxy_http_version 1.1;
          chunked_transfer_encoding off;
      }
  }
  ```

- [ ] **5.4.4 监控告警**
  - Prometheus 指标采集
  - Grafana 监控面板
  - 日志收集（ELK）
  - 告警配置（钉钉/邮件）

#### 交付物
- [x] 生产就绪的代码
- [x] 完整的测试报告
- [x] 完整的文档
- [x] 生产环境部署

---

## 🎯 里程碑

| 里程碑 | 时间 | 产出 | 验收标准 |
|--------|------|------|---------|
| **M1: 项目启动** | 第 1 周 | 项目骨架、开发环境 | 项目可本地运行 |
| **M2: 基础框架** | 第 3 周 | CRUD 接口、列表页面 | 可创建智能体 |
| **M3: MVP 版本** | 第 7 周 | 对话功能、会话管理 | 可进行智能体对话 |
| **M4: 完整功能** | 第 11 周 | 工具、知识库、监控 | 功能完整可用 |
| **M5: 生产上线** | 第 13 周 | 优化、测试、部署 | 生产环境稳定运行 |

---

## 📊 资源需求

### 人力资源

| 角色 | 人数 | 职责 |
|------|------|------|
| 后端开发 | 2 人 | 后端服务开发、智能体集成 |
| 前端开发 | 1 人 | 前端页面、组件开发 |
| 全栈开发 | 1 人 | 全栈开发、架构设计 |
| 测试工程师 | 1 人 | 测试用例、自动化测试 |
| 产品经理 | 1 人 | 需求管理、用户验收 |

### 开发工具

- **IDE**: IntelliJ IDEA / VS Code
- **API 测试**: Postman / Apifox
- **数据库工具**: Navicat / DBeaver
- **版本控制**: Git / GitHub
- **项目管理**: Jira / Trello / GitHub Projects
- **文档协作**: Notion / 飞书文档

### 服务器资源（开发环境）

| 资源 | 配置 | 数量 |
|------|------|------|
| 应用服务器 | 4C8G | 2 台 |
| 数据库服务器 | 4C8G | 1 台 |
| Redis 服务器 | 2C4G | 1 台 |

### 服务器资源（生产环境）

| 资源 | 配置 | 数量 |
|------|------|------|
| 应用服务器 | 8C16G | 3 台（集群） |
| 数据库服务器 | 8C32G | 2 台（主从） |
| Redis 服务器 | 4C8G | 2 台（集群） |
| Nginx 服务器 | 4C8G | 2 台（主备） |

---

## 🔄 后续演进路径

### 阶段 1：引入 Higress（3-6 个月后）

**触发条件：**
- 智能体调用量 > 10万次/天
- 需要多模型支持和负载均衡
- 需要 Token 级别的流控

**架构升级：**
```
原架构：前端 → yudao-gateway → yudao-module-agent → LLM
升级后：前端 → Higress → yudao-module-agent → LLM
```

**开发工作量：** 1-2 周

### 阶段 2：引入 HiMarket（6-9 个月后）

**触发条件：**
- 需要对外提供智能体 API
- 有外部开发者接入需求

**架构升级：**
```
原架构：yudao-ui-admin-vue3 直接调用后端
升级后：yudao-ui-admin-vue3 + HiMarket Portal（API 市场）
```

**开发工作量：** 2-3 周

### 阶段 3：前端微服务化（9-12 个月后）

**触发条件：**
- 前端代码量 > 10万行
- 打包时间 > 5分钟
- 团队规模 > 5人

**架构升级：**
```
原架构：yudao-ui-admin-vue3 单体
升级后：Module Federation 多应用架构
```

**开发工作量：** 4-6 周

---

## ⚠️ 风险管理

### 技术风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| agentscope-java 版本更新不兼容 | 高 | 低 | 固定版本，关注更新公告 |
| LLM API 不稳定 | 高 | 中 | 多模型备份，降级策略 |
| SSE 连接频繁断开 | 中 | 中 | 实现重连机制，降级为轮询 |
| 前端性能问题 | 中 | 中 | 懒加载、虚拟滚动、分页 |
| 并发量过大导致系统崩溃 | 高 | 低 | 限流、熔断、扩容 |

### 进度风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 需求变更频繁 | 高 | 高 | 锁定核心需求，迭代开发 |
| 人员流动 | 中 | 低 | 代码规范、文档完善、知识共享 |
| 技术难点攻关时间超预期 | 中 | 中 | 提前技术预研、寻求外部支持 |
| 测试时间不足 | 高 | 中 | 并行开发测试、自动化测试 |

---

## 📈 成功指标

### 技术指标

- ✅ 单元测试覆盖率 ≥ 70%
- ✅ 接口响应时间 < 2s（P95）
- ✅ 系统可用性 ≥ 99.9%
- ✅ 并发支持 ≥ 100 QPS
- ✅ 前端首屏加载时间 < 3s

### 业务指标

- ✅ 智能体创建成功率 ≥ 95%
- ✅ 对话响应成功率 ≥ 99%
- ✅ 用户满意度 ≥ 4.0/5.0
- ✅ 日活跃用户数稳步增长

---

## 📝 附录

### A. 相关文档

- [yudao-cloud 官方文档](https://cloud.iocoder.cn/)
- [agentscope-java 文档](https://github.com/agentscope/agentscope-java)
- [Element Plus 文档](https://element-plus.org/)
- [Vue 3 文档](https://cn.vuejs.org/)

### B. 技术支持

- **yudao 社区**: https://t.zsxq.com/09b2a
- **agentscope-java Issues**: https://github.com/agentscope/agentscope-java/issues
- **Higress 社区**: https://github.com/alibaba/higress/discussions

### C. 联系方式

- **项目负责人**: [Your Name]
- **技术负责人**: [Your Name]
- **产品负责人**: [Your Name]

---

## 📌 更新日志

| 日期 | 版本 | 更新内容 | 更新人 |
|------|------|---------|--------|
| 2025-01-22 | v1.0 | 初始版本 | AI Assistant |

---

**备注：本开发计划为初步规划，实际执行中可根据项目进展进行调整。**
