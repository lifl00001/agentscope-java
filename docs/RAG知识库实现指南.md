# AgentScope RAG 知识库实现指南

## 一、整体架构

AgentScope 的 RAG 系统采用**插件化架构**，核心层定义统一接口，各扩展模块提供不同实现：

```
+-------------------------------------------------------------+
|                        CORE RAG LAYER                        |
|  Knowledge 接口  |  GenericRAGHook  |  KnowledgeRetrievalTools |
|  Document / DocumentMetadata / RetrieveConfig / RAGMode        |
+-------------------------------------------------------------+
|                   EXTENSION: rag-simple                       |
|  EmbeddingModel | Readers | TextChunker | SimpleKnowledge       |
|  VDBStoreBase (InMemory/PgVector/Milvus/Qdrant/Elasticsearch) |
+----------+----------+-----------+-------------+--------------+
| bailian  |   dify   | haystack  |   ragflow    |              |
| 阿里云百炼| Dify 平台 | HayStack  |  RAGFlow    |              |
| (云 API) | (云 API) | (云 API)  |  (云 API)   |              |
+----------+----------+-----------+-------------+--------------+
```

两种集成模式：

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| **Simple（本地）** | 完全控制文档导入、向量化和检索 | 需要自定义文档处理、私有化部署 |
| **云 API 集成** | 百炼/Dify/HayStack/RAGFlow | 文档管理交给云服务，只做检索 |

## 二、核心接口

### 2.1 Knowledge 接口

所有 RAG 后端都实现此接口：

```java
public interface Knowledge {
    // 导入文档（本地模式使用，云 API 模式抛 UnsupportedOperationException）
    Mono<Void> addDocuments(List<Document> documents);

    // 检索相关文档
    Mono<List<Document>> retrieve(String query, RetrieveConfig config);
}
```

### 2.2 RAGMode 枚举

| 模式 | 说明 | 实现方式 |
|------|------|---------|
| `GENERIC` | 每次推理前自动检索 | `GenericRAGHook` 拦截 `PreCallEvent` |
| `AGENTIC` | Agent 自主决定何时检索 | 注册 `retrieve_knowledge` 工具 |
| `NONE` | 不启用 RAG | - |

### 2.3 RetrieveConfig 检索配置

```java
RetrieveConfig config = RetrieveConfig.builder()
    .limit(5)                          // 返回的最大文档数（默认 5）
    .scoreThreshold(0.5)               // 最低相似度阈值（默认 0.5，范围 0.0-1.0）
    .vectorName("my_vector")           // 向量空间名称（可选，多向量场景）
    .conversationHistory(msgList)      // 对话历史（可选，用于多轮查询重写）
    .build();
```

### 2.4 Document 文档模型

```java
Document document = Document.builder()
    .id("uuid")                        // 确定性 UUID（基于 doc_id + chunk_id + content 的 MD5）
    .metadata(DocumentMetadata.builder()
        .content(TextBlock.builder().text("文档内容...").build())  // 文档内容块
        .docId("source_doc_001")       // 来源文档标识
        .chunkId(0)                    // 文档内的分块索引
        .payload(Map.of("filename", "report.pdf", "department", "finance"))
        .build())
    .embedding(new double[]{0.1, 0.2, ...})  // 向量嵌入
    .score(0.92)                       // 相似度分数
    .vectorName("default")             // 向量空间名称
    .build();
```

## 三、完整流程：从文档导入到检索

### 3.1 文档导入流程（本地模式）

```
原始文档 (文本/文件/PDF/图片)
    |
    v
Reader 读取器 (TextReader / PDFReader / TikaReader / ImageReader / ExternalApiReader)
    |-- 提取原始文本内容
    |-- TextChunker.chunkText() 文本分块
    |     |-- 按 chunkSize 切分
    |     |-- 按 splitStrategy 选择切分策略
    |     |-- 按 overlapSize 保留重叠
    |-- 生成 List<Document>，每个 Document 包含一个文本块
    v
SimpleKnowledge.addDocuments(documents)
    |-- 对每个 Document 调用 embeddingModel.embed(contentBlock)
    |-- 生成 double[] 向量嵌入
    |-- embeddingStore.add(docsWithEmbeddings) 批量存入向量库
    v
向量数据库 (InMemory / PgVector / Milvus / Qdrant / Elasticsearch)
```

### 3.2 检索流程

```
用户查询 "北京有什么好玩的？"
    |
    v
GenericRAGHook (GENERIC 模式) 或 KnowledgeRetrievalTools (AGENTIC 模式)
    |
    v
Knowledge.retrieve(query, config)
    |-- 将查询文本转为向量嵌入
    |-- 在向量库中搜索相似文档
    |-- 过滤：score >= scoreThreshold
    |-- 排序：按 score 降序
    |-- 截取：top K 条结果
    v
List<Document>（带相似度分数）
    |-- GENERIC 模式：包装为 <retrieved_knowledge> 标签，注入为 USER 消息
    |-- AGENTIC 模式：格式化为文本字符串，作为工具调用结果返回
    |
    v
LLM 结合检索到的知识生成回答
```

### 3.3 代码示例

```java
// 1. 创建嵌入模型
EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("text-embedding-v3")
    .build();

// 2. 创建向量存储
InMemoryStore store = new InMemoryStore();

// 3. 创建 Knowledge
Knowledge knowledge = new SimpleKnowledge(embeddingModel, store);

// 4. 读取并导入文档
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
List<Document> docs = reader.read(ReaderInput.ofFilePath("knowledge/report.txt")).block();
knowledge.addDocuments(docs).block();

// 5. 构建 Agent 时注册 RAG
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .knowledge(knowledge)        // 注册知识库
    .ragMode(RAGMode.GENERIC)    // 自动检索模式
    .build();

// 6. 检索时，GenericRAGHook 会自动在每次推理前检索相关知识并注入
Msg response = agent.call(userMsg).block();
```

## 四、文档分块

### 4.1 什么是文档分块？

知识库存储的确实是一段一段的文本（称为 **chunk/块**），而不是整篇文档。这是因为：

1. **向量嵌入有长度限制**：嵌入模型通常只支持 512-8192 个 token
2. **检索精度**：短文本的向量表示更精确，检索时匹配更准确
3. **灵活性**：可以只返回相关段落，而不是整篇文档

### 4.2 分块策略

AgentScope 提供四种分块策略：

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| `CHARACTER` | 固定大小的滑动窗口 | 通用场景 |
| `PARAGRAPH`（默认） | 按段落边界（`\n\n`）切分 | 结构化文档（Markdown、报告） |
| `TOKEN` | 按近似 token 数切分（1 token ~ 4 字符） | 需要精确控制 token 数 |
| `SEMANTIC` | 语义边界切分（当前回退到 PARAGRAPH） | 未来扩展 |

### 4.3 重叠机制

每个策略都支持 `overlapSize` 参数。新分块开始时，会从上一个分块末尾复制 `overlapSize` 个字符作为前缀：

```
原文: "ABCDEFGHIJKLMN"（chunkSize=5, overlapSize=2）

分块结果:
  chunk 0: [ABCDE]
  chunk 1: [DEFGH]     ← 前 2 个字符 "DE" 与 chunk 0 重叠
  chunk 2: [GHIJK]     ← 前 2 个字符 "GH" 与 chunk 1 重叠
  chunk 3: [IJKLMN]    ← 前 2 个字符 "IJ" 与 chunk 2 重叠
```

**重叠的目的**：避免重要信息恰好被切在分块边界处导致检索遗漏。

### 4.4 配置

```java
// 默认配置：chunkSize=512, PARAGRAPH, overlapSize=50
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);

// PDF 读取器同样支持分块配置
PDFReader pdfReader = new PDFReader(1024, SplitStrategy.PARAGRAPH, 100);
```

## 五、检索详解

### 5.1 向量检索（语义检索）

**原理**：将查询文本和文档块都转换为向量（高维数值数组），通过计算向量之间的相似度来找到最相关的文档。

```
查询: "北京的天气怎么样？"
  ↓ embeddingModel.embed()
查询向量: [0.12, 0.45, 0.78, ...]

文档块 1: "北京今天晴，25度"     → 向量: [0.10, 0.43, 0.80, ...]  → 余弦相似度: 0.95
文档块 2: "上海明天有雨"         → 向量: [0.80, 0.12, 0.30, ...]  → 余弦相似度: 0.21
文档块 3: "北京的历史文化"       → 向量: [0.15, 0.50, 0.70, ...]  → 余弦相似度: 0.72

结果: 文档块 1 (0.95) > 文档块 3 (0.72) > 文档块 2 (0.21)
```

**特点**：理解语义，即使查询词和文档词不完全匹配也能找到相关内容。例如查询"怎么减肥"能匹配到"瘦身方法"相关的文档。

### 5.2 当查询匹配后，这段内容都会被查询出来吗？

**是的。当查询匹配到某个文档块时，返回的是该块的完整内容**（`DocumentMetadata.content` 中的文本），而不是只返回匹配的几个关键词。

具体流程：

```
1. 用户查询 "北京的天气"
2. 系统将查询转为向量，在向量库中搜索
3. 找到最相似的 5 个文档块（假设 limit=5）
4. 每个文档块返回完整内容，例如:
   - 块 1: "北京今天天气晴朗，气温25度，适合外出活动。明天预计多云转阴，气温略有下降。" (score=0.95)
   - 块 2: "北京四季分明，春季多风沙，夏季炎热多雨，秋季凉爽宜人，冬季寒冷干燥。" (score=0.72)
   - ...
5. GenericRAGHook 将这些完整内容包装为 <retrieved_knowledge> 注入给 LLM
```

**所以知识库检索返回的是"一段一段的完整文本块"，而不是只返回关键词或片段。**

### 5.3 关键词检索（稀疏检索）

**原理**：基于 BM25/TF-IDF 算法，通过关键词匹配来检索文档。

```
查询: "北京的天气"
  ↓ 分词
关键词: ["北京", "天气"]

文档块 1: "北京今天晴，25度"       → 匹配 "北京" → BM25 分数: 1.8
文档块 2: "天气预报显示明日有雨"   → 匹配 "天气" → BM25 分数: 1.2
文档块 3: "上海是个好城市"         → 无匹配    → BM25 分数: 0.0
```

**特点**：精确匹配关键词，适合专有名词、编号、代码等精确查询。缺点是不理解语义，查询"减肥"不会匹配到"瘦身"。

## 六、什么是混合检索

### 6.1 定义

**混合检索（Hybrid Retrieval） = 向量检索（语义） + 关键词检索（词汇）**，将两种检索的结果合并，取长补短。

### 6.2 为什么需要混合检索？

| 检索方式 | 擅长 | 不擅长 |
|---------|------|--------|
| 向量检索 | 语义理解（"减肥" → "瘦身"） | 精确匹配（产品编号 "XJ-2024-001"） |
| 关键词检索 | 精确匹配（专有名词、编号） | 语义理解（同义词、近义词） |

混合检索让两者互补：既不会遗漏精确匹配的结果，也不会错过语义相关但词汇不同的内容。

### 6.3 AgentScope 中的混合检索实现

混合检索在云 API 集成中支持，本地 `SimpleKnowledge` 目前只支持纯向量检索。

#### 百炼（Bailian）— 支持混合检索

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId("your_ak")
    .accessKeySecret("your_sk")
    .workspaceId("ws_001")
    .indexId("index_001")
    .denseSimilarityTopK(50)    // 向量检索返回 top 50（范围 0-100）
    .sparseSimilarityTopK(50)   // 关键词检索返回 top 50（范围 0-100）
    // 两者之和不超过 200
    .build();

Knowledge knowledge = new BailianKnowledge(config);
```

百炼服务端会同时执行两种检索并合并结果。

#### Dify — 支持混合检索

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey("dataset-xxxxxxxx")
    .datasetId("uuid")
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)  // 混合检索模式
    .weights(0.7)   // 语义权重 0.7，关键词权重 0.3
    .build();

Knowledge knowledge = new DifyKnowledge(config);
```

`weights` 参数控制两种检索的权重比例（0-1），1.0 表示完全语义检索，0.0 表示完全关键词检索。

#### RAGFlow — 支持混合检索

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("your_key")
    .datasetIds(List.of("ds_001"))
    .keyword(true)                     // 启用关键词匹配
    .vectorSimilarityWeight(0.3)       // 向量相似度权重 0.3，词相似度权重 0.7
    .build();

Knowledge knowledge = new RAGFlowKnowledge(config);
```

#### HayStack — 支持混合检索

```java
HayStackConfig config = HayStackConfig.builder()
    .baseUrl("http://localhost:8000")
    .queryEmbedding(denseEmbedding)          // 稠密向量（语义检索）
    .querySparseEmbedding(sparseEmbedding)   // 稀疏向量（关键词检索）
    .build();

Knowledge knowledge = new HayStackKnowledge(config);
```

### 6.4 混合检索流程图

```
用户查询 "产品 XJ-2024-001 的退货政策"
        |
        +---+---+
        |       |
    向量检索  关键词检索
        |       |
   语义相关    精确匹配 "XJ-2024-001"
   的文档块    的文档块
        |       |
        +---+---+
            |
      合并 + 去重 + 加权排序
            |
            v
      最终检索结果
```

## 七、什么是重排序（详细解释）

### 7.1 先理解问题：初次检索为什么不够好？

用一个具体例子来说明。假设知识库中有以下 5 个文档块：

```
块 A: "Java 是一种面向对象的编程语言，由 Sun 公司于 1995 年发布。"
块 B: "Python 是一种解释型编程语言，广泛用于数据科学和机器学习。"
块 C: "Java 8 引入了 Lambda 表达式和 Stream API，极大简化了集合操作。"
块 D: "Java 咖啡产自印度尼西亚爪哇岛，是世界著名的咖啡品种之一。"
块 E: "Spring Boot 是基于 Java 框架 Spring 的快速开发工具，简化了项目配置。"
```

用户查询：**"Java 有什么新特性？"**

#### 初次检索（向量检索）的结果

向量检索会把查询和每个文档块都转成向量，然后算余弦相似度：

```
查询向量 "Java 有什么新特性？" 与各文档块的相似度:

  块 A: 0.88  ← "Java" 和 "编程语言" 语义相关
  块 B: 0.45  ← "编程语言" 有点相关
  块 C: 0.82  ← "Java" + "新特性"（Lambda、Stream）
  块 D: 0.80  ← "Java" 这个词完全匹配！
  块 E: 0.75  ← "Java" 相关
```

按分数排序（假设 limit=3）：

```
排名 1: 块 A (0.88) — Java 编程语言简介
排名 2: 块 C (0.82) — Java 8 新特性       ← 这才是用户真正想要的
排名 3: 块 D (0.80) — Java 咖啡           ← 完全不相关！
```

**问题暴露了**：
- 块 A（Java 简介）排在第一，但它只是泛泛介绍了 Java，没有回答"新特性"
- 块 D（Java 咖啡）竟然排到了第三！因为向量只看到了 "Java" 这个词
- 块 C（Java 8 新特性）才是最佳答案，却排在了第二

**原因**：向量检索是"粗筛"。它把查询和文档**分别独立**编码成向量，只看两个向量是否"方向相近"，无法真正理解查询和文档之间的**精确语义关系**。

### 7.2 重排序如何解决这个问题？

重排序模型（交叉编码器）的做法完全不同——它把查询和文档**拼在一起**，作为一个整体输入：

```
双编码器（初次检索）— 分别编码，独立理解:
  ┌─────────────┐          ┌──────────────────┐
  │ "Java 有什么  │  相似度?  │ "Java 是一种面向   │
  │  新特性？"    │ ←──────→ │ 对象的编程语言..." │
  └─────────────┘          └──────────────────┘
  查询和文档各看各的，只比较两个向量是否接近

交叉编码器（重排序）— 拼接编码，联合理解:
  ┌─────────────────────────────────────────────┐
  │ [CLS] Java 有什么新特性？ [SEP] Java 是一种  │
  │ 面向对象的编程语言，由 Sun 公司于 1995 年发布。│
  │ [SEP]                                        │
  └──────────────────┬──────────────────────────┘
                     ↓
              精确相关性分数: 0.15（低！因为这段话没提到"新特性"）
```

交叉编码器能"看到"查询和文档的**完整上下文**，因此能做出更精确的判断。

#### 重排序后的结果

```
重排序模型对 3 个候选逐一打分:

  输入: "Java 有什么新特性？" + 块 A（Java 编程语言简介）
  → 模型理解: "这段话介绍了 Java 的历史，但没提到新特性" → 分数: 0.15

  输入: "Java 有什么新特性？" + 块 C（Java 8 Lambda、Stream）
  → 模型理解: "Lambda 和 Stream 就是 Java 的新特性！" → 分数: 0.95

  输入: "Java 有什么新特性？" + 块 D（Java 咖啡）
  → 模型理解: "这讲的是咖啡，跟编程完全无关" → 分数: 0.02
```

重排序后：

```
排名 1: 块 C (0.95) — Java 8 新特性       ← 正确排到第一！
排名 2: 块 A (0.15) — Java 编程语言简介
排名 3: 块 D (0.02) — Java 咖啡           ← 被正确识别为不相关
```

### 7.3 用一个生活类比理解

**初次检索 = 人事初筛**：

> HR 收到 1000 份简历，每份只花 3 秒扫一眼关键词（"Java"、"5年经验"），挑出 50 份交给技术面试官。快但粗糙，可能有误判——有人简历里写了 "Java" 但其实只学过一个月。

**重排序 = 技术面试**：

> 技术面试官对 50 份候选简历逐一深入考察（问具体问题、看项目细节），最终选出 5 个最合适的人。慢但精确。

```
1000 份简历（知识库全量文档）
    ↓
人事初筛（向量检索，3秒/份）→ 挑出 50 份
    ↓
技术面试（重排序，30分钟/份）→ 选出 5 份
    ↓
发 offer（注入给 LLM 生成回答）
```

如果不做重排序，就等于跳过技术面试直接用初筛结果发 offer——可能招到不合适的人。

### 7.4 为什么不直接用交叉编码器做全量检索？

因为**太慢了**：

| | 双编码器（向量检索） | 交叉编码器（重排序） |
|---|---|---|
| 查询 vs 1000 个文档 | **快**：查询编码 1 次 + 1000 个向量比较 | **极慢**：查询+文档拼接编码 1000 次 |
| 向量能否预计算 | **能**：文档向量入库时就算好了 | **不能**：每次查询都要重新计算 |
| 单次耗时 | ~1 毫秒 | ~100 毫秒 |
| 1000 文档总耗时 | ~1 毫秒（向量比较极快） | ~100 秒（不可接受） |
| 50 文档总耗时 | ~1 毫秒 | ~5 秒（可以接受） |

所以实际做法是**两阶段检索**：

```
第一阶段（双编码器）: 从 1000 个文档中快速筛选出 50 个候选
第二阶段（交叉编码器）: 对 50 个候选精确排序，选出最终 5 个

总耗时: ~1 毫秒 + ~5 秒 = ~5 秒（可接受）
精度: 远高于只用向量检索
```

### 7.5 再一个例子：关键词歧义

查询：**"苹果的价格"**

知识库文档块：
```
块 1: "iPhone 16 Pro Max 售价 9999 元起，支持钛金属材质。"
块 2: "红富士苹果今年批发价每斤 3.5 元，受气候影响产量下降 15%。"
块 3: "苹果公司 2024 年第四季度营收创历史新高，达到 1240 亿美元。"
块 4: "MacBook Pro 搭载 M4 芯片，起售价 14999 元。"
```

向量检索结果（"苹果"这个词太模糊了）：
```
排名 1: 块 1 (0.91) — iPhone 价格
排名 2: 块 2 (0.88) — 水果苹果价格
排名 3: 块 3 (0.85) — 苹果公司营收
排名 4: 块 4 (0.82) — MacBook 价格
```

如果用户问的是水果苹果的价格，向量检索把 iPhone 排在了第一。

重排序后（模型能理解上下文）：
```
  "苹果的价格" + 块 1（iPhone）  → 0.60（可能是指苹果产品）
  "苹果的价格" + 块 2（水果苹果）→ 0.92（直接匹配水果价格）
  "苹果的价格" + 块 3（苹果公司）→ 0.55（公司营收不是"价格"）
  "苹果的价格" + 块 4（MacBook） → 0.30（不太相关）

重排序后: 块 2 (0.92) > 块 1 (0.60) > 块 3 (0.55) > 块 4 (0.30)
```

### 7.6 交叉编码器的技术原理

#### 交叉编码器用到了模型理解吗？

**是的，交叉编码器就是一个神经网络模型**（通常是 BERT 类模型），它对文本有真正的语义理解能力。理解的关键在于 Transformer 的**自注意力机制（Self-Attention）**——当查询和文档拼接在一起输入模型时，查询中的每个词都能和文档中的每个词互相"观察"，判断它们之间是否存在语义关联。

#### 双编码器 vs 交叉编码器的技术细节

**双编码器（向量检索用）**——查询和文档各自独立过模型：

```
查询: "Java 有什么新特性？"
        ↓
    [BERT 编码器]          ← 独立的模型调用
        ↓
  查询向量 [0.12, 0.45, 0.78, ...]    ← 一个 768 维的数字数组

文档: "Java 是一种面向对象的编程语言"
        ↓
    [同一个 BERT 编码器]     ← 又一次独立的模型调用
        ↓
  文档向量 [0.10, 0.43, 0.80, ...]    ← 另一个 768 维的数字数组

相似度 = cos(查询向量, 文档向量) = 0.88    ← 纯数学计算，不再过模型
```

**关键点**：查询和文档是各自独立过模型的。模型在编码文档时，根本不知道用户问的是什么。它只是把文档压缩成一个向量"摘要"。比较相似度时，只是两个数字数组的余弦运算，不再有任何"理解"参与。

**交叉编码器（重排序用）**——查询和文档拼接在一起过模型：

```
把查询和文档拼成一段话，一起喂给模型:

"[CLS] Java 有什么新特性？ [SEP] Java 是一种面向对象的编程语言，由 Sun 公司于 1995 年发布。 [SEP]"
        ↓
    [BERT 交叉编码器]       ← 一次模型调用，同时看到查询和文档
        ↓
  通过 Transformer 的自注意力机制:
    "新特性" ←attend to→ "Lambda"、"Stream"（如果文档里有）
    "新特性" ←attend to→ "1995年发布"（发现不相关，降低注意力）
        ↓
  [CLS] token 的输出 → 全连接层 → Sigmoid → 相关性分数: 0.15
```

#### 注意力机制是怎么工作的

Transformer 的核心是**自注意力（Self-Attention）**。当查询和文档拼接在一起输入模型时，模型内部的每一层都在计算词与词之间的关联强度：

```
输入: "[CLS] Java 有什么 新 特性？ [SEP] Java 8 引入了 Lambda 表达式 [SEP]"

注意力计算（简化）:
  "新" ←attend to→ "Lambda"   权重 0.3  （新东西 ↔ 新特性）
  "新" ←attend to→ "特性"     权重 0.2  （自身语义）
  "新" ←attend to→ "引入"     权重 0.15 （"引入"暗示新东西）
  "新" ←attend to→ "Java"     权重 0.05 （常见词，不太重要）
  "新" ←attend to→ "8"        权重 0.01  （数字，无关）
  ...
```

模型内部有几十层这样的注意力计算，每一层都能捕捉不同层次的语义关系：

| 层级 | 捕捉的内容 | 例子 |
|------|-----------|------|
| 浅层（1-4 层） | 词语级别的共现 | "新" 关注 "Lambda" |
| 中层（5-8 层） | 短语级别的关系 | "新特性" 关注 "Lambda 表达式" |
| 深层（9-12 层） | 句子级别的语义关联 | "有什么新特性" 关注 "引入了 Lambda" |

最终，`[CLS]` 这个特殊 token 汇聚了所有层的注意力信息，经过全连接层输出一个 0-1 之间的分数：

```
[CLS] 最终表示 → Linear(768, 1) → Sigmoid → 0.95
```

#### 一个极端例子说明差异

查询：**"怎么治疗感冒？"**

```
文档: "感冒是一种常见的呼吸道疾病，症状包括鼻塞、流涕、咽痛等。
       多由病毒感染引起，一般 5-7 天可自愈。"
```

**双编码器的处理**：

```
查询向量编码: 模型看到 "怎么治疗感冒？"
  → 提取语义: "治疗"、"感冒" → 向量 [0.3, 0.8, 0.2, ...]

文档向量编码: 模型看到 "感冒是一种常见疾病...5-7天可自愈"
  → 提取语义: "感冒"、"疾病"、"自愈" → 向量 [0.28, 0.75, 0.25, ...]

余弦相似度: 0.92（很高！因为两者都和"感冒"高度相关）
```

问题：文档讲的是感冒的"症状和自愈"，没有讲怎么"治疗"。但双编码器给出 0.92 的高分，因为它只看整体语义方向是否相近，**模型在编码文档时根本不知道用户问的是"治疗"**。

**交叉编码器的处理**：

```
模型同时看到:
  "[CLS] 怎么治疗感冒？ [SEP] 感冒是一种常见的呼吸道疾病，
   症状包括鼻塞、流涕、咽痛等。多由病毒感染引起，一般 5-7 天可自愈。 [SEP]"

注意力:
  "治疗" ←search→ 文档中所有词...
  → 找到 "鼻塞"、"流涕"（不是治疗方法）
  → 找到 "自愈"（意思是自己好，不需要治疗）
  → 找不到 "吃药"、"休息"、"就医" 等治疗相关内容

模型判断: 这段话描述了感冒的症状和病程，但没有提供治疗方案
  → 分数: 0.25（低分！）
```

#### 一句话总结两种编码器的本质区别

| | 双编码器 | 交叉编码器 |
|---|---|---|
| 模型看到什么 | 查询和文档**分别单独**看 | 查询和文档**拼在一起**看 |
| 是否理解两者关系 | **不理解**，只看各自"长什么样" | **理解**，看到词与词之间的关联 |
| 打分方式 | 两个向量的余弦相似度（数学计算） | 模型直接输出相关性概率（语义判断） |
| 类比 | 分别看两个人的照片，判断像不像 | 让两个人面对面聊天，判断搭不搭 |

交叉编码器之所以更准，就是因为它在模型的注意力层中，让查询的每个词都能"看到"文档的每个词，从而做出真正的语义判断，而不是仅仅比较两个抽象向量的距离。

### 7.7 AgentScope 中的重排序配置

#### 百炼（Bailian）

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId("your_ak")
    .accessKeySecret("your_sk")
    .workspaceId("ws_001")
    .indexId("index_001")
    .denseSimilarityTopK(50)
    .enableReranking(true)             // 启用重排序
    .rerankConfig(RerankConfig.builder()
        .rerankModelName("gte-rerank-hybrid")  // 重排序模型
        .rerankMinScore(0.3)            // 重排序最低分数
        .rerankTopN(5)                  // 重排序后返回 top 5
        .build())
    .build();
```

百炼的重排序在服务端完成，支持阿里云的 `gte-rerank-hybrid` 模型。

#### Dify

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey("dataset-xxxxxxxx")
    .datasetId("uuid")
    .enableRerank(true)                 // 启用重排序
    .rerankConfig(RerankConfig.builder()
        .topN(5)                        // 重排序后返回 top 5
        .build())
    .build();
```

Dify 的重排序在其平台内部完成。

#### RAGFlow

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("your_key")
    .datasetIds(List.of("ds_001"))
    .rerankId("rerank_model_001")       // 指定重排序模型 ID
    .build();
```

### 7.8 完整的检索 + 重排序流程

```
用户查询
    |
    v
[初次检索] 向量检索 + 关键词检索（混合）
    |
    v
50 个候选文档（粗排结果）
    |
    v
[重排序] 交叉编码器精确打分
    |
    v
过滤 score < rerankMinScore
    |
    v
取 top N 个结果（精排结果）
    |
    v
注入给 LLM
```

## 八、什么是查询重写

### 8.1 定义

**查询重写（Query Rewriting）** 是指在检索之前，用 LLM 将用户的原始查询改写为更适合检索的形式。这在**多轮对话**场景中尤其重要。

### 8.2 为什么需要查询重写？

在多轮对话中，用户经常会使用省略语或指代词：

```
用户: 北京有哪些好玩的景点？
助手: 故宫、颐和园、天坛、长城等。
用户: 第二个怎么去？        ← 这里的"第二个"指什么？
```

如果直接用"第二个怎么去？"去检索，向量检索很可能找不到相关文档。查询重写会将其改写为：

```
改写后: "怎么去颐和园？"    ← 明确了指代对象
```

### 8.3 AgentScope 中的查询重写实现

目前只有**百炼（Bailian）**内置了查询重写功能：

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId("your_ak")
    .accessKeySecret("your_sk")
    .workspaceId("ws_001")
    .indexId("index_001")
    .enableRewrite(true)                 // 启用查询重写
    .rewriteConfig(RewriteConfig.builder()
        .rewriteModelName("conv-rewrite-qwen-1.8b")  // 重写模型
        .build())
    .build();
```

**工作原理**：

```
原始查询: "第二个怎么去？"
    |
    v
RewriteConfig 指定的重写模型（conv-rewrite-qwen-1.8b）
    |
    + 对话历史:
    |   USER: "北京有哪些好玩的景点？"
    |   ASSISTANT: "故宫、颐和园、天坛、长城等。"
    |   USER: "第二个怎么去？"
    |
    v
重写后查询: "怎么去颐和园？"
    |
    v
用重写后的查询进行检索 → 结果更准确
```

对话历史通过 `RetrieveConfig.conversationHistory` 传入，框架会自动从 Agent 的 Memory 中提取。

### 8.4 自定义查询重写

对于不内置查询重写的 RAG 后端，可以通过 Agent 的工具能力实现自定义重写。项目中 `multiagent-patterns/workflow` 示例展示了使用独立的 ReActAgent 来重写查询：

```java
// 自定义查询重写节点
RewriteNode rewriteNode = new RewriteNode(rewriteAgent);

// 工作流: 用户查询 → 查询重写 → 知识检索 → 生成回答
Pipeline pipeline = Pipelines.sequential(rewriteNode, retrievalNode, generationNode);
```

## 九、向量数据库支持

### 9.1 本地向量存储

| 存储 | 距离度量 | 持久化 | 适用场景 |
|------|---------|--------|---------|
| `InMemoryStore` | 余弦相似度 | 内存（重启丢失） | 开发/测试 |
| `PgVectorStore` | COSINE / L2 / IP | PostgreSQL | 生产环境 |
| `MilvusStore` | COSINE / L2 / IP | Milvus v2 | 大规模数据 |
| `QdrantStore` | 余弦相似度 | Qdrant | 高性能场景 |
| `ElasticsearchStore` | 余弦相似度 | Elasticsearch | 已有 ES 基础设施 |

### 9.2 PgVector 示例

```java
PgVectorStore store = PgVectorStore.builder()
    .host("localhost")
    .port(5432)
    .database("agentscope")
    .username("postgres")
    .password("password")
    .dimension(1024)               // 向量维度，需与嵌入模型一致
    .distanceType(DistanceType.COSINE)
    .tableName("documents")        // 自动建表
    .schemaName("public")
    .build();
```

### 9.3 嵌入模型

| 模型 | 提供商 | 内容类型 | 默认维度 |
|------|--------|---------|---------|
| `DashScopeTextEmbedding` | 阿里云 DashScope | 文本 | 1024 |
| `DashScopeMultiModalEmbedding` | 阿里云 DashScope | 文本 + 图片 | 1024 |
| `OpenAITextEmbedding` | OpenAI | 文本 | 1536 |
| `OllamaTextEmbedding` | Ollama（本地） | 文本 | 自动 |

## 十、RAG 与 Agent 的集成方式

### 10.1 GENERIC 模式（自动检索）

框架在每次用户调用 Agent 时自动检索知识：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .knowledge(knowledge)
    .ragMode(RAGMode.GENERIC)  // 默认值
    .retrieveConfig(RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build())
    .build();
```

**注入的消息格式**：

```json
{
  "role": "user",
  "content": "<retrieved_knowledge>Use the following content from the knowledge base(s) if it is helpful:\n\n- Score: 0.920, Content: 故宫是北京最著名的景点...\n- Score: 0.850, Content: 颐和园位于北京西郊...\n</retrieved_knowledge>"
}
```

### 10.2 AGENTIC 模式（Agent 自主检索）

Agent 自主决定何时检索，注册为工具：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
// 框架自动注册 retrieve_knowledge 工具
```

LLM 可以在推理过程中自主决定调用 `retrieve_knowledge(query="...")` 工具。

## 十一、各 RAG 后端能力对比

| 特性 | SimpleKnowledge | Bailian | Dify | HayStack | RAGFlow |
|------|:---:|:---:|:---:|:---:|:---:|
| 本地文档导入 | 支持 | 不支持 | 不支持 | 不支持 | 不支持 |
| 向量检索 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 关键词检索 | 不支持 | 支持 | 支持 | 支持 | 支持 |
| **混合检索** | 不支持 | **支持** | **支持** | **支持** | **支持** |
| **重排序** | 不支持 | **支持** | **支持** | 不支持 | **支持** |
| **查询重写** | 不支持 | **支持** | 不支持 | 不支持 | 不支持 |
| 多向量空间 | 支持 | 不支持 | 不支持 | 支持 | 不支持 |
| 多模态嵌入 | 支持 | 不支持 | 不支持 | 不支持 | 不支持 |
| 对话历史感知 | 不支持 | **支持** | 不支持 | 不支持 | 不支持 |
| 元数据过滤 | 不支持 | 支持 | 支持 | 支持 | 支持 |
| 知识图谱 | 不支持 | 不支持 | 不支持 | 不支持 | 支持 |
| 跨语言检索 | 不支持 | 不支持 | 不支持 | 不支持 | 支持 |
