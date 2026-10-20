<template>
  <div>
    <h2 class="page-title">🏸 体育场馆</h2>
    <p class="page-sub">
      20 个场馆全部免费；<b>每人每周限 4 次</b>（取消回退次数）、<b>单次最长 3 小时</b>、<b>仅支持预约当天与明天</b>。
      开放时间 08:00–22:00 按小时预约；到场请签到，未签到将限制次日预约；
      <b>临近开始 30 分钟内取消将禁约 5 分钟</b>。
    </p>

    <el-radio-group v-model="typeFilter" class="filter" @change="load">
      <el-radio-button value="">全部</el-radio-button>
      <el-radio-button v-for="t in types" :key="t.value" :value="t.value">
        {{ t.icon }} {{ t.label }}
      </el-radio-button>
    </el-radio-group>

    <div class="venue-grid" v-loading="loading">
      <el-card v-for="v in venues" :key="v.id" shadow="hover" class="venue-card">
        <div class="venue-head">
          <span class="venue-icon">{{ typeIcon(v.venueType) }}</span>
          <span class="venue-name">{{ v.name }}</span>
        </div>
        <div class="venue-meta">
          <el-tag size="small" effect="plain">{{ v.campus }}</el-tag>
          <span class="price">{{ v.pricePerHour > 0 ? '￥' + v.pricePerHour + '/小时' : '免费' }}</span>
          <!-- 周次数上限为业务规则（后端 campus.reservation.venue-weekly-limit，用户维度每周 4 次） -->
          <span class="limit">每周限 4 次</span>
        </div>
        <el-button size="small" type="primary" class="btn" @click="openSlots(v)">
          选时段预约
        </el-button>
      </el-card>
    </div>

    <!-- 选时段预约弹窗 -->
    <el-dialog v-model="slotVisible" width="680px" destroy-on-close :title="current?.name">
      <TimeSlotGrid
        v-if="current"
        :resource-id="current.resourceId"
        :max-hours="3"
        title="选择时段（点击起始与结束格，一次 1~3 小时）"
        selectable
        @select="onSelect"
      />
      <template #footer>
        <div class="booking-bar">
          <span v-if="selection" class="picked">
            ✔ 已选 {{ selection.slotDate }} {{ selection.startTime }}–{{ selection.endTime }}（{{ selection.hours }} 小时）
          </span>
          <span v-else class="hint">在网格中点击起始格与结束格</span>
          <el-button type="primary" :disabled="!selection" :loading="booking" @click="submit">
            确认预约
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getVenues } from '../api/campus'
import { createReservation } from '../api/reservation'
import TimeSlotGrid from '../components/TimeSlotGrid.vue'

const types = [
  { value: 'BADMINTON', label: '羽毛球', icon: '🏸' },
  { value: 'BASKETBALL', label: '篮球', icon: '🏀' },
  { value: 'TENNIS', label: '网球', icon: '🎾' },
  { value: 'GYM', label: '健身', icon: '💪' },
  { value: 'SWIMMING', label: '游泳', icon: '🏊' }
]

const venues = ref([])
const loading = ref(false)
const typeFilter = ref('')
const slotVisible = ref(false)
const current = ref(null)
const selection = ref(null)
const booking = ref(false)

function onSelect(sel) {
  selection.value = sel
}

async function submit() {
  if (!selection.value || !current.value) return
  booking.value = true
  try {
    const r = await createReservation({
      resourceId: current.value.resourceId,
      slotDate: selection.value.slotDate,
      startTime: selection.value.startTime,
      endTime: selection.value.endTime
    })
    ElMessage.success(`预约成功：${r.resourceName} ${r.slotDate} ${r.startTime}-${r.endTime}`)
    slotVisible.value = false
  } catch (e) {
    // 失败原因（冲突/手慢了/超周限/禁约中）已由 axios 拦截器弹出
  } finally {
    booking.value = false
  }
}

function typeIcon(t) {
  return types.find((x) => x.value === t)?.icon || '🏟️'
}

async function load() {
  loading.value = true
  try {
    venues.value = await getVenues(typeFilter.value ? { venueType: typeFilter.value } : {})
  } finally {
    loading.value = false
  }
}

function openSlots(v) {
  current.value = v
  selection.value = null
  slotVisible.value = true
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
  margin-bottom: 16px;
}

.filter {
  margin-bottom: 16px;
}

.venue-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

.venue-card {
  border-radius: 12px;
}

.venue-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.venue-icon {
  font-size: 20px;
}

.venue-name {
  font-weight: 600;
  color: #1e293b;
  flex: 1;
}

.venue-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
  font-size: 12.5px;
  color: #64748b;
}

.price {
  color: #b45309;
  font-weight: 600;
}

.limit {
  color: #94a3b8;
}

.btn {
  width: 100%;
}

.booking-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.picked {
  color: #15803d;
  font-size: 13px;
}

.hint {
  color: #94a3b8;
  font-size: 12.5px;
}
</style>
