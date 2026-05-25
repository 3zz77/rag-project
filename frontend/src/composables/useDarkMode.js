import { ref, watchEffect } from "vue";

const isDark = ref(false);

export function useDarkMode() {
  const stored = localStorage.getItem("theme");
  if (stored === "dark") {
    isDark.value = true;
  }

  watchEffect(() => {
    document.documentElement.setAttribute(
      "data-theme",
      isDark.value ? "dark" : "light"
    );
    localStorage.setItem("theme", isDark.value ? "dark" : "light");
  });

  function toggle() {
    isDark.value = !isDark.value;
  }

  return { isDark, toggle };
}
