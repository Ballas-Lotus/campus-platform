<template>
  <div>
    <h2 class="page-title">👤 个人中心</h2>
    <p class="page-sub">个人资料 · 使用统计 · 当日日程（96 格位图反解）· 修改密码</p>

    <div class="top-grid">
      <!-- 资料卡 -->
      <el-card shadow="never" class="profile-card" v-loading="loading">
        <div class="pf-head">
          <el-avatar :size="56" class="pf-avatar">{{ profile.realName?.charAt(0) }}</el-avatar>
          <div class="pf-info">
            <div class="pf-name">
              {{ profile.realName }}
              <el-tag size="small" :type="profile.role === 'ADMIN' ? 'danger' : 'success'" effect="dark">
                {{ roleLabel(profile.role) }}
              </el-tag>
            </div>
            <div class="pf-meta">
              {{ profile.studentNo }}
              <span v-if="profile.college"> · {{ profile.college }}</span>
              <span v-if="profile.major"> · {{ profile.major }}</span>
              <span v-if="profile.grade"> · {{ profile.grade }} 级</span>
            </div>
            <div class="pf-meta2">
              <span>📱 {{ profile.phone }}</span>
              <span>✉️ {{ profile.email }}</span>
              <span v-if="profile.createTime">🗓 注册于 {{ profile.createTime }}</span>
            </div>
          </div>
          <el-button size="small" @click="openPwd">修改密码</el-button>
        </div>

        <el-alert
          v-if="profile.penaltyUntil"
          class="status-alert"
          type="error"
          :closable="false"
          show-icon
          :title="`当前被限制预约：${profile.penaltyReason}`"
          :description="`解禁时间 ${profile.penaltyUntil}（期间无法新建任何预约）`"
        />
        <el-alert
          v-else
          class="status-alert"
          type="success"
          :closable="false"
          show-icon
          title="预约状态正常（当前无禁约）"
        />
      </el-card>

      <!-- 统计卡 -->
      <el-card shadow="never" class="stats-card" v-loading="loading">
        <div class="stat-grid">
          <div class="stat">
            <div class="n">{{ profile.totalReservations ?? '—' }}</div>
            <div class="l">累计预约</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.activeReservations ?? '—' }}</div>
            <div class="l">进行中</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.completed ?? '—' }}</div>
            <div class="l">已完成</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.cancelled ?? '—' }}</div>
            <div class="l">已取消</div>
          </div>
          <div class="stat" :class="{ warn: profile.noShow > 0 }">
            <div class="n">{{ profile.noShow ?? '—' }}</div>
            <div class="l">爽约</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.courseCount ?? '—' }}</div>
            <div class="l">已选课程</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.courseCredits ?? '—' }}</div>
            <div class="l">学分</div>
          </div>
          <div class="stat">
            <div class="n">{{ profile.venueWeeklyUsed ?? '—' }}/{{ profile.venueWeeklyLimit ?? '—' }}</div>
            <div class="l">本周场馆</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 当日日程 -->
    <el-card shadow="never" class="sched-card" v-loading="schedLoading">
      <div class="sched-head">
        <span class="panel-title">📅 当日日程（预约 + 课表 合并时间轴）</span>
        <el-date-picker
          v-model="schedDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          :disabled-date="() => false"
          @change="loadSchedule"
        />
      </div>

      <el-empty v-if="!schedule.items?.length" description="当日暂无安排 —— 去预约一个时段或选一门课吧" />
      <el-timeline v-else class="timeline">
        <el-timeline-item
          v-for="(it, i) in schedule.items"
          :key="i"
          :timestamp="`${it.startTime} – ${it.endTime}`"
          placement="top"
          :color="itemColor(it)"
          :hollow="it.status === 'COMPLETED'"
        >
          <div class="sched-item">
            <el-tag size="small" :type="it.type === 'COURSE' ? 'primary' : 'success'" effect="plain">
              {{ it.type === 'COURSE' ? '课程' : typeLabel(it.resourceType) }}
            </el-tag>
            <b>{{ it.title }}</b>
            <span v-if="it.location" class="muted">📍 {{ it.location }}</span>
            <el-tag v-if="it.status" size="small" :type="statusTag(it.status)" effect="light">
              {{ statusLabel(it.status) }}
            </el-tag>
          </div>
        </el-timeline-item>
      </el-timeline>

      <div class="bitmap-hint" v-if="schedule.bitmap">
        当日占用位图（96 格 × 15 分钟 → 24 hex）：<code>{{ schedule.bitmap }}</code>
      </div>
    </el-card>

    <!-- 修改密码 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="420px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="原密码">
          <el-input v-model="pwd.oldPassword" type="password" show-password placeholder="当前使用的密码" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwd.newPassword" type="password" show-password placeholder="6~32 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwd.confirm" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" @click="submitPwd">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfile, getSchedule, changePassword } from '../api/user'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const loading = ref(false)
const profile = ref({})

const schedLoading = ref(false)
const schedule = ref({})
const schedDate = ref(new Date().toISOString().slice(0, 10))

const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function roleLabel(role) {
  return role === 'ADMIN' ? '管理员' : role === 'TEACHER' ? '教师' : '学生'
}

function typeLabel(t) {
  return { LIBRARY_SEAT: '图书馆', VENUE: '场馆', LECTURE: '讲座', COURSE: '课程' }[t] || '预约'
}

function statusLabel(s) {
  return { PENDING: '待处理', APPROVED: '已生效', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '爽约' }[s] || s
}

function statusTag(s) {
  return { APPROVED: 'success', COMPLETED: 'info', CANCELLED: 'info', NO_SHOW: 'danger', PENDING: 'warning' }[s] || 'info'
}

function itemColor(it) {
  if (it.type === 'COURSE') return '#6366f1'
  if (it.status === 'APPROVED') return '#10b981'
  if (it.status === 'COMPLETED') return '#94a3b8'
  return '#f59e0b'
}

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await getProfile()
  } finally {
    loading.value = false
  }
}

async function loadSchedule() {
  schedLoading.value = true
  try {
    schedule.value = await getSchedule(schedDate.value)
  } finally {
    schedLoading.value = false
  }
}

function openPwd() {
  pwd.oldPassword = ''
  pwd.newPassword = ''
  pwd.confirm = ''
  pwdVisible.value = true
}

async function submitPwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (pwd.newPassword.length < 6 || pwd.newPassword.length > 32) {
    ElMessage.warning('新密码长度需 6~32 位')
    return
  }
  if (pwd.newPassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    await changePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    if (userStore.info?.studentNo === 'test') {
      ElMessage.success('密码修改成功（测试账号：下次登录成功后密码会自动还原为 123456）')
    } else {
      ElMessage.success('密码修改成功，下次登录请使用新密码')
    }
    pwdVisible.value = false
  } catch {
    /* 业务错误提示由 request.js 统一弹出 */
  } finally {
    pwdLoading.value = false
  }
}

onMounted(() => {
  loadProfile()
  loadSchedule()
})
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
  margin-bottom: 16px;
}

.top-grid {
  display: grid;
  grid-template-columns: minmax(380px, 1.2fr) 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

@media (max-width: 900px) {
  .top-grid {
    grid-template-columns: 1fr;
  }
}

.profile-card,
.stats-card,
.sched-card {
  border-radius: 12px;
}

.pf-head {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.pf-avatar {
  background: #2563eb;
  font-size: 22px;
  flex-shrink: 0;
}

.pf-info {
  flex: 1;
  min-width: 0;
}

.pf-name {
  font-weight: 700;
  font-size: 17px;
  color: #0f172a;
  display: flex;
  align-items: center;
  gap: 8px;
}

.pf-meta {
  color: #475569;
  font-size: 13px;
  margin-top: 6px;
}

.pf-meta2 {
  color: #94a3b8;
  font-size: 12px;
  margin-top: 6px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.status-alert {
  margin-top: 14px;
  border-radius: 8px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.stat {
  text-align: center;
  padding: 10px 4px;
  background: #f8fafc;
  border-radius: 10px;
}

.stat .n {
  font-size: 19px;
  font-weight: 700;
  color: #2563eb;
  font-family: Consolas, monospace;
}

.stat.warn .n {
  color: #dc2626;
}

.stat .l {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}

.sched-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 10px;
}

.panel-title {
  font-weight: 600;
  color: #334155;
}

.timeline {
  padding-left: 4px;
}

.sched-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.sched-item b {
  color: #1e293b;
}

.muted {
  color: #94a3b8;
  font-size: 12.5px;
}

.bitmap-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
}

.bitmap-hint code {
  font-family: Consolas, monospace;
  color: #475569;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 6px;
}
</style>
