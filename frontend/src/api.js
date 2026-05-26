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
      }

      function processChunk(chunk) {
        buffer += chunk;
        // SSE events are separated by blank lines (double newline)
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
                // Process remaining incomplete event
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

export async function getHistory(limit = 10) {
  const { data } = await http.get("/api/qa/history", { params: { limit } });
  return data;
}
