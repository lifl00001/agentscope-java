---
name: cinno-cluster-dedup
description: CINNO 半导体日报聚类去重 skill。对 normalized_articles.jsonl 做语义聚类，输出簇代表 + 簇成员映射，供 cinno-daily-filter 主筛选使用
trigger:
  - 半导体聚类去重
  - 半导体去重
  - cluster dedup
  - 聚类
---

# CINNO 半导体日报聚类去重

## 用途

同一新闻往往被多个来源转载。本 skill 把语义相似的文章聚成簇，每簇选一个代表，供下游主筛选 Agent 减少"同题材重复"。

## 输入

- `data/normalized_articles.jsonl`：来自 `cinno-preprocess` 的标准化文章流

## 输出

- `data/clusters.jsonl`：每行一个簇
  ```json
  {
    "cluster_id": "<string>",
    "representative_id": "<article_id>",
    "members": ["<article_id>", ...],
    "size": <int>,
    "cohesion_score": <float>
  }
  ```
- `data/dedup-stats.json`：去重统计（输入数、簇数、去重率、平均簇大小）

## 步骤

1. **读取配置**：从 `data/cinno-config.json` 读取 `dedup_threshold`（默认 0.85）。
2. **加载 embedding 模型**：用 `bash` 启动本地 embedding 服务（如 bge-small-zh / qwen-embedding），或调用 `data/cinno-config.json` 指定的远程 embedding endpoint。
3. **批量 embed**：对所有文章的 `title + "\n" + content[:500]` 计算向量。
4. **聚类**：用单连接层次聚类（或简单的 greedy 邻接合并），阈值取 `dedup_threshold`。
   - 阈值越高 → 簇越细 → 保留更多近义但不同的稿
   - 阈值越低 → 簇越粗 → 更激进合并
5. **选代表**：每簇选 `publish_time` 最早（首发）的成员作为 representative。
6. **写输出**：用 `write` 工具写入 `clusters.jsonl` 和 `dedup-stats.json`。

## 可调参数

| 参数 | 配置项 | 默认值 | 说明 |
|---|---|---|---|
| 合并阈值 | `dedup_threshold` | 0.85 | cosine similarity 阈值 |
| Embedding 模型 | `embedding.model` | `bge-small-zh` | 本地或远程模型 |
| Embedding endpoint | `embedding.endpoint` | `null` | 远程 endpoint，留空用本地 |
| 最大簇大小 | `max_cluster_size` | 20 | 超过则告警，可能阈值过低 |

## 错误处理

- Embedding 服务不可用：fallback 到 TF-IDF + Jaccard，记录到 stats 的 `fallback: "tfidf"`。
- 空文章：跳过，不计入聚类。
- 单元素簇：保留（不强行合并）。

## 调用方式

```
/skill:cinno-cluster-dedup
```

或被 `cinno-daily-filter` 在步骤 3 自动引用。
