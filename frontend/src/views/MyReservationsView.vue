<template>
  <div>
    <h2 class="page-title">📋 我的预约</h2>
    <p class="page-sub">
      图书馆座位与体育场馆预约。<b>签到</b>：开始前 30 分钟至开始后 15 分钟内可签到，未签到将禁约 24 小时。
      <b>取消</b>：图书馆取消无惩罚；体育馆临近开始 30 分钟内取消将禁约 5 分钟。
    </p>

    <el-radio-group v-model="status" class="filter" @change="search">
      <el-radio-button value="">全部</el-radio-button>
      <el-radio-button value="PENDING">待审批</el-radio-button>
      <el-radio-button value="APPROVED">已批准</el-radio-button>
      <el-radio-button value="COMPLETED">已完成</el-radio-button>
      <el-radio-button value="CANCELLED">已取消</el-radio-button>
      <el-radio-button value="NO_SHOW">爽约</el-radio-button>
    </el-radio-group>

    <el-card shadow="never" class="table-card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="日期" width="110">
          <template #default="{ row }">{{ row.slotDate }}</template>
        </el-table-column>
        <el-table-column label="时段" width="130">
          <template #default="{ row }">{{ row.startTime }} – {{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="resourceName" label="资源" min-width="180" show-overflow-tooltip />
        <el-table-column prop="resourceLocation" label="位置" width="120" />
        <el-table-column prop="resourceType" label="类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.resourceType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
            <el-tag v-if="row.checkinTime" size="small" type="success" effect="plain" style="margin-left: 4px">
              已签到
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canCheckin(row)"
              link
              type="success"
              @click="onCheckin(row)"
            >签到</el-button>
            <el-button
              v-if="canCancel(row)"
              link
              type="danger"
              @click="onCancel(row)"
            >取消</el-button>
            <span v-if="!canCheckin(row) && !canCancel(row)" class="none">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyReservations, cancelReservation, checkinReservation } from '../api/reservation'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const status = ref('')
const pageNum = ref(1)
const pageSize = ref(10)

const typeLabel = (t) =>
  ({ LIBRARY_SEAT: '图书馆座位', VENUE: '体育场馆', LECTURE: '讲座', LECTURE_SEAT: '讲座', COURSE_QUOTA: '课程名额' })[t] || t

const statusLabel = (s) =>
  ({ PENDING: '待审批', APPROVED: '已批准', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '爽约' })[s] || s

const statusTag = (s) =>
  ({ PENDING: 'warning', APPROVED: 'success', COMPLETED: 'info', CANCELLED: 'info', NO_SHOW: 'danger' })[s] || 'info'

function startDate(row) {
  return new Date(`${row.slotDate}T${row.startTime}:00`)
}

function canCancel(row) {
  if (row.status !== 'PENDING' && row.status !== 'APPROVED') return false
  // 开始前才能取消
  return startDate(row) > new Date()
}

/** 签到窗口：开始前 30 分钟 ~ 开始后 15 分钟（与服务端 campus.reservation.* 配置一致） */
function canCheckin(row) {
  if (row.status !== 'APPROVED' || row.checkinTime) return false
  const now = new Date()
  const start = startDate(row)
  return now >= new Date(start.getTime() - 30 * 60 * 1000)
      && now <= new Date(start.getTime() + 15 * 60 * 1000)
}

function search() {
  pageNum.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const data = await getMyReservations({
      status: status.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function onCheckin(row) {
  await checkinReservation(row.id)
  ElMessage.success('签到成功，请按时使用')
  load()
}

async function onCancel(row) {
  await ElMessageBox.confirm(
    `确定取消这一条预约吗？\n${row.slotDate} ${row.startTime}-${row.endTime} ${row.resourceName}`,
    '取消预约',
    { type: 'warning' }
  )
  await cancelReservation(row.id)
  ElMessage.success('已取消，库存已释放')
  load()
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
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.table-card {
  border-radius: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.none {
  color: #cbd5e1;
}
</style>
