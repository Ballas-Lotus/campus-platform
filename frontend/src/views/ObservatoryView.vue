<template>
  <div>
    <h2 class="page-title">🔭 齐射观测台</h2>
    <p class="page-sub">
      面向"万人同抢一课"的<b>实时观测页</b>：每 2 秒采样 闸门 / 缓存 / 限流 三处指标，画成滚动曲线。
      工作流：<b>先「📍 打点」→ 去命令行跑齐射（python tools/loadtest.py verify --users 10000）
      → 回来点「📋 战报」</b>，增量与判读一张纸看完。零新增依赖（SVG 手绘曲线）。
    </p>

    <!-- 控制条 -->
    <el-card shadow="never" class="section">
      <div class="ctrl-row">
        <el-tag :type="qpsTag" effect="dark" size="large">选课限流阈值：{{ sentinel.qps ?? '—' }} QPS</el-tag>
        <el-button type="warning" plain size="small" :loading="qpsLoading" @click="changeQps(100000)">
          放开限流（100000）
        </el-button>
        <el-button type="success" plain size="small" :loading="qpsLoading" @click="changeQps(20)">
          恢复默认（20）
        </el-button>
        <el-divider direction="vertical" />
        <el-button type="primary" size="small" @click="markBaseline">📍 打点（基准）</el-button>
        <el-button type="danger" plain size="small" :disabled="!baseline" @click="makeReport">📋 战报（看增量）</el-button>
        <el-button text size="small" @click="polling = !polling">{{ polling ? '⏸ 暂停采样' : '▶ 继续采样' }}</el-button>
        <span class="muted">上次采样：{{ lastTick || '—' }}</span>
      </div>
      <div class="hint">
        提示：限流阈值默认 20 QPS 会先把人挡光——观察闸门真实挡量先「放开限流」；
        战报的<b>完整版</b>（含客户端延迟分布/直方图）在命令行侧：跑完 verify 会自动生成
        <code>tools/reports/verify-report-*.html</code> 单文件战报。
      </div>
    </el-card>

    <!-- 实时曲线 -->
    <div class="chart-grid">
      <el-card v-for="c in charts" :key="c.key" shadow="never" class="chart-card">
        <div class="chart-head">
          <span class="chart-title">{{ c.icon }} {{ c.title }}</span>
          <span class="chart-now" :style="{ color: c.color }">{{ c.now }}</span>
        </div>
        <svg :viewBox="`0 0 ${W} ${H}`" class="spark" preserveAspectRatio="none">
          <line :x1="0" :y1="H - 2" :x2="W" :y2="H - 2" stroke="#e2e8f0" stroke-width="1" />
          <polygon v-if="c.area" :points="c.area" :fill="c.color" opacity="0.12" />
          <polyline v-if="c.points" :points="c.points" fill="none" :stroke="c.color" stroke-width="2" />
        </svg>
        <div class="chart-foot">
          <span>最近 {{ Math.round(SAMPLES * INTERVAL_MS / 1000) }} 秒</span>
          <span>峰值 {{ c.peak }}</span>
        </div>
      </el-card>
    </div>

    <!-- 计数器现场 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>🧮 预扣计数器现场（Redis 预计数 vs DB 真值）</span>
          <span class="muted">共 {{ counters.length }} 个活跃计数器，最大偏差 {{ maxDrift }}</span>
        </div>
      </template>
      <el-table :data="counters.slice(0, 8)" size="small" border>
        <el-table-column prop="key" label="计数器 key" min-width="260" />
        <el-table-column prop="label" label="对象" min-width="180" />
        <el-table-column prop="redisRemaining" label="Redis 预计数" width="140" />
        <el-table-column prop="dbRemaining" label="DB 真值" width="110" />
        <el-table-column label="偏差" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.drift === 0" size="small" type="success" effect="plain">0</el-tag>
            <el-tag v-else-if="row.drift == null" size="small" type="info" effect="plain">—</el-tag>
            <el-tag v-else size="small" type="warning" effect="dark">{{ row.drift > 0 ? '+' : '' }}{{ row.drift }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!counters.length" description="暂无活跃计数器（跑一次选课/预约后出现）" />
    </el-card>

    <!-- 战报 -->
    <el-card shadow="never" class="section" v-if="report">
      <template #header>
        <div class="card-head">
          <span>📋 本轮战报（{{ report.at }} 打点 → {{ report.now }} 结束，跨度 {{ report.spanS }}s）</span>
          <el-button text type="info" size="small" @click="report = null">清除</el-button>
        </div>
      </template>
      <div class="check-grid">
        <div v-for="ck in report.checks" :key="ck.name" class="check-item" :class="{ bad: !ck.pass }">
          <span class="mk">{{ ck.pass ? '✓' : '✗' }}</span>
          <span class="ck-name">{{ ck.name }}</span>
          <span class="ck-detail">{{ ck.detail }}</span>
        </div>
      </div>
      <el-table :data="report.rows" size="small" border style="margin-top: 12px">
        <el-table-column prop="name" label="观测项" min-width="200" />
        <el-table-column prop="before" label="打点前" width="130" />
        <el-table-column prop="after" label="现在" width="130" />
        <el-table-column label="增量" width="130">
          <template #default="{ row }">
            <b :class="row.delta > 0 ? 'hl' : 'z'">{{ row.delta > 0 ? '+' : '' }}{{ row.delta }}</b>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint">
        判读要点：<b>拦截 + 放行 = 到达数</b>（与服务端逐笔记账闭合）、<b>回补 = 0</b>（未走"少卖"路径）、
        <b>降级 = 0</b>（Redis 全程在线）。完整的客户端延迟/响应分布战报：命令行跑
        <code>python tools/loadtest.py verify --users N</code>，自动生成 HTML。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPreDeductState } from '../api/prededuct'
import { getCacheMetrics } from '../api/cache'
import { getSentinel, setSentinelQps } from '../api/stress'

const W = 320
const H = 72
const SAMPLES = 60
const INTERVAL_MS = 2000

const polling = ref(true)
const lastTick = ref('')
const qpsLoading = ref(false)
const sentinel = reactive({ qps: null, blocked: 0 })
const counters = ref([])

const series = reactive({ inter: [], pass: [], hit: [], flow: [] })
let prev = null            // 上一采样点的累计值（算速率）
let timer = null
const baseline = ref(null)
const report = ref(null)

const qpsTag = computed(() => (sentinel.qps >= 1000 ? 'danger' : 'success'))
const maxDrift = computed(() => {
  const ds = counters.value.map((c) => Math.abs(c.drift || 0))
  return ds.length ? Math.max(...ds) : 0
})

function spark(values) {
  if (values.length < 2) return { points: '', area: '' }
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const step = W / (SAMPLES - 1)
  const pts = values.map((v, i) => {
    const x = i * step
    const y = H - 6 - ((v - min) / span) * (H - 16)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  return { points: pts.join(' '), area: `0,${H - 2} ${pts.join(' ')} ${((values.length - 1) * step).toFixed(1)},${H - 2}` }
}

const charts = computed(() => {
  const defs = [
    { key: 'inter', icon: '🛡️', title: '闸门·拦截速率 (/s)', color: '#dc2626', unit: '/s' },
    { key: 'pass', icon: '✅', title: '闸门·放行速率 (/s)', color: '#2563eb', unit: '/s' },
    { key: 'flow', icon: '🚦', title: '限流·拦截速率 (/s)', color: '#d97706', unit: '/s' },
    { key: 'hit', icon: '🎯', title: '缓存命中率 (%)', color: '#059669', unit: '%' }
  ]
  return defs.map((d) => {
    const vals = series[d.key]
    const s = spark(vals)
    const now = vals.length ? vals[vals.length - 1] : 0
    return {
      ...d,
      now: `${now}${d.unit}`,
      peak: vals.length ? Math.max(...vals) : 0,
      points: s.points,
      area: s.area
    }
  })
})

async function tick() {
  if (!polling.value) return
  try {
    const [pd, cm, sn] = await Promise.all([
      getPreDeductState(),
      getCacheMetrics(),
      getSentinel()
    ])
    const m = pd.metrics || {}
    const mm = cm.metrics || {}
    sentinel.qps = sn.qps
    sentinel.blocked = sn.blocked
    counters.value = (pd.counters || []).filter((c) => c.redisRemaining != null || c.dbRemaining != null)

    if (prev) {
      const inter = Math.max(0, (m.intercepted - prev.inter) / (INTERVAL_MS / 1000))
      const pass = Math.max(0, (m.passed - prev.pass) / (INTERVAL_MS / 1000))
      const flow = Math.max(0, (sn.blocked - prev.flow) / (INTERVAL_MS / 1000))
      push(series.inter, Math.round(inter))
      push(series.pass, Math.round(pass))
      push(series.flow, Math.round(flow))
    }
    push(series.hit, Math.round((mm.hitRate || 0) * 10) / 10)
    prev = { inter: m.intercepted, pass: m.passed, flow: sn.blocked }
    lastTick.value = new Date().toLocaleTimeString()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function push(arr, v) {
  arr.push(v)
  while (arr.length > SAMPLES) arr.shift()
}

async function markBaseline() {
  try {
    const [pd, cm, sn] = await Promise.all([getPreDeductState(), getCacheMetrics(), getSentinel()])
    const m = pd.metrics || {}
    const mm = cm.metrics || {}
    baseline.value = {
      at: new Date().toLocaleTimeString(),
      ts: Date.now(),
      inter: m.intercepted, pass: m.passed, rollback: m.rolledBack, degrade: m.degradeOnError,
      incr: mm.stockIncrs, flow: sn.blocked, hit: mm.hitRate
    }
    report.value = null
    ElMessage.success('已打点：现在去跑齐射（命令行 verify），回来点「📋 战报」')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function makeReport() {
  if (!baseline.value) return
  try {
    const [pd, cm, sn] = await Promise.all([getPreDeductState(), getCacheMetrics(), getSentinel()])
    const m = pd.metrics || {}
    const mm = cm.metrics || {}
    const b = baseline.value
    const d = {
      inter: m.intercepted - b.inter,
      pass: m.passed - b.pass,
      rollback: m.rolledBack - b.rollback,
      degrade: m.degradeOnError - b.degrade,
      incr: mm.stockIncrs - b.incr,
      flow: sn.blocked - b.flow
    }
    const rows = [
      { name: '闸门·Redis 拦截', before: b.inter, after: m.intercepted, delta: d.inter },
      { name: '闸门·放行到数据库', before: b.pass, after: m.passed, delta: d.pass },
      { name: '闸门·回补', before: b.rollback, after: m.rolledBack, delta: d.rollback },
      { name: '闸门·降级直通', before: b.degrade, after: m.degradeOnError, delta: d.degrade },
      { name: '缓存·余量增量 HINCRBY', before: b.incr, after: mm.stockIncrs, delta: d.incr },
      { name: '限流·累计拦截', before: b.flow, after: sn.blocked, delta: d.flow },
      { name: '缓存命中率', before: b.hit + '%', after: mm.hitRate + '%', delta: Math.round((mm.hitRate - b.hit) * 10) / 10 }
    ]
    report.value = {
      at: b.at,
      now: new Date().toLocaleTimeString(),
      spanS: Math.round((Date.now() - b.ts) / 1000),
      rows,
      checks: [
        { name: '有请求到达闸门', pass: d.inter + d.pass > 0, detail: `拦截 ${d.inter} + 放行 ${d.pass} = ${d.inter + d.pass}` },
        { name: '零回补（未走「少卖」路径）', pass: d.rollback === 0, detail: `回补 ${d.rollback} 次` },
        { name: '零降级（Redis 全程在线）', pass: d.degrade === 0, detail: `降级 ${d.degrade} 次` },
        { name: '读模型同步增量（余量 HINCRBY ≥ 0）', pass: d.incr >= 0, detail: `HINCRBY ${d.incr} 次` }
      ]
    }
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function changeQps(qps) {
  qpsLoading.value = true
  try {
    const d = await setSentinelQps(qps)
    sentinel.qps = d.qps
    ElMessage.success(`阈值已热更新：${d.previousQps} → ${d.qps} QPS（无需重启）`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    qpsLoading.value = false
  }
}

onMounted(() => {
  tick()
  timer = setInterval(tick, INTERVAL_MS)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.page-title { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.page-sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.8; }
.section { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; color: #1e293b; }

.ctrl-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.muted { color: #94a3b8; font-size: 12.5px; }
.hint { margin-top: 12px; font-size: 12.5px; color: #64748b; line-height: 1.8; background: #f8fafc; padding: 8px 10px; border-radius: 8px; }
code { font-size: 12px; color: #475569; background: #f1f5f9; padding: 1px 5px; border-radius: 5px; }

.chart-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
@media (max-width: 1100px) { .chart-grid { grid-template-columns: repeat(2, 1fr); } }
.chart-card { border-radius: 12px; }
.chart-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.chart-title { font-size: 12.5px; color: #475569; font-weight: 600; }
.chart-now { font-size: 16px; font-weight: 800; font-family: Consolas, monospace; }
.spark { width: 100%; height: 72px; display: block; }
.chart-foot { display: flex; justify-content: space-between; font-size: 11.5px; color: #94a3b8; margin-top: 4px; }

.check-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.check-item { display: grid; grid-template-columns: 22px 1fr auto; gap: 8px; align-items: center; background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 10px; padding: 8px 12px; font-size: 13px; }
.check-item.bad { background: #fef2f2; border-color: #fca5a5; }
.check-item .mk { font-weight: 800; color: #059669; }
.check-item.bad .mk { color: #dc2626; }
.ck-detail { color: #64748b; font-size: 12px; }
.hl { color: #b45309; }
.z { color: #94a3b8; }
</style>
