# CLAUDE.md

## 项目概述

RAG API文档智能问答系统 — 基于检索增强生成（RAG）的技术文档问答平台。用户上传文档后，系统自动分块、向量化存入 FAISS，提问时检索相关文档块作为上下文，由 LLM 生成答案。

## 技术栈

| 层 | 技术 | 端口 |
|----|------|------|
| 前端 | Vue 3 + Element Plus + Vite + Axios | 5173 |
| 后端 | Spring Boot 3.4.5 + Java 17 + MyBatis 3.0.5 | 8080 |
| 向量服务 | Python Flask + FAISS + BM25 + Rerank | 5000 |
| 数据库 | MySQL (rag_qa_system) | 3306 |
| LLM | DeepSeek deepseek-v4-flash (OpenAI 兼容 API) | - |
| Embedding | 硅基流动 BAAI/bge-m3 (1024维) | - |
| Rerank | 硅基流动 BAAI/bge-reranker-v2-m3 | - |

## 目录结构

```
D:\RAG\
├── backend/qa-system/          # Spring Boot 后端
│   ├── src/main/java/com/rag/qa_system/
│   │   ├── controller/         # DocumentController, QaController, HealthController, LlmDebugController
│   │   ├── service/            # QaService (核心RAG), DocumentService, DeepSeekChatService, PythonVectorClient
│   │   ├── model/              # Document, DocumentChunk, QaHistory, RetrievalResult
│   │   ├── mapper/             # MyBatis Mapper (注解SQL + XML混合)
│   │   └── config/             # WebConfig (CORS), RestClientConfig, RateLimitInterceptor
│   └── src/main/resources/
│       ├── application.yml     # 主配置 (含所有自定义属性)
│       └── application-local.yml  # 敏感配置 (API Key, DB密码, gitignore'd)
├── frontend/                   # Vue 3 前端
│   └── src/
│       ├── views/              # ChatView.vue (问答), DocumentsView.vue (文档管理)
│       ├── components/         # AppHeader.vue
│       ├── router/             # Vue Router 配置
│       ├── composables/        # useDarkMode.js
│       └── api.js              # Axios 封装 (baseURL 通过 VITE_API_BASE_URL 配置)
├── python-service/             # Python 向量微服务
│   ├── app.py                  # Flask API: /health, /embed, /index/add, /search, 混合检索+BM25+Rerank
│   ├── .env                    # SILICONFLOW_API_KEY (gitignore'd)
│   └── data/                   # faiss.index + chunk_ids.json + chunk_texts.json (持久化)
└── uploads/                    # 上传文档存储
```

## 启动方式

按顺序启动三个服务：

```bash
# 窗口1: Python 向量服务
cd d:\RAG\python-service && python app.py

# 窗口2: Java 后端 (IDEA 运行 QaSystemApplication)
# 或: cd d:\RAG\backend\qa-system && mvn spring-boot:run

# 窗口3: 前端
cd d:\RAG\frontend && npm run dev
```

三个健康检查地址：
- `http://127.0.0.1:5000/health` — Python 服务
- `http://localhost:8080/api/health` — Java 后端
- `http://localhost:5173` — 前端

Docker 启动：`docker-compose up -d`（需要配置 .env 中的 API key）

## 核心 RAG 数据流

```
上传: 用户上传文件 → DocumentController → DocumentService
      → 保存文件到 uploads/ → 插入 documents 表
      → 分块(1000字符/重叠200) → 批量插入 document_chunks 表
      → 批量调用 PythonVectorClient.batchAddChunks()
      → Python /index/batch_add → 硅基流动 embedding → FAISS add → 持久化

问答: 用户提问 → QaController → QaService
      → PythonVectorClient.search(question, topK=5)
      → Python /search → embedding → 混合检索 (向量+BM25) → Rerank 精排
      → 按 chunk_id 从 document_chunks 查内容 → 去重排序
      → 构建 RAG prompt → DeepSeekChatService → deepseek-v4-flash (支持SSE流式)
      → 保存到 qa_history 表 → 返回答案
```

## 关键设计决策

- **FAISS 通过 Python 微服务调用**（而非 Java 绑定），简化集成
- **Embedding 采用 Spring Profile 切换**：`siliconflow-embedding` profile 使用真实 API，`mock-embedding` 用于测试
- **分块策略**：按段落+句子分割，1000 字符/块，200 字符重叠，仅支持 txt/pdf/md
- **向量相似度**：FAISS IndexFlatIP + L2 归一化 = 余弦相似度，超 100 条自动升级 IVF
- **混合检索**：向量检索（权重 0.6）+ BM25 关键词检索（权重 0.4），粗召回 20 条后 Rerank 精排
- **检索去重**：QaService 对相同内容只保留最高分，避免重复上下文浪费 token
- **LLM 调用**：DeepSeek API (OpenAI 兼容)，temperature=0.3，prompt 中明确要求基于文档回答
- **FAISS 持久化**：使用 `.tmp` 临时文件 + `os.replace` 原子替换，降低写入中断风险
- **配置文件分层**：`application.yml` 存公共配置，`application-local.yml` 存密钥（gitignore）
- **流式响应**：QaController /api/qa/ask/stream 支持 SSE 流式输出，前端实时渲染
- **限流保护**：RateLimitInterceptor 对 /api/qa/ask 做 IP 级别限流（60次/分钟）

## API 端点一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/health | 健康检查 |
| GET | /api/documents | 文档列表 |
| POST | /api/documents | 创建文档元数据 |
| POST | /api/documents/upload | 上传文件（自动分块+向量化） |
| DELETE | /api/documents/{id} | 删除文档及索引 |
| POST | /api/qa/ask | 问答（核心RAG接口） |
| POST | /api/qa/ask/stream | 问答流式输出 (SSE) |
| GET | /api/qa/history | 问答历史 |
| POST | /api/llm/chat | LLM调试（无RAG） |

## 数据库表

- `documents` — 文档元数据 (id, name, type, file_path, file_size, status)
- `document_chunks` — 文档分块 (id, document_id FK→documents, chunk_index, content)，含复合索引 (document_id, chunk_index)
- `qa_history` — 问答记录 (id, question, answer, context, model)

## 注意事项

1. **启动顺序**：必须先启动 Python 服务（端口5000），否则后端上传/问答会报连接拒绝
2. **配置文件**：`application-local.yml` 和 `python-service/.env` 包含 API 密钥，已被 gitignore，修改密钥需改这些文件
3. **环境变量**：前端 `VITE_API_BASE_URL` 控制 API 地址，默认 localhost:8080
4. **DeepSeek API**：需要在环境变量或 application-local.yml 中配置 `DEEPSEEK_API_KEY`
5. **FAISS 数据**：`python-service/data/` 下的 `faiss.index` 和 `chunk_ids.json` 是持久化数据，删除即重置索引
6. **上传文件**：存放在 `d:/RAG/uploads/`，文件名自动加 UUID 前缀防冲突
7. **前端 dev 模式**：Vite 端口 5173，CORS 已在 `WebConfig.java` 放行
8. **文档类型限制**：仅支持 pdf/txt/md，DocumentService 做白名单+文件头校验

## 当前状态

- 项目处于可用阶段，已完成核心 RAG 流程
- 混合检索（向量+BM25）+ Rerank 已实现
- SSE 流式问答已实现
- Docker Compose 一键部署已配置
- 暗色模式已实现
