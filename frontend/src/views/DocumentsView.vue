<template>
  <div class="documents-view">
    <div class="page-header">
      <h2>文档管理</h2>
      <p>上传和管理 API 文档，支持 PDF、Word、TXT、Markdown 格式</p>
    </div>

    <el-row :gutter="24">
      <el-col :xs="24" :md="10">
        <div class="upload-card">
          <h4>上传新文档</h4>
          <el-upload
            class="upload-area"
            drag
            :auto-upload="false"
            :on-change="onFileChange"
            :limit="1"
            :show-file-list="false"
            accept=".pdf,.txt,.md,.doc,.docx"
          >
            <el-icon :size="40" color="#6366f1"><UploadFilled /></el-icon>
            <div class="upload-text">
              <p>拖拽文件到此处或点击选择</p>
              <span>支持 PDF / Word / TXT / MD，最大 50MB</span>
            </div>
          </el-upload>

          <div v-if="selectedFileName" class="file-info">
            <el-tag effect="plain" type="info">{{ selectedFileName }}</el-tag>
          </div>

          <el-button
            type="primary"
            size="large"
            :loading="uploading"
            :disabled="!selectedFile"
            @click="handleUpload"
            style="width: 100%; margin-top: 16px"
          >
            {{ uploading ? "上传中..." : "开始上传" }}
          </el-button>

          <el-alert
            v-if="uploadResult"
            :title="uploadResult"
            type="success"
            :closable="true"
            show-icon
            style="margin-top: 12px"
          />
        </div>
      </el-col>

      <el-col :xs="24" :md="14">
        <div class="doc-list-card">
          <div class="doc-list-header">
            <h4>已上传文档 ({{ totalDocuments }})</h4>
            <el-button text @click="loadDocuments">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>

          <el-table :data="documents" stripe v-loading="loading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="name" label="文件名" min-width="200" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'PDF' ? 'danger' : 'success'">
                  {{ row.type }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'completed' ? 'success' : row.status === 'failed' ? 'danger' : 'warning'"
                >
                  {{ row.status === 'completed' ? '已完成' : row.status === 'failed' ? '失败' : '处理中' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="fileSize" label="大小" width="100">
              <template #default="{ row }">
                {{ formatSize(row.fileSize) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button
                  text
                  type="danger"
                  size="small"
                  @click="handleDelete(row.id)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <div class="doc-pagination" v-if="totalDocuments > pageSize">
            <el-pagination
              v-model:current-page="currentPage"
              :page-size="pageSize"
              :total="totalDocuments"
              layout="total, prev, pager, next"
              @current-change="onPageChange"
            />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { UploadFilled, Refresh } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { uploadDocument, getDocuments, deleteDocument } from "../api";

const documents = ref([]);
const loading = ref(false);
const uploading = ref(false);
const selectedFile = ref(null);
const selectedFileName = ref("");
const uploadResult = ref("");

// 分页
const currentPage = ref(1);
const pageSize = 10;
const totalDocuments = ref(0);

function onFileChange(file) {
  if (!file?.raw) return;
  selectedFile.value = file.raw;
  selectedFileName.value = file.name;
}

async function handleUpload() {
  if (!selectedFile.value) {
    ElMessage.warning("请先选择文件");
    return;
  }
  try {
    uploading.value = true;
    uploadResult.value = "";
    const res = await uploadDocument(selectedFile.value);
    uploadResult.value = `上传成功 · 文档 ID: ${res.id}`;
    ElMessage.success("上传成功，文档已开始处理");
    selectedFile.value = null;
    selectedFileName.value = "";
    currentPage.value = 1;
    await loadDocuments();
  } catch (e) {
    const msg = e?.message || "上传失败";
    ElMessage.error(msg);
  } finally {
    uploading.value = false;
  }
}

async function loadDocuments() {
  try {
    loading.value = true;
    const result = await getDocuments(currentPage.value, pageSize);
    documents.value = result.list || [];
    totalDocuments.value = result.total || 0;
  } catch (e) {
    ElMessage.error("加载文档列表失败");
  } finally {
    loading.value = false;
  }
}

function onPageChange(page) {
  currentPage.value = page;
  loadDocuments();
}

async function handleDelete(id) {
  try {
    await ElMessageBox.confirm("确认删除此文档？相关的向量索引也将被清除。", "删除确认", {
      type: "warning",
    });
    await deleteDocument(id);
    ElMessage.success("文档已删除");
    await loadDocuments();
  } catch (e) {
    if (e !== "cancel") {
      ElMessage.error("删除失败");
    }
  }
}

function formatSize(bytes) {
  if (!bytes) return "-";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1048576).toFixed(1) + " MB";
}

onMounted(loadDocuments);
</script>

<style scoped>
.doc-pagination {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  padding-top: 12px;
}
</style>
