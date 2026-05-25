import { createRouter, createWebHistory } from "vue-router";

const routes = [
  {
    path: "/",
    name: "Chat",
    component: () => import("../views/ChatView.vue"),
  },
  {
    path: "/documents",
    name: "Documents",
    component: () => import("../views/DocumentsView.vue"),
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;
