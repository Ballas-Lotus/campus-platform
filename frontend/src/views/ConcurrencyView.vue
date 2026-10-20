<template>
  <div>
    <h2 class="page-title">🔒 并发控制</h2>
    <p class="page-sub">
      阶段 18：三件"让并发不失控"的工具——<b>分布式锁</b>（同一时刻只有一个执行者）、
      <b>接口幂等</b>（重复提交只生效一次）、<b>线程池隔离与观测</b>（具名 + 有界 + 拒绝计数）。
    </p>

    <!-- 分布式锁 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>🔐 分布式锁（SET NX PX + Lua 校验删除）</span>
          <el-button text type="info" size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <div class="stat-grid" v-if="lock">
        <div class="stat"><div class="stat-n">{{ lock.acquired }}</div><div class="stat-l">获取成功</div></div>
        <div class="stat"><div class="stat-n">{{ lock.acquiredFirstTry }}</div><div class="stat-l">一次即得（无争抢）</div></div>
        <div class="stat"><div class="stat-n">{{ lock.acquiredAfterSpin }}</div><div class="stat-l">自旋后获得（有争抢）</div></div>
        <div class="stat"><div class="stat-n">{{ lock.timeouts }}</div><div class="stat-l">等待超时放弃</div></div>
        <div class="stat"><div class="stat-n">{{ lock.released }}</div><div class="stat-l">已释放</div></div>
        <div class="stat"><div class="stat-n">{{ lock.holding }}</div><div class="stat-l">当前持锁数</div></div>
        <div class="stat"><div class="stat-n" :class="{ warn: lock.degraded > 0 }">{{ lock.degraded }}</div>
          <div class="stat-l">Redis 异常降级放行</div></div>
        <div class="stat"><div class="stat-n">{{ lock.holders.length }}</div><div class="stat-l">持有者快照</div></div>
      </div>

      <el-divider content-position="left">现场实验：N 个线程同抢一把锁（临界区内做共享计数）</el-divider>
      <div class="probe">
        <span class="lbl">线程数</span>
        <el-input-number v-model="demo.threads" :min="2" :max="1000" :step="10" />
        <span class="lbl">等待毫秒（0 = 只试一次，&gt;0 = 自旋排队）</span>
        <el-input-number v-model="demo.waitMs" :min="0" :max="5000" :step="100" />
        <el-button type="primary" :loading="demoLoading" @click="runDemo">▶ 运行</el-button>
      </div>
      <div class="demo-result" v-if="demoResult">
        <el-tag :type="demoResult.pass ? 'success' : 'danger'" effect="dark">
          {{ demoResult.pass ? 'PASS' : 'FAIL' }}
        </el-tag>
        <span>进入临界区 <b>{{ demoResult.entered }}</b></span>
        <span>未抢到锁（立即放弃） <b>{{ demoResult.timedOut }}</b></span>
        <span>互斥性违例 <b :class="{ bad: demoResult.violations > 0 }">{{ demoResult.violations }}</b></span>
        <span>总耗时 <b>{{ demoResult.wallMs }}</b> ms</span>
        <div class="hint">{{ demoResult.note }}</div>
      </div>
      <div class="hint">
        锁的用途：缓存防击穿回源 · 定时结算防多实例重复执行 · 预扣计数器初始化。
        锁保护的都是"重复执行无害"的动作——正确性最终由数据库唯一索引兜底（所以 Redis 抖动时可安全降级放行）。
      </div>
    </el-card>

    <!-- 接口幂等 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>♻️ 接口幂等（Redis 两态 Lua：CONSUMING / CONSUMED）</span>
          <el-button type="primary" size="small" :loading="idemDemoRunning" @click="oneClickIdempotentDemo">
            ▶ 一键演示：同一请求并发提交 100 次
          </el-button>
        </div>
      </template>
      <div class="stat-grid" v-if="idem">
        <div class="stat"><div class="stat-n">{{ idem.passed }}</div><div class="stat-l">放行（首次请求）</div></div>
        <div class="stat"><div class="stat-n danger">{{ idem.blocked }}</div><div class="stat-l">拦截重复提交</div></div>
        <div class="stat"><div class="stat-n">{{ idem.blockRate }}<small>%</small></div><div class="stat-l">拦截占比</div></div>
        <div class="stat"><div class="stat-n">{{ idem.releasedForRetry }}</div><div class="stat-l">业务失败后释放（可重试）</div></div>
      </div>
      <div class="hint">
        注解：<code>@CampusIdempotent(prefix, key=SpEL, ttlSeconds)</code>，幂等键 = 业务前缀 + 用户 + 业务键。
        与阶段 13 的 MQ 消费幂等同一套路（两态 Lua）。数据库唯一索引仍是最终唯一性保障。
        <br />
        现场看效果：去「⚡ 并发压测」跑「幂等重复提交」场景（同一请求并发提交 100 次 → 恰好 1 次成功、99 次 40060）。
      </div>
      <el-table :data="idemRows" size="small" border style="margin-top: 10px" v-if="idemRows.length">
        <el-table-column prop="prefix" label="业务前缀" />
        <el-table-column prop="blocked" label="拦截次数" />
      </el-table>
    </el-card>

    <!-- 阶段 19：合并 Lua -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>⚡ 选课快路径：幂等 + 预扣合并 Lua（阶段 19）</span>
          <el-button type="primary" size="small" :loading="mergeRunning" @click="runLuaMerge">▶ 运行对照</el-button>
        </div>
      </template>
      <p class="lbl" style="margin: 2px 0 10px">
        阶段 18 成功路径 = hasKey 检查 → 幂等两态 Lua → 预扣 Lua → 置已消费（<b>4 次 Redis 往返</b>）；
        阶段 19 把"幂等判定 + 预扣扣减"合并成一次原子脚本（<b>2 次往返</b>）。同一计数器各跑 N 轮：
      </p>
      <div class="probe">
        <span class="lbl">轮数</span>
        <el-input-number v-model="mergeRounds" :min="50" :max="2000" :step="50" />
      </div>
      <div class="merge-cmp" v-if="mergeResult">
        <div class="merge-col">
          <div class="merge-title">{{ mergeResult.oldPath.name }}</div>
          <div class="merge-avg">{{ mergeResult.oldPath.avgUs }}<small>µs</small></div>
          <div class="merge-sub">P50 {{ mergeResult.oldPath.p50Us }}µs · P95 {{ mergeResult.oldPath.p95Us }}µs</div>
          <div class="merge-tag">🔴 {{ mergeResult.oldPath.redisRounds }} 次 Redis 往返</div>
        </div>
        <div class="merge-arrow">
          <div class="merge-ratio">{{ mergeRatio }}</div>
          <div class="merge-arrow-icon">→</div>
        </div>
        <div class="merge-col ok">
          <div class="merge-title">{{ mergeResult.newPath.name }}</div>
          <div class="merge-avg">{{ mergeResult.newPath.avgUs }}<small>µs</small></div>
          <div class="merge-sub">P50 {{ mergeResult.newPath.p50Us }}µs · P95 {{ mergeResult.newPath.p95Us }}µs</div>
          <div class="merge-tag">🟢 {{ mergeResult.newPath.redisRounds }} 次 Redis 往返</div>
        </div>
      </div>
      <div class="hint" v-if="mergeResult">{{ mergeResult.note }}</div>
    </el-card>

    <!-- 线程池 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>🧵 线程池水位（具名 + 有界队列 + 拒绝计数）</span>
          <span>累计拒绝 <b :class="{ bad: totalRejected > 0 }">{{ totalRejected }}</b></span>
        </div>
      </template>
      <el-table :data="pools" size="small" border>
        <el-table-column prop="name" label="池名" min-width="200" />
        <el-table-column prop="core" label="core" width="70" />
        <el-table-column prop="max" label="max" width="70" />
        <el-table-column prop="poolSize" label="当前线程" width="90" />
        <el-table-column label="活跃" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.active > 0 ? 'warning' : 'info'" effect="plain">{{ row.active }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="largest" label="峰值线程" width="90" />
        <el-table-column label="队列" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.directHandoff" size="small" type="info" effect="plain">不排队（直接交接）</el-tag>
            <span v-else>{{ row.queueSize }} / {{ row.queueCapacity }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="completed" label="已完成" width="90" />
        <el-table-column label="拒绝" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rejected > 0 ? 'danger' : 'success'" effect="plain">{{ row.rejected }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!pools.length" description="暂无已注册线程池 —— 跑一次压测（齐射池会注册）后即可看到" />
      <div class="hint">
        隔离的意义：压测齐射、缓存回源、定时结算各用各的池——一个池打满不影响另一个。
        拒绝策略是"计数后抛异常"而不是"静默丢弃"：任务被悄悄丢掉是线程池最危险的失败模式。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getConcurrencyMetrics, runLockDemo, runLuaMergeDemo } from '../api/concurrency'
import { runStress } from '../api/stress'

const lock = ref(null)
const idem = ref(null)
const pools = ref([])
const totalRejected = ref(0)
const demoLoading = ref(false)
const demoResult = ref(null)
const demo = reactive({ threads: 100, waitMs: 0 })
const idemDemoRunning = ref(false)
const mergeRunning = ref(false)
const mergeResult = ref(null)
const mergeRounds = ref(300)

const mergeRatio = computed(() => {
  if (!mergeResult.value) return ''
  const o = mergeResult.value.oldPath.avgUs
  const n = mergeResult.value.newPath.avgUs
  if (!n) return '—'
  const r = o / n
  return r >= 1.05 ? `快 ${r.toFixed(2)}×` : '相当'
})

async function runLuaMerge() {
  mergeRunning.value = true
  try {
    mergeResult.value = await runLuaMergeDemo({ rounds: mergeRounds.value })
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    mergeRunning.value = false
  }
}

/** 一键演示：同一用户并发提交同一笔预约 100 次（看幂等拦截） */
async function oneClickIdempotentDemo() {
  idemDemoRunning.value = true
  try {
    const r = await runStress('IDEMPOTENT', 100)
    const blocked = r.failures['40060'] || 0
    ElMessageBox.alert(
      `<div style="line-height:2">
        结论：<b style="color:${r.conclusion === 'PASS' ? '#059669' : '#dc2626'}">${r.conclusion}</b><br/>
        并发提交 <b>${r.requests}</b> 次 · 成功 <b>${r.success}</b> · 总耗时 <b>${r.durationMs} ms</b><br/>
        幂等拦截（40060）<b>${blocked}</b> 次 —— 只有首笔进入业务，其余在 Redis 层就被挡回<br/>
        结果：片 booked = 1、有效预约记录 = 1（不重复、不少记）
      </div>`,
      '接口幂等压测结果',
      { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
    )
    await load()
    ElMessage.success('已刷新幂等与线程池指标')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    idemDemoRunning.value = false
  }
}

const idemRows = computed(() => {
  const by = idem.value?.blockedByPrefix || {}
  return Object.keys(by).map((k) => ({ prefix: k, blocked: by[k] }))
})

async function load() {
  try {
    const d = await getConcurrencyMetrics()
    lock.value = d.lock
    idem.value = d.idempotent
    pools.value = d.pools || []
    totalRejected.value = d.totalRejected
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function runDemo() {
  demoLoading.value = true
  try {
    demoResult.value = await runLockDemo({ threads: demo.threads, waitMs: demo.waitMs })
    load()
  } catch (e) {
    /* ignore */
  } finally {
    demoLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.page-sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.8; }
.section { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; color: #1e293b; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 12px; text-align: center; }
.stat-n { font-size: 22px; font-weight: 700; color: #2563eb; }
.stat-n.danger { color: #dc2626; }
.stat-n.warn { color: #d97706; }
.stat-n small { font-size: 13px; margin-left: 2px; }
.stat-l { font-size: 12px; color: #64748b; margin-top: 4px; }

.probe { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 6px 0 12px; }
.lbl { font-size: 12.5px; color: #64748b; }

.demo-result { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; font-size: 13px; color: #334155; margin-bottom: 8px; }
.bad { color: #dc2626; }

.merge-cmp { display: flex; align-items: center; gap: 18px; margin-top: 12px; }
.merge-col { flex: 1; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; text-align: center; background: #fff7ed; }
.merge-col.ok { background: #ecfdf5; border-color: #a7f3d0; }
.merge-title { font-size: 12.5px; color: #64748b; }
.merge-avg { font-size: 26px; font-weight: 700; color: #ea580c; }
.merge-col.ok .merge-avg { color: #059669; }
.merge-avg small { font-size: 13px; margin-left: 2px; }
.merge-sub { font-size: 12px; color: #94a3b8; }
.merge-tag { font-size: 12px; color: #475569; margin-top: 6px; }
.merge-arrow { text-align: center; min-width: 90px; }
.merge-ratio { font-size: 15px; font-weight: 700; color: #2563eb; }
.merge-arrow-icon { font-size: 20px; color: #94a3b8; }
.hint { font-size: 12.5px; color: #64748b; line-height: 1.8; background: #f8fafc; padding: 8px 10px; border-radius: 8px; width: 100%; }
code { font-size: 12px; color: #475569; }
</style>
