# SessionManager 完整架构与调用树分析

## 📊 SessionManager.saveSession() 完整调用树

### 层级1: 用户代码调用

```
用户代码: sessionManager.saveSession()
    ↓
SessionManager.saveSession() [第157-161行]
```

### 层级2: SessionManager 协调层

```java
// SessionManager.java 第157-161行
public void saveSession() {
    Session session = checkAndGetSession();                    // 1. 获取Session实例
    Map<String, StateModule> componentMap = buildComponentMap(); // 2. 构建组件映射
    session.saveSessionState(sessionId, componentMap);         // 3. 委托给Session保存
}
```

**调用步骤详解**:

#### 步骤1: checkAndGetSession()
```java
// SessionManager.java 第243-248行
private Session checkAndGetSession() {
    if (session == null) {
        throw new IllegalStateException("No session configured. Use withSession()");
    }
    return session;  // 返回 JsonSession/RedisSession/MysqlSession 等实例
}
```

#### 步骤2: buildComponentMap()
```java
// SessionManager.java 第250-258行
private Map<String, StateModule> buildComponentMap() {
    Map<String, StateModule> componentMap = new LinkedHashMap<>();
    for (StateModule component : components) {
        String name = getComponentName(component);  // 获取组件名称
        componentMap.put(name, component);          // 构建 name -> component 映射
    }
    return componentMap;
    // 返回示例: {
    //   "reActAgent" -> ReActAgent实例,
    //   "autoContextMemory" -> AutoContextMemory实例
    // }
}
```

#### 步骤3: getComponentName()
```java
// SessionManager.java 第260-273行
private String getComponentName(StateModule component) {
    // 1. 优先使用组件自定义名称
    String componentName = component.getComponentName();
    if (componentName != null && !componentName.trim().isEmpty()) {
        return componentName;
    }

    // 2. 使用类名(首字母小写)作为默认名称
    // ReActAgent -> "reActAgent"
    // AutoContextMemory -> "autoContextMemory"
    String className = component.getClass().getSimpleName();
    if (className.isEmpty()) {
        return "component";
    }
    return Character.toLowerCase(className.charAt(0)) + className.substring(1);
}
```

---

### 层级3: Session 存储层 (以JsonSession为例)

```java
// JsonSession.java 第88-110行
@Override
public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
    validateSessionId(sessionId);  // 1. 验证sessionId合法性

    try {
        // 2. 收集所有组件的状态
        Map<String, Object> sessionState = new HashMap<>();
        for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
            sessionState.put(entry.getKey(), entry.getValue().stateDict());
            //                 ↑ 组件名           ↑ 调用StateModule.stateDict()
        }

        // 3. 确定文件路径
        Path sessionFile = getSessionPath(sessionId);
        // 例如: /home/user/.agentscope/sessions/session000005.json

        // 4. 写入JSON文件
        objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(sessionFile.toFile(), sessionState);

    } catch (IOException e) {
        throw new RuntimeException("Failed to save session: " + sessionId, e);
    }
}
```

**关键方法详解**:

#### validateSessionId()
```java
// JsonSession.java 第280-290行
protected void validateSessionId(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("Session ID cannot be null or empty");
    }
    if (sessionId.contains("/") || sessionId.contains("\\")) {
        throw new IllegalArgumentException("Session ID cannot contain path separators");
    }
    if (sessionId.length() > 255) {
        throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
    }
}
```

#### getSessionPath()
```java
// JsonSession.java 第298-300行
private Path getSessionPath(String sessionId) {
    return sessionDirectory.resolve(sessionId + ".json");
    // sessionDirectory: /home/user/.agentscope/sessions
    // 返回: /home/user/.agentscope/sessions/session000005.json
}
```

---

### 层级4: StateModule 状态收集层

```java
// StateModuleBase.java 第55-97行
@Override
public Map<String, Object> stateDict() {
    // 确保嵌套模块已被发现
    refreshNestedModules();

    Map<String, Object> state = new LinkedHashMap<>();

    // 1. 收集嵌套模块的状态
    for (Map.Entry<String, StateModule> entry : moduleMap.entrySet()) {
        state.put(entry.getKey(), entry.getValue().stateDict());  // 递归调用
    }

    // 2. 收集已注册属性的状态
    for (Map.Entry<String, AttributeInfo> entry : attributeMap.entrySet()) {
        String attrName = entry.getKey();
        AttributeInfo attrInfo = entry.getValue();

        try {
            Object value = getAttributeValue(attrName);  // 通过反射获取字段值
            if (value != null) {
                // 应用自定义序列化函数(如果有)
                if (attrInfo.toJsonFunction != null) {
                    Field field = findField(attrName);
                    if (field != null) {
                        value = attrInfo.toJsonFunction.apply(value);
                    }
                }
                state.put(attrName, value);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize attribute: " + attrName, e);
        }
    }

    return state;
}
```

**以AutoContextMemory为例**:

```java
// AutoContextMemory.java 第115-134行
public AutoContextMemory(AutoContextConfig autoContextConfig, Model model) {
    this.model = model;
    this.autoContextConfig = autoContextConfig;
    workingMemoryStorage = new ArrayList<>();
    originalMemoryStorage = new ArrayList<>();
    offloadContext = new HashMap<>();
    compressionEvents = new ArrayList<>();
    
    // 注册需要持久化的字段
    registerState("workingMemoryStorage", 
                  MsgUtils::serializeMsgList,      // 序列化函数
                  MsgUtils::deserializeToMsgList); // 反序列化函数
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

---

## 🏗️ 整体架构设计

### 三层架构模式

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: SessionManager (协调层)                            │
│                                                              │
│  职责:                                                       │
│  - 管理组件列表 (List<StateModule> components)              │
│  - 组件名称映射 (buildComponentMap)                          │
│  - Session实例管理                                           │
│  - 提供便捷API (saveSession/loadSession)                    │
│                                                              │
│  核心数据结构:                                               │
│  - sessionId: String                                         │
│  - components: List<StateModule>                             │
│  - session: Session                                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Session (存储抽象层)                                │
│                                                              │
│  接口定义:                                                   │
│  - saveSessionState(sessionId, stateModules)                │
│  - loadSessionState(sessionId, stateModules)                │
│  - sessionExists(sessionId)                                 │
│  - deleteSession(sessionId)                                 │
│                                                              │
│  实现类:                                                     │
│  ├─ JsonSession (文件存储)                                   │
│  ├─ RedisSession (Redis存储)                                 │
│  ├─ MysqlSession (MySQL存储)                                 │
│  └─ InMemorySession (内存存储)                               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: StateModule (状态抽象层)                            │
│                                                              │
│  接口定义:                                                   │
│  - stateDict(): Map<String, Object>                         │
│  - loadStateDict(stateDict, strict)                         │
│  - registerState(name, toJson, fromJson)                    │
│  - getComponentName(): String                               │
│                                                              │
│  实现基类: StateModuleBase                                   │
│  - 自动发现嵌套StateModule                                   │
│  - 支持自定义序列化函数                                       │
│  - 通过反射读写字段值                                         │
│                                                              │
│  具体实现:                                                   │
│  ├─ ReActAgent                                               │
│  ├─ AutoContextMemory                                        │
│  ├─ InMemoryMemory                                           │
│  └─ ... 其他组件                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 💾 数据保存机制详解

### 1. 数据收集阶段

```
SessionManager.saveSession()
    ↓
buildComponentMap()
    ↓
创建组件名称映射:
{
  "reActAgent" -> ReActAgent实例,
  "autoContextMemory" -> AutoContextMemory实例
}
    ↓
session.saveSessionState(sessionId, componentMap)
    ↓
对每个组件调用 component.stateDict()
    ↓
AutoContextMemory.stateDict() {
    返回: {
        "workingMemoryStorage": [序列化后的消息列表],
        "originalMemoryStorage": [序列化后的消息列表],
        "offloadContext": {序列化后的卸载内容},
        "compressionEvents": [序列化后的压缩事件]
    }
}
    ↓
ReActAgent.stateDict() {
    返回: {
        "memory": {嵌套的Memory状态}
    }
}
    ↓
汇总所有组件状态:
{
  "reActAgent": {
      "memory": {...}
  },
  "autoContextMemory": {
      "workingMemoryStorage": [...],
      "originalMemoryStorage": [...],
      "offloadContext": {...},
      "compressionEvents": [...]
  }
}
```

### 2. 数据序列化阶段

#### JsonSession 序列化
```java
// 使用Jackson ObjectMapper序列化为JSON
objectMapper.writerWithDefaultPrettyPrinter()
    .writeValue(sessionFile.toFile(), sessionState);
```

**生成的JSON文件结构**:
```json
{
  "reActAgent": {
    "memory": {
      "messages": [
        {
          "id": "msg-123",
          "role": "USER",
          "content": [
            {
              "type": "text",
              "text": "帮我创建文件"
            }
          ]
        }
      ]
    }
  },
  "autoContextMemory": {
    "workingMemoryStorage": [
      {
        "id": "msg-123",
        "role": "USER",
        "content": [...]
      },
      {
        "id": "msg-124",
        "role": "ASSISTANT",
        "content": [...]
      }
    ],
    "originalMemoryStorage": [...],
    "offloadContext": {
      "uuid-abc-123": [
        {
          "id": "msg-125",
          "content": "大型消息内容..."
        }
      ]
    },
    "compressionEvents": [
      {
        "eventType": "TOOL_INVOCATION_COMPRESS",
        "timestamp": 1735123456789,
        "compressedMessageCount": 5,
        "metadata": {...}
      }
    ]
  }
}
```

#### RedisSession 序列化
```java
// 序列化为JSON字符串存储到Redis
String json = objectMapper.writeValueAsString(sessionState);
redisClient.set(sessionKey, json);
```

**Redis存储结构**:
```
Key: agentscope:session:session000005
Value: {"reActAgent":{...},"autoContextMemory":{...}}

Key: agentscope:session:session000005:meta
Value: {"lastModified":1735123456789,"componentCount":2}
```

#### MysqlSession 序列化
```java
// 序列化为JSON存储到MySQL
String json = objectMapper.writeValueAsString(sessionState);
String sql = "INSERT INTO sessions (session_id, state_data, last_modified) VALUES (?, ?, NOW()) " +
             "ON DUPLICATE KEY UPDATE state_data = ?, last_modified = NOW()";
```

**MySQL表结构**:
```sql
CREATE TABLE sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    state_data TEXT NOT NULL,
    last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 🔄 数据加载机制详解

### SessionManager.loadIfExists() 调用树

```
SessionManager.loadIfExists()
    ↓
session.sessionExists(sessionId)  // 检查会话是否存在
    ↓
buildComponentMap()  // 构建组件映射
    ↓
session.loadSessionState(sessionId, componentMap)
    ↓
JsonSession.loadSessionState()
    ↓
读取JSON文件:
Map<String, Object> sessionState = objectMapper.readValue(file, Map.class)
    ↓
对每个组件:
for (componentName, module in componentMap) {
    componentState = sessionState.get(componentName)
    module.loadStateDict(componentState, false)  // 非严格模式
}
    ↓
StateModuleBase.loadStateDict()
    ↓
1. 加载嵌套模块状态
2. 加载已注册属性状态
    ↓
AutoContextMemory字段恢复:
- workingMemoryStorage <- 反序列化
- originalMemoryStorage <- 反序列化
- offloadContext <- 反序列化
- compressionEvents <- 反序列化
```

### 自定义反序列化示例

```java
// AutoContextMemory 注册状态时指定反序列化函数
registerState("workingMemoryStorage", 
    MsgUtils::serializeMsgList,      // 序列化: List<Msg> -> JSON
    MsgUtils::deserializeToMsgList); // 反序列化: JSON -> List<Msg>

// StateModuleBase.loadStateDict() 调用反序列化
Object value = stateDict.get("workingMemoryStorage");
if (attrInfo.fromJsonFunction != null && value != null) {
    value = attrInfo.fromJsonFunction.apply(value);
    // MsgUtils.deserializeToMsgList(value)
}
setAttributeValue("workingMemoryStorage", value);  // 通过反射设置字段
```

---

## 📦 保存的数据内容

### 1. AutoContextMemory 保存内容

| 字段名 | 类型 | 说明 | 示例大小 |
|-------|------|------|---------|
| `workingMemoryStorage` | `List<Msg>` | 压缩后的工作消息列表 | 10-30条消息 |
| `originalMemoryStorage` | `List<Msg>` | 完整的原始消息历史 | 所有历史消息 |
| `offloadContext` | `Map<String, List<Msg>>` | 卸载的大型消息内容 | 根据卸载次数 |
| `compressionEvents` | `List<CompressionEvent>` | 压缩操作记录 | 每次压缩1条 |

### 2. ReActAgent 保存内容

| 字段名 | 类型 | 说明 |
|-------|------|------|
| `memory` | `Memory` | 嵌套的Memory状态 (递归保存) |

### 3. InMemoryMemory 保存内容

| 字段名 | 类型 | 说明 |
|-------|------|------|
| `messages` | `List<Msg>` | 所有对话消息 |

---

## 💡 实际使用示例

### 示例1: 基本用法

```java
// 初始化
String sessionId = "session000005";
Path sessionPath = Paths.get(System.getProperty("user.home"), 
                            ".agentscope", "examples", "sessions");

// 创建SessionManager
SessionManager sessionManager = SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(sessionPath))
    .addComponent(agent)      // 添加Agent组件
    .addComponent(memory);    // 添加Memory组件

// 加载已存在的会话
if (sessionManager.sessionExists()) {
    sessionManager.loadIfExists();
    System.out.println("会话已恢复，历史消息数: " + 
                       agent.getMemory().getMessages().size());
}

// 运行对话
while (true) {
    String userInput = scanner.nextLine();
    if ("exit".equals(userInput)) break;
    
    Msg userMsg = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder().text(userInput).build())
        .build();
    
    Msg response = agent.call(userMsg).block();
    System.out.println("Assistant: " + response.getTextContent());
    
    // 每次对话后保存会话
    sessionManager.saveSession();
}
```

**保存的JSON文件** (`session000005.json`):
```json
{
  "reActAgent": {
    "memory": {
      "messages": [
        {
          "id": "msg-1",
          "role": "USER",
          "name": "User",
          "content": [
            {
              "type": "text",
              "text": "帮我创建一个hello.txt文件"
            }
          ],
          "metadata": {}
        },
        {
          "id": "msg-2",
          "role": "ASSISTANT",
          "name": "Assistant",
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
              "id": "call-123"
            }
          ],
          "metadata": {}
        },
        {
          "id": "msg-3",
          "role": "TOOL",
          "name": "WriteFileTool",
          "content": [
            {
              "type": "tool_result",
              "toolName": "WriteFileTool",
              "toolCallId": "call-123",
              "content": "文件已创建成功",
              "isError": false
            }
          ],
          "metadata": {}
        }
      ]
    }
  },
  "autoContextMemory": {
    "workingMemoryStorage": [...],  // 同上
    "originalMemoryStorage": [...], // 完整历史
    "offloadContext": {},           // 无卸载内容
    "compressionEvents": []         // 无压缩事件
  }
}
```

### 示例2: 使用Redis存储

```java
// 创建Redis连接池
JedisPool jedisPool = new JedisPool("localhost", 6379);

// 创建RedisSession
Session redisSession = JedisSession.builder()
    .jedisPool(jedisPool)
    .keyPrefix("agentscope:session:")
    .build();

// 使用RedisSession
SessionManager sessionManager = SessionManager.forSessionId("user-123")
    .withSession(redisSession)
    .addComponent(agent)
    .addComponent(memory);

// 保存到Redis
sessionManager.saveSession();
```

**Redis存储内容**:
```
127.0.0.1:6379> GET "agentscope:session:user-123"
"{\"reActAgent\":{\"memory\":{...}},\"autoContextMemory\":{...}}"

127.0.0.1:6379> GET "agentscope:session:user-123:meta"
"{\"lastModified\":1735123456789,\"componentCount\":2}"
```

### 示例3: 自定义组件名称

```java
// 自定义组件名称
public class MyCustomMemory extends StateModuleBase implements Memory {
    
    @Override
    public String getComponentName() {
        return "myMemory";  // 自定义名称
    }
    
    // ... 其他实现
}

// 使用
SessionManager sessionManager = SessionManager.forSessionId("session-001")
    .withSession(new JsonSession())
    .addComponent(agent)           // 默认名称: "reActAgent"
    .addComponent(new MyCustomMemory());  // 自定义名称: "myMemory"

sessionManager.saveSession();
```

**保存的JSON**:
```json
{
  "reActAgent": {...},
  "myMemory": {...}  // 使用自定义名称
}
```

---

## 🎯 核心设计优势

### 1. 分层解耦
- **SessionManager**: 只负责协调，不关心存储细节
- **Session**: 只负责存储，不关心组件内部结构
- **StateModule**: 只负责状态序列化，不关心存储方式

### 2. 灵活扩展
- 新增存储方式: 实现`Session`接口
- 新增可持久化组件: 实现`StateModule`接口
- 自定义序列化: 通过`registerState()`注册自定义函数

### 3. 类型安全
```java
// 强类型的组件注册
SessionManager.addComponent(StateModule component)

// 强类型的状态管理
Map<String, StateModule> componentMap
```

### 4. 自动发现
```java
// StateModuleBase 自动发现嵌套StateModule字段
private void refreshNestedModules() {
    for (Field field : this.getClass().getDeclaredFields()) {
        if (StateModule.class.isAssignableFrom(field.getType())) {
            // 自动添加到 moduleMap
        }
    }
}
```

### 5. 线程安全
```java
// 使用ConcurrentHashMap保证线程安全
private final Map<String, AttributeInfo> attributeMap = new ConcurrentHashMap<>();
```

---

## 🔍 关键技术点

### 1. 反射机制
```java
// 通过反射读取字段值
private Object getAttributeValue(String attributeName) throws Exception {
    Field field = findField(attributeName);
    if (field != null) {
        field.setAccessible(true);
        return field.get(this);
    }
    return null;
}

// 通过反射设置字段值
private void setAttributeValue(String attributeName, Object value) throws Exception {
    Field field = findField(attributeName);
    if (field != null) {
        field.setAccessible(true);
        field.set(this, value);
    }
}
```

### 2. 自定义序列化
```java
// 注册自定义序列化函数
registerState("messages", 
    // 序列化: List<Msg> -> String
    messages -> objectMapper.writeValueAsString(messages),
    // 反序列化: String -> List<Msg>
    json -> objectMapper.readValue(json.toString(), 
                                   new TypeReference<List<Msg>>() {})
);
```

### 3. 递归状态收集
```java
@Override
public Map<String, Object> stateDict() {
    Map<String, Object> state = new LinkedHashMap<>();
    
    // 递归收集嵌套模块状态
    for (Map.Entry<String, StateModule> entry : moduleMap.entrySet()) {
        state.put(entry.getKey(), entry.getValue().stateDict());  // 递归
    }
    
    // 收集当前层级的属性状态
    for (Map.Entry<String, AttributeInfo> entry : attributeMap.entrySet()) {
        state.put(entry.getKey(), getAttributeValue(entry.getKey()));
    }
    
    return state;
}
```

### 4. 非严格加载
```java
// 加载时使用 strict=false，允许部分字段缺失
module.loadStateDict(componentStateMap, false);

// 好处: 向后兼容，新版本添加字段不影响旧会话加载
```

---

## 📊 性能考虑

### 1. 文件IO优化
```java
// JsonSession 使用 Pretty Printer 便于调试
objectMapper.writerWithDefaultPrettyPrinter()
    .writeValue(sessionFile.toFile(), sessionState);

// 生产环境可以去掉Pretty Printer减少文件大小
objectMapper.writeValue(sessionFile.toFile(), sessionState);
```

### 2. 内存优化
```java
// 使用 LinkedHashMap 保持插入顺序，避免额外排序
Map<String, Object> state = new LinkedHashMap<>();

// InMemoryMemory 使用 CopyOnWriteArrayList
private final List<Msg> messages = new CopyOnWriteArrayList<>();
```

### 3. Redis优化
```java
// 使用连接池复用连接
JedisPool jedisPool = new JedisPool(config, "localhost", 6379);

// 批量操作
Pipeline pipeline = jedis.pipelined();
pipeline.set(sessionKey, json);
pipeline.set(metaKey, metaJson);
pipeline.sync();
```

---

## 🚀 最佳实践

### 1. 定期保存
```java
// 每次对话后保存
Msg response = agent.call(userMsg).block();
sessionManager.saveSession();
```

### 2. 异常处理
```java
try {
    sessionManager.saveSession();
} catch (Exception e) {
    log.error("Failed to save session: {}", sessionId, e);
    // 可以尝试备份存储或降级处理
}
```

### 3. 会话清理
```java
// 定期清理过期会话
List<String> sessionIds = jsonSession.listSessions();
for (String sid : sessionIds) {
    SessionInfo info = jsonSession.getSessionInfo(sid);
    if (info.getLastModified() < cutoffTime) {
        jsonSession.deleteSession(sid);
    }
}
```

### 4. 大型会话优化
```java
// 对于大型会话，考虑使用压缩
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)      // 消息数量阈值
    .tokenRatio(0.4)       // Token比例
    .lastKeep(10)          // 保留最后N条消息
    .build();

AutoContextMemory memory = new AutoContextMemory(config, model);
```

---

## 📝 总结

SessionManager 采用**三层架构**设计:

1. **SessionManager层**: 协调组件管理和会话操作
2. **Session层**: 抽象存储接口，支持多种存储后端
3. **StateModule层**: 组件状态管理，支持自定义序列化

**核心优势**:
- ✅ 分层解耦，职责清晰
- ✅ 灵活扩展，支持多种存储方式
- ✅ 自动状态管理，减少样板代码
- ✅ 类型安全，编译时检查
- ✅ 线程安全，支持并发访问
- ✅ 向后兼容，非严格加载模式

**保存的数据**:
- Agent状态(嵌套的Memory)
- Memory的所有消息历史
- AutoContextMemory的压缩状态和卸载内容
- 自定义组件的注册字段

**存储方式**:
- JsonSession: 文件系统 (开发/单机)
- RedisSession: Redis (生产/分布式)
- MysqlSession: MySQL (持久化/查询)
- InMemorySession: 内存 (测试)
