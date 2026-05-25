import axios from "axios";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
});

export async function uploadDocument(file) {
  const form = new FormData();
  form.append("file", file);
  const { data } = await http.post("/api/documents/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
  });
  return data;
}

export async function getDocuments() {
  const { data } = await http.get("/api/documents");
  return data;
}

export async function deleteDocument(id) {
  const { data } = await http.delete(`/api/documents/${id}`);
  return data;
}

export async function askQuestion(question) {
  const { data } = await http.post("/api/qa/ask", { question });
  return data;
}

export function askQuestionStream(question, callbacks) {
  const { onToken, onContext, onDone, onError } = callbacks;

  fetch(`${API_BASE}/api/qa/ask/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  })
    .then((response) => {
      if (!response.ok) throw new Error("HTTP " + response.status);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let lastEvent = "";
      let finished = false;

      function finish() {
        if (finished) return;
        finished = true;
        onDone?.();
      }

      function processChunk(chunk) {
        buffer += chunk;
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed.startsWith("event:")) {
            lastEvent = trimmed.substring(6).trim();
          } else if (trimmed.startsWith("data:")) {
            const data = trimmed.substring(5).trim();
            if (lastEvent === "context") onContext?.(data);
            else if (lastEvent === "token") onToken?.(data);
            else if (lastEvent === "done") finish();
            lastEvent = "";
          }
        }
      }

      function read() {
        reader
          .read()
          .then(({ done, value }) => {
            if (done) {
              processChunk("");
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

export async function getHistory(limit = 10) {
  const { data } = await http.get("/api/qa/history", { params: { limit } });
  return data;
}
