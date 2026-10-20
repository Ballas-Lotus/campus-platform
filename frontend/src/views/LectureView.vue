<template>
  <div>
    <h2 class="page-title">🎤 讲座预约</h2>
    <p class="page-sub">
      报名制（<b>人数限额</b>，无选座，到场自选座位）。每场讲座在<b>开始前 7 天</b>开放报名；
      未开放的讲座可查看信息，届时自动开放。
    </p>

    <!-- 可预约 -->
    <h3 class="group-title">
      ✅ 可预约 <span class="count">{{ bookableList.length }} 场</span>
    </h3>
    <div class="lecture-list" v-loading="loading">
      <el-card v-for="l in bookableList" :key="l.id" shadow="hover" class="lecture-card">
        <div class="lecture-main">
          <div class="lecture-info">
            <div class="lecture-title">
              {{ l.title }}
              <el-tag v-if="isHot(l)" size="small" type="danger" effect="dark">热门</el-tag>
              <el-tag v-if="isToday(l)" size="small" type="warning" effect="dark">今天</el-tag>
            </div>
            <div class="lecture-meta">
              <span>👤 {{ l.speaker }}</span>
              <span>📍 {{ l.location }}</span>
              <span>🕐 {{ fmtDateTime(l.startTime) }}</span>
            </div>
          </div>
          <div class="lecture-progress">
            <el-progress
              type="dashboard"
              :percentage="Math.round((l.bookedCount / l.capacity) * 100)"
              :width="78"
              :stroke-width="7"
              :color="progressColor(l)"
            >
              <template #default="{ percentage }">
                <div class="pct">{{ percentage }}%</div>
                <div class="pct-sub">已报名</div>
              </template>
            </el-progress>
            <div class="remain" :class="{ warn: remain(l) < 50 }">
              剩余 {{ remain(l) }} / {{ l.capacity }} 人
            </div>
            <el-button
              type="primary"
              size="small"
              class="signup-btn"
              :disabled="remain(l) <= 0"
              :loading="signingId === l.id"
              @click="onSignup(l)"
            >
              {{ remain(l) <= 0 ? '已满' : '立即报名' }}
            </el-button>
          </div>
        </div>
      </el-card>
      <el-empty v-if="!loading && !bookableList.length" description="暂无可预约讲座" :image-size="60" />
    </div>

    <!-- 暂未开放 -->
    <h3 class="group-title muted">
      ⏳ 暂未开放预约 <span class="count">{{ upcomingList.length }} 场</span>
      <span class="hint">（开始前 7 天开放；未开放期间不展示预约情况）</span>
    </h3>
    <div class="lecture-list" v-loading="loading">
      <el-card v-for="l in upcomingList" :key="l.id" shadow="never" class="lecture-card upcoming">
        <div class="lecture-main">
          <div class="lecture-info">
            <div class="lecture-title">{{ l.title }}</div>
            <div class="lecture-meta">
              <span>👤 {{ l.speaker }}</span>
              <span>📍 {{ l.location }}</span>
              <span>🕐 {{ fmtDateTime(l.startTime) }}</span>
            </div>
          </div>
          <div class="open-info">
            <div class="open-label">开放预约时间</div>
            <div class="open-time">{{ fmtBookableFrom(l.bookableFrom) }}</div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLectures, signupLecture } from '../api/campus'

const lectures = ref([])
const loading = ref(false)
const signingId = ref(null)

async function onSignup(l) {
  await ElMessageBox.confirm(
    `确认报名「${l.title}」？\n${l.startTime.slice(0, 16)} · ${l.location}\n报名后请按时到场（未到场将影响后续预约）。`,
    '讲座报名',
    { type: 'info', confirmButtonText: '确认报名' }
  )
  signingId.value = l.id
  try {
    await signupLecture(l.id)
    ElMessage.success('报名成功，届时凭记录入场（到场自选座位）')
    await load()
  } catch (e) {
    // 失败原因（已满/冲突/未开放/重复）由拦截器提示
  } finally {
    signingId.value = null
  }
}

const bookableList = computed(() => lectures.value.filter((l) => l.bookable))
const upcomingList = computed(() => lectures.value.filter((l) => !l.bookable))

const remain = (l) => l.capacity - l.bookedCount
const isHot = (l) => l.bookedCount / l.capacity >= 0.9

function isToday(l) {
  const d = new Date(l.startTime.replace(' ', 'T'))
  const now = new Date()
  return d.toDateString() === now.toDateString()
}

function fmtDateTime(s) {
  return (s || '').slice(0, 16)
}

function fmtBookableFrom(s) {
  // "2026-10-15 19:00:00" → "10-15 19:00"
  return (s || '').slice(5, 16)
}

function progressColor(l) {
  const p = l.bookedCount / l.capacity
  if (p >= 0.9) return '#ef4444'
  if (p >= 0.7) return '#f59e0b'
  return '#10b981'
}

async function load() {
  loading.value = true
  try {
    lectures.value = await getLectures()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  margin: 4px 0 6px;
  font-size: 20px;
  color: #0f172a;
}

.page-sub {
  color: #64748b;
  font-size: 13px;
  margin-bottom: 18px;
}

.group-title {
  margin: 18px 0 12px;
  font-size: 15px;
  color: #334155;
}

.group-title.muted {
  color: #94a3b8;
}

.group-title .count {
  color: #2563eb;
  font-weight: 400;
  font-size: 13px;
  margin-left: 6px;
}

.group-title .hint {
  font-size: 12px;
  color: #cbd5e1;
  margin-left: 8px;
  font-weight: 400;
}

.lecture-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lecture-card {
  border-radius: 12px;
}

.lecture-card.upcoming {
  background: #fafbfc;
  border-style: dashed;
}

.lecture-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.lecture-title {
  font-weight: 600;
  font-size: 15px;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 8px;
}

.lecture-card.upcoming .lecture-title {
  color: #64748b;
}

.lecture-meta {
  display: flex;
  gap: 18px;
  margin-top: 8px;
  font-size: 12.5px;
  color: #64748b;
}

.lecture-progress {
  text-align: center;
}

.pct {
  font-size: 15px;
  font-weight: 700;
  color: #334155;
}

.pct-sub {
  font-size: 11px;
  color: #94a3b8;
}

.remain {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.remain.warn {
  color: #ef4444;
  font-weight: 600;
}

.signup-btn {
  margin-top: 8px;
  width: 100%;
}

.open-info {
  text-align: right;
  flex-shrink: 0;
}

.open-label {
  font-size: 11px;
  color: #94a3b8;
}

.open-time {
  font-size: 14px;
  font-weight: 600;
  color: #b45309;
  font-family: Consolas, monospace;
  margin-top: 2px;
}
</style>
