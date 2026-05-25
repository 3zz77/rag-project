<template>
  <header class="app-header">
    <div class="header-left">
      <router-link to="/" class="logo">
        <el-icon :size="22"><Document /></el-icon>
        <span>RAG DocQA</span>
      </router-link>
      <nav class="nav-links">
        <router-link to="/" active-class="nav-active">智能问答</router-link>
        <router-link to="/documents" active-class="nav-active">文档管理</router-link>
      </nav>
    </div>
    <div class="header-right">
      <el-switch
        v-model="isDark"
        :active-icon="Moon"
        :inactive-icon="Sunny"
        inline-prompt
      />
    </div>
  </header>
</template>

<script setup>
import { Document, Moon, Sunny } from "@element-plus/icons-vue";
import { useDarkMode } from "../composables/useDarkMode";

const { isDark } = useDarkMode();
</script>
