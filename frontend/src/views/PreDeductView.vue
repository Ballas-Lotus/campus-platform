<template>
  <div>
    <h2 class="page-title">🔥 预扣闸门</h2>
    <p class="page-sub">
      阶段 17：把"余量判定"从数据库前移到 Redis——请求先经 <b>Lua 原子"校验 + 预扣"</b>，
      判定不过的立即返回（<b>不占用数据库连接</b>），只有放行的少量请求进入 MySQL 事务。
      <b>Redis 是"预计数"，MySQL 是"真相"</b>：最终裁决仍是 <code>UPDATE ... WHERE booked &lt; capacity</code>。
    </p>

    <!-- 闸门全流程 -->
    <el-card shadow="never" class="section">
      <template #header><div class="card-head">🧭 挡量链路（三道防线）</div></template>
      <div class="flow">
        <div class="node">用户请求</div>
        <div class="arrow">→</div>
        <div class="node primary">① Redis 预扣<br /><small>Lua 校验+扣减（原子）</small></div>
        <div class="arrow">→</div>
        <div class="node danger">余量不足<br /><small>立即返回，不查库</small></div>
      </div>
      <div class="flow">
        <div class="node ghost">放行</div>
        <div class="arrow">↓</div>
        <div class="node success">② MySQL 条件更新<br /><small>最终裁决（真相）</small></div>
        <div class="arrow">→</div>
        <div class="node warn">③ 被拒则回补预扣<br /><small>否则"少卖"</small></div>
        <div class="arrow">⇄</div>
        <div class="node info">对账自愈<br /><small>每 30 秒纠偏</small></div>
      </div>
      <div class="hint">
        "预扣"只解决存量争抢（名额/余量）；用户自己的时间冲突（96 格位图）与"同一用户串行化"
        仍在数据库事务内完成——那是"读后判定"，无法在 Redis 上无条件化。
      </div>
    </el-card>

    <!-- 指标 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>📊 闸门指标</span>
          <span>
            <el-button type="primary" size="small" :loading="demoRunning" @click="oneClickDemo">
              ▶ 一键演示：1000 并发抢 10 个课额
            </el-button>
            <el-button text type="primary" size="small" :loading="reconciling" @click="doReconcile">手动对账一次</el-button>
            <el-button text type="info" size="small" @click="load">刷新</el-button>
          </span>
        </div>
      </template>
      <div class="stat-grid" v-if="metrics">
        <div class="stat"><div class="stat-n">{{ metrics.passed }}</div><div class="stat-l">放行到数据库</div></div>
        <div class="stat"><div class="stat-n danger">{{ metrics.intercepted }}</div><div class="stat-l">Redis 拦截（未查库）</div></div>
        <div class="stat"><div class="stat-n">{{ metrics.interceptRate }}<small>%</small></div><div class="stat-l">拦截率</div></div>
        <div class="stat"><div class="stat-n">{{ metrics.rolledBack }}</div><div class="stat-l">DB 拒绝后回补</div></div>
        <div class="stat"><div class="stat-n">{{ metrics.released }}</div><div class="stat-l">取消/退课释放</div></div>
        <div class="stat"><div class="stat-n">{{ metrics.initCount }}</div><div class="stat-l">计数器初始化</div></div>
        <div class="stat"><div class="stat-n">{{ metrics.reconcileRuns }}</div><div class="stat-l">对账轮次</div></div>
        <div class="stat"><div class="stat-n" :class="{ warn: metrics.reconcileFixes > 0 }">{{ metrics.reconcileFixes }}</div>
          <div class="stat-l">对账纠偏次数</div></div>
      </div>
      <div class="hint">
        计数器 TTL {{ metrics?.ttlSeconds }} 秒 · 冗余比例 {{ metrics?.redundancyRatio }}（0 = 严格等于真值）·
        对账阈值 {{ metrics?.driftThreshold }}（吸收"已预扣、未提交"的在途噪音）·
        降级直通 {{ metrics?.degradeOnError }} 次（Redis 异常时自动放行，正确性交给数据库）
      </div>
    </el-card>

    <!-- 计数器现场 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">🧮 活跃计数器现场（Redis 预计数 vs 数据库真值）</div>
      </template>
      <el-table :data="counters" size="small" border v-if="counters.length">
        <el-table-column prop="key" label="计数器 key" min-width="240" />
        <el-table-column prop="type" label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'CLASS' ? 'primary' : 'success'" effect="plain">
              {{ row.type === 'CLASS' ? '教学班' : '时间片' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="对象" min-width="160" />
        <el-table-column prop="redisRemaining" label="Redis 剩余" width="110" />
        <el-table-column prop="dbRemaining" label="DB 真值" width="100" />
        <el-table-column label="偏差" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.drift === 0 ? 'success' : 'warning'" effect="dark">{{ row.drift }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无活跃计数器 —— 去「⚡ 并发压测」跑一次「预扣挡量」，或正常选课/预约一次即可看到" />
      <div class="hint">
        偏差 = Redis 预计数 − DB 真值；0 表示完全一致。出现非 0 且持续 30 秒以上，对账会自动纠偏并计数。
      </div>
    </el-card>

    <!-- 与 12306 的对照 -->
    <el-card shadow="never" class="section">
      <template #header><div class="card-head">🤝 与 12306 令牌桶的异同（面试口径）</div></template>
      <el-table :data="compareRows" size="small" border>
        <el-table-column prop="dim" label="维度" width="140" />
        <el-table-column prop="ref" label="12306 令牌桶" min-width="240" />
        <el-table-column prop="mine" label="本项目的预扣闸门" min-width="280" />
      </el-table>
      <div class="hint">
        一句话：12306 让 Redis 当唯一真相（靠删桶自愈）；本项目让 Redis 当预计数（靠 DB 兜底 + 对账纠偏）——
        同样能挡量，但 Redis 抖动/清空不会造成超卖，正确性零妥协。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPreDeductState, reconcile } from '../api/prededuct'
import { runStress } from '../api/stress'

const metrics = ref(null)
const counters = ref([])
const reconciling = ref(false)
const demoRunning = ref(false)

/** 一键演示：跑「预扣挡量」压测场景（1000 并发抢 10 个课额），跑完刷新现场 */
async function oneClickDemo() {
  demoRunning.value = true
  try {
    const r = await runStress('PREDEDUCT', 1000)
    const intercepted = r.detail['Redis 拦截（未查库/未占连接）']
    ElMessageBox.alert(
      `<div style="line-height:2">
        结论：<b style="color:${r.conclusion === 'PASS' ? '#059669' : '#dc2626'}">${r.conclusion}</b><br/>
        并发请求 <b>${r.requests}</b> · 成功 <b>${r.success}</b> · 总耗时 <b>${r.durationMs} ms</b><br/>
        Redis 拦截 <b>${intercepted}</b>（未占数据库连接）· 放行到数据库 <b>${r.detail['放行到数据库']}</b><br/>
        失败分布：${Object.entries(r.failures).map(([k, v]) => k + ' × ' + v).join('、') || '无'}
      </div>`,
      '预扣闸门压测结果',
      { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
    )
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    demoRunning.value = false
  }
}

const compareRows = [
  { dim: 'Redis 角色', ref: '唯一真相（数据库无唯一索引兜底）', mine: '预计数（真相始终在 MySQL）' },
  { dim: 'Redis 不可用', ref: '购票不可用（取不到令牌）', mine: '自动降级直通数据库，只损失性能不损失正确性' },
  { dim: '状态漂移', ref: '删桶重建（全量刷新）', mine: '30 秒对账纠偏（只拉回真值，不打断在途请求）' },
  { dim: '跨段扣减', ref: '区间扣减：跨站全成或全败', mine: '跨时间片扣减：多片 all-or-nothing（同一 Lua 模式）' },
  { dim: '失败反馈', ref: '令牌不足即返回', mine: 'Redis 拦截即返回（都不占数据库连接）' }
]

async function load() {
  try {
    const d = await getPreDeductState()
    metrics.value = d.metrics
    counters.value = d.counters || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function doReconcile() {
  reconciling.value = true
  try {
    const r = await reconcile()
    ElMessage.success(r.skipped ? `跳过：${r.skipped}` : `对账完成：检查 ${r.checked} 个，纠偏 ${r.fixed} 个`)
    load()
  } catch (e) {
    /* ignore */
  } finally {
    reconciling.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title { margin: 0 0 6px; font-size: 20px; color: #1e293b; }
.page-sub { margin: 0 0 16px; color: #64748b; font-size: 13px; line-height: 1.8; }
.section { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; color: #1e293b; }

.flow { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.node { border: 1px solid #e2e8f0; border-radius: 10px; padding: 10px 14px; font-size: 13px; text-align: center; background: #f8fafc; color: #334155; }
.node small { color: #94a3b8; font-size: 11.5px; }
.node.primary { background: #eff6ff; border-color: #bfdbfe; color: #1d4ed8; font-weight: 600; }
.node.danger { background: #fef2f2; border-color: #fecaca; color: #b91c1c; font-weight: 600; }
.node.success { background: #ecfdf5; border-color: #a7f3d0; color: #047857; font-weight: 600; }
.node.warn { background: #fffbeb; border-color: #fde68a; color: #b45309; font-weight: 600; }
.node.info { background: #f0f9ff; border-color: #bae6fd; color: #0369a1; font-weight: 600; }
.node.ghost { background: #fff; color: #64748b; }
.arrow { color: #94a3b8; font-size: 16px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 12px; text-align: center; }
.stat-n { font-size: 22px; font-weight: 700; color: #2563eb; }
.stat-n.danger { color: #dc2626; }
.stat-n.warn { color: #d97706; }
.stat-n small { font-size: 13px; margin-left: 2px; }
.stat-l { font-size: 12px; color: #64748b; margin-top: 4px; }

.hint { margin-top: 12px; font-size: 12.5px; color: #64748b; line-height: 1.8; background: #f8fafc; padding: 8px 10px; border-radius: 8px; }
code { font-size: 12px; color: #475569; }
</style>
