import { createRouter, createWebHistory } from 'vue-router'
import AgentView from '@/views/AgentView.vue'
import VehicleView from '@/views/VehicleView.vue'
import PlanView from '@/views/PlanView.vue'
import TripView from '@/views/TripView.vue'
import AutomationView from '@/views/AutomationView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/agent',
    },
    {
      path: '/agent',
      name: 'agent',
      component: AgentView,
      meta: { title: 'Agent 出行工作台' },
    },
    {
      path: '/plan', name: 'plan', component: PlanView, meta: { title: '执行计划与安全确认' },
    },
    {
      path: '/trip', name: 'trip', component: TripView, meta: { title: '实时行程' },
    },
    {
      path: '/vehicle',
      name: 'vehicle',
      component: VehicleView,
      meta: { title: '车辆数字孪生' },
    },
    {
      path: '/automation',
      name: 'automation',
      component: AutomationView,
      meta: { title: '偏好与定时任务' },
    },
  ],
})

export default router
