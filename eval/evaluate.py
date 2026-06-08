"""
RAG 系统评测脚本 v3

核心改进：用「目标块唯一文本片段」做精确的块级检索验证
  - 每个测试题指定 target_snippets（来自文档中应被检索到的块的唯一文本）
  - Chunk Recall: 这些片段在检索上下文中的命中率 = 真正的检索召回率
  - Answer Accuracy: 生成答案与标准答案的语义相似度

指标解释：
  Chunk Recall (块召回率)    = 目标块的唯一文本片段在检索结果中被找到的比例
    这是 RAG 检索环节的核心指标。100% = 所有正确答案所在的块都被检索到了
  Answer Accuracy (答案准确度) = 生成答案与标准答案的文本/语义匹配度
    这是 RAG 生成环节的核心指标。高分 = LLM 生成的答案接近标准答案
  Precision (检索精度)       = 检索结果中命中了目标片段的块占比
    反映检索结果是否干净。低分 = 检索了很多无关内容

使用方式:
  python eval/generate_doc.py          # 生成大文档
  (通过 Web UI 上传 uploads/CloudPay_API_v3.md)
  python eval/evaluate.py              # 运行评测
"""

import requests
import json
import time
import os
import sys
from difflib import SequenceMatcher

# ============ 配置 ============
API_BASE = os.environ.get("API_BASE", "http://localhost:8080")
API_URL = f"{API_BASE}/api/qa/ask"
TEST_FILE = os.path.join(os.path.dirname(__file__), "test_questions.json")

with open(TEST_FILE, "r", encoding="utf-8") as f:
    test_data = json.load(f)

questions = test_data["questions"]


def ask(question: str) -> dict:
    """调用问答 API"""
    try:
        resp = requests.post(API_URL, json={"question": question}, timeout=90)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        return {"error": str(e)}


def strip_markdown(text: str) -> str:
    """去除 Markdown 格式标记，用于精确文本匹配"""
    import re
    # 去粗体/斜体: **text** -> text, *text* -> text
    text = re.sub(r'\*\*(.+?)\*\*', r'\1', text)
    text = re.sub(r'\*(.+?)\*', r'\1', text)
    # 去行内代码: `text` -> text
    text = re.sub(r'`(.+?)`', r'\1', text)
    # 去标题标记: ### , ## , #
    text = re.sub(r'^#{1,6}\s+', '', text, flags=re.MULTILINE)
    # 去列表标记: - , * , +
    text = re.sub(r'^[\s]*[-*+]\s+', '', text, flags=re.MULTILINE)
    # 去表格竖线（保留内容）
    text = text.replace('|', ' ')
    # 压缩多余空白
    text = re.sub(r'\s+', ' ', text)
    return text.strip()


def snippet_hit_count(context: str, snippets: list) -> int:
    """检查目标片段在检索上下文中命中多少个"""
    if not context or not snippets:
        return 0
    # 先去除 markdown 格式再匹配
    context_clean = strip_markdown(context).lower()
    hits = 0
    for s in snippets:
        # 支持 | 分隔的多个关键词，任意一个匹配即算命中
        parts = [strip_markdown(p.strip()).lower() for p in s.split("|")]
        if any(p in context_clean for p in parts):
            hits += 1
    return hits


def chunk_recall(context: str, snippets: list) -> float:
    """块召回率：目标片段在检索上下文中的命中率"""
    if not snippets:
        return 0.0
    return snippet_hit_count(context, snippets) / len(snippets)


def answer_similarity(answer: str, ground_truth: str) -> float:
    """答案与标准答案的语义相似度"""
    if not answer or not ground_truth:
        return 0.0
    return SequenceMatcher(None, answer, ground_truth).ratio()


def evaluate_one(q: dict) -> dict:
    """评估单个问题"""
    qid = q["id"]
    question = q["question"]
    category = q["category"]
    ground_truth = q["ground_truth"]
    snippets = q["target_snippets"]

    start = time.time()
    response = ask(question)
    elapsed = time.time() - start

    if "error" in response:
        return {
            "id": qid, "question": question, "category": category,
            "error": response["error"], "elapsed": round(elapsed, 2),
            "chunk_recall": 0, "answer_accuracy": 0,
            "snippets_found": 0, "snippets_total": len(snippets),
        }

    # 兼容新旧 API 响应格式
    # 新格式: {code, message, data: {answer, context, ...}, timestamp}
    # 旧格式: {id, question, answer, context, model}
    if "data" in response and isinstance(response["data"], dict):
        body = response["data"]
    else:
        body = response

    answer = body.get("answer", "")
    context = body.get("context", "")

    # 如果没有检索到任何文档内容
    if not context or context == "未命中任何文档内容。":
        return {
            "id": qid, "question": question, "category": category,
            "error": "未检索到文档内容",
            "elapsed": round(elapsed, 2),
            "chunk_recall": 0, "answer_accuracy": 0,
            "snippets_found": 0, "snippets_total": len(snippets),
        }

    # 核心指标
    recall = chunk_recall(context, snippets)
    accuracy = answer_similarity(answer, ground_truth)
    found = snippet_hit_count(context, snippets)

    return {
        "id": qid, "question": question, "category": category,
        "elapsed": round(elapsed, 2),
        "chunk_recall": round(recall, 4),
        "answer_accuracy": round(accuracy, 4),
        "snippets_found": found,
        "snippets_total": len(snippets),
        "recall_pass": recall >= 0.5,
        "accuracy_pass": accuracy >= 0.25,
    }


def main():
    print("=" * 60)
    print("  RAG 系统评测 v3 — 块级检索验证")
    print("=" * 60)
    print(f"  API: {API_URL}")
    print(f"  测试题: {len(questions)} 道")
    print()

    # 检查 Java 后端
    try:
        requests.get(f"{API_BASE}/api/health", timeout=5)
        print("  ✓ Java 后端正常")
    except Exception as e:
        print(f"  ✗ 无法连接 Java 后端 (端口8080): {e}")
        sys.exit(1)

    # 检查 Python 向量服务
    try:
        py_health = requests.get("http://localhost:5000/health", timeout=5)
        print(f"  ✓ Python 向量服务正常 (索引量: {py_health.json().get('index_size', '?')})")
    except Exception as e:
        print(f"  ✗ Python 向量服务未启动 (端口5000)")
        print(f"  请先启动: cd python-service && python app.py")
        sys.exit(1)

    # 检查是否有已上传的文档
    try:
        docs_resp = requests.get(f"{API_BASE}/api/documents", params={"page": 1, "pageSize": 50}, timeout=10)
        docs_data = docs_resp.json()
        if "data" in docs_data:
            docs_list = docs_data["data"].get("list", [])
        else:
            docs_list = docs_data if isinstance(docs_data, list) else []
        completed_docs = [d for d in docs_list if d.get("status") == "completed"]
    except Exception:
        docs_list = []
        completed_docs = []

    # 检查是否已有 CloudPay 测试文档
    has_test_doc = any("CloudPay" in d.get("name", "") for d in completed_docs)
    if not has_test_doc:
        doc_path = os.path.join(os.path.dirname(__file__), "..", "uploads", "CloudPay_API_v3.md")
        if not os.path.exists(doc_path):
            print("  ✗ 测试文档不存在，正在生成...")
            # 动态生成
            import subprocess
            gen_script = os.path.join(os.path.dirname(__file__), "generate_doc.py")
            subprocess.run([sys.executable, gen_script], check=True)

        if os.path.exists(doc_path):
            print("  ⚠ 未找到 CloudPay 测试文档，自动上传...")
            try:
                with open(doc_path, "rb") as f:
                    upload_resp = requests.post(
                        f"{API_BASE}/api/documents/upload",
                        files={"file": ("CloudPay_API_v3.md", f, "text/markdown")},
                        timeout=60
                    )
                if upload_resp.status_code == 200:
                    upload_data = upload_resp.json()
                    doc_id = (upload_data.get("data", {}) or {}).get("id") or upload_data.get("id", "?")
                    print(f"  ✓ CloudPay 测试文档已上传，文档 ID: {doc_id}")
                    print("  ⏳ 等待文档向量化处理...")
                    # 轮询等待文档状态变为 completed
                    for _ in range(30):  # 最多等30秒
                        time.sleep(1)
                        try:
                            check = requests.get(f"{API_BASE}/api/documents", params={"page":1,"pageSize":50}, timeout=5)
                            check_data = check.json()
                            docs = check_data.get("data", check_data)
                            if isinstance(docs, dict):
                                docs = docs.get("list", [])
                            for d in docs:
                                if d.get("id") == doc_id:
                                    if d.get("status") == "completed":
                                        print(f"  ✓ 文档处理完成")
                                        break
                                    elif d.get("status") == "failed":
                                        print(f"  ✗ 文档处理失败！请检查 Python 向量服务日志")
                                        sys.exit(1)
                            else:
                                continue
                            break
                        except:
                            continue
                    else:
                        print(f"  ⚠ 文档处理超时（仍为 processing），继续评测...")
                else:
                    print(f"  ✗ 上传失败 (HTTP {upload_resp.status_code}): {upload_resp.text[:200]}")
                    print("  请通过 Web UI 手动上传 uploads/CloudPay_API_v3.md")
                    sys.exit(1)
            except Exception as e:
                print(f"  ✗ 上传失败: {e}")
                sys.exit(1)
        else:
            print("  ✗ 无法生成测试文档")
            sys.exit(1)
    else:
        print(f"  ✓ CloudPay 测试文档已就绪 (共 {len(completed_docs)} 个文档)")

    print(f"\n  开始评测...\n")

    results = []
    for i, q in enumerate(questions):
        r = evaluate_one(q)
        results.append(r)

        status = "✓" if r.get("recall_pass") and r.get("accuracy_pass") else (
            "△" if r.get("recall_pass") or r.get("accuracy_pass") else "✗")
        print(f"  [{r['id']}] {status} | "
              f"Chunk Recall={r.get('chunk_recall',0):.0%} ({r.get('snippets_found',0)}/{r.get('snippets_total',0)} snippets) | "
              f"Answer Acc={r.get('answer_accuracy',0):.0%} | "
              f"{r.get('elapsed',0):.1f}s | {r['question'][:30]}...")
        time.sleep(0.6)

    # ============ 汇总 ============
    valid = [r for r in results if "error" not in r]
    errors = [r for r in results if "error" in r]

    if not valid:
        print("\n所有请求失败，请检查服务状态。")
        return

    avg_recall = sum(r["chunk_recall"] for r in valid) / len(valid)
    avg_accuracy = sum(r["answer_accuracy"] for r in valid) / len(valid)
    avg_elapsed = sum(r["elapsed"] for r in valid) / len(valid)
    composite = (avg_recall * 0.5 + avg_accuracy * 0.5) * 100

    recall_pass_n = sum(1 for r in valid if r["recall_pass"])
    acc_pass_n = sum(1 for r in valid if r["accuracy_pass"])
    perfect_n = sum(1 for r in valid if r["chunk_recall"] >= 1.0)
    both_pass_n = sum(1 for r in valid if r["recall_pass"] and r["accuracy_pass"])

    by_cat = {}
    for r in valid:
        by_cat.setdefault(r["category"], []).append(r)

    # ============ 报告 ============
    lines = []
    w = 62
    lines.append("=" * w)
    lines.append("  RAG 系统评测报告 v3 — 块级检索验证")
    lines.append("=" * w)
    lines.append(f"  时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"  题数: {len(questions)} | 成功: {len(valid)} | 失败: {len(errors)}")
    lines.append(f"  总耗时: {sum(r['elapsed'] for r in valid):.1f}s | 平均: {avg_elapsed:.1f}s/题")
    lines.append("")

    lines.append("  " + "─" * 54)
    lines.append("  综合指标")
    lines.append("  " + "─" * 54)
    lines.append(f"  Chunk Recall (块召回率):     {avg_recall:.1%}  合格线 50% | 通过 {recall_pass_n}/{len(valid)}")
    lines.append(f"    → 正确答案所在的文档块，有多少被检索到了")
    lines.append(f"    满分题数: {perfect_n}/{len(valid)} (全部目标片段均命中)")
    lines.append("")
    lines.append(f"  Answer Accuracy (答案准确度): {avg_accuracy:.1%}  合格线 25% | 通过 {acc_pass_n}/{len(valid)}")
    lines.append(f"    → 生成答案与标准答案的语义匹配度")
    lines.append("")
    lines.append(f"  综合评分:                    {composite:.1f}/100")
    lines.append(f"  双项通过率:                  {both_pass_n}/{len(valid)} = {both_pass_n/len(valid):.0%}")
    lines.append("")

    lines.append("  " + "─" * 54)
    lines.append("  按类别分析")
    lines.append("  " + "─" * 54)
    for cat, items in sorted(by_cat.items()):
        cr = sum(r["chunk_recall"] for r in items) / len(items)
        ca = sum(r["answer_accuracy"] for r in items) / len(items)
        lines.append(f"  {cat}: ChunkRec {cr:.1%} | AnsAcc {ca:.1%} ({len(items)}题)")

    lines.append("")
    lines.append("  " + "─" * 54)
    lines.append("  逐题详情")
    lines.append("  " + "─" * 54)
    for r in valid:
        both = r["recall_pass"] and r["accuracy_pass"]
        flag = "✓" if both else ("△" if r["recall_pass"] or r["accuracy_pass"] else "✗")
        lines.append(
            f"  [{r['id']}] {flag} CR={r['chunk_recall']:.0%} AA={r['answer_accuracy']:.0%} "
            f"({r['snippets_found']}/{r['snippets_total']}snip) | {r['question'][:32]}..."
        )

    lines.append("")
    lines.append("  " + "─" * 54)
    lines.append("  指标解读")
    lines.append("  " + "─" * 54)
    lines.append("  Chunk Recall = 用文档中唯一文本片段验证特定块是否被检索到")
    lines.append("    这是真正的「检索召回率」— 不是模糊关键词匹配")
    lines.append("  Answer Accuracy = LLM 生成答案与人工标准答案的匹配度")
    lines.append("    低 Chunk Recall + 高 Answer Accuracy = LLM 自身知识在补位(幻觉风险)")
    lines.append("    高 Chunk Recall + 低 Answer Accuracy = 检索到了但 LLM 没用好")

    report_str = "\n".join(lines)
    print("\n" + report_str)

    base = os.path.dirname(__file__)
    with open(os.path.join(base, "report.txt"), "w", encoding="utf-8") as f:
        f.write(report_str)
    with open(os.path.join(base, "results.json"), "w", encoding="utf-8") as f:
        json.dump({
            "summary": {
                "avg_chunk_recall": round(avg_recall, 4),
                "avg_answer_accuracy": round(avg_accuracy, 4),
                "avg_elapsed_seconds": round(avg_elapsed, 2),
                "composite_score": round(composite, 1),
                "recall_pass_rate": round(recall_pass_n / len(valid), 4),
                "accuracy_pass_rate": round(acc_pass_n / len(valid), 4),
                "both_pass_rate": round(both_pass_n / len(valid), 4),
            },
            "results": results
        }, f, ensure_ascii=False, indent=2)

    print(f"\n报告已保存: eval/report.txt | eval/results.json")


if __name__ == "__main__":
    main()
