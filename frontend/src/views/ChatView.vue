<template>
  <div class="chat-view">
    <div class="chat-main">
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
        >
          <div class="history-question">{{ h.question }}</div>
          <div class="history-meta">{{ h.model }} · {{ formatTime(h.createTime) }}</div>
        </div>
        <el-empty v-if="historyList.length === 0" description="暂无历史" :image-size="60" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from "vue";
import { ChatDotRound, UserFilled, Promotion, Refresh } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { marked } from "marked";
import { askQuestionStream, getHistory } from "../api";

const messages = ref([]);
const historyList = ref([]);
const input = ref("");
const streaming = ref(false);
const streamingText = ref("");
const messagesContainer = ref(null);

const suggestions = [
  "如何进行接口认证？",
  "返回的错误码有哪些？",
  "请求频率限制是多少？",
  "如何发起 POST 请求？",
];

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

  let contextData = "";
  let answerText = "";

  askQuestionStream(question, {
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
  });
}

async function loadHistory() {
  try {
    historyList.value = await getHistory(10);
  } catch (e) {
    // silent fail for history
  }
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
  // 规范化换行：3个以上换行压缩为2个，保证段落清晰
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
