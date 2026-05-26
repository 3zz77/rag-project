from flask import Flask, request, jsonify
from flask_cors import CORS
import faiss
import numpy as np
import requests
import os
import json
import logging
from dotenv import load_dotenv
from rank_bm25 import BM25Okapi

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

load_dotenv()

app = Flask(__name__)
CORS(app)

dimension = 1024
index = None
chunk_ids = []
chunk_texts = []
bm25 = None

IVF_THRESHOLD = 100
NLIST = 0  # will be computed dynamically

SILICONFLOW_API_KEY = os.getenv("SILICONFLOW_API_KEY")
SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
EMBEDDING_MODEL = "BAAI/bge-m3"
RERANK_MODEL = "BAAI/bge-reranker-v2-m3"
DATA_DIR = "data"
FAISS_INDEX_PATH = os.path.join(DATA_DIR, "faiss.index")
CHUNK_IDS_PATH = os.path.join(DATA_DIR, "chunk_ids.json")
CHUNK_TEXTS_PATH = os.path.join(DATA_DIR, "chunk_texts.json")

# Hybrid search weights
VECTOR_WEIGHT = 0.6
BM25_WEIGHT = 0.4
RETRIEVAL_K = 20
FINAL_K = 5
MIN_RELEVANCE_SCORE = 0.3  # rerank 相关性最低阈值，低于此分数的chunk视为不相关


def call_embedding_api(text):
    if not SILICONFLOW_API_KEY:
        raise Exception("缺少 SILICONFLOW_API_KEY，请在 python-service/.env 或系统环境变量中配置")
    url = f"{SILICONFLOW_BASE_URL}/embeddings"
    headers = {
        "Authorization": f"Bearer {SILICONFLOW_API_KEY}",
        "Content-Type": "application/json"
    }
    data = {"model": EMBEDDING_MODEL, "input": text if text else ""}
    response = requests.post(url, json=data, headers=headers, timeout=30)
    if response.status_code != 200:
        raise Exception(f"Embedding API error: {response.status_code} {response.text}")
    return response.json()["data"][0]["embedding"]


def call_embedding_api_batch(texts):
    """批量调用 embedding API，一次发送多个文本"""
    if not texts:
        return []
    if not SILICONFLOW_API_KEY:
        raise Exception("缺少 SILICONFLOW_API_KEY")
    url = f"{SILICONFLOW_BASE_URL}/embeddings"
    headers = {
        "Authorization": f"Bearer {SILICONFLOW_API_KEY}",
        "Content-Type": "application/json"
    }
    data = {"model": EMBEDDING_MODEL, "input": texts}
    response = requests.post(url, json=data, headers=headers, timeout=60)
    if response.status_code != 200:
        raise Exception(f"Batch embedding API error: {response.status_code} {response.text}")
    items = response.json()["data"]
    items.sort(key=lambda x: x["index"])
    return [item["embedding"] for item in items]


def call_rerank_api(query, documents):
    """调用硅基流动 rerank API 精排，过滤低相关性 chunk"""
    if not SILICONFLOW_API_KEY or len(documents) == 0:
        return list(range(len(documents)))

    url = f"{SILICONFLOW_BASE_URL}/rerank"
    headers = {
        "Authorization": f"Bearer {SILICONFLOW_API_KEY}",
        "Content-Type": "application/json"
    }
    data = {
        "model": RERANK_MODEL,
        "query": query,
        "documents": documents,
        "top_n": len(documents)  # 获取全部候选的分数，用于后续过滤
    }
    try:
        response = requests.post(url, json=data, headers=headers, timeout=30)
        if response.status_code != 200:
            logger.warning("Rerank API error, falling back to score fusion: %s", response.status_code)
            return None
        items = response.json().get("results", [])
        # 按相关性分数过滤，只保留高于阈值的
        qualified = [(it["index"], it.get("relevance_score", 0)) for it in items
                     if it.get("relevance_score", 0) >= MIN_RELEVANCE_SCORE]
        if not qualified:
            logger.info("Rerank 后无 chunk 达到相关性阈值 %.2f，返回空结果", MIN_RELEVANCE_SCORE)
            return []
        qualified.sort(key=lambda x: x[1], reverse=True)
        result = [idx for idx, _ in qualified[:FINAL_K]]
        if len(result) < len(items):
            logger.info("Rerank 过滤: %d/%d 个 chunk 达到相关性阈值, 最终保留 %d 个",
                       len(qualified), len(items), len(result))
        return result
    except Exception as e:
        logger.warning("Rerank failed, falling back to score fusion: %s", e)
        return None


def _tokenize(text):
    """简单分词，支持中英文"""
    import re
    tokens = []
    for part in re.findall(r'[a-zA-Z0-9_]+|[一-鿿]|[^\s]', text.lower()):
        tokens.append(part)
    return tokens if tokens else [text.lower()]


def _build_bm25():
    global bm25, chunk_texts
    if chunk_texts:
        tokenized = [_tokenize(t) for t in chunk_texts]
        bm25 = BM25Okapi(tokenized)
    else:
        bm25 = None


def _save_index_and_mapping():
    global index, chunk_ids, chunk_texts
    os.makedirs(DATA_DIR, exist_ok=True)
    if index is None:
        return
    faiss.write_index(index, FAISS_INDEX_PATH)

    tmp_path = CHUNK_IDS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(chunk_ids, f, ensure_ascii=False)
    os.replace(tmp_path, CHUNK_IDS_PATH)

    tmp_path = CHUNK_TEXTS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(chunk_texts, f, ensure_ascii=False)
    os.replace(tmp_path, CHUNK_TEXTS_PATH)


def _load_index_and_mapping():
    global index, chunk_ids, chunk_texts

    if os.path.exists(FAISS_INDEX_PATH) and os.path.exists(CHUNK_IDS_PATH):
        index = faiss.read_index(FAISS_INDEX_PATH)
        with open(CHUNK_IDS_PATH, "r", encoding="utf-8") as f:
            chunk_ids = json.load(f)
        if os.path.exists(CHUNK_TEXTS_PATH):
            with open(CHUNK_TEXTS_PATH, "r", encoding="utf-8") as f:
                chunk_texts = json.load(f)
        else:
            chunk_texts = [""] * len(chunk_ids)
        _build_bm25()
        logger.info("已加载FAISS索引，向量数=%d，映射数=%d，BM25已就绪", index.ntotal, len(chunk_ids))
        return

    index = faiss.IndexFlatIP(dimension)
    chunk_ids = []
    chunk_texts = []
    bm25 = None
    logger.info("未找到持久化索引，已新建空索引，维度=%d", dimension)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "index_size": len(chunk_ids),
        "bm25_ready": bm25 is not None
    })


@app.route("/embed", methods=["POST"])
def embed():
    data = request.json
    text = data.get("text", "")
    try:
        vector = call_embedding_api(text)
        return jsonify({"vector": vector, "dimension": len(vector)})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


def _create_index(n_vectors):
    """根据向量数量选择合适的索引类型"""
    global NLIST
    if n_vectors < IVF_THRESHOLD:
        return faiss.IndexFlatIP(dimension)
    NLIST = max(4, int(np.sqrt(n_vectors)))
    quantizer = faiss.IndexFlatIP(dimension)
    idx = faiss.IndexIVFFlat(quantizer, dimension, NLIST, faiss.METRIC_INNER_PRODUCT)
    idx.nprobe = max(1, NLIST // 4)
    return idx


def _init_index_internal():
    global index, chunk_ids, chunk_texts, bm25
    index = faiss.IndexFlatIP(dimension)
    chunk_ids = []
    chunk_texts = []
    bm25 = None
    _save_index_and_mapping()


def _rebuild_index():
    """重建索引（训练 IVF 如需要）"""
    global index, chunk_ids, chunk_texts, NLIST
    if len(chunk_ids) == 0:
        _init_index_internal()
        return

    vectors = np.array([index.reconstruct(i) for i in range(index.ntotal)], dtype=np.float32)
    new_index = _create_index(len(chunk_ids))

    if isinstance(new_index, faiss.IndexIVFFlat):
        new_index.train(vectors)

    new_index.add(vectors)
    index = new_index
    _save_index_and_mapping()
    logger.info("索引已重建: 类型=%s, 向量数=%d, nlist=%s",
                'IVF' if isinstance(index, faiss.IndexIVFFlat) else 'FlatIP',
                index.ntotal,
                NLIST if isinstance(index, faiss.IndexIVFFlat) else 'N/A')


@app.route("/index/init", methods=["POST"])
def init_index():
    _init_index_internal()
    return jsonify({"message": "索引初始化成功", "dimension": dimension})


@app.route("/index/add", methods=["POST"])
def add_to_index():
    global index, chunk_ids, chunk_texts
    if index is None:
        _init_index_internal()

    data = request.json
    chunk_id = data.get("chunk_id")
    text = data.get("text")

    if not text:
        return jsonify({"error": "text不能为空"}), 400

    try:
        vector = call_embedding_api(text)
        vec_array = np.array([vector], dtype=np.float32)
        faiss.normalize_L2(vec_array)
        index.add(vec_array)
        chunk_ids.append(chunk_id)
        chunk_texts.append(text)
        _build_bm25()
        _save_index_and_mapping()

        return jsonify({
            "message": "添加成功",
            "chunk_id": chunk_id,
            "index_size": len(chunk_ids)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/index/batch_add", methods=["POST"])
def batch_add_to_index():
    global index, chunk_ids, chunk_texts

    if index is None:
        _init_index_internal()

    data = request.json
    items = data.get("items", [])
    if not items:
        return jsonify({"error": "items不能为空"}), 400

    try:
        texts = [item["text"] for item in items]
        chunk_ids_batch = [item["chunk_id"] for item in items]

        vectors = call_embedding_api_batch(texts)

        vec_array = np.array(vectors, dtype=np.float32)
        faiss.normalize_L2(vec_array)
        index.add(vec_array)
        chunk_ids.extend(chunk_ids_batch)
        chunk_texts.extend(texts)
        _build_bm25()

        # 检查是否需要从 FlatIP 升级到 IVF
        old_ntotal = index.ntotal - len(chunk_ids_batch)
        if old_ntotal < IVF_THRESHOLD and index.ntotal >= IVF_THRESHOLD:
            _rebuild_index()
        else:
            _save_index_and_mapping()

        index_type = "IVF" if isinstance(index, faiss.IndexIVFFlat) else "FlatIP"
        return jsonify({
            "message": "批量添加成功",
            "count": len(chunk_ids_batch),
            "index_size": len(chunk_ids),
            "index_type": index_type
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/index/delete_by_doc", methods=["POST"])
def delete_by_doc():
    global index, chunk_ids, chunk_texts

    data = request.json
    doc_id = str(data.get("document_id", ""))

    if index is None or len(chunk_ids) == 0:
        return jsonify({"message": "索引为空，无需删除", "removed": 0})

    keep_indices = []
    to_remove = 0
    for i, cid in enumerate(chunk_ids):
        if cid.startswith(doc_id + ":"):
            to_remove += 1
        else:
            keep_indices.append(i)

    if to_remove == 0:
        return jsonify({"message": "未找到该文档的向量", "removed": 0})

    new_index = faiss.IndexFlatIP(dimension)
    new_chunk_ids = []
    new_chunk_texts = []

    if keep_indices:
        kept_vectors = np.array([index.reconstruct(i) for i in keep_indices], dtype=np.float32)
        new_index.add(kept_vectors)
        new_chunk_ids = [chunk_ids[i] for i in keep_indices]
        new_chunk_texts = [chunk_texts[i] for i in keep_indices]

    index = new_index
    chunk_ids = new_chunk_ids
    chunk_texts = new_chunk_texts
    _build_bm25()
    _save_index_and_mapping()

    return jsonify({
        "message": f"已删除文档 {doc_id} 的 {to_remove} 条向量",
        "removed": to_remove,
        "index_size": len(chunk_ids)
    })


@app.route("/search", methods=["POST"])
def search():
    global index, chunk_ids, chunk_texts, bm25

    if index is None or len(chunk_ids) == 0:
        return jsonify({"results": []})

    data = request.json
    query_text = data.get("query")
    top_k = data.get("top_k", FINAL_K)
    use_rerank = data.get("rerank", True)

    try:
        # 1. Vector search (top RETRIEVAL_K)
        query_vector = call_embedding_api(query_text)
        query_array = np.array([query_vector], dtype=np.float32)
        faiss.normalize_L2(query_array)
        vec_k = min(RETRIEVAL_K, len(chunk_ids))
        vec_scores, vec_indices = index.search(query_array, vec_k)

        # Collect vector results with normalized scores
        candidates = {}
        for i in range(vec_k):
            idx = vec_indices[0][i]
            score = float(vec_scores[0][i])
            cid = chunk_ids[idx]
            candidates[cid] = {
                "chunk_id": cid,
                "vector_score": score,
                "bm25_score": 0.0,
                "text": chunk_texts[idx] if idx < len(chunk_texts) else ""
            }

        # 2. BM25 search
        if bm25 is not None:
            tokenized_query = _tokenize(query_text)
            bm25_scores = bm25.get_scores(tokenized_query)
            # Normalize BM25 scores to [0, 1]
            bm25_max = float(np.max(bm25_scores)) if len(bm25_scores) > 0 else 1.0
            if bm25_max > 0:
                bm25_scores = bm25_scores / bm25_max

            # Get top BM25 results
            bm25_k = min(RETRIEVAL_K, len(chunk_ids))
            top_bm25_idx = np.argsort(bm25_scores)[::-1][:bm25_k]
            for idx in top_bm25_idx:
                cid = chunk_ids[idx]
                s = float(bm25_scores[idx])
                if cid in candidates:
                    candidates[cid]["bm25_score"] = s
                else:
                    candidates[cid] = {
                        "chunk_id": cid,
                        "vector_score": 0.0,
                        "bm25_score": s,
                        "text": chunk_texts[idx] if idx < len(chunk_texts) else ""
                    }

        # 3. Fuse scores
        for c in candidates.values():
            c["fused_score"] = VECTOR_WEIGHT * c["vector_score"] + BM25_WEIGHT * c["bm25_score"]

        ranked = sorted(candidates.values(), key=lambda x: x["fused_score"], reverse=True)

        # 4. Rerank (if enabled) — 按相关性分数过滤不相关chunk
        if use_rerank and len(ranked) > 0:
            rerank_docs = [item["text"] for item in ranked]
            rerank_order = call_rerank_api(query_text, rerank_docs)
            if rerank_order is not None:
                ranked = [ranked[i] for i in rerank_order]
            else:
                ranked = ranked[:top_k]
        else:
            ranked = ranked[:top_k]

        # 5. Final output
        results = []
        for item in ranked:
            results.append({
                "chunk_id": item["chunk_id"],
                "score": round(item["fused_score"], 4),
                "vector_score": round(item["vector_score"], 4),
                "bm25_score": round(item["bm25_score"], 4)
            })

        return jsonify({"results": results, "count": len(results)})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


ocr_reader = None
OCR_CACHE_DIR = os.path.join(DATA_DIR, "ocr_models")


def _get_ocr_reader():
    """懒加载 easyocr reader，通过 HF 镜像下载模型"""
    global ocr_reader
    if ocr_reader is None:
        # 使用 HuggingFace 国内镜像加速下载
        os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
        import easyocr
        os.makedirs(OCR_CACHE_DIR, exist_ok=True)
        logger.info("正在加载 easyOCR 模型（首次使用会下载约 200MB，请稍候）...")
        ocr_reader = easyocr.Reader(
            ['ch_sim', 'en'],
            model_storage_directory=OCR_CACHE_DIR,
            download_enabled=True,
            gpu=False
        )
        logger.info("easyOCR 模型加载完成")
    return ocr_reader


@app.route("/ocr/pdf", methods=["POST"])
def ocr_pdf():
    """对扫描型/图片型 PDF 进行 OCR 文本提取"""
    data = request.json
    file_path = data.get("file_path", "")

    if not file_path or not os.path.exists(file_path):
        return jsonify({"error": "file_path 无效或文件不存在"}), 400

    if not file_path.lower().endswith('.pdf'):
        return jsonify({"error": "仅支持 PDF 文件"}), 400

    try:
        import fitz
        import io
        from PIL import Image

        doc = fitz.open(file_path)
        all_text = []
        reader = _get_ocr_reader()

        for page_num, page in enumerate(doc):
            pix = page.get_pixmap(dpi=250)
            img = Image.open(io.BytesIO(pix.tobytes("png")))
            results = reader.readtext(img, detail=0)
            page_text = "\n".join(results)
            if page_text.strip():
                all_text.append(page_text)
            logger.info("OCR 第 %d/%d 页完成，提取 %d 段文字",
                       page_num + 1, len(doc), len(results))
            logger.info("OCR 第 %d/%d 页完成，提取 %d 段文字",
                       page_num + 1, len(doc), len(lines))

        doc.close()
        full_text = "\n\n".join(all_text)

        if not full_text.strip():
            return jsonify({"error": "OCR 未能识别出任何文字"}), 500

        logger.info("OCR 完成，共提取 %d 字符", len(full_text))
        return jsonify({"text": full_text, "pages": len(doc)})

    except Exception as e:
        logger.error("OCR 失败: %s", e)
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    logger.info("Python向量服务启动中...")
    logger.info("Embedding模型: %s", EMBEDDING_MODEL)
    logger.info("Rerank模型: %s", RERANK_MODEL)
    logger.info("向量维度: %d", dimension)
    logger.info("混合检索: 向量权重=%s, BM25权重=%s", VECTOR_WEIGHT, BM25_WEIGHT)
    _load_index_and_mapping()
    app.run(host="0.0.0.0", port=5000, debug=False)
