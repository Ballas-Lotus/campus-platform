import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录' }
    },
    {
      path: '/',
      component: () => import('../components/AppLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: '工作台' } },
        { path: 'library', name: 'library', component: () => import('../views/LibraryView.vue'), meta: { title: '图书馆座位' } },
        { path: 'venue', name: 'venue', component: () => import('../views/VenueView.vue'), meta: { title: '体育场馆' } },
        { path: 'lecture', name: 'lecture', component: () => import('../views/LectureView.vue'), meta: { title: '讲座' } },
        { path: 'course', name: 'course', component: () => import('../views/CourseView.vue'), meta: { title: '选课' } },
        { path: 'reservations', name: 'reservations', component: () => import('../views/MyReservationsView.vue'), meta: { title: '我的预约' } },
        { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '个人中心' } },
        { path: 'stress', name: 'stress', component: () => import('../views/StressView.vue'), meta: { title: '并发压测' } },
        { path: 'mq', name: 'mq', component: () => import('../views/MqView.vue'), meta: { title: 'MQ 演示' } },
        { path: 'sharding', name: 'sharding', component: () => import('../views/ShardingView.vue'), meta: { title: '分库分表' } },
        { path: 'evolution', name: 'evolution', component: () => import('../views/EvolutionView.vue'), meta: { title: '演进对比' } },
        { path: 'cache', name: 'cache', component: () => import('../views/CacheView.vue'), meta: { title: '缓存' } },
        { path: 'prededuct', name: 'prededuct', component: () => import('../views/PreDeductView.vue'), meta: { title: '预扣闸门' } },
        { path: 'concurrency', name: 'concurrency', component: () => import('../views/ConcurrencyView.vue'), meta: { title: '并发控制' } },
        { path: 'observatory', name: 'observatory', component: () => import('../views/ObservatoryView.vue'), meta: { title: '齐射观测台' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

/** 全局守卫：未登录 → /login；已登录访问 /login → / */
router.beforeEach((to) => {
  const user = useUserStore()
  if (to.path !== '/login' && !user.isLoggedIn) {
    return { path: '/login' }
  }
  if (to.path === '/login' && user.isLoggedIn) {
    return { path: '/' }
  }
  document.title = `${to.meta.title || ''} · 校园综合服务平台`
})

export default router
