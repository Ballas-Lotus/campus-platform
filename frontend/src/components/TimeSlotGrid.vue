<template>
  <div class="tsg" v-loading="loading">
    <div class="tsg-head">
      <span class="tsg-title">{{ title }}</span>
      <el-date-picker
        v-model="date"
        type="date"
        size="small"
        :clearable="false"
        :disabled-date="disabledDate"
        @change="load"
        style="width: 150px"
      />
    </div>

    <div v-if="slots.length" class="tsg-grid">
      <div
        v-for="(s, i) in slots"
        :key="s.id"
        class="tsg-cell"
        :class="[cellClass(s), { selected: isSelected(i), selectable: selectable && !isPast(s) }]"
        :title="`${s.startTime}-${s.endTime}　容量 ${s.capacity}　已订 ${s.booked}`"
        @click="onCellClick(i)"
      >
        <div class="t">{{ s.startTime }}</div>
        <div class="s">{{ cellText(s) }}</div>
      </div>
    </div>
    <el-empty v-else description="该日期暂无时间片（可能超出 14 天滚动窗口）" :image-size="60" />

    <div class="tsg-bottom">
      <div class="tsg-legend">
        <span><i class="dot free"></i>空闲</span>
        <span><i class="dot partial"></i>部分占</span>
        <span><i class="dot full"></i>已满</span>
        <span v-if="selectable"><i class="dot sel"></i>已选时段</span>
      </div>
      <div v-if="selectable" class="tsg-hint">
        <template v-if="selStart === null">点击起始时段，再点结束时段</template>
        <template v-else-if="selEnd === null">已选起点 {{ slots[selStart]?.startTime }}，请点击结束时段</template>
        <template v-else>
          已选 {{ slots[selStart]?.startTime }} – {{ endTimeOf(selEnd) }}（{{ selEnd - selStart + 1 }} 小时）
          <el-button link type="info" size="small" @click="resetSelection">重选</el-button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getResourceSlots } from '../api/campus'

const props = defineProps({
  resourceId: { type: Number, required: true },
  title: { type: String, default: '时间片可用性' },
  /** 选择模式：两次点击选出连续时段并 emit('select', {startTime, endTime, hours}) */
  selectable: { type: Boolean, default: false },
  /** 单次预约时长上限（小时）：图书馆 4 / 体育馆 3（与后端一致） */
  maxHours: { type: Number, default: 4 }
})
const emit = defineEmits(['select'])

const slots = ref([])
const loading = ref(false)
const date = ref(new Date())
const selStart = ref(null)
const selEnd = ref(null)

/** 每分钟刷新一次"当前时间"，用于把当天已过去的时段置灰（避免点了已开始的时段才被拒绝） */
const nowTick = ref(0)
let timer = null

const nowHHmm = computed(() => {
  nowTick.value // 依赖触发
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})

/** 所看日期是否为今天 */
const isTodayView = computed(() => fmt(date.value) === fmt(new Date()))

/** 时段是否已开始（仅对"今天"有意义；字符串同格式可直接比较） */
function isPast(s) {
  return isTodayView.value && s.startTime <= nowHHmm.value
}

function fmt(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

function disabledDate(d) {
  // 2026-10-08 规则：图书馆/场馆仅支持预约当天与明天
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const max = new Date(today)
  max.setDate(max.getDate() + 1)
  return d < today || d > max
}

async function load() {
  loading.value = true
  resetSelection()
  try {
    slots.value = await getResourceSlots(props.resourceId, fmt(date.value))
  } finally {
    loading.value = false
  }
}

function resetSelection() {
  selStart.value = null
  selEnd.value = null
  emit('select', null)
}

function isSelected(i) {
  return selStart.value !== null && i >= selStart.value && i <= (selEnd.value ?? selStart.value)
}

function endTimeOf(i) {
  return slots.value[i]?.endTime
}

function cellClass(s) {
  if (isPast(s)) return 'past'
  if (s.status !== 1 || s.available <= 0) return 'full'
  if (s.booked > 0) return 'partial'
  return 'free'
}

function cellText(s) {
  if (isPast(s)) return '已过'
  if (s.available <= 0) return '已满'
  return s.capacity > 1 ? `余 ${s.available}` : '空闲'
}

function onCellClick(i) {
  if (!props.selectable) return
  const s = slots.value[i]
  if (isPast(s)) {
    ElMessage.warning('该时段已开始，不能预约')
    return
  }
  if (!s || s.status !== 1 || s.available <= 0) {
    ElMessage.warning('该时段已满，不能选择')
    return
  }
  if (selStart.value === null) {
    selStart.value = i
    selEnd.value = null
    return
  }
  if (selEnd.value === null) {
    const a = Math.min(selStart.value, i)
    const b = Math.max(selStart.value, i)
    if (b - a + 1 > props.maxHours) {
      ElMessage.warning(`单次预约最长 ${props.maxHours} 小时`)
      return
    }
    for (let k = a; k <= b; k++) {
      if (slots.value[k].available <= 0) {
        ElMessage.warning('所选区间内包含已满时段，请重新选择')
        return
      }
    }
    selStart.value = a
    selEnd.value = b
    emit('select', {
      slotDate: fmt(date.value),
      startTime: slots.value[a].startTime,
      endTime: slots.value[b].endTime,
      hours: b - a + 1
    })
    return
  }
  // 第三次点击：重新开始
  selStart.value = i
  selEnd.value = null
  emit('select', null)
}

watch(() => props.resourceId, load)
onMounted(() => {
  load()
  timer = setInterval(() => nowTick.value++, 60_000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.tsg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.tsg-title {
  font-weight: 600;
  color: #334155;
  font-size: 14px;
}

.tsg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
  gap: 8px;
}

.tsg-cell {
  border-radius: 8px;
  padding: 8px 6px;
  text-align: center;
  border: 1px solid transparent;
}

.tsg-cell.selectable {
  cursor: pointer;
}

.tsg-cell.selectable:hover {
  filter: brightness(0.96);
}

.tsg-cell .t {
  font-size: 13px;
  font-weight: 600;
  font-family: Consolas, monospace;
}

.tsg-cell .s {
  font-size: 11px;
  margin-top: 2px;
}

.tsg-cell.free {
  background: #f0fdf4;
  border-color: #bbf7d0;
  color: #15803d;
}

.tsg-cell.partial {
  background: #fffbeb;
  border-color: #fde68a;
  color: #b45309;
}

.tsg-cell.full {
  background: #f1f5f9;
  border-color: #e2e8f0;
  color: #94a3b8;
}

/* 当天已过去的时段：置灰（不可选） */
.tsg-cell.past {
  background: #f8fafc;
  border-color: #f1f5f9;
  color: #cbd5e1;
  text-decoration: line-through;
}

.tsg-cell.selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.25);
}

.tsg-bottom {
  margin-top: 12px;
}

.tsg-legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #64748b;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 4px;
  vertical-align: -1px;
}

.dot.free { background: #4ade80; }
.dot.partial { background: #fbbf24; }
.dot.full { background: #cbd5e1; }
.dot.sel { background: #2563eb; }

.tsg-hint {
  margin-top: 8px;
  font-size: 12.5px;
  color: #2563eb;
}
</style>
