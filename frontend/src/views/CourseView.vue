<template>
  <div>
    <h2 class="page-title">📖 课程与选课</h2>
    <p class="page-sub">
      8 学院 × 25 门 · 300 个教学班 ·
      <b>人数限额先到先得</b>（满员直接拒绝，无候补）· 选课时自动检测时间冲突
    </p>

    <el-tabs v-model="tab">
      <!-- ==================== 课程列表 ==================== -->
      <el-tab-pane label="课程列表" name="list">
        <div class="filters">
          <el-select v-model="query.college" placeholder="全部学院" clearable style="width: 170px" @change="search">
            <el-option v-for="c in colleges" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select v-model="query.courseType" placeholder="全部类型" clearable style="width: 120px" @change="search">
            <el-option label="必修" value="必修" />
            <el-option label="选修" value="选修" />
            <el-option label="通识" value="通识" />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="课程名称 / 代码"
            clearable
            style="width: 220px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" @click="search">查询</el-button>
        </div>

        <el-card shadow="never" class="table-card">
          <el-table :data="courses" v-loading="loading" stripe>
            <el-table-column prop="courseCode" label="代码" width="90" />
            <el-table-column prop="name" label="课程名称" min-width="190">
              <template #default="{ row }">
                <span>{{ row.name }}</span>
                <el-tag v-if="row.mySelected" size="small" type="success" class="sel-tag" effect="dark">
                  已选 · {{ row.myClassName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="teacher" label="教师" width="100" />
            <el-table-column prop="credits" label="学分" width="70" align="center">
              <template #default="{ row }">{{ row.credits }}</template>
            </el-table-column>
            <el-table-column prop="courseType" label="类型" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="typeTag(row.courseType)" effect="plain">{{ row.courseType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="college" label="开课学院" width="130" />
            <el-table-column label="教学班" width="80" align="center">
              <template #default="{ row }">{{ row.classCount }}</template>
            </el-table-column>
            <el-table-column label="名额情况" min-width="180">
              <template #default="{ row }">
                <div class="capacity">
                  <el-progress
                    :percentage="liveAgg(row).capacity ? Math.round((liveAgg(row).selected / liveAgg(row).capacity) * 100) : 0"
                    :stroke-width="10"
                    :color="capColor(liveAgg(row).selected, liveAgg(row).capacity)"
                    :show-text="false"
                    style="flex: 1"
                  />
                  <span class="cap-text">{{ liveAgg(row).selected }}/{{ liveAgg(row).capacity }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="118" align="center">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="openClasses(row)">查看教学班</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="query.pageNum"
              v-model:page-size="query.pageSize"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @current-change="load"
              @size-change="search"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 我的课表 ==================== -->
      <el-tab-pane name="mine">
        <template #label>
          <span>我的课表</span>
          <el-badge v-if="myCourses.length" :value="myCourses.length" class="tab-badge" />
        </template>

        <el-card shadow="never" v-loading="myLoading">
          <el-empty v-if="!myCourses.length" description="本学期还没有选课 —— 去「课程列表」挑几门吧" />

          <template v-else>
            <div class="my-summary">
              共 <b>{{ myCourses.length }}</b> 门课程 · <b>{{ totalCredits }}</b> 学分 ·
              <el-tag size="small" type="success" effect="plain">选课时已自动检测：无时间冲突</el-tag>
            </div>

            <!-- 周课表（全部格子显式定位，避免自动流式被课程块挤位串格） -->
            <div class="timetable">
              <div class="tt-cell tt-head" style="grid-column: 1; grid-row: 1"></div>
              <div
                v-for="d in 7"
                :key="'h' + d"
                class="tt-cell tt-head"
                :style="{ gridColumn: d + 1, gridRow: 1 }"
              >{{ weekNames[d] }}</div>
              <template v-for="s in maxSection" :key="'r' + s">
                <div class="tt-cell tt-label" :style="{ gridColumn: 1, gridRow: s + 1 }">第{{ s }}节</div>
                <div
                  v-for="d in 7"
                  :key="d + '-' + s"
                  class="tt-cell tt-slot"
                  :style="{ gridColumn: d + 1, gridRow: s + 1 }"
                ></div>
              </template>
              <div v-for="(c, i) in myCourses" :key="c.id" class="tt-course" :style="blockStyle(c, i)">
                <div class="ttc-name">{{ c.courseName }}</div>
                <div class="ttc-meta">{{ c.location }}</div>
              </div>
            </div>

            <!-- 明细表 -->
            <el-table :data="myCourses" stripe class="my-table">
              <el-table-column label="课程" min-width="160">
                <template #default="{ row }">
                  <b>{{ row.courseName }}</b>
                  <span class="code">（{{ row.courseCode }}）</span>
                </template>
              </el-table-column>
              <el-table-column prop="className" label="教学班" width="80" align="center" />
              <el-table-column prop="teacher" label="教师" width="100" />
              <el-table-column label="学分" width="70" align="center">
                <template #default="{ row }">{{ row.credits }}</template>
              </el-table-column>
              <el-table-column label="上课时间" min-width="150">
                <template #default="{ row }">{{ fmtMyTime(row) }}</template>
              </el-table-column>
              <el-table-column prop="location" label="地点" width="140" />
              <el-table-column label="操作" width="90" align="center">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain @click="onDropMy(row)">退课</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 教学班抽屉 ==================== -->
    <el-drawer v-model="drawer" size="640px">
      <template #header>
        <div class="drawer-head">
          <span>{{ drawerTitle }}</span>
          <el-tag size="small" :type="connTag.type" effect="plain">{{ connTag.text }}</el-tag>
        </div>
      </template>
      <div v-loading="classLoading" class="class-list">
        <el-empty v-if="!classLoading && !classes.length" description="该课程暂无教学班" />
        <div
          v-for="cls in classes"
          :key="cls.id"
          class="class-card"
          :class="{ 'is-mine': cls.myStatus === 'SELECTED' }"
        >
          <div class="cc-left">
            <div class="cc-title">
              {{ cls.className }}
              <el-tag v-if="cls.myStatus === 'SELECTED'" size="small" type="success">已选</el-tag>
              <el-tag v-else-if="taskActive(cls)" size="small" type="info" effect="plain">选课中…</el-tag>
              <el-tag v-else-if="isFull(cls)" size="small" type="danger" effect="plain">已满</el-tag>
            </div>
            <div class="cc-line">🕐 {{ fmtClassTime(cls) }} · 第 {{ cls.startWeek }}-{{ cls.endWeek }} 周</div>
            <div class="cc-line">👩🏫 {{ cls.teacher }}　📍 {{ cls.location }}</div>
            <div class="cc-cap">
              <el-progress
                :percentage="cls.capacity ? Math.round((liveSelected(cls) / cls.capacity) * 100) : 0"
                :stroke-width="8"
                :color="capColor(liveSelected(cls), cls.capacity)"
                :show-text="false"
                style="flex: 1"
              />
              <span class="cap-text" :class="{ 'cap-flash': isCorrected(cls.id) }">
                {{ liveSelected(cls) }}/{{ cls.capacity }}（余 {{ liveRemain(cls) }}）
              </span>
            </div>
          </div>
          <div class="cc-right">
            <el-button
              v-if="cls.myStatus === 'SELECTED'"
              size="small"
              type="danger"
              plain
              @click="onDrop(cls)"
            >退课</el-button>
            <el-button v-else-if="taskActive(cls)" size="small" disabled>选课中…</el-button>
            <el-button v-else-if="isFull(cls)" size="small" disabled>已满</el-button>
            <el-button v-else size="small" type="primary" @click="onSelect(cls)">选课</el-button>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- ==================== 选课任务队列（阶段 21.1 · 回执确认制） ==================== -->
    <!-- 提交后先入队；班级卡片不提前标"已选"，回执到达后才提示结果并刷新 -->
    <div v-if="selectTasks.hasTasks" class="task-panel">
      <div class="task-head">
        <span>🧾 选课任务<em v-if="selectTasks.pendingCount">（{{ selectTasks.pendingCount }} 项处理中）</em></span>
        <el-button link size="small" @click="selectTasks.clearFinished()">清空已完成</el-button>
      </div>
      <div
        v-for="t in selectTasks.tasks"
        :key="t.id"
        class="task-row"
        :class="'is-' + t.status"
      >
        <span class="task-name" :title="`《${t.courseName}》${t.className}`">《{{ t.courseName }}》{{ t.className }}</span>
        <span class="task-state">
          <template v-if="t.status === 'queued'">排队中…</template>
          <template v-else-if="t.status === 'submitting'">提交中…</template>
          <template v-else-if="t.status === 'accepted'">已受理，等待确认…</template>
          <template v-else-if="t.status === 'unknown'">结果确认中，请稍后刷新</template>
          <template v-else-if="t.status === 'success'">✅ 已选上</template>
          <template v-else-if="t.status === 'failed'">❌ 未选上：{{ t.reason }}</template>
        </span>
        <el-button
          v-if="t.status === 'success' || t.status === 'failed'"
          link
          size="small"
          class="task-close"
          @click="selectTasks.dismiss(t.id)"
        >×</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCourses, getCourseClasses, selectCourse, dropCourse, getMyCourses } from '../api/campus'
import { useStockStore } from '../stores/stock'
import { useSelectTasksStore } from '../stores/selectTasks'

const colleges = [
  '计算机学院', '机械工程学院', '外国语学院', '经济管理学院',
  '数学学院', '物理学院', '文学院', '医学院'
]
const weekNames = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

const tab = ref('list')

/** 阶段 20：名额实时同步（SSE 信号 + 乐观渲染 + 防抖校准；写路径未改动） */
const stock = useStockStore()
/** 阶段 21.1：选课任务队列（回执确认制）——提交只入队，确认后才算"已选" */
const selectTasks = useSelectTasksStore()

const connTag = computed(() => {
  if (stock.conn === 'open') return { type: 'success', text: '⚡ 实时同步' }
  if (stock.conn === 'fallback') return { type: 'warning', text: '轮询校准中' }
  return { type: 'info', text: '连接中…' }
})

/** 实时余量：有实时值用实时值，否则回退到快照值（remain 仅用于展示，判定以服务端为准） */
function liveRemain(cls) {
  const r = stock.remainOf(cls.id)
  return r == null ? cls.available : r
}

function liveSelected(cls) {
  const r = stock.remainOf(cls.id)
  return r == null ? cls.selected : Math.max(0, (cls.capacity || 0) - r)
}

function isFull(cls) {
  const r = stock.remainOf(cls.id)
  return r == null ? cls.selected >= cls.capacity : r <= 0
}

/** 阶段 21.1：该教学班是否有未终结的选课任务（排队/提交中/已受理/确认中） */
function taskActive(cls) {
  return !!selectTasks.activeOf(cls.id)
}

/** 课程列表聚合：该课程班级已全量加载过时用实时值，否则用快照 */
function liveAgg(row) {
  return stock.courseAgg(row.id) || { selected: row.totalSelected, capacity: row.totalCapacity }
}

/** 查询校准后短暂高亮（平滑纠正，不突兀跳变） */
function isCorrected(classId) {
  const c = stock.classes[classId]
  return !!(c && c.correctedAt)
}

// ---------------- 课程列表 ----------------
const courses = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ college: '', courseType: '', keyword: '', pageNum: 1, pageSize: 10 })

function typeTag(t) {
  return t === '必修' ? 'danger' : t === '选修' ? 'warning' : 'success'
}

function capColor(selected, capacity) {
  if (!capacity) return '#cbd5e1'
  const p = selected / capacity
  if (p >= 0.98) return '#ef4444'
  if (p >= 0.8) return '#f59e0b'
  return '#10b981'
}

function search() {
  query.pageNum = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const data = await getCourses({
      college: query.college || undefined,
      courseType: query.courseType || undefined,
      keyword: query.keyword || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    courses.value = data.list
    total.value = data.total
    syncLiveCourses()   // 阶段 21.2：当前页课程「名额情况」实时化（不阻塞列表渲染）
  } finally {
    loading.value = false
  }
}

/** 当前页课程 id（用于课程列表名额列的实时订阅） */
const liveCourseIds = ref([])

/**
 * 阶段 21.2：让课程列表的「名额情况」列实时更新。
 * 做法：对当前页尚未加载过班级的课程，按需拉一次班级列表并登记到 stock store
 * （登记后才有容量与班级集合，信号才能推导出聚合值）；随后把“当前页课程 + 抽屉课程”
 * 作为 SSE 订阅集合。每门课只拉一次（courseLoaded 去重），失败不影响其他课。
 */
async function syncLiveCourses() {
  liveCourseIds.value = courses.value.map((c) => c.id)
  const missing = liveCourseIds.value.filter((id) => !stock.courseLoaded[id])
  await Promise.allSettled(
    missing.map(async (id) => {
      try {
        const list = await getCourseClasses(id)
        stock.registerClasses(id, list)
      } catch {
        /* 单门课失败忽略：其余课程照常实时化 */
      }
    })
  )
  refreshSubscription()
}

/** 实时订阅集合 = 当前页课程 + 正在查看的教学班抽屉所属课程 */
function refreshSubscription() {
  const ids = new Set(liveCourseIds.value)
  if (currentCourse.value) ids.add(currentCourse.value.id)
  stock.setCourses([...ids])
}

// ---------------- 教学班抽屉 ----------------
const drawer = ref(false)
const currentCourse = ref(null)
const classes = ref([])
const classLoading = ref(false)

const drawerTitle = computed(() =>
  currentCourse.value ? `${currentCourse.value.name} · 教学班` : '教学班'
)

function openClasses(row) {
  currentCourse.value = row
  drawer.value = true
  loadClasses()
  refreshSubscription()   // 订阅集合 = 当前页课程 + 抽屉课程（列表名额列也要实时）
}

async function loadClasses() {
  if (!currentCourse.value) return   // 修既有缺陷：从"我的课表"退课时无抽屉上下文，直接跳过
  classLoading.value = true
  try {
    classes.value = await getCourseClasses(currentCourse.value.id)
    stock.registerClasses(currentCourse.value.id, classes.value)   // 建立班级→实时数据映射
    refreshSubscription()
  } finally {
    classLoading.value = false
  }
}

function fmtClassTime(cls) {
  return `${weekNames[cls.weekDay]} 第${cls.startSection}-${cls.endSection}节`
}

async function onSelect(cls) {
  // 阶段 21.1：同班/同课程已有未终结任务 → 不重复入队
  if (selectTasks.activeOf(cls.id)) {
    ElMessage.info('这个教学班已在选课任务中，请等待结果')
    return
  }
  if (currentCourse.value && selectTasks.activeOfCourse(currentCourse.value.id)) {
    ElMessage.info('这门课已有选课任务在处理中，请等结果出来后再选其他教学班')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认选课：《${currentCourse.value.name}》${cls.className}<br/>` +
        `时间：${fmtClassTime(cls)}（第 ${cls.startWeek}-${cls.endWeek} 周）<br/>` +
        `地点：${cls.location}　名额：${cls.selected}/${cls.capacity}<br/><br/>` +
        `提交后进入选课任务队列；确认成功后才计为已选，若被拒（冲突/满员）名额自动退回并在这里提示你。`,
      '选课确认',
      {
        confirmButtonText: '加入选课任务',
        cancelButtonText: '再想想',
        type: 'info',
        dangerouslyUseHTMLString: true
      }
    )
  } catch {
    return
  }
  const task = selectTasks.enqueue({
    classId: cls.id,
    courseId: currentCourse.value ? currentCourse.value.id : null,
    courseName: currentCourse.value ? currentCourse.value.name : '',
    className: cls.className
  })
  if (!task) {
    ElMessage.info('这个教学班已在选课任务中，请等待结果')
    return
  }
  ElMessage.info(`已加入选课任务：《${task.courseName}》${task.className}`)
  runTaskQueue()
}

// ---------------- 阶段 21.1：串行选课任务队列 ----------------
// 同一时刻只提交一个任务；前一个拿到回执（或超时兜底）后才提交下一个。
// 这样第二个任务的时间冲突判定能基于第一个已落库的结果，结果顺序也符合直觉。
let queueRunning = false
const SETTLE_WAIT_MS = 4000
/** classId -> 释放等待的函数（settled 到达或超时时调用，用于放行队列） */
const settleWaiters = new Map()

async function runTaskQueue() {
  if (queueRunning) return
  queueRunning = true
  try {
    for (;;) {
      const task = selectTasks.nextQueued()
      if (!task) break
      selectTasks.markSubmitting(task.id)
      let resp
      try {
        resp = await selectCourse(task.classId)
      } catch (e) {
        // 业务拒绝（40051/40052/40055/40060/40056…）：request.js 已弹提示，任务面板保留原因
        selectTasks.markDone(task.id, false, e && e.message ? e.message : '提交失败')
        continue
      }
      if (!(resp && resp.accepted)) {
        // 回退开关关闭：老链路同步落库成功
        selectTasks.markDone(task.id, true)
        stock.applyOwnDelta(task.classId, -1)
        await refreshAfterChange()
        continue
      }
      // 受理成功：响应里的余量/版本是服务端真实数据（ACCEPT 已在 Redis 扣减），直接采用；
      // 但**不提前**改"我的选课状态"——等 settled 回执到达后才算确认
      if (resp.remain != null && resp.version != null) {
        stock.applyServerValue(task.classId, resp.remain, resp.version)
      }
      selectTasks.markAccepted(task.id, resp)
      // 回执可能先于 HTTP 响应到达（任务已终结）→ 无需再等
      const cur = selectTasks.tasks.find((t) => t.id === task.id)
      if (cur && cur.status === 'accepted') {
        await waitForSettle(task)
      }
    }
  } finally {
    queueRunning = false
  }
}

/** 等待回执；超时（SSE 断/离线）标记"确认中"并刷新，按数据库真相收敛，随后放行队列 */
function waitForSettle(task) {
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      settleWaiters.delete(task.classId)
      resolve()
    }
    const timer = setTimeout(async () => {
      const cur = selectTasks.tasks.find((t) => t.id === task.id)
      if (cur && cur.status === 'accepted') {
        selectTasks.markUnknown(task.id, '回执未到，请稍后刷新确认')
      }
      await refreshAfterChange()
      // 回执丢了但落库成功：刷新后以数据库为准直接收敛（未落库则维持"确认中"，等迟到回执）
      const t2 = selectTasks.tasks.find((t) => t.id === task.id)
      const cls = classes.value.find((c) => c.id === task.classId)
      if (t2 && t2.status === 'unknown' && cls && cls.myStatus === 'SELECTED') {
        selectTasks.markDone(task.id, true)
      }
      finish()
    }, SETTLE_WAIT_MS)
    settleWaiters.set(task.classId, finish)
  })
}

async function onDrop(cls) {
  try {
    await ElMessageBox.confirm(
      `确认退掉《${currentCourse.value.name}》${cls.className}？<br/>` +
        `名额将立即释放，先到先得。`,
      '退课确认',
      {
        confirmButtonText: '确认退课',
        cancelButtonText: '再想想',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )
  } catch {
    return
  }
  try {
    await dropCourse(cls.id)
    ElMessage.success('已退课，名额已释放')
    stock.applyOwnDelta(cls.id, 1)   // 立即乐观 +1，随后自动校准
    await refreshAfterChange()
  } catch {
    /* 业务错误提示由 request.js 统一弹出 */
  }
}

async function refreshAfterChange() {
  await Promise.all([loadClasses(), loadMy(), load()])
}

// ---------------- 我的课表 ----------------
const myCourses = ref([])
const myLoading = ref(false)

const totalCredits = computed(() =>
  myCourses.value.reduce((sum, c) => sum + Number(c.credits || 0), 0)
)

const maxSection = computed(() => Math.max(10, ...myCourses.value.map((c) => c.endSection || 0)))

const PALETTE = [
  { bg: 'hsl(210, 90%, 93%)', line: 'hsl(210, 80%, 60%)' },
  { bg: 'hsl(150, 70%, 91%)', line: 'hsl(150, 60%, 45%)' },
  { bg: 'hsl(35, 95%, 92%)', line: 'hsl(35, 90%, 55%)' },
  { bg: 'hsl(280, 70%, 94%)', line: 'hsl(280, 55%, 62%)' },
  { bg: 'hsl(0, 85%, 94%)', line: 'hsl(0, 70%, 62%)' },
  { bg: 'hsl(180, 65%, 91%)', line: 'hsl(180, 55%, 45%)' },
  { bg: 'hsl(60, 85%, 90%)', line: 'hsl(55, 75%, 48%)' },
  { bg: 'hsl(320, 70%, 94%)', line: 'hsl(320, 55%, 62%)' }
]

function blockStyle(c, i) {
  const p = PALETTE[i % PALETTE.length]
  return {
    gridColumn: (c.weekDay || 1) + 1,
    gridRow: `${(c.startSection || 1) + 1} / ${(c.endSection || 1) + 2}`,
    background: p.bg,
    borderLeft: `3px solid ${p.line}`
  }
}

function fmtMyTime(row) {
  return `${weekNames[row.weekDay]} 第${row.startSection}-${row.endSection}节`
}

async function loadMy() {
  myLoading.value = true
  try {
    myCourses.value = await getMyCourses()
  } finally {
    myLoading.value = false
  }
}

async function onDropMy(row) {
  try {
    await ElMessageBox.confirm(
      `确认退掉《${row.courseName}》${row.className}？<br/>名额将立即释放，先到先得。`,
      '退课确认',
      { confirmButtonText: '确认退课', cancelButtonText: '再想想', type: 'warning', dangerouslyUseHTMLString: true }
    )
  } catch {
    return
  }
  try {
    await dropCourse(row.classId)
    ElMessage.success('已退课，名额已释放')
    stock.applyOwnDelta(row.classId, 1)
    await refreshAfterChange()
  } catch {
    /* 业务错误提示由 request.js 统一弹出 */
  }
}

// 抽屉关闭 → 只取消抽屉课程的订阅；当前页课程仍保持实时（列表名额列继续更新）
watch(drawer, (open) => {
  if (!open) refreshSubscription()
})

// 阶段 21.1：受理回执（settled）——只落定任务并提示；卡片从未提前标"已选"，因此无需回滚
watch(() => stock.lastSettlement, async (s) => {
  if (!s || s.classId == null) return
  const task = selectTasks.settle(s.classId, s.ok, s.reason)
  const waiter = settleWaiters.get(s.classId)
  if (waiter) waiter()   // 放行队列：下一个任务可以提交
  if (task) {
    if (s.ok) {
      ElMessage.success(`选课成功：《${task.courseName}》${task.className}`)
    } else {
      ElMessage.error(`选课未成功（${task.className}）：${s.reason || '名额已退回，可重新选课'}`)
    }
  }
  // 无论是否有本页任务（多标签页 / 刷新后），都以服务端为准刷新一次
  await refreshAfterChange()
  // 成功任务 8 秒后自动收起；失败任务保留，供查看原因后手动关闭
  if (task && s.ok) {
    setTimeout(() => {
      const cur = selectTasks.tasks.find((t) => t.id === task.id)
      if (cur && cur.status === 'success') selectTasks.dismiss(task.id)
    }, 8000)
  }
})

onMounted(() => {
  load()
  loadMy()
})

onUnmounted(() => {
  stock.stop()
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
  margin-bottom: 12px;
}

.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.table-card {
  border-radius: 12px;
}

.capacity {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cap-text {
  font-size: 12px;
  color: #64748b;
  font-family: Consolas, monospace;
  white-space: nowrap;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.tab-badge {
  margin-left: 6px;
  margin-top: -2px;
}

.sel-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.tab-badge :deep(.el-badge__content) {
  border: none;
}

/* ---------- 教学班抽屉 ---------- */
.class-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.class-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
  transition: box-shadow 0.15s;
}

.class-card:hover {
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.08);
}

.class-card.is-mine {
  border-color: #86efac;
  background: #f0fdf4;
}

.cc-left {
  flex: 1;
  min-width: 0;
}

.cc-title {
  font-weight: 600;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.cc-line {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.cc-cap {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  max-width: 340px;
}

.cc-right {
  flex-shrink: 0;
}

/* ---------- 我的课表 ---------- */
.my-summary {
  font-size: 13px;
  color: #475569;
  margin-bottom: 14px;
}

.my-summary b {
  color: #2563eb;
  font-size: 15px;
}

.timetable {
  display: grid;
  grid-template-columns: 54px repeat(7, minmax(0, 1fr));
  grid-auto-rows: 46px;
  gap: 4px;
  margin-bottom: 20px;
}

.tt-cell {
  border-radius: 6px;
}

.tt-head {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  background: #f1f5f9;
}

.tt-label {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #94a3b8;
  background: #f8fafc;
}

.tt-slot {
  background: #fbfdff;
  border: 1px dashed #e2e8f0;
}

.tt-course {
  border-radius: 6px;
  padding: 6px 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.ttc-name {
  font-size: 12px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ttc-meta {
  font-size: 10px;
  color: #64748b;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.my-table {
  margin-top: 4px;
}

.code {
  color: #94a3b8;
  font-size: 12px;
}

/* ---------- 阶段 20：名额实时同步 ---------- */
.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  color: #1e293b;
}

/* 查询校准后的短暂高亮：以服务端为准，但不突兀跳变 */
.cap-text {
  transition: color 0.3s;
}

.cap-flash {
  animation: capFlash 1.2s ease;
}

@keyframes capFlash {
  0% { color: #2563eb; transform: scale(1.06); }
  60% { color: #2563eb; }
  100% { color: inherit; transform: scale(1); }
}

/* ---------- 阶段 21.1：选课任务队列 ---------- */
.task-panel {
  position: fixed;
  right: 22px;
  bottom: 22px;
  width: 430px;
  max-width: calc(100vw - 44px);
  max-height: 46vh;
  overflow-y: auto;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.16);
  padding: 10px 12px 8px;
  z-index: 3000;
}

.task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
  padding: 2px 2px 8px;
  border-bottom: 1px dashed #e2e8f0;
}

.task-head em {
  font-style: normal;
  font-weight: 500;
  color: #64748b;
  font-size: 12px;
}

.task-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 2px;
  font-size: 12.5px;
  border-bottom: 1px dashed #f1f5f9;
}

.task-row:last-child {
  border-bottom: none;
}

.task-name {
  flex: 0 0 44%;
  color: #334155;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-state {
  flex: 1;
  color: #64748b;
  word-break: break-all;
}

.task-row.is-success .task-state {
  color: #059669;
  font-weight: 700;
}

.task-row.is-failed .task-state {
  color: #dc2626;
}

.task-row.is-accepted .task-state,
.task-row.is-submitting .task-state {
  color: #2563eb;
}

.task-row.is-queued .task-state {
  color: #94a3b8;
}

.task-close {
  flex: 0 0 auto;
  color: #94a3b8;
  padding: 0 2px;
}
</style>
