# RAG API 文档智能问答系统

基于检索增强生成（RAG）的技术文档问答平台。上传 API 文档后自动分块、向量化存入 FAISS，提问时混合检索相关文档块作为上下文，由 LLM 生成答案。

## 技术栈

| 层 | 技术 | 端口 |
|----|------|:---:|
| 前端 | Vue 3 + Element Plus + Vite + Axios | 5173 |
| 后端 | Spring Boot 3.4.5 + Java 17 + MyBatis 3.0.5 | 8080 |
| 向量服务 | Python Flask + FAISS + BM25 + Rerank | 5000 |
| 数据库 | MySQL (rag_qa_system) | 3306 |
| LLM | DeepSeek deepseek-v4-flash (OpenAI 兼容 API) | - |
| Embedding | 硅基流动 BAAI/bge-m3 (1024维) | - |
| Rerank | 硅基流动 BAAI/bge-reranker-v2-m3 | - |

## 项目结构

```
RAG/
├── backend/qa-system/          # Spring Boot 后端
│   └── src/main/java/com/rag/qa_system/
│       ├── controller/         # REST API 控制器
│       ├── service/            # 核心服务 (QaService RAG 主流程)
│       ├── model/              # 数据模型 + ApiResponse/PageResult
│       ├── mapper/             # MyBatis 数据访问
│       └── config/             # CORS、限流、链路追踪
├── frontend/                   # Vue 3 前端
│   └── src/
│       ├── views/              # ChatView (问答) / DocumentsView (文档管理)
│       ├── components/         # AppHeader
│       ├── composables/        # useDarkMode (暗色模式)
│       └── api.js              # Axios 封装
├── python-service/             # Python 向量微服务
│   ├── app.py                  # Flask API: /embed, /search, /index
│   └── data/                   # FAISS 索引持久化 (gitignored)
├── eval/                       # 评测体系
│   ├── evaluate.py             # 块级检索验证脚本
│   ├── test_questions.json     # 20道测试题 + 标准答案 + 目标片段
│   ├── generate_doc.py         # 测试文档生成器
│   └── experiments/            # 实验记录与报告
└── uploads/                    # 上传文档存储 (gitignored)
```

## 快速开始

### 环境要求

- Java 17+ & Maven 3.8+
- Python 3.10+
- Node.js 18+
- MySQL 8.0+

### 1. 配置密钥

**后端** — 创建 `backend/qa-system/src/main/resources/application-local.yml`：

```yaml
spring:
  datasource:
    password: your_mysql_password
deepseek:
  api-key: sk-your-deepseek-api-key
siliconflow:
  api-key: sk-your-siliconflow-api-key
```

**向量服务** — 创建 `python-service/.env`：

```
SILICONFLOW_API_KEY=sk-your-siliconflow-api-key
```

### 2. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS rag_qa_system DEFAULT CHARACTER SET utf8mb4;
```

表结构由 MyBatis 自动创建（首次启动时）。

### 3. 安装依赖

```bash
# Python 向量服务
cd python-service
pip install -r requirements.txt

# 前端
cd frontend
npm install
```

### 4. 启动服务（按顺序）

```bash
# 窗口1: Python 向量服务 (端口 5000)
cd python-service
python app.py

# 窗口2: Java 后端 (端口 8080)
cd backend/qa-system
mvn spring-boot:run

# 窗口3: 前端 (端口 5173)
cd frontend
npm run dev
```

### 5. 验证

```bash
curl http://127.0.0.1:5000/health     # Python: {"status":"ok"}
curl http://localhost:8080/api/health   # Java:   {"code":200,...}
curl http://localhost:5173              # 前端首页
```

### Docker 一键部署

```bash
docker-compose up -d
```

## 核心 RAG 流程

```
上传文档
  → 文件保存到 uploads/
  → 分块 (1000字符/块, 重叠200)
  → 批量调用硅基流动 embedding API (bge-m3, 1024维)
  → FAISS IndexFlatIP 存储 (余弦相似度)
  → BM25 索引构建 (关键词检索)

用户提问
  → Query 改写 (LLM 优化检索词)
  → 混合检索: 向量检索 (权重0.6) + BM25 (权重0.4)
  → 粗召回 20 条 → Rerank 精排 (bge-reranker-v2-m3)
  → 相关性过滤 (≥0.3) → top-K 筛选
  → 从 MySQL 查完整 chunk 内容 → 去重
  → 构建 RAG Prompt → DeepSeek 生成答案
  → SSE 流式输出到前端
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/documents` | 文档列表（分页） |
| POST | `/api/documents/upload` | 上传文件（自动分块+向量化） |
| DELETE | `/api/documents/{id}` | 删除文档及向量索引 |
| POST | `/api/qa/ask` | 问答（核心 RAG 接口） |
| POST | `/api/qa/ask/stream` | 问答流式输出 (SSE) |
| GET | `/api/qa/history` | 问答历史记录 |
| POST | `/api/llm/chat` | LLM 调试（无 RAG） |

## 评测体系

项目包含完整的 RAG 检索质量评测框架，位于 `eval/` 目录。

### 评测指标

**Chunk Recall（块召回率）** — RAG 系统的核心指标，衡量"正确答案所在的文档块是否被检索到"。

- 方法：对每道测试题指定 `target_snippets`（目标块的唯一文本片段），检查这些片段在检索上下文中的命中率
- 合格线：≥50%
- 当前成绩：**97.4%**（19/19 题通过）

### 实验历程

| 实验 | 参数 | Chunk Recall | 说明 |
|:---:|------|:---:|------|
| #1 | VECTOR:BM25=6:4, RETRIEVAL_K=20, topK=5, MIN=0.3 | 71.1% | 基线（含评测 Markdown 假阴性） |
| #2 | VECTOR:BM25=4:6, RETRIEVAL_K=20, topK=8, MIN=0.2 | 72.5% | BM25 加强 |
| #3 | VECTOR:BM25=5:5, RETRIEVAL_K=30, topK=8, MIN=0.2 | 70.0% | RETRIEVAL_K 过大引入噪音 |
| **修复** | 同#1 + 评测脚本修复 + Prompt 优化 | **97.4%** | 真实基线：假阴性修复后 |

> **重要发现**：前三轮实验中 4 道题（Q07/Q14/Q17/Q20）被误判为 CR=0%。根因是评测脚本做精确子串匹配时未处理 Markdown 格式标记（`**bold**`）。修复后这 4 题全部恢复 CR=100%。详见 `eval/experiments/README.md`。

### 运行评测

```bash
cd eval

# 生成测试文档并自动上传
python generate_doc.py

# 运行评测（需三个服务均已启动）
PYTHONIOENCODING=utf-8 python evaluate.py
```

## 关键设计决策

- **FAISS 通过 Python 微服务调用**，而非 Java 绑定，降低集成复杂度
- **混合检索**：向量（权重 0.6）+ BM25（权重 0.4），粗召回 20 条后 Rerank 精排到 topK=5
- **分块策略**：按段落+句子分割，1000 字符/块，200 字符重叠，仅支持 txt/pdf/md
- **向量相似度**：FAISS IndexFlatIP + L2 归一化 = 余弦相似度，超 100 条自动升级 IVF
- **Query 改写**：LLM 将口语化问题转为专业检索词（如「怎么登录」→「API认证方式 OAuth Token」）
- **检索去重**：对相同内容的 chunk 只保留最高分，避免重复上下文浪费 token
- **SSE 流式**：`/api/qa/ask/stream` 支持 SSE 流式输出，前端实时渲染
- **FAISS 持久化**：原子替换策略（临时文件 + `os.replace`），降低写入中断风险
- **限流保护**：RateLimitInterceptor 对 `/api/qa/ask` 做 IP 级别限流（60次/分钟）
- **链路追踪**：TraceIdFilter 为每个请求生成唯一 traceId，方便日志排查

## 支持的文件类型

| 类型 | 扩展名 |
|------|--------|
| 纯文本 | `.txt` |
| Markdown | `.md` |
| PDF | `.pdf` |

文件大小限制：50MB。上传时做文件头校验防止类型伪装。

## 配置参考

### 检索参数 (`python-service/app.py`)

| 参数 | 默认值 | 说明 |
|------|:---:|------|
| VECTOR_WEIGHT | 0.6 | 向量检索在混合排序中的权重 |
| BM25_WEIGHT | 0.4 | BM25 关键词检索权重 |
| RETRIEVAL_K | 20 | 粗召回候选数 |
| MIN_RELEVANCE_SCORE | 0.3 | Rerank 最低相关性阈值 |
| FINAL_K | 5 | 传给 Java 后端的最终结果数 |

### 生成参数 (`application.yml`)

| 参数 | 默认值 | 说明 |
|------|:---:|------|
| rag.retrieval.top-k | 5 | 最终传给 LLM 的 chunk 数量 |
| deepseek.chat-model | deepseek-v4-flash | LLM 模型 |
| deepseek.temperature | 0.3 | 生成温度 |

## 数据库表

| 表 | 说明 |
|------|------|
| `documents` | 文档元数据 (name, type, file_path, file_size, status) |
| `document_chunks` | 文档分块 (document_id FK, chunk_index, content) |
| `qa_history` | 问答记录 (question, answer, context, model, conversation_id) |

## 注意事项

1. **启动顺序**：Python 向量服务 → Java 后端 → 前端，顺序不可颠倒
2. **密钥配置**：`application-local.yml` 和 `python-service/.env` 包含 API 密钥，已被 gitignore，不会上传
3. **FAISS 数据**：`python-service/data/` 下的索引文件是持久化数据，删除即重置
4. **首次使用**：需通过 Web UI 上传至少一份文档，系统自动分块和建立索引
5. **评测文档**：运行 `python eval/generate_doc.py` 可生成 CloudPay API 测试文档

## License

MIT
