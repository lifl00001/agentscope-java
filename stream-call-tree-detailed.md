# Model.stream() 完整调用链路 - 深入到 OkHttpTransport 层

## 📊 完整调用树 (从 Agent 到网络层)

### 层级1: ReActAgent 推理阶段

```
ReActAgent.ReasoningPipeline.prepareAndStream() [第404-419行]
├─ 准备消息列表
│   └─ messagePreparer.prepareMessageList(handler)
│       ├─ addSystemPromptIfNeeded(messages)  [添加系统提示]
│       └─ messages.addAll(memory.getMessages())  [添加历史上下文]
│
├─ 准备生成选项
│   └─ buildGenerateOptions()
│
├─ 获取工具Schema
│   └─ toolkit.getToolSchemas()
│
└─ 调用模型流式接口
    └─ model.stream(modifiedMsgs, toolSchemas, options)  [第417行]
        ↓
```

### 层级2: ChatModelBase 抽象层

```
ChatModelBase.stream(messages, tools, options) [第42-48行]
├─ 功能: 提供链路追踪包装
├─ 位置: io.agentscope.core.model.ChatModelBase
└─ 实现:
    └─ TracerRegistry.get().callModel(
           this, 
           messages, 
           tools, 
           options, 
           () -> doStream(messages, tools, options)  [调用子类实现]
       )
```

**关键设计**: 
- 使用模板方法模式，定义流程骨架
- `doStream()` 是抽象方法，由具体模型实现
- TracerRegistry 提供分布式追踪能力

---

### 层级3: DashScopeChatModel 实现层

#### 3.1 doStream 入口方法

```java
// DashScopeChatModel.java 第159-171行
@Override
protected Flux<ChatResponse> doStream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    
    log.debug("DashScope API call: model={}, multimodal={}", 
              modelName, requiresMultiModalApi());

    // 调用HTTP客户端执行流式请求
    Flux<ChatResponse> responseFlux = streamWithHttpClient(messages, tools, options);

    // 应用超时和重试配置
    return ModelUtils.applyTimeoutAndRetry(
            responseFlux, options, defaultOptions, modelName, "dashscope", log);
}
```

#### 3.2 streamWithHttpClient 核心逻辑

```java
// DashScopeChatModel.java 第178-250行
private Flux<ChatResponse> streamWithHttpClient(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    
    Instant start = Instant.now();
    boolean useMultimodal = requiresMultiModalApi();  // 判断是否使用多模态API
    
    // 步骤1: 获取有效的生成选项
    GenerateOptions effectiveOptions = options != null ? options : defaultOptions;
    ToolChoice toolChoice = effectiveOptions.getToolChoice();
    
    // 步骤2: 格式化消息 (转换为DashScope格式)
    List<DashScopeMessage> dashScopeMessages;
    if (useMultimodal) {
        // 多模态模式 (视觉模型)
        dashScopeMessages = formatter.formatMultiModal(messages);
    } else {
        // 文本模式
        dashScopeMessages = formatter.format(messages);
    }
    
    // 步骤3: 构建DashScope请求对象
    DashScopeRequest request = formatter.buildRequest(
            modelName,
            dashScopeMessages,
            stream,  // 流式模式标志
            options,
            defaultOptions,
            tools,
            toolChoice
    );
    
    // 步骤4: 应用思考模式配置
    applyThinkingMode(request, effectiveOptions);
    
    // 步骤5: 执行HTTP流式调用
    if (stream) {
        // 流式模式
        return httpClient.stream(request)  // ← 调用DashScopeHttpClient
                .map(response -> formatter.parseResponse(response, start));
    } else {
        // 非流式模式 (同步调用)
        return Flux.defer(() -> {
            DashScopeResponse response = httpClient.call(request);
            ChatResponse chatResponse = formatter.parseResponse(response, start);
            return Flux.just(chatResponse);
        });
    }
}
```

**流程图**:
```
Messages (通用格式)
    ↓
Formatter.format()
    ↓
DashScopeMessages (DashScope格式)
    ↓
Formatter.buildRequest()
    ↓
DashScopeRequest (包含model, messages, tools, parameters)
    ↓
httpClient.stream(request)
    ↓
Flux<DashScopeResponse> (原始API响应流)
    ↓
formatter.parseResponse()
    ↓
Flux<ChatResponse> (标准化响应流)
```

---

### 层级4: DashScopeHttpClient HTTP客户端层

#### 4.1 stream 方法

```java
// DashScopeHttpClient.java 第213-256行
public Flux<DashScopeResponse> stream(
        DashScopeRequest request,
        Map<String, String> additionalHeaders,
        Map<String, Object> additionalBodyParams,
        Map<String, String> additionalQueryParams) {
    
    // 步骤1: 选择API端点
    String endpoint = selectEndpoint(request.getModel());
    // 返回值: "/api/v1/services/aigc/text-generation/generation" 
    //    或: "/api/v1/services/aigc/multimodal-generation/generation"
    
    // 步骤2: 构建完整URL
    String url = buildUrl(endpoint, additionalQueryParams);
    // 例如: "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
    
    try {
        // 步骤3: 确保启用增量输出
        if (request.getParameters() != null) {
            request.getParameters().setIncrementalOutput(true);  // SSE流式关键配置
        }
        
        // 步骤4: 序列化请求体
        String requestBody = buildRequestBody(request, additionalBodyParams);
        // 将DashScopeRequest对象序列化为JSON字符串
        
        log.debug("DashScope streaming request to {}: {}", url, requestBody);
        
        // 步骤5: 构建HttpRequest对象
        HttpRequest httpRequest = HttpRequest.builder()
                .url(url)
                .method("POST")
                .headers(buildHeaders(true, additionalHeaders))  // 流式请求头
                .body(requestBody)
                .build();
        
        // 步骤6: 调用底层传输层执行流式请求
        return transport.stream(httpRequest)  // ← 调用OkHttpTransport.stream()
                .map(data -> {
                    // 步骤7: 解析每个SSE数据块
                    try {
                        return objectMapper.readValue(data, DashScopeResponse.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse SSE data: {}. Error: {}", data, e.getMessage());
                        return null;
                    }
                })
                .filter(response -> response != null);  // 过滤解析失败的响应
                
    } catch (JsonProcessingException e) {
        return Flux.error(new DashScopeHttpException("Failed to serialize request", e));
    }
}
```

#### 4.2 buildHeaders 构建流式请求头

```java
// DashScopeHttpClient.java 第293-310行
private Map<String, String> buildHeaders(
        boolean streaming, Map<String, String> additionalHeaders) {
    
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer " + apiKey);  // 认证
    headers.put("Content-Type", "application/json");   // JSON格式
    headers.put("User-Agent", Version.getUserAgent()); // 用户代理
    
    if (streaming) {
        headers.put("X-DashScope-SSE", "enable");  // 启用SSE流式传输
    }
    
    // 合并额外的请求头
    if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
        headers.putAll(additionalHeaders);
    }
    
    return headers;
}
```

**关键请求头**:
- `Authorization: Bearer <api_key>` - API密钥认证
- `Content-Type: application/json` - JSON请求体
- `X-DashScope-SSE: enable` - 启用Server-Sent Events流式传输
- `User-Agent: agentscope-java/x.x.x` - 客户端标识

#### 4.3 selectEndpoint 端点路由

```java
// DashScopeHttpClient.java 第271-281行
public String selectEndpoint(String modelName) {
    if (modelName == null) {
        return TEXT_GENERATION_ENDPOINT;
    }
    // 多模态模型路由规则
    if (modelName.startsWith("qvq") || modelName.contains("-vl")) {
        log.debug("Using multimodal API for model: {}", modelName);
        return MULTIMODAL_GENERATION_ENDPOINT;
    }
    log.debug("Using text generation API for model: {}", modelName);
    return TEXT_GENERATION_ENDPOINT;
}
```

**端点常量**:
```java
// 文本生成API
TEXT_GENERATION_ENDPOINT = "/api/v1/services/aigc/text-generation/generation"

// 多模态生成API
MULTIMODAL_GENERATION_ENDPOINT = "/api/v1/services/aigc/multimodal-generation/generation"
```

---

### 层级5: OkHttpTransport 网络传输层

#### 5.1 stream 方法 - SSE流式处理

```java
// OkHttpTransport.java 第114-192行
@Override
public Flux<String> stream(HttpRequest request) {
    Request okHttpRequest = buildOkHttpRequest(request);  // 构建OkHttp请求

    return Flux.<String>create(sink -> {
        Response response = null;
        BufferedReader reader = null;
        
        try {
            // 步骤1: 执行HTTP请求 (阻塞调用)
            response = client.newCall(okHttpRequest).execute();
            
            // 步骤2: 检查响应状态
            if (!response.isSuccessful()) {
                String errorBody = getResponseBodyString(response);
                sink.error(new HttpTransportException(
                    "HTTP request failed with status " + response.code(),
                    response.code(),
                    errorBody));
                return;
            }
            
            // 步骤3: 获取响应体
            ResponseBody body = response.body();
            if (body == null) {
                sink.complete();
                return;
            }
            
            // 步骤4: 创建字符流读取器
            reader = new BufferedReader(
                new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
            
            // 步骤5: 逐行读取SSE流
            String line;
            while ((line = reader.readLine()) != null) {
                // 检查是否被取消
                if (sink.isCancelled()) {
                    break;
                }
                
                // 跳过空行
                if (line.isEmpty()) {
                    continue;
                }
                
                // 步骤6: 解析SSE数据行
                if (line.startsWith(SSE_DATA_PREFIX)) {  // "data:"
                    String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                    
                    // 检查流结束标记
                    if (SSE_DONE_MARKER.equals(data)) {  // "[DONE]"
                        log.debug("Received SSE [DONE] marker");
                        break;
                    }
                    
                    // 步骤7: 发射数据到订阅者
                    if (!data.isEmpty()) {
                        sink.next(data);  // 发送JSON字符串
                    }
                }
                // 跳过其他SSE字段 (event:, id:, retry:, 注释)
            }
            
            // 步骤8: 完成流
            sink.complete();
            
        } catch (IOException e) {
            if (!sink.isCancelled()) {
                sink.error(new HttpTransportException(
                    "SSE stream read failed: " + e.getMessage(), e));
            }
        } finally {
            // 步骤9: 清理资源
            closeQuietly(reader);
            if (response != null) {
                closeQuietly(response.body());
            }
            closeQuietly(response);
        }
    })
    .publishOn(Schedulers.boundedElastic());  // 在弹性调度器上发布
}
```

**SSE (Server-Sent Events) 格式示例**:
```
data: {"output":{"choices":[{"message":{"content":"你"}}]},"usage":{"input_tokens":10}}

data: {"output":{"choices":[{"message":{"content":"好"}}]},"usage":{"input_tokens":10}}

data: [DONE]
```

#### 5.2 buildOkHttpRequest - 构建OkHttp请求

```java
// OkHttpTransport.java 第220-261行
private Request buildOkHttpRequest(HttpRequest request) {
    Request.Builder builder = new Request.Builder().url(request.getUrl());
    
    // 步骤1: 添加请求头
    for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
        builder.addHeader(header.getKey(), header.getValue());
    }
    
    // 步骤2: 设置HTTP方法和请求体
    String method = request.getMethod().toUpperCase();
    String body = request.getBody();
    
    switch (method) {
        case "GET":
            builder.get();
            break;
        case "POST":
            builder.post(
                body != null 
                    ? RequestBody.create(body, JSON_MEDIA_TYPE)
                    : RequestBody.create("", JSON_MEDIA_TYPE)
            );
            break;
        case "PUT":
            builder.put(
                body != null 
                    ? RequestBody.create(body, JSON_MEDIA_TYPE)
                    : RequestBody.create("", JSON_MEDIA_TYPE)
            );
            break;
        case "DELETE":
            if (body != null) {
                builder.delete(RequestBody.create(body, JSON_MEDIA_TYPE));
            } else {
                builder.delete();
            }
            break;
        default:
            builder.method(method, 
                body != null ? RequestBody.create(body, JSON_MEDIA_TYPE) : null);
    }
    
    return builder.build();
}
```

**常量定义**:
```java
private static final MediaType JSON_MEDIA_TYPE = 
    MediaType.parse("application/json; charset=utf-8");
private static final String SSE_DATA_PREFIX = "data:";
private static final String SSE_DONE_MARKER = "[DONE]";
```

#### 5.3 OkHttpClient 配置

```java
// OkHttpTransport.java 第89-100行
private OkHttpClient buildClient(HttpTransportConfig config) {
    return new OkHttpClient.Builder()
        .connectTimeout(config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(config.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .connectionPool(new ConnectionPool(
            config.getMaxIdleConnections(),
            config.getKeepAliveDuration().toMillis(),
            TimeUnit.MILLISECONDS
        ))
        .build();
}
```

**默认配置** (HttpTransportConfig):
- `connectTimeout`: 30秒
- `readTimeout`: 60秒 (流式请求需要较长超时)
- `writeTimeout`: 30秒
- `maxIdleConnections`: 5
- `keepAliveDuration`: 5分钟

---

## 🔄 完整数据流转示意图

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: ReActAgent                                             │
│                                                                  │
│  用户消息: "帮我写文件"                                           │
│       ↓                                                          │
│  memory.getMessages() → [System, History..., User消息]           │
│       ↓                                                          │
│  model.stream(messages, tools, options)                         │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 2: ChatModelBase                                          │
│                                                                  │
│  TracerRegistry.callModel(() -> doStream(...))                  │
│    - 添加分布式追踪                                               │
│    - 调用子类实现                                                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 3: DashScopeChatModel                                     │
│                                                                  │
│  1. formatter.format(messages)                                  │
│     → List<DashScopeMessage>                                    │
│                                                                  │
│  2. formatter.buildRequest(model, messages, tools, options)     │
│     → DashScopeRequest {                                        │
│          model: "qwen-max",                                     │
│          input: { messages: [...] },                            │
│          parameters: {                                          │
│            result_format: "message",                            │
│            incremental_output: true,  ← 流式关键                 │
│            tools: [...]                                         │
│          }                                                       │
│       }                                                          │
│                                                                  │
│  3. httpClient.stream(request)                                  │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 4: DashScopeHttpClient                                    │
│                                                                  │
│  1. selectEndpoint("qwen-max")                                  │
│     → "/api/v1/services/aigc/text-generation/generation"       │
│                                                                  │
│  2. buildUrl(endpoint, queryParams)                             │
│     → "https://dashscope.aliyuncs.com/api/v1/services/aigc/    │
│        text-generation/generation"                              │
│                                                                  │
│  3. buildHeaders(streaming=true)                                │
│     → {                                                          │
│          "Authorization": "Bearer sk-xxx",                      │
│          "Content-Type": "application/json",                    │
│          "X-DashScope-SSE": "enable"  ← 启用SSE                  │
│       }                                                          │
│                                                                  │
│  4. objectMapper.writeValueAsString(request)                    │
│     → JSON请求体                                                 │
│                                                                  │
│  5. HttpRequest httpRequest = HttpRequest.builder()             │
│        .url(url)                                                │
│        .method("POST")                                          │
│        .headers(headers)                                        │
│        .body(requestBody)                                       │
│        .build();                                                │
│                                                                  │
│  6. transport.stream(httpRequest)                               │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 5: OkHttpTransport                                        │
│                                                                  │
│  1. buildOkHttpRequest(httpRequest)                             │
│     → okhttp3.Request {                                         │
│          url: "https://dashscope.aliyuncs.com/...",            │
│          method: "POST",                                        │
│          headers: [...],                                        │
│          body: RequestBody(JSON)                                │
│       }                                                          │
│                                                                  │
│  2. client.newCall(okHttpRequest).execute()                     │
│     ↓ 建立HTTP连接                                               │
│     ↓ 发送请求                                                   │
│     ↓ 接收响应流                                                 │
│                                                                  │
│  3. BufferedReader reader = new BufferedReader(                 │
│        new InputStreamReader(response.body().byteStream())      │
│     )                                                            │
│                                                                  │
│  4. while ((line = reader.readLine()) != null) {                │
│        if (line.startsWith("data:")) {                          │
│          String data = line.substring(5).trim();                │
│          if (!data.equals("[DONE]")) {                          │
│            sink.next(data);  ← 发射JSON字符串                    │
│          }                                                       │
│        }                                                         │
│     }                                                            │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 网络层: DashScope API服务器                                      │
│                                                                  │
│  接收POST请求:                                                    │
│    URL: https://dashscope.aliyuncs.com/api/v1/services/aigc/   │
│         text-generation/generation                              │
│    Headers: {                                                   │
│      "Authorization": "Bearer sk-xxx",                          │
│      "X-DashScope-SSE": "enable"                                │
│    }                                                             │
│    Body: {                                                       │
│      "model": "qwen-max",                                       │
│      "input": { "messages": [...] },                            │
│      "parameters": { "incremental_output": true, ... }          │
│    }                                                             │
│                                                                  │
│  返回SSE流:                                                       │
│    data: {"output":{"choices":[{"message":{"content":"我"}}]}}  │
│    data: {"output":{"choices":[{"message":{"content":"来"}}]}}  │
│    data: {"output":{"choices":[{"message":{"content":"帮"}}]}}  │
│    ...                                                           │
│    data: [DONE]                                                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
                     (响应向上传递)
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 响应处理流程 (从底向上)                                           │
│                                                                  │
│ OkHttpTransport:                                                │
│   sink.next("{"output":{...}}")  → Flux<String>                │
│                                                                  │
│ DashScopeHttpClient:                                            │
│   objectMapper.readValue(data, DashScopeResponse.class)        │
│   → Flux<DashScopeResponse>                                     │
│                                                                  │
│ DashScopeChatModel:                                             │
│   formatter.parseResponse(response, startTime)                  │
│   → Flux<ChatResponse>                                          │
│                                                                  │
│ ReActAgent.ReasoningPipeline:                                   │
│   .concatMap(chunk -> {                                         │
│     context.processChunk(chunk);  // 累积内容                    │
│     hookNotifier.notifyStreamingMsg(msg, context);              │
│   })                                                             │
│                                                                  │
│ 最终输出到用户:                                                   │
│   "我来帮你创建文件..."                                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎯 关键组件职责总结

| 层级 | 组件 | 职责 | 关键方法 |
|-----|------|-----|---------|
| **1** | `ReActAgent.ReasoningPipeline` | 编排推理流程 | `prepareAndStream()` |
| **2** | `ChatModelBase` | 提供追踪包装 | `stream()` |
| **3** | `DashScopeChatModel` | 格式化请求/响应 | `doStream()`, `streamWithHttpClient()` |
| **4** | `DashScopeHttpClient` | HTTP通信协调 | `stream()`, `buildHeaders()` |
| **5** | `OkHttpTransport` | 底层网络传输 | `stream()`, `buildOkHttpRequest()` |

---

## 🔧 技术要点

### 1. SSE (Server-Sent Events) 处理

**SSE格式**:
```
data: <JSON payload>
data: <JSON payload>
data: [DONE]
```

**解析流程**:
```java
if (line.startsWith("data:")) {
    String data = line.substring(5).trim();
    if (!data.equals("[DONE]")) {
        sink.next(data);  // 发射数据
    } else {
        break;  // 结束流
    }
}
```

### 2. Reactive Streams 背压处理

```java
Flux.<String>create(sink -> {
    // 生产者
    while ((line = reader.readLine()) != null) {
        if (sink.isCancelled()) {  // 检查取消信号
            break;
        }
        sink.next(data);  // 按需发射
    }
    sink.complete();
})
.publishOn(Schedulers.boundedElastic())  // 异步处理
```

**特点**:
- 自动背压控制
- 支持取消操作
- 资源自动清理

### 3. 连接池管理

```java
ConnectionPool connectionPool = new ConnectionPool(
    maxIdleConnections: 5,      // 最大空闲连接数
    keepAliveDuration: 5分钟    // 保活时间
)
```

**优势**:
- 连接复用，减少握手开销
- 自动清理过期连接
- 提高并发性能

### 4. 超时配置

```java
OkHttpClient.Builder()
    .connectTimeout(30秒)   // 连接超时
    .readTimeout(60秒)      // 读取超时 (流式需要更长)
    .writeTimeout(30秒)     // 写入超时
```

### 5. 错误处理层次

```
┌──────────────────────────────────────┐
│ ModelUtils.applyTimeoutAndRetry      │ ← 超时和重试
├──────────────────────────────────────┤
│ DashScopeHttpClient                  │ ← HTTP错误
├──────────────────────────────────────┤
│ OkHttpTransport                      │ ← 网络错误
├──────────────────────────────────────┤
│ OkHttp.execute()                     │ ← IO异常
└──────────────────────────────────────┘
```

---

## 📝 实际HTTP请求示例

### 请求 (Request)

```http
POST /api/v1/services/aigc/text-generation/generation HTTP/1.1
Host: dashscope.aliyuncs.com
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
X-DashScope-SSE: enable
User-Agent: agentscope-java/1.0.0

{
  "model": "qwen-max",
  "input": {
    "messages": [
      {
        "role": "system",
        "content": "You are a helpful AI assistant."
      },
      {
        "role": "user",
        "content": "帮我创建一个hello.txt文件,内容是Hello World"
      }
    ]
  },
  "parameters": {
    "result_format": "message",
    "incremental_output": true,
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "WriteFileTool",
          "description": "Write content to a file",
          "parameters": {
            "type": "object",
            "properties": {
              "filePath": { "type": "string" },
              "content": { "type": "string" }
            },
            "required": ["filePath", "content"]
          }
        }
      }
    ]
  }
}
```

### 响应 (Response - SSE流)

```
data: {"output":{"choices":[{"finish_reason":"null","message":{"role":"assistant","content":"我"}}]},"usage":{"input_tokens":123,"output_tokens":1}}

data: {"output":{"choices":[{"finish_reason":"null","message":{"role":"assistant","content":"来"}}]},"usage":{"input_tokens":123,"output_tokens":2}}

data: {"output":{"choices":[{"finish_reason":"null","message":{"role":"assistant","content":"帮"}}]},"usage":{"input_tokens":123,"output_tokens":3}}

data: {"output":{"choices":[{"finish_reason":"null","message":{"role":"assistant","content":"你"}}]},"usage":{"input_tokens":123,"output_tokens":4}}

data: {"output":{"choices":[{"finish_reason":"null","message":{"role":"assistant","content":"创建"}}]},"usage":{"input_tokens":123,"output_tokens":5}}

data: {"output":{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"WriteFileTool","arguments":"{\"filePath\":\"hello.txt\",\"content\":\"Hello World\"}"},"id":"call_abc123","type":"function"}]}}]},"usage":{"input_tokens":123,"output_tokens":25}}

data: [DONE]
```

---

## 🚀 性能优化点

### 1. 连接复用
- 使用ConnectionPool复用TCP连接
- 减少TLS握手开销

### 2. 流式处理
- 数据到达即处理，无需等待完整响应
- 降低内存占用
- 提升用户体验

### 3. 异步IO
- 使用Reactor的弹性调度器
- 非阻塞IO操作
- 提高并发能力

### 4. 智能超时
- 连接超时: 30秒
- 读取超时: 60秒 (适配流式场景)
- 支持自定义配置

### 5. 自动重试
```java
ModelUtils.applyTimeoutAndRetry(
    responseFlux, 
    options, 
    defaultOptions, 
    modelName, 
    "dashscope", 
    log
)
```

---

## 🔍 调试技巧

### 1. 启用详细日志

```java
// logback.xml
<logger name="io.agentscope.core.model" level="DEBUG"/>
<logger name="okhttp3" level="DEBUG"/>
```

### 2. 查看请求详情

```java
log.debug("DashScope streaming request to {}: {}", url, requestBody);
```

输出:
```
DashScope streaming request to https://dashscope.aliyuncs.com/...: 
{"model":"qwen-max","input":{"messages":[...]},...}
```

### 3. 监控SSE流

```java
return transport.stream(httpRequest)
    .doOnNext(data -> log.debug("Received SSE data: {}", data))
    .map(...)
```

### 4. 追踪调用链

使用TracerRegistry查看完整调用链:
```
[Trace] AgentBase.call() 
  → ReActAgent.doCall()
    → ReasoningPipeline.prepareAndStream()
      → ChatModelBase.stream()
        → DashScopeChatModel.doStream()
          → DashScopeHttpClient.stream()
            → OkHttpTransport.stream()
```

---

## 📚 总结

这个调用链路展示了一个完整的**分层架构设计**:

1. **Agent层**: 业务逻辑编排
2. **Model抽象层**: 统一接口和追踪
3. **Model实现层**: 特定厂商协议适配
4. **HTTP客户端层**: 请求构建和响应解析
5. **传输层**: 底层网络通信

**核心优势**:
- ✅ 职责清晰，易于维护
- ✅ 支持流式和非流式两种模式
- ✅ 完善的错误处理和重试机制
- ✅ 高性能的连接池和异步IO
- ✅ 灵活的配置和扩展能力

**流式处理关键**:
- SSE协议解析
- Reactive Streams背压控制
- 资源自动管理
- 增量内容累积
