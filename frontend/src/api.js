import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8080",
  timeout: 30000
});

export async function uploadDocument(file) {
  const form = new FormData();
  form.append("file", file);
  const { data } = await http.post("/api/documents/upload", form, {
    headers: { "Content-Type": "multipart/form-data" }
  });
  return data;
}

export async function askQuestion(question) {
  const { data } = await http.post("/api/qa/ask", { question });
  return data;
}

export async function getHistory(limit = 10) {
  const { data } = await http.get("/api/qa/history", { params: { limit } });
  return data;
}
