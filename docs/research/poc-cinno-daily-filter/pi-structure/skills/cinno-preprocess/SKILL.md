---
name: cinno-preprocess
description: CINNO 半导体日报源数据预处理 skill。将多来源 JSON 和历史日报 docx 标准化为稳定 article_id 的 normalized_articles.jsonl、normalized_history.jsonl 和 source_manifest.json
trigger:
  - 半导体预处理
  - 标准化半导体源数据
  - 解析json
  - 解析word
  - normalized_articles
---

# CINNO 半导体日报源数据预处理

## 用途

将原始多来源数据（不同 schema 的 JSON、Word 文档等）标准化为统一的、带稳定 `article_id` 的 JSONL，供下游 `cinno-cluster-dedup` 与 `cinno-daily-filter` 使用。

## 输入

- `data/raw/*.json`：当日多来源抓取结果（schema 各异）
- `data/raw/history/*.docx`：历史日报 Word 文档

## 输出

- `data/normalized_articles.jsonl`：标准化后的当日文章流（每行一条 JSON）
- `data/normalized_history.jsonl`：标准化后的历史日报条目流
- `data/source_manifest.json`：源数据清单（来源、抓取时间、原始记录数、去重前后数量）

## 步骤

1. **扫描输入目录**：用 `ls` 和 `read` 工具列出 `data/raw/` 下所有待处理文件。
2. **识别 schema**：对每个 JSON 文件，读取首条记录判断来源 schema（如 `cls / sina / eet-china / supply-chain / memoryinfo` 等）。
3. **字段映射**：把不同 schema 映射到统一 schema：
   ```json
   {
     "article_id": "<source>:<original_id>",       // 稳定 ID
     "title": "<string>",
     "content": "<string>",
     "publish_time": "<ISO8601>",
     "source": "<provider>",
     "source_url": "<url>",
     "category_hint": "<optional>"
   }
   ```
4. **解析 Word**：对 `.docx` 文件用 `bash` 调 `python-docx` 或 `pandoc` 提取段落，按"标题/正文"切分。
5. **生成稳定 article_id**：`source + ":" + sha1(title+publish_time)[:8]`，保证同一文章跨天 ID 一致。
6. **写 JSONL**：用 `write` 工具把每条记录一行一个 JSON 写入对应 `.jsonl` 文件。
7. **生成 manifest**：统计每来源原始记录数、去重前后数量，写入 `source_manifest.json`。

## 错误处理

- 字段缺失：`publish_time` 缺失时用抓取时间代替并打 `publish_time_estimated: true` 标记。
- 编码异常：统一转 UTF-8，无法解码的字符替换为占位符并记录到 manifest 的 `encoding_warnings`。
- 单条解析失败：跳过，写入 manifest 的 `failed_records` 数组。

## 调用方式

```
/skill:cinno-preprocess
```

或自然语言触发："标准化半导体源数据"。
