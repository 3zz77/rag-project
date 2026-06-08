import axios from "axios";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
});

// 响应拦截器：统一解包 ApiResponse
http.interceptors.response.use(
  (response) => {
    const body = response.data;
    // 如果响应体符合 ApiResponse 格式 {code, message, data, timestamp}，自动解包
    if (body && typeof body.code === "number" && "data" in body) {
      if (body.code === 200) {
        return body.data;
      }
      return Promise.reject(new Error(body.message || "请求失败"));
    }
    return body;
  },
  (error) => {
    const detail = error?.response?.data?.message || error.message || "网络错误";
    return Promise.reject(new Error(detail));
  }
);

export async function uploadDocument(file) {
  const form = new FormData();
  form.append("file", file);
  return http.post("/api/documents/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
  });
}

export async function getDocuments(page = 1, pageSize = 10) {
  return http.get("/api/documents", { params: { page, pageSize } });
}

export async function deleteDocument(id) {
  return http.delete(`/api/documents/${id}`);
}

export async function askQuestion(question, conversationId = null) {
  return http.post("/api/qa/ask", { question, conversationId });
}

export function askQuestionStream(question, callbacks, conversationId = null) {
  const { onToken, onContext, onDone, onError, onConversationId, onRewrittenQuery } = callbacks;

  fetch(`${API_BASE}/api/qa/ask/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, conversationId }),
  })
    .then((response) => {
      if (!response.ok) throw new Error("HTTP " + response.status);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let finished = false;

      function finish() {
        if (finished) return;
        finished = true;
        onDone?.();
      }

      function dispatch(eventType, data) {
        if (eventType === "context") onContext?.(data);
        else if (eventType === "token") onToken?.(data);
        else if (eventType === "done") finish();
        else if (eventType === "conversationId") onConversationId?.(data);
        else if (eventType === "rewrittenQuery") onRewrittenQuery?.(data);
      }

      function processChunk(chunk) {
        buffer += chunk;
        const parts = buffer.split("\n\n");
        buffer = parts.pop() || "";

        for (const part of parts) {
          if (!part.trim()) continue;
          const lines = part.split("\n");
          let eventType = "";
          const dataLines = [];

          for (const line of lines) {
            if (line.startsWith("event:")) {
              eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
              dataLines.push(line.substring(5));
            }
          }

          if (eventType && dataLines.length > 0) {
            dispatch(eventType, dataLines.join("\n"));
          }
        }
      }

      function read() {
        reader
          .read()
          .then(({ done, value }) => {
            if (done) {
              if (buffer.trim()) {
                const lines = buffer.split("\n");
                let eventType = "";
                const dataLines = [];
                for (const line of lines) {
                  if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                  } else if (line.startsWith("data:")) {
                    dataLines.push(line.substring(5));
                  }
                }
                if (eventType && dataLines.length > 0) {
                  dispatch(eventType, dataLines.join("\n"));
                }
              }
              finish();
              return;
            }
            processChunk(decoder.decode(value, { stream: true }));
            read();
          })
          .catch((e) => onError?.(e));
      }

      read();
    })
    .catch((e) => onError?.(e));
}

export async function getHistory(page = 1, pageSize = 10) {
  return http.get("/api/qa/history", { params: { page, pageSize } });
}

export async function clearConversation(conversationId) {
  return http.delete(`/api/qa/history/conversation/${conversationId}`);
}
