<template>
  <div class="app-shell">
    <section class="hero-banner">
      <h1>RAG API文档智能问答</h1>
      <p>面向 API 文档的上传、检索与问答系统</p>
    </section>

    <el-row :gutter="18" class="layout-row">
      <el-col :xs="24" :lg="10">
        <el-card class="panel" shadow="hover">
          <template #header>
            <div class="panel-header">
              <span>文档上传</span>
              <el-tag effect="plain" type="info">txt / md / pdf</el-tag>
            </div>
          </template>
          <el-upload
            :auto-upload="false"
            :show-file-list="true"
            :on-change="onFileChange"
            :limit="1"
          >
            <template #trigger>
              <el-button type="primary" plain>选择文件</el-button>
            </template>
          </el-upload>
          <p class="hint" v-if="selectedFileName">已选择：{{ selectedFileName }}</p>
          <div class="action-row">
            <el-button :loading="uploading" type="success" @click="handleUpload">上传文档</el-button>
          </div>
          <el-alert
            v-if="uploadResult"
            :title="uploadResult"
            type="success"
            :closable="false"
            show-icon
          />
        </el-card>

        <el-card class="panel question-panel" shadow="hover">
          <template #header>
            <div class="panel-header">
              <span>发起提问</span>
              <el-tag effect="plain">RAG</el-tag>
            </div>
          </template>
          <el-input
            v-model="question"
            type="textarea"
            :rows="4"
            resize="none"
            placeholder="请输入你的问题，例如：如何调用用户登录接口？"
          />
          <div class="action-row action-row-end">
            <el-button :loading="asking" type="primary" @click="handleAsk">发送问题</el-button>
            <el-button @click="clearQuestion">清空输入</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card class="panel answer-panel" shadow="hover">
          <template #header>
            <div class="panel-header">
              <span>回答结果</span>
              <el-tag v-if="answerData?.model" effect="plain" type="success">{{ answerData.model }}</el-tag>
            </div>
          </template>
          <el-empty v-if="!answerData" description="暂无回答，先上传文档并提问"></el-empty>
          <div v-else class="answer-box">
            <el-descriptions :column="1" border size="small" class="meta">
              <el-descriptions-item label="问题">{{ answerData.question }}</el-descriptions-item>
            </el-descriptions>
            <div class="answer-main">{{ answerData.answer }}</div>
            <el-collapse>
              <el-collapse-item title="查看检索上下文">
                <pre>{{ answerData.context }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-card>

        <el-card class="panel history-panel" shadow="hover">
          <template #header>
            <div class="panel-header">
              <span>最近历史（Top 5）</span>
              <el-button text type="primary" @click="loadHistory">刷新历史</el-button>
            </div>
          </template>
          <el-table :data="historyList" stripe>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="question" label="问题" />
            <el-table-column prop="model" label="模型" width="180" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { askQuestion, getHistory, uploadDocument } from "./api";

const selectedFile = ref(null);
const selectedFileName = ref("");
const question = ref("");
const answerData = ref(null);
const historyList = ref([]);
const uploading = ref(false);
const asking = ref(false);
const uploadResult = ref("");

function onFileChange(file) {
  if (!file?.raw) {
    return;
  }
  selectedFile.value = file.raw;
  selectedFileName.value = file.name;
}

async function handleUpload() {
  if (!selectedFile.value) {
    ElMessage.warning("请先选择一个文件");
    return;
  }
  try {
    uploading.value = true;
    const res = await uploadDocument(selectedFile.value);
    uploadResult.value = `上传成功，文档ID=${res.id}`;
    ElMessage.success("上传成功");
  } catch (e) {
    const msg = e?.response?.data?.detail || e.message || "上传失败";
    ElMessage.error(msg);
  } finally {
    uploading.value = false;
  }
}

async function handleAsk() {
  if (!question.value.trim()) {
    ElMessage.warning("问题不能为空");
    return;
  }
  try {
    asking.value = true;
    answerData.value = await askQuestion(question.value.trim());
    ElMessage.success("问答成功");
    await loadHistory();
  } catch (e) {
    const msg = e?.response?.data?.detail || e.message || "问答失败";
    ElMessage.error(msg);
  } finally {
    asking.value = false;
  }
}

async function loadHistory() {
  try {
    historyList.value = await getHistory(5);
  } catch (e) {
    const msg = e?.response?.data?.detail || e.message || "加载历史失败";
    ElMessage.error(msg);
  }
}

function clearQuestion() {
  question.value = "";
}

onMounted(() => {
  loadHistory();
});
</script>
