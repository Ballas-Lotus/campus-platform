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
        { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '个人中心' } }
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
