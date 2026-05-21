from flask import Flask, request, jsonify
from flask_cors import CORS
import faiss
import numpy as np
import requests
import os
import json
from dotenv import load_dotenv

# 自动加载 python-service/.env 文件
load_dotenv()

app = Flask(__name__)
CORS(app)

# 全局变量
dimension = 1024  # bge-m3 向量维度
index = None
chunk_ids = []  # 存储chunk_id与索引位置的映射

# 硅基流动配置（从环境变量读取）
SILICONFLOW_API_KEY = os.getenv("SILICONFLOW_API_KEY", "your-api-key-here")
SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
EMBEDDING_MODEL = "BAAI/bge-m3"
DATA_DIR = "data"
FAISS_INDEX_PATH = os.path.join(DATA_DIR, "faiss.index")
CHUNK_IDS_PATH = os.path.join(DATA_DIR, "chunk_ids.json")


def call_embedding_api(text):
    """调用硅基流动embedding接口"""
    url = f"{SILICONFLOW_BASE_URL}/embeddings"
    headers = {
        "Authorization": f"Bearer {SILICONFLOW_API_KEY}",
        "Content-Type": "application/json"
    }
    data = {
        "model": EMBEDDING_MODEL,
        "input": text if text else ""
    }
    
    response = requests.post(url, json=data, headers=headers, timeout=30)
    if response.status_code != 200:
        raise Exception(f"Embedding API error: {response.status_code} {response.text}")
    
    result = response.json()
    return result["data"][0]["embedding"]


def _save_index_and_mapping():
    """将 FAISS 索引和 chunk_id 映射持久化到磁盘"""
    global index, chunk_ids
    os.makedirs(DATA_DIR, exist_ok=True)
    if index is None:
        return

    # 写索引文件
    faiss.write_index(index, FAISS_INDEX_PATH)

    # 写映射文件（原子替换，降低中断损坏风险）
    tmp_path = CHUNK_IDS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(chunk_ids, f, ensure_ascii=False)
    os.replace(tmp_path, CHUNK_IDS_PATH)


def _load_index_and_mapping():
    """启动时加载已持久化的索引和映射；若不存在则新建"""
    global index, chunk_ids

    if os.path.exists(FAISS_INDEX_PATH) and os.path.exists(CHUNK_IDS_PATH):
        index = faiss.read_index(FAISS_INDEX_PATH)
        with open(CHUNK_IDS_PATH, "r", encoding="utf-8") as f:
            chunk_ids = json.load(f)
        print(f"已加载FAISS索引，向量数={index.ntotal}，映射数={len(chunk_ids)}")
        return

    index = faiss.IndexFlatIP(dimension)
    chunk_ids = []
    print(f"未找到持久化索引，已新建空索引，维度={dimension}")


@app.route("/health", methods=["GET"])
def health():
    """健康检查"""
    return jsonify({"status": "ok", "index_size": len(chunk_ids)})


@app.route("/embed", methods=["POST"])
def embed():
    """文本转向量接口"""
    data = request.json
    text = data.get("text", "")
    
    try:
        vector = call_embedding_api(text)
        return jsonify({"vector": vector, "dimension": len(vector)})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


def _init_index_internal():
    """内部初始化函数（不返回JSON）"""
    global index, chunk_ids
    index = faiss.IndexFlatIP(dimension)
    chunk_ids = []
    print(f"FAISS索引初始化成功，维度={dimension}")
    _save_index_and_mapping()


@app.route("/index/init", methods=["POST"])
def init_index():
    """初始化FAISS索引（HTTP接口）"""
    _init_index_internal()
    return jsonify({"message": "索引初始化成功", "dimension": dimension})


@app.route("/index/add", methods=["POST"])
def add_to_index():
    """添加向量到索引"""
    global index, chunk_ids
    
    if index is None:
        init_index()
    
    data = request.json
    chunk_id = data.get("chunk_id")
    text = data.get("text")
    
    if not text:
        return jsonify({"error": "text不能为空"}), 400
    
    try:
        # 1. 文本转向量
        vector = call_embedding_api(text)
        
        # 2. L2归一化（余弦相似度需要）
        vec_array = np.array([vector], dtype=np.float32)
        faiss.normalize_L2(vec_array)
        
        # 3. 添加到索引
        index.add(vec_array)
        chunk_ids.append(chunk_id)
        _save_index_and_mapping()
        
        return jsonify({
            "message": "添加成功",
            "chunk_id": chunk_id,
            "index_size": len(chunk_ids)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/search", methods=["POST"])
def search():
    """向量检索TopK"""
    global index, chunk_ids
    
    if index is None or len(chunk_ids) == 0:
        return jsonify({"results": []})
    
    data = request.json
    query_text = data.get("query")
    top_k = data.get("top_k", 3)
    
    try:
        # 1. 问题转向量
        query_vector = call_embedding_api(query_text)
        
        # 2. L2归一化
        query_array = np.array([query_vector], dtype=np.float32)
        faiss.normalize_L2(query_array)
        
        # 3. 检索TopK
        actual_k = min(top_k, len(chunk_ids))
        scores, indices = index.search(query_array, actual_k)
        
        # 4. 组装结果
        results = []
        for i in range(actual_k):
            idx = indices[0][i]
            score = float(scores[0][i])
            chunk_id = chunk_ids[idx]
            results.append({
                "chunk_id": chunk_id,
                "score": score
            })
        
        return jsonify({"results": results, "count": len(results)})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    print(f"Python向量服务启动中...")
    print(f"Embedding模型: {EMBEDDING_MODEL}")
    print(f"向量维度: {dimension}")
    _load_index_and_mapping()
    app.run(host="0.0.0.0", port=5000, debug=True)
