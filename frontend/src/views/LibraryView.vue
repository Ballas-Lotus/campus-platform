<template>
  <div>
    <h2 class="page-title">📚 图书馆座位</h2>
    <p class="page-sub">
      逸夫楼 5 层、3,000 个座位，不限次预约；<b>仅支持预约当天与明天</b>。预约后请按时签到
      （开始前 30 分钟至开始后 15 分钟内），未签到将限制次日预约；<b>取消无惩罚</b>。
    </p>

    <!-- 馆区选择 -->
    <div class="area-grid" v-loading="loadingAreas">
      <el-card
        v-for="a in areas"
        :key="a.id"
        shadow="hover"
        class="area-card"
        :class="{ active: current?.id === a.id }"
        @click="selectArea(a)"
      >
        <div class="area-name">{{ a.name }}</div>
        <div class="area-meta">
          <el-tag size="small" effect="plain">{{ a.campus }}</el-tag>
          <span>{{ a.seatCount }} 座</span>
          <span class="time">{{ fmtTime(a.openTime) }}–{{ fmtTime(a.closeTime) }}</span>
        </div>
        <div class="area-rate">
          <span class="area-rate-label">今日空闲率</span>
          <el-progress
            :percentage="a.freeRate ?? 0"
            :stroke-width="6"
            :show-text="false"
            :color="rateColor(a)"
            style="flex: 1"
          />
          <span class="rate-text">{{ a.freeRate != null ? a.freeRate + '%' : '—' }}</span>
        </div>
      </el-card>
    </div>

    <!-- 座位列表 -->
    <template v-if="current">
      <el-card shadow="never" class="seat-panel">
        <div class="panel-head">
          <span class="panel-title">{{ current.name }} · 座位列表</span>
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next, total"
            small
            @current-change="loadSeats"
          />
        </div>
        <div class="seat-grid" v-loading="loadingSeats">
          <div v-for="s in seats" :key="s.id" class="seat" @click="openSlots(s)">
            <div class="seat-no">{{ s.name.split('-').pop() }}</div>
            <div class="seat-loc">{{ s.location }}</div>
          </div>
        </div>
        <p class="tip">点击任意座位 → 查看时间片 → 点击起始与结束格选择时段 → 确认预约（一次 1~4 小时；签到与取消规则见页首说明）。</p>
      </el-card>
    </template>

    <!-- 选时段预约弹窗 -->
    <el-dialog v-model="slotVisible" width="680px" destroy-on-close :title="slotSeat?.name">
      <TimeSlotGrid
        v-if="slotSeat"
        :resource-id="slotSeat.id"
        :max-hours="4"
        title="选择时段（点击起始与结束格，一次 1~4 小时）"
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
import { getLibraryAreas, getResources } from '../api/campus'
import { createReservation } from '../api/reservation'
import TimeSlotGrid from '../components/TimeSlotGrid.vue'

const areas = ref([])
const loadingAreas = ref(false)
const current = ref(null)
const seats = ref([])
const loadingSeats = ref(false)
const page = ref(1)
const pageSize = ref(24)
const total = ref(0)
const slotVisible = ref(false)
const slotSeat = ref(null)
const selection = ref(null)
const booking = ref(false)

function onSelect(sel) {
  selection.value = sel
}

async function submit() {
  if (!selection.value || !slotSeat.value) return
  booking.value = true
  try {
    const r = await createReservation({
      resourceId: slotSeat.value.id,
      slotDate: selection.value.slotDate,
      startTime: selection.value.startTime,
      endTime: selection.value.endTime
    })
    ElMessage.success(`预约成功：${r.resourceName} ${r.slotDate} ${r.startTime}-${r.endTime}`)
    slotVisible.value = false
    loadAreas() // 刷新各楼层今日空闲率
  } catch (e) {
    // 失败原因（冲突/手慢了/超配额）已由 axios 拦截器弹出
  } finally {
    booking.value = false
  }
}

const fmtTime = (t) => (t || '').slice(0, 5)

function rateColor(a) {
  const r = a.freeRate
  if (r == null) return '#cbd5e1'
  if (r >= 80) return '#10b981'
  if (r >= 60) return '#f59e0b'
  return '#ef4444'
}

async function loadAreas() {
  loadingAreas.value = true
  try {
    areas.value = await getLibraryAreas()
  } finally {
    loadingAreas.value = false
  }
}

async function selectArea(a) {
  current.value = a
  page.value = 1
  await loadSeats()
}

async function loadSeats() {
  if (!current.value) return
  loadingSeats.value = true
  try {
    // 座位资源 location 形如 "A馆3层A区"，用馆区名去掉"图书馆"前缀作为关键字
    const keyword = current.value.name.replace('图书馆', '')
    const data = await getResources({
      resourceType: 'LIBRARY_SEAT',
      keyword,
      pageNum: page.value,
      pageSize: pageSize.value
    })
    seats.value = data.list
    total.value = data.total
  } finally {
    loadingSeats.value = false
  }
}

function openSlots(seat) {
  slotSeat.value = seat
  selection.value = null
  slotVisible.value = true
}

onMounted(loadAreas)
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

.area-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.area-card {
  border-radius: 10px;
  cursor: pointer;
  border: 1.5px solid transparent;
}

.area-card.active {
  border-color: #2563eb;
  background: #eff6ff;
}

.area-name {
  font-weight: 600;
  color: #1e293b;
  font-size: 14px;
}

.area-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
}

.area-meta .time {
  color: #94a3b8;
}

.area-rate {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.area-rate-label {
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
}

.rate-text {
  font-size: 12px;
  color: #475569;
  font-family: Consolas, monospace;
  white-space: nowrap;
  min-width: 42px;
  text-align: right;
}

.seat-panel {
  margin-top: 16px;
  border-radius: 12px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.panel-title {
  font-weight: 600;
  color: #334155;
}

.seat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
}

.seat {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 8px 4px;
  text-align: center;
  cursor: pointer;
  transition: 0.15s;
}

.seat:hover {
  border-color: #2563eb;
  background: #eff6ff;
}

.seat-no {
  font-weight: 600;
  font-family: Consolas, monospace;
  color: #1e293b;
  font-size: 13px;
}

.seat-loc {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.tip {
  margin-top: 14px;
  font-size: 12px;
  color: #94a3b8;
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
