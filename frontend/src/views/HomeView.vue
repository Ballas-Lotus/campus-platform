<template>
  <div>
    <!-- 欢迎卡片 -->
    <el-card shadow="never" class="welcome" v-if="userStore.info">
      <div class="welcome-body">
        <div>
          <h2>{{ greeting }}，{{ userStore.info.realName }} 👋</h2>
          <p class="meta">
            <el-tag size="small" effect="plain">{{ userStore.info.studentNo }}</el-tag>
            <span v-if="userStore.info.college">{{ userStore.info.college }} · {{ userStore.info.major }}</span>
            <span v-if="userStore.info.grade">{{ userStore.info.grade }} 级</span>
          </p>
        </div>
        <el-button text type="info" @click="refresh">刷新</el-button>
      </div>
    </el-card>

    <!-- 四功能入口 -->
    <h3 class="section-title">功能入口</h3>
    <div class="entry-grid">
      <el-card
        v-for="entry in entries"
        :key="entry.name"
        shadow="hover"
        class="entry"
        :class="{ todo: entry.stage }"
        @click="onEntry(entry)"
      >
        <div class="entry-top">
          <div class="entry-icon">{{ entry.icon }}</div>
          <el-tag v-if="entry.stage" size="small" type="info" class="entry-tag">{{ entry.stage }} 上线</el-tag>
          <el-tag v-else size="small" type="success" class="entry-tag">可用</el-tag>
        </div>
        <div class="entry-name">{{ entry.name }}</div>
        <div class="entry-desc">{{ entry.desc }}</div>
        <div class="entry-stat" v-if="entryStat(entry)">{{ entryStat(entry) }}</div>
      </el-card>
    </div>

    <!-- 今日概览 -->
    <el-card shadow="never" class="today" v-if="stats">
      <div class="today-row">
        <div class="today-item">
          <div class="n">{{ stats.todayFreeSlots?.toLocaleString() }}</div>
          <div class="l">今日空闲座位片</div>
        </div>
        <div class="today-item">
          <div class="n">{{ stats.librarySeat?.toLocaleString() }}</div>
          <div class="l">图书馆座位</div>
        </div>
        <div class="today-item">
          <div class="n">{{ stats.venue }}</div>
          <div class="l">体育场馆</div>
        </div>
        <div class="today-item">
          <div class="n">{{ stats.lecture }}</div>
          <div class="l">讲座场次</div>
        </div>
      </div>
    </el-card>

  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { getOverviewStats, getLibraryAreas } from '../api/campus'

const router = useRouter()
const userStore = useUserStore()
const stats = ref(null)
const libraryAreas = ref([])

/** 图书馆整体今日空闲率（由各楼层汇总，与图书馆页同口径） */
const libraryRateText = computed(() => {
  const list = libraryAreas.value
  if (!list.length) return ''
  const total = list.reduce((s, a) => s + (a.totalSlots || 0), 0)
  const free = list.reduce((s, a) => s + (a.freeSlots || 0), 0)
  if (!total) return ''
  return `今日空闲率 ${Math.round((free / total) * 1000) / 10}%`
})

const entries = [
  { icon: '📚', name: '图书馆座位预约', desc: '3,000 座 · 逸夫楼 5 层 · 时段预约', path: '/library', stage: '', stat: () => libraryRateText.value },
  { icon: '🏸', name: '体育场馆预约', desc: '羽毛球 / 篮球 / 网球 / 健身 / 游泳', path: '/venue', stage: '', stat: (s) => `${s.venue} 个场馆` },
  { icon: '🎤', name: '讲座预约', desc: '人数限额报名 · 到场自选座位', path: '/lecture', stage: '', stat: (s) => `当前可预约 ${s.lectureBookable} 场讲座，${s.lectureUpcoming} 场讲座将开放预约` },
  { icon: '📖', name: '选课', desc: '200 门课程 · 人数限额先到先得 · 自动查冲突', path: '/course', stage: '' },
  { icon: '👤', name: '个人中心', desc: '资料统计 · 当日日程（位图反解）· 修改密码', path: '/profile', stage: '', stat: () => '预约与课表合并时间轴' }
]

function entryStat(entry) {
  return entry.stat && stats.value ? entry.stat(stats.value) || '' : ''
}

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

function onEntry(entry) {
  if (entry.path) {
    router.push(entry.path)
  } else {
    ElMessage.info(`「${entry.name}」将在 ${entry.stage} 交付，敬请期待`)
  }
}

async function refresh() {
  await userStore.fetchMe()
  ElMessage.success('已刷新')
}

onMounted(async () => {
  getOverviewStats().then((d) => (stats.value = d)).catch(() => {})
  getLibraryAreas().then((list) => (libraryAreas.value = list)).catch(() => {})
})
</script>

<style scoped>
.welcome {
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: linear-gradient(120deg, #eff6ff, #f0fdfa);
}

.welcome-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.welcome h2 {
  margin: 0 0 8px;
  color: #0f172a;
  font-size: 20px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #64748b;
  font-size: 13px;
  margin: 0;
}

.section-title {
  margin: 22px 0 12px;
  font-size: 15px;
  color: #334155;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.entry {
  border-radius: 12px;
  cursor: pointer;
  transition: transform 0.15s;
}

.entry:hover {
  transform: translateY(-3px);
}

.entry-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.entry-icon {
  font-size: 30px;
}

.entry-name {
  font-weight: 600;
  color: #1e293b;
  margin: 8px 0 4px;
}

.entry-desc {
  font-size: 12px;
  color: #94a3b8;
  min-height: 34px;
}

.entry-stat {
  font-size: 12px;
  color: #2563eb;
  margin-top: 4px;
  font-weight: 600;
}

.today {
  margin-top: 22px;
  border-radius: 12px;
}

.today-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.today-item {
  flex: 1;
  min-width: 110px;
  text-align: center;
  padding: 8px 4px;
  background: #f8fafc;
  border-radius: 10px;
}

.today-item .n {
  font-size: 20px;
  font-weight: 700;
  color: #2563eb;
  font-family: Consolas, monospace;
}

.today-item .l {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}

</style>
