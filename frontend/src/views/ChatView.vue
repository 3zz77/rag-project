<template>
  <div class="chat-view">
    <div class="chat-main">
      <!-- 多轮对话工具栏 -->
      <div class="chat-toolbar" v-if="messages.length > 0">
        <span></span>
        <el-button text size="small" type="primary" @click="newConversation">
          <el-icon><Plus /></el-icon> 新对话
        </el-button>
      </div>

      <div class="chat-messages" ref="messagesContainer">
        <div v-if="messages.length === 0" class="chat-empty">
          <div class="empty-icon">
            <el-icon :size="48"><ChatDotRound /></el-icon>
          </div>
          <h3>RAG API 文档智能问答</h3>
          <p>上传 API 文档后，向我提问任何关于文档的问题</p>
          <div class="suggestion-chips">
            <el-tag
              v-for="q in suggestions"
              :key="q"
              class="chip"
              @click="quickAsk(q)"
              effect="plain"
              type="info"
            >
              {{ q }}
            </el-tag>
          </div>
        </div>

        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="message-row"
          :class="msg.role"
        >
          <div class="message-avatar">
            <el-avatar
              :size="32"
              :icon="msg.role === 'user' ? UserFilled : ChatDotRound"
              :style="{ background: msg.role === 'user' ? '#6366f1' : '#10b981' }"
            />
          </div>
          <div class="message-body">
            <div class="message-role">{{ msg.role === 'user' ? '你' : 'AI 助手' }}</div>
            <div class="message-content" v-html="renderContent(msg.content)"></div>
            <div v-if="msg.context" class="message-context">
              <el-collapse>
                <el-collapse-item title="查看检索来源">
                  <pre>{{ msg.context }}</pre>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </div>

        <div v-if="streaming" class="message-row assistant">
          <div class="message-avatar">
            <el-avatar :size="32" :icon="ChatDotRound" :style="{ background: '#10b981' }" />
          </div>
          <div class="message-body">
            <div class="message-role">AI 助手 <span class="typing-dot">●</span></div>
            <div v-if="rewrittenQueryText" class="rewritten-query-hint">
              <el-icon :size="14"><Search /></el-icon>
              检索词：{{ rewrittenQueryText }}
            </div>
            <div class="message-content" v-html="renderContent(streamingText)"></div>
          </div>
        </div>
      </div>

      <div class="chat-input-area">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入你的问题，例如：这个 API 的认证方式是什么？"
          @keydown.enter.exact="handleSend"
          :disabled="streaming"
        />
        <div class="chat-input-actions">
          <span class="char-count">{{ input.length }}/2000</span>
          <el-button
            type="primary"
            :loading="streaming"
            :disabled="!input.trim() || streaming"
            @click="handleSend"
          >
            <el-icon><Promotion /></el-icon>
            发送
          </el-button>
        </div>
      </div>
    </div>

    <div class="chat-sidebar">
      <div class="sidebar-section">
        <h4>问答历史</h4>
        <el-button text size="small" @click="loadHistory">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
      <div class="history-list">
        <div
          v-for="h in historyList"
          :key="h.id"
          class="history-item"
          @click="showHistoryDetail(h)"
        >
          <div class="history-question">{{ h.question }}</div>
          <div class="history-meta">{{ h.model }} · {{ formatTime(h.createTime) }}</div>
        </div>
        <el-empty v-if="historyList.length === 0" description="暂无历史" :image-size="60" />
      </div>
      <!-- 历史分页 -->
      <div class="history-pagination" v-if="historyTotal > historyPageSize">
        <el-pagination
          small
          layout="prev, next"
          :page-size="historyPageSize"
          :total="historyTotal"
          :current-page="historyPage"
          @current-change="onHistoryPageChange"
        />
      </div>
    </div>

    <el-dialog
      v-model="historyDialogVisible"
      :title="historyDialogTitle"
      width="680px"
      top="5vh"
      destroy-on-close
    >
      <div class="history-detail-question">
        <span class="detail-label">问题</span>
        <p>{{ selectedHistory?.question }}</p>
      </div>
      <div class="history-detail-answer">
        <span class="detail-label">回答</span>
        <div class="message-content" v-html="renderContent(selectedHistory?.answer || '')"></div>
      </div>
      <div v-if="selectedHistory?.context" class="history-detail-context">
        <el-collapse>
          <el-collapse-item title="查看检索来源">
            <pre>{{ selectedHistory?.context }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from "vue";
import { ChatDotRound, UserFilled, Promotion, Refresh, Plus, Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { marked } from "marked";
import { askQuestionStream, getHistory } from "../api";

const messages = ref([]);
const historyList = ref([]);
const input = ref("");
const streaming = ref(false);
const streamingText = ref("");
const rewrittenQueryText = ref("");
const messagesContainer = ref(null);
const historyDialogVisible = ref(false);
const selectedHistory = ref(null);
const historyDialogTitle = ref("");
const conversationId = ref(generateId());

// 分页状态
const historyPage = ref(1);
const historyPageSize = 10;
const historyTotal = ref(0);

const suggestions = [
  "如何进行接口认证？",
  "返回的错误码有哪些？",
  "请求频率限制是多少？",
  "如何发起 POST 请求？",
];

function generateId() {
  return crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).substring(2);
}

function newConversation() {
  conversationId.value = generateId();
  messages.value = [];
  ElMessage.success("已开启新对话");
}

function quickAsk(q) {
  input.value = q;
  handleSend();
}

async function handleSend() {
  const question = input.value.trim();
  if (!question || streaming.value) return;
  if (question.length > 2000) {
    ElMessage.warning("问题长度不能超过 2000 字符");
    return;
  }

  messages.value.push({ role: "user", content: question });
  input.value = "";
  streaming.value = true;
  streamingText.value = "";
  rewrittenQueryText.value = "";

  let contextData = "";
  let answerText = "";

  askQuestionStream(question, {
    onConversationId(cid) {
      if (cid && cid !== conversationId.value) {
        conversationId.value = cid;
      }
    },
    onRewrittenQuery(q) {
      rewrittenQueryText.value = q;
    },
    onContext(data) {
      contextData = data;
    },
    onToken(token) {
      streamingText.value += token;
      nextTick(scrollToBottom);
    },
    onDone() {
      answerText = streamingText.value;
      messages.value.push({
        role: "assistant",
        content: answerText,
        context: contextData,
      });
      streamingText.value = "";
      streaming.value = false;
      nextTick(() => {
        scrollToBottom();
        loadHistory();
      });
    },
    onError(err) {
      ElMessage.error("请求失败: " + (err.message || "未知错误"));
      streaming.value = false;
      streamingText.value = "";
    },
  }, conversationId.value);
}

async function loadHistory() {
  try {
    const result = await getHistory(historyPage.value, historyPageSize);
    historyList.value = result.list || [];
    historyTotal.value = result.total || 0;
  } catch (e) {
    // silent fail for history
  }
}

function onHistoryPageChange(page) {
  historyPage.value = page;
  loadHistory();
}

function showHistoryDetail(h) {
  selectedHistory.value = h;
  historyDialogTitle.value = h.question.length > 30 ? h.question.substring(0, 30) + "..." : h.question;
  historyDialogVisible.value = true;
}

function scrollToBottom() {
  const el = messagesContainer.value;
  if (el) el.scrollTop = el.scrollHeight;
}

marked.setOptions({
  breaks: true,
  gfm: true,
});

function renderContent(text) {
  if (!text) return "";
  text = text.replace(/\n{3,}/g, '\n\n');
  return marked.parse(text);
}

function formatTime(time) {
  if (!time) return "";
  const d = new Date(time);
  return d.toLocaleDateString("zh-CN") + " " + d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

onMounted(loadHistory);
</script>

<style scoped>
.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 24px;
  background: var(--bg-tertiary);
  border-bottom: 1px solid var(--border-color);
  gap: 8px;
}
.rewritten-query-hint {
  font-size: 12px;
  color: var(--text-muted);
  padding: 4px 8px;
  margin-bottom: 4px;
  background: var(--accent-light);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: 4px;
}
.history-pagination {
  display: flex;
  justify-content: center;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--border-color);
}
</style>
