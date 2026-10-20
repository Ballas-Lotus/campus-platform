<template>
  <div>
    <h2 class="page-title">🚀 缓存</h2>
    <p class="page-sub">
      阶段 16：把"读多写少"的查询搬进 Redis——<b>Cache-Aside（写后失效）</b> +
      <b>防击穿</b>（互斥回源 + 双检）+ <b>防穿透</b>（布隆过滤器 + 空值回填）。
      Redis 只做加速器，数据真相始终在 MySQL。
    </p>

    <!-- 命中率总览 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>📊 运行指标</span>
          <span>
            <el-button text type="primary" size="small" :loading="warming" @click="warmUp">
              一键生成读流量（概览/资源列表/时段各 5 次）
            </el-button>
            <el-button text type="info" size="small" @click="load">刷新</el-button>
          </span>
        </div>
      </template>
      <div class="stat-grid" v-if="metrics">
        <div class="stat">
          <div class="stat-n">{{ metrics.hitRate }}<small>%</small></div>
          <div class="stat-l">命中率</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.hits }}</div>
          <div class="stat-l">命中缓存</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.misses }}</div>
          <div class="stat-l">未命中</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.dbLoads }}</div>
          <div class="stat-l">回源查库</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.lockAcquires }}</div>
          <div class="stat-l">防击穿·抢到锁回源</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.lockMisses }}</div>
          <div class="stat-l">防击穿·被抑制的并发回源</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.nullPuts }}</div>
          <div class="stat-l">空值回填（防穿透）</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.evictions }}</div>
          <div class="stat-l">写后失效</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.pipelineReads }}</div>
          <div class="stat-l">pipeline 合并读（阶段 19）</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.stockIncrs }}</div>
          <div class="stat-l">写后增量 HINCRBY（阶段 19）</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.stockSkips }}</div>
          <div class="stat-l">增量跳过（Hash 已过期）</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.stockRebuilds }}</div>
          <div class="stat-l">读模型重建（按 DB 真值）</div>
        </div>
        <div class="stat">
          <div class="stat-n">{{ metrics.stockFixes }}</div>
          <div class="stat-l">对账纠偏 Hash 数</div>
        </div>
      </div>
      <el-empty v-else description="加载中…" />
    </el-card>

    <!-- 阶段 19：教学班读模型 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>🧩 教学班读模型（静态信息 + 动态余量 Hash）—— 阶段 19</span>
          <el-button text type="info" size="small" @click="loadStockState">刷新状态</el-button>
        </div>
      </template>
      <div class="probe">
        <el-input v-model="stockCourseId" placeholder="课程 id（如 1）" style="width: 170px" />
        <el-button type="primary" :loading="warmLoading" @click="warmCourse">🔥 预热该课程（开窗前）</el-button>
        <el-button :loading="reconcileLoading" @click="reconcileNow">🧮 立即对账</el-button>
        <el-button type="warning" plain :loading="poisonLoading" @click="poisonNow">🧪 毒化一格（看自愈）</el-button>
      </div>
      <template v-if="stockState">
        <div class="kv-line">
          <span>key：<code>{{ stockState.key }}</code></span>
          <span>TTL：<b>{{ stockState.ttlSeconds ?? '已过期（读一次即重建）' }}</b></span>
          <span>增量维护：<b :class="{ okk: stockState.incrementalEnabled }">{{ stockState.incrementalEnabled ? '开' : '关' }}</b></span>
          <span v-if="lastAction" class="action-note">{{ lastAction }}</span>
        </div>
        <el-table :data="stockState.classes" size="small" border style="margin-top: 10px">
          <el-table-column prop="classId" label="教学班" width="110" />
          <el-table-column label="缓存余量（Hash）" width="160">
            <template #default="{ row }">{{ row.hashAvailable ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="DB 真值" width="120">
            <template #default="{ row }">{{ row.dbAvailable ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="偏差" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.drift === 0" size="small" type="success" effect="plain">0</el-tag>
              <el-tag v-else-if="row.drift == null" size="small" type="info" effect="plain">—</el-tag>
              <el-tag v-else size="small" type="danger" effect="dark">{{ row.drift > 0 ? '+' : '' }}{{ row.drift }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="260">
            <template #default="{ row }">
              <span v-if="row.drift > 0" class="muted">缓存显示偏多 → 用户会"看到有余量、点进去 40052"（展示可旧，裁决以闸门为准）</span>
              <span v-else-if="row.drift < 0" class="muted">缓存显示偏少 → 对账/重建会拉回真值</span>
              <span v-else class="muted">缓存与真相一致</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div class="hint">
        设计：<b>静态信息（TTL 600s）与动态余量（Hash，TTL 120s）分离</b>——写路径提交后 HINCRBY 增量维护余量、
        不再整键失效；读路径一次 pipeline 合并；Hash 过期/漂移由"读回源重建 + 30 秒对账"自愈。
        此为<b>展示读模型</b>：它的偏差不影响选课裁决（预扣闸门 + MySQL 条件更新说了算）。
      </div>
    </el-card>

    <!-- 布隆过滤器 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">🧭 布隆过滤器（防穿透：不存在的 id 不查库、不查缓存）</div>
      </template>
      <div class="bloom-grid" v-if="bloom">
        <div class="bloom-item"><b>{{ bloom.m?.toLocaleString() }}</b><span>位数组长度 m</span></div>
        <div class="bloom-item"><b>{{ bloom.k }}</b><span>哈希函数个数 k</span></div>
        <div class="bloom-item"><b>{{ bloom.inserted?.toLocaleString() }}</b><span>已插入元素（资源 id）</span></div>
        <div class="bloom-item"><b>{{ bloom.fillRatio }}%</b><span>位图填充率</span></div>
        <div class="bloom-item"><b>{{ bloom.currentFpp }}%</b><span>当前误判率（填充率^k）</span></div>
        <div class="bloom-item"><b>{{ bloom.rejected }}</b><span>累计拦截次数</span></div>
      </div>
      <div class="probe">
        <el-input v-model="probeId" placeholder="输入资源 id（试 99999999 = 不存在）" style="width: 300px" />
        <el-button type="primary" :loading="probing" @click="doProbe">探针查询</el-button>
        <span v-if="probeResult" class="probe-result" :class="{ bad: !probeResult.maybeExist }">
          <el-tag :type="probeResult.maybeExist ? 'success' : 'danger'" effect="dark" size="small">
            {{ probeResult.maybeExist ? '可能存在' : '一定不存在' }}
          </el-tag>
          {{ probeResult.conclusion }}（{{ probeResult.ms }} ms）
        </span>
      </div>
      <div class="hint">
        公式：m = -n·ln p /(ln2)²，k = (m/n)·ln2；双哈希法模拟 k 个哈希点，位操作走 Lua 一次往返完成。
        布隆"说不存在"= 一定不存在（可直接拒绝），"说可能"= 还要回源确认。
      </div>
    </el-card>

    <!-- 对照实验 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">⚖️ 对照实验：有缓存 vs 无缓存（同一读路径各跑 N 轮）</div>
      </template>
      <div class="probe">
        <el-select v-model="demo.type" style="width: 260px">
          <el-option label="工作台概览（6 次聚合）" value="overview" />
          <el-option label="时段可用性（分片查询）" value="slots" />
          <el-option label="教学班读模型：写后失效 vs 写后增量（阶段 19）" value="class_stock" />
        </el-select>
        <template v-if="demo.type === 'slots'">
          <el-input v-model="demo.resourceId" placeholder="资源 id（如 10001）" style="width: 180px" />
          <el-input v-model="demo.date" placeholder="日期（如 2026-10-11）" style="width: 170px" />
        </template>
        <template v-if="demo.type === 'class_stock'">
          <el-input v-model="demo.courseId" placeholder="课程 id（如 1）" style="width: 170px" />
        </template>
        <el-input-number v-model="demo.rounds" :min="5" :max="100" :step="5" />
        <el-button type="primary" :loading="demoLoading" @click="runDemo">运行对照</el-button>
      </div>

      <div class="compare" v-if="demoResult && demoResult.type !== 'class_stock'">
        <div class="cmp-col">
          <div class="cmp-title">无缓存（每次查库）</div>
          <div class="cmp-avg">{{ demoResult.noCache.avgMs }} <small>ms</small></div>
          <div class="cmp-sub">P95 {{ demoResult.noCache.p95Ms }}ms · min {{ demoResult.noCache.minMs }}ms</div>
        </div>
        <div class="cmp-arrow">
          <div class="cmp-ratio">{{ ratioText }}</div>
          <div class="cmp-arrow-icon">→</div>
        </div>
        <div class="cmp-col ok">
          <div class="cmp-title">有缓存（首轮回源，其余命中）</div>
          <div class="cmp-avg">{{ demoResult.cache.avgMs }} <small>ms</small></div>
          <div class="cmp-sub">P95 {{ demoResult.cache.p95Ms }}ms · min {{ demoResult.cache.minMs }}ms</div>
        </div>
      </div>

      <div class="compare" v-if="demoResult && demoResult.type === 'class_stock'">
        <div class="cmp-col">
          <div class="cmp-title">{{ demoResult.evictStrategy.name }}</div>
          <div class="cmp-avg">{{ demoResult.evictStrategy.avgMs }} <small>ms</small></div>
          <div class="cmp-sub">P95 {{ demoResult.evictStrategy.p95Ms }}ms</div>
          <div class="cmp-sub">回源查库 <b class="bad">{{ demoResult.evictStrategy.reloads }}</b> 次</div>
        </div>
        <div class="cmp-arrow">
          <div class="cmp-ratio">{{ ratioText }}</div>
          <div class="cmp-arrow-icon">→</div>
        </div>
        <div class="cmp-col ok">
          <div class="cmp-title">{{ demoResult.incrStrategy.name }}</div>
          <div class="cmp-avg">{{ demoResult.incrStrategy.avgMs }} <small>ms</small></div>
          <div class="cmp-sub">P95 {{ demoResult.incrStrategy.p95Ms }}ms</div>
          <div class="cmp-sub">回源查库 <b class="ok">{{ demoResult.incrStrategy.reloads }}</b> 次</div>
        </div>
      </div>
      <div class="hint" v-if="demoResult && demoResult.type === 'class_stock' && demoResult.sample">
        写后立即可见：毒化前 Hash={{ demoResult.sample.hashBefore }} → 增量 -1 后读到的 available =
        <b>{{ demoResult.sample.displayedAfterWrite }}</b>（没回源、没失效，显示值当场变化）。
      </div>
      <div class="hint" v-if="demoResult">{{ demoResult.note }}</div>
    </el-card>

    <!-- 规则表 + 轨迹 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">📋 缓存规则（缓存了什么、TTL 多久、写后如何失效）</div>
      </template>
      <el-table :data="rules" size="small" border>
        <el-table-column prop="name" label="域" width="120" />
        <el-table-column prop="pattern" label="key 模式" min-width="260" />
        <el-table-column prop="ttlSeconds" label="TTL" width="90">
          <template #default="{ row }">{{ row.ttlSeconds }} 秒</template>
        </el-table-column>
        <el-table-column prop="note" label="说明" min-width="320" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>🕒 最近访问轨迹（key 从哪来：缓存命中 / 回源 / 防击穿 / 失效）</span>
          <span>
            <el-button text type="danger" size="small" @click="evict('all')">清空全部缓存</el-button>
          </span>
        </div>
      </template>
      <el-table :data="metrics?.recent || []" size="small" border max-height="320">
        <el-table-column prop="key" label="key" min-width="380" />
        <el-table-column label="结果" width="180">
          <template #default="{ row }">
            <el-tag :type="resultType(row.result)" size="small" effect="plain">{{ resultLabel(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ms" label="耗时(ms)" width="100" />
      </el-table>
      <div class="hint" v-if="keys.length">现存 key 采样（{{ keys.length }}）：<code>{{ keys.slice(0, 6).join(' , ') }}</code></div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getCacheMetrics, runCacheDemo, bloomProbe, evictCache,
  getStockState, warmupCache, reconcileStock, poisonStock
} from '../api/cache'
import { getOverviewStats, getResources, getResourceSlots } from '../api/campus'

const warming = ref(false)
const metrics = ref(null)
const bloom = ref(null)
const rules = ref([])
const keys = ref([])
const probeId = ref('99999999')
const probeResult = ref(null)
const probing = ref(false)
const demoLoading = ref(false)
const demoResult = ref(null)
const demo = reactive({ type: 'overview', resourceId: '10001', courseId: '1', date: todayStr(1), rounds: 20 })

/* 阶段 19：教学班读模型面板 */
const stockCourseId = ref('1')
const stockState = ref(null)
const lastAction = ref('')
const warmLoading = ref(false)
const reconcileLoading = ref(false)
const poisonLoading = ref(false)

async function loadStockState() {
  try {
    stockState.value = await getStockState(stockCourseId.value)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function warmCourse() {
  warmLoading.value = true
  try {
    const d = await warmupCache(stockCourseId.value)
    if (d.courses !== undefined) {
      lastAction.value = `已预热热门课程 ${d.courses} 门（${d.totalClasses} 个余量字段，${d.elapsedMs}ms）`
    } else {
      lastAction.value = `已预热课程 ${d.courseId}：${d.classes} 个余量字段（${d.elapsedMs}ms）`
    }
    ElMessage.success('预热完成：静态信息 + 余量 Hash 已就位')
    await loadStockState()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    warmLoading.value = false
  }
}

async function reconcileNow() {
  reconcileLoading.value = true
  try {
    const d = await reconcileStock()
    lastAction.value = `对账：扫描 ${d.scanned} 个 Hash、检查 ${d.checked} 个、纠偏/清理 ${d.fixed} 个`
    ElMessage.success(d.fixed > 0 ? `已纠偏 ${d.fixed} 个 Hash` : '全部一致，无需纠偏')
    await loadStockState()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    reconcileLoading.value = false
  }
}

async function poisonNow() {
  const rows = stockState.value?.classes || []
  if (!rows.length) {
    ElMessage.warning('先刷新状态（确保该课程有教学班）')
    return
  }
  poisonLoading.value = true
  try {
    const d = await poisonStock({ courseId: stockCourseId.value, classId: rows[0].classId })
    lastAction.value = `毒化教学班 ${d.classId}：缓存余量 ${d.before} → ${d.after}（错误值）`
    ElMessage.warning('已把缓存余量改错——看下方"偏差"列，然后点「立即对账」看自愈')
    await loadStockState()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    poisonLoading.value = false
  }
}

function todayStr(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 86400000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function load() {
  try {
    const d = await getCacheMetrics()
    metrics.value = d.metrics
    bloom.value = d.bloom
    rules.value = d.rules
    keys.value = d.keys || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 一键生成真实读流量：让"命中率/回源/防击穿"这些指标立刻有数字可看 */
async function warmUp() {
  warming.value = true
  try {
    for (let i = 0; i < 5; i++) {
      await getOverviewStats()
      await getResources({ pageNum: 1, pageSize: 10 })
      await getResourceSlots(10001, demo.date)
    }
    ElMessage.success('已生成读流量：概览 ×5、资源列表 ×5、时段查询 ×5（第 1 次回源，其余命中缓存）')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    warming.value = false
  }
}

async function doProbe() {
  probing.value = true
  try {
    probeResult.value = await bloomProbe(probeId.value)
    load()
  } catch (e) {
    /* ignore */
  } finally {
    probing.value = false
  }
}

async function runDemo() {
  demoLoading.value = true
  try {
    demoResult.value = await runCacheDemo({
      type: demo.type,
      resourceId: demo.type === 'slots' ? demo.resourceId
        : demo.type === 'class_stock' ? demo.courseId : undefined,
      date: demo.type === 'slots' ? demo.date : undefined,
      rounds: demo.rounds
    })
    load()
    if (demo.type === 'class_stock') {
      loadStockState()
    }
  } catch (e) {
    /* ignore */
  } finally {
    demoLoading.value = false
  }
}

async function evict(scope) {
  try {
    const d = await evictCache({ scope })
    ElMessage.success(`已失效 ${d.removed} 个 key`)
    load()
  } catch (e) {
    /* ignore */
  }
}

const ratioText = computed(() => {
  if (!demoResult.value) return ''
  const n = demoResult.value.noCache.avgMs
  const c = demoResult.value.cache.avgMs
  if (!c) return '—'
  const r = n / c
  return r >= 1.05 ? `快 ${r.toFixed(1)}×` : '相当'
})

function resultLabel(r) {
  return {
    HIT: '缓存命中',
    MISS: '未命中',
    DB: '回源查库',
    NULL_PUT: '空值回填',
    LOCK_ACQUIRED: '防击穿·抢到锁',
    LOCK_MISSED: '防击穿·被抑制',
    EVICT: '写后失效',
    ERROR: '降级直查',
    PIPE: 'pipeline 合并读',
    STOCK_INCR: '写后增量 HINCRBY',
    STOCK_SKIP: '增量跳过（已过期）',
    STOCK_REBUILD: '读模型重建',
    STOCK_FIX: '对账纠偏'
  }[r] || r
}

function resultType(r) {
  return {
    HIT: 'success',
    MISS: 'info',
    DB: 'warning',
    NULL_PUT: 'info',
    LOCK_ACQUIRED: 'primary',
    LOCK_MISSED: 'info',
    EVICT: 'danger',
    ERROR: 'danger',
    PIPE: 'success',
    STOCK_INCR: 'primary',
    STOCK_SKIP: 'info',
    STOCK_REBUILD: 'warning',
    STOCK_FIX: 'danger'
  }[r] || 'info'
}

onMounted(() => {
  load()
  loadStockState()
})
</script>

<style scoped>
.page-title { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.page-sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.8; }
.section { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; color: #1e293b; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 12px; text-align: center; }
.stat-n { font-size: 22px; font-weight: 700; color: #2563eb; }
.stat-n small { font-size: 13px; margin-left: 2px; }
.stat-l { font-size: 12px; color: #64748b; margin-top: 4px; }

.bloom-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 14px; }
.bloom-item { background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 10px; padding: 10px; text-align: center; }
.bloom-item b { display: block; font-size: 17px; color: #0369a1; }
.bloom-item span { font-size: 11.5px; color: #64748b; }

.probe { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.probe-result { font-size: 12.5px; color: #475569; }

.hint { margin-top: 12px; font-size: 12.5px; color: #64748b; line-height: 1.8; background: #f8fafc; padding: 8px 10px; border-radius: 8px; }

.compare { display: flex; align-items: center; gap: 18px; margin-top: 14px; }
.cmp-col { flex: 1; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; text-align: center; background: #fff7ed; }
.cmp-col.ok { background: #ecfdf5; border-color: #a7f3d0; }
.cmp-title { font-size: 13px; color: #64748b; }
.cmp-avg { font-size: 30px; font-weight: 700; color: #ea580c; }
.cmp-col.ok .cmp-avg { color: #059669; }
.cmp-avg small { font-size: 14px; }
.cmp-sub { font-size: 12px; color: #94a3b8; }
.cmp-arrow { text-align: center; min-width: 90px; }
.cmp-ratio { font-size: 15px; font-weight: 700; color: #2563eb; }
.cmp-arrow-icon { font-size: 20px; color: #94a3b8; }
code { font-size: 12px; color: #475569; }

.kv-line { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12.5px; color: #475569; }
.kv-line b.okk { color: #059669; }
.action-note { color: #b45309; font-weight: 600; }
.muted { color: #94a3b8; font-size: 12px; }
.bad { color: #dc2626; }
.ok { color: #059669; }
</style>
