<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => String(route.meta.title || 'RoadMind Agent'))
</script>

<template>
  <div class="app-shell cockpit-shell">
    <aside class="side-nav" aria-label="主导航">
      <div class="brand-lockup" aria-label="RoadMind">
        <div class="brand-mark"><span class="brand-orbit"></span><span>R</span></div>
        <strong>RoadMind</strong>
      </div>
      <nav>
        <RouterLink class="nav-item" to="/agent" active-class="active">
          <span class="nav-icon">◉</span><span>Agent</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/plan" active-class="active"><span class="nav-icon">⌘</span><span>计划</span></RouterLink>
        <RouterLink class="nav-item" to="/trip" active-class="active"><span class="nav-icon">▣</span><span>行程</span></RouterLink>
        <RouterLink class="nav-item" to="/vehicle" active-class="active">
          <span class="nav-icon">◇</span><span>车辆</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/automation" active-class="active">
          <span class="nav-icon">⌘</span><span>自动化</span>
        </RouterLink>
        <a class="nav-item disabled" aria-disabled="true"><span class="nav-icon">▤</span><span>审计</span></a>
      </nav>
      <div class="nav-spacer"></div>
      <a class="nav-item disabled" aria-disabled="true"><span class="nav-icon">⚙</span><span>设置</span></a>
      <p class="side-disclaimer">车辆能力为数字孪生模拟<br />不接入真实汽车</p>
    </aside>

    <div class="cockpit-stage">
      <header class="top-bar cockpit-topbar">
        <div class="vehicle-selector"><span class="selector-car">RM</span><strong>RoadMind 演示车辆</strong><span>⌄</span></div>
        <div class="environment-pill"><span class="status-dot"></span>模拟环境</div>
        <div class="top-title"><small>ROADMind Agent</small><strong>{{ title }}</strong></div>
        <div class="system-actions"><span>♧</span><span>⌁</span><span>♢</span><b>RM</b><i></i><small>在线</small></div>
      </header>

      <main class="main-stage">
        <RouterView />
      </main>

      <footer class="cabin-dock" aria-label="座舱状态栏">
        <div class="dock-cluster"><span>⌂</span><span>▦</span><b>23.5°</b><span>♨</span></div>
        <div class="app-shortcuts"><span>➤</span><span>♫</span><span>☎</span><span>⌂</span></div>
        <div class="now-playing"><span class="wave-icon">≋</span><div><strong>轻松出行，智在掌控</strong><small>RoadMind</small></div><i></i><span>◀</span><span>▶</span><span>▶|</span><span>◕</span><b>23.5°</b></div>
      </footer>
    </div>
  </div>
</template>
