<template>
  <div class="page">
    <header class="nav">
      <div class="nav-left">
        <router-link to="/" class="nav-brand">🎓 校园综合服务平台</router-link>
        <nav class="nav-menu">
          <router-link v-for="m in menus" :key="m.path" :to="m.path" class="nav-item"
                       :class="{ active: isActive(m.path) }">
            {{ m.icon }} {{ m.name }}
          </router-link>
          <!-- 工程演示页（阶段 9 起逐阶段增加）收进下拉：主菜单只留业务入口，避免横向溢出 -->
          <el-dropdown trigger="click" @command="goDemo">
            <span class="nav-item demo-trigger" :class="{ active: isDemoActive }">
              🛠 工程演示 <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="d in demoMenus" :key="d.path" :command="d.path">
                  <span :class="{ 'demo-active': route.path.startsWith(d.path) }">
                    {{ d.icon }} {{ d.name }}
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </nav>
      </div>
      <div class="nav-right" v-if="userStore.info">
        <!-- 站内信铃铛（阶段 13：MQ 异步通知读取侧） -->
        <el-badge :value="unread" :hidden="!unread" :max="99" class="bell-badge">
          <span class="nav-bell" title="站内信" @click="openNotifications">🔔</span>
        </el-badge>
        <el-dropdown @command="onCommand">
          <span class="nav-user">
            <el-avatar :size="28" class="avatar">{{ userStore.info.realName?.charAt(0) }}</el-avatar>
            <span>{{ userStore.info.realName }}</span>
            <el-tag size="small" :type="userStore.info.role === 'ADMIN' ? 'danger' : 'success'" effect="dark">
              {{ userStore.info.role === 'ADMIN' ? '管理员' : '学生' }}
            </el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="main">
      <router-view />
    </main>

    <!-- 站内信抽屉 -->
    <el-drawer v-model="notifVisible" title="🔔 站内信" size="420px">
      <div class="notif-head">
        <span class="notif-count">{{ notifTotal }} 条 · 未读 {{ unread }}</span>
        <el-button size="small" text type="primary" :disabled="!unread" @click="readAll">全部已读</el-button>
      </div>
      <el-empty v-if="!notifications.length" description="暂无站内信 —— 预约或选课成功后会自动送达" />
      <div v-else class="notif-list">
        <div
          v-for="n in notifications"
          :key="n.id"
          class="notif-item"
          :class="{ unread: !n.isRead }"
          @click="readOne(n)"
        >
          <div class="notif-title">
            <span class="dot" v-if="!n.isRead"></span>{{ n.title }}
            <span class="notif-time">{{ n.createTime }}</span>
          </div>
          <div class="notif-content">{{ n.content }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menus = [
  { path: '/', name: '工作台', icon: '🏠' },
  { path: '/library', name: '图书馆座位', icon: '📚' },
  { path: '/venue', name: '体育场馆', icon: '🏸' },
  { path: '/lecture', name: '讲座', icon: '🎤' },
  { path: '/course', name: '选课', icon: '📖' },
  { path: '/reservations', name: '我的预约', icon: '📋' },
  { path: '/profile', name: '个人中心', icon: '👤' }
]

/** 工程演示（阶段 9~18）：架构与并发能力的现场观测页 */
const demoMenus = [
  { path: '/stress', name: '并发压测（8 场景）', icon: '⚡' },
  { path: '/prededuct', name: '预扣闸门（阶段 17）', icon: '🔥' },
  { path: '/cache', name: '缓存（阶段 16）', icon: '🚀' },
  { path: '/concurrency', name: '并发控制（阶段 18）', icon: '🔒' },
  { path: '/observatory', name: '齐射观测台（阶段 19）', icon: '🔭' },
  { path: '/mq', name: 'MQ 演示', icon: '📨' },
  { path: '/sharding', name: '分库分表', icon: '🧩' },
  { path: '/evolution', name: '演进对比', icon: '📈' }
]

const isDemoActive = computed(() => demoMenus.some((d) => route.path.startsWith(d.path)))

function goDemo(path) {
  router.push(path)
}

// ---------------- 站内信（阶段 13） ----------------
const unread = ref(0)
const notifVisible = ref(false)
const notifTotal = ref(0)
const notifications = ref([])
let unreadTimer = null

async function loadUnread() {
  try {
    const d = await getNotifications({ pageNum: 1, pageSize: 20 })
    unread.value = d.unread
    notifTotal.value = d.total
    notifications.value = d.list
  } catch {
    /* 登录态问题由拦截器处理 */
  }
}

function openNotifications() {
  notifVisible.value = true
  loadUnread()
}

async function readOne(n) {
  if (n.isRead) return
  try {
    await markNotificationRead(n.id)
    n.isRead = 1
    unread.value = Math.max(0, unread.value - 1)
  } catch {
    /* 忽略 */
  }
}

async function readAll() {
  try {
    await markAllNotificationsRead()
    notifications.value.forEach((n) => (n.isRead = 1))
    unread.value = 0
    ElMessage.success('已全部标记为已读')
  } catch {
    /* 忽略 */
  }
}

function isActive(path) {
  return path === '/' ? route.path === '/' : route.path.startsWith(path)
}

async function onCommand(cmd) {
  if (cmd !== 'logout') return
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(async () => {
  // 刷新场景：仅有 token 无详细信息时补拉 /auth/me
  if (userStore.token && (!userStore.info || !userStore.info.college)) {
    try {
      await userStore.fetchMe()
    } catch (e) {
      /* 拦截器会处理登录态失效 */
    }
  }
  // 站内信未读数：进入加载 + 每 60 秒刷新（MQ 异步通知）
  loadUnread()
  unreadTimer = setInterval(loadUnread, 60000)
})

onUnmounted(() => {
  if (unreadTimer) clearInterval(unreadTimer)
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f7fa;
}

.nav {
  height: 58px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 18px;
  min-width: 0;
}

.nav-brand {
  font-weight: 700;
  font-size: 16px;
  color: #1e293b;
  text-decoration: none;
  white-space: nowrap;
}

.nav-menu {
  display: flex;
  gap: 2px;
  /* 阶段 16~18 菜单变多：窄屏时横向滚动，避免末项被挤出屏幕 */
  overflow-x: auto;
  max-width: calc(100vw - 420px);
  scrollbar-width: none;
}

.nav-menu::-webkit-scrollbar {
  display: none;
}

.nav-item {
  text-decoration: none;
  color: #475569;
  font-size: 13px;
  padding: 6px 9px;
  border-radius: 8px;
  transition: 0.15s;
  white-space: nowrap;
  flex-shrink: 0;
}

.nav-item:hover {
  background: #f1f5f9;
}

.nav-item.active {
  background: #eff6ff;
  color: #2563eb;
  font-weight: 600;
}

/* 工程演示下拉触发点 */
.demo-trigger {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  outline: none;
}

.demo-active {
  color: #2563eb;
  font-weight: 600;
}

.nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #334155;
  outline: none;
}

.avatar {
  background: #2563eb;
  color: #fff;
  font-weight: 600;
}

.main {
  max-width: 1120px;
  margin: 0 auto;
  padding: 22px 20px 60px;
}

/* ---------- 站内信铃铛（阶段 13） ---------- */
.nav-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.nav-bell {
  font-size: 19px;
  cursor: pointer;
  line-height: 1;
  display: inline-block;
  transition: transform 0.15s;
}

.nav-bell:hover {
  transform: scale(1.15);
}

.bell-badge :deep(.el-badge__content) {
  z-index: 1;
}

.notif-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: #64748b;
}

.notif-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.notif-item {
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 10px 12px;
  background: #f8fafc;
  cursor: pointer;
  transition: box-shadow 0.15s;
}

.notif-item:hover {
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.08);
}

.notif-item.unread {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.notif-title {
  font-weight: 600;
  color: #1e293b;
  font-size: 13.5px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ef4444;
  flex-shrink: 0;
}

.notif-time {
  margin-left: auto;
  font-size: 11px;
  color: #94a3b8;
  font-weight: 400;
}

.notif-content {
  margin-top: 5px;
  font-size: 12.5px;
  color: #64748b;
  line-height: 1.7;
}
</style>
