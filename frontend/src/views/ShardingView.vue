<template>
  <div>
    <el-card shadow="never" class="head">
      <div class="head-row">
        <div>
          <h3>🧩 分库分表（ShardingSphere-JDBC）</h3>
          <p class="sub">
            2 库 × 4 表 = 8 分片 · 基因法单号 · t_user 列加密（AES）· 读扩散索引表 · Sentinel 限流
          </p>
        </div>
        <el-button text type="info" @click="loadAll">刷新</el-button>
      </div>
      <div class="topo" v-if="topology">
        <el-tag type="primary" effect="plain">库：{{ topology.databases.join(' / ') }}</el-tag>
        <el-tag type="success" effect="plain">分片键公式：{{ topology.shardingRule }}</el-tag>
        <el-tag type="warning" effect="plain">加密列：{{ topology.encryptColumns.join('、') }}</el-tag>
      </div>
      <div class="tables" v-if="topology">
        <div class="tbl-line"><b>分片表</b>：{{ topology.shardedTables.join('、') }}</div>
        <div class="tbl-line"><b>广播表</b>：{{ topology.broadcastTables.join('、') }}（两库全量同构副本）</div>
        <div class="tbl-line"><b>索引表</b>：{{ topology.indexTables.join('、') }}（登录标识 → user_id，单分片点查）</div>
        <div class="tbl-line gene">💡 基因法：{{ topology.gene }}</div>
      </div>
    </el-card>

    <!-- 各分片数据分布 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">📊 各分片物理行数（绕过 ShardingSphere 直连数表）</div>
      </template>
      <el-radio-group v-model="activeTable" size="small" class="tbl-switch">
        <el-radio-button v-for="t in shardedTables" :key="t" :value="t">{{ t }}</el-radio-button>
      </el-radio-group>
      <div class="bars" v-if="activeCounts.length">
        <div v-for="c in activeCounts" :key="c.target" class="bar-row">
          <span class="bar-label">{{ c.target }}</span>
          <div class="bar-track">
            <div class="bar" :style="{ width: barWidth(c.rows) }"></div>
          </div>
          <span class="bar-num">{{ c.rows.toLocaleString() }}</span>
        </div>
        <div class="bar-total">
          合计 {{ activeTotal.toLocaleString() }} 行（同表 8 分片之和 = 单体库该表行数）
        </div>
      </div>
      <el-empty v-else description="加载中…" />
      <div class="tbl-line bw" v-if="broadcastCounts">
        <b>广播表（每库各一份）</b>：
        <span v-for="(v, k) in broadcastCounts" :key="k" class="bw-item">{{ k }}={{ v.toLocaleString() }}</span>
      </div>
    </el-card>

    <!-- 基因法验证器 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">🧬 基因法路由验证器（按 user_id 查 与 按单号查 → 同一分片）</div>
      </template>
      <div class="verify-form">
        <el-input v-model="form.userId" placeholder="user_id（默认当前用户）" style="width: 220px" />
        <el-input v-model="form.reservationNo" placeholder="预约单号（留空自动取该用户最近一条）" style="width: 380px" />
        <el-button type="primary" :loading="verifying" @click="verify">验证</el-button>
      </div>
      <div class="verify-result" v-if="route">
        <div class="vr-row" v-if="route.userShard">
          <el-tag type="info" effect="dark">按 user_id = {{ route.userId }}</el-tag>
          → 分片 <b>{{ route.userShard.shard }}</b>（{{ route.userShard.database }} · 表后缀 {{ route.userShard.tableSuffix }}）
        </div>
        <div class="vr-row" v-if="route.noShard">
          <el-tag type="info" effect="dark">按单号尾 6 位基因 {{ route.gene }}</el-tag>
          → 分片 <b>{{ typeof route.noShard === 'string' ? route.noShard : route.noShard.shard }}</b>
          <template v-if="typeof route.noShard !== 'string'">（{{ route.noShard.database }} · 表后缀 {{ route.noShard.tableSuffix }}）</template>
          <el-tag v-if="route.sameShard === true" type="success" class="same-tag">✓ 与 user_id 同分片</el-tag>
        </div>
        <div class="vr-sql" v-if="route.verifyByUserId">
          <code>{{ route.verifyByUserId.sql }}</code> → 命中 {{ route.verifyByUserId.count }} 行（{{ route.verifyByUserId.routed }}）
        </div>
        <div class="vr-sql" v-if="route.verifyByNo">
          <code>{{ route.verifyByNo.sql }}</code> → {{ route.verifyByNo.found ? `查到：id=${route.verifyByNo.id} / user_id=${route.verifyByNo.userId} / ${route.verifyByNo.status}` : '未查到' }}（{{ route.verifyByNo.routed }}）
        </div>
        <div class="vr-note">真查询执行在真实分片之上——查得到即路由正确（路由错误会因落错分片而查不到）。</div>
      </div>
    </el-card>

    <!-- Sentinel -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">🚦 Sentinel 限流（选课接口）</div>
      </template>
      <div class="sentinel" v-if="sentinel">
        <div class="s-item"><div class="s-n">{{ sentinel.qpsLimit }}</div><div class="s-l">QPS 阈值（快速失败）</div></div>
        <div class="s-item"><div class="s-n">{{ sentinel.blockedCount }}</div><div class="s-l">累计拦截次数</div></div>
        <div class="s-item"><div class="s-n">{{ sentinel.errorCode }}</div><div class="s-l">拦截返回错误码</div></div>
        <div class="s-item"><div class="s-n">{{ sentinel.resource }}</div><div class="s-l">限流资源（选课入口）</div></div>
      </div>
      <div class="vr-note">
        限流是"入口保护"：超阈值请求在切面层快速失败（不产生任何 DB 动作），与"防超卖"（原子条件更新）两层互补。
        去「⚡ 并发压测」运行「Sentinel 限流」场景（120 并发抢课）可现场触发拦截。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getShardingInfo, getShardingRoute, getSentinelInfo } from '../api/sharding'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const topology = ref(null)
const shardedTables = ref([])
const counts = ref({})
const broadcastCounts = ref(null)
const sentinel = ref(null)
const route = ref(null)
const verifying = ref(false)
const activeTable = ref('t_reservation')
const form = ref({ userId: '', reservationNo: '' })

const activeCounts = computed(() => counts.value[activeTable.value] || [])
const activeTotal = computed(() => activeCounts.value.reduce((s, c) => s + (c.rows > 0 ? c.rows : 0), 0))
const maxRows = computed(() => Math.max(1, ...activeCounts.value.map((c) => c.rows)))

function barWidth(rows) {
  return Math.max(2, Math.round((rows / maxRows.value) * 100)) + '%'
}

async function loadAll() {
  try {
    const d = await getShardingInfo()
    topology.value = d.topology
    shardedTables.value = d.topology.shardedTables
    counts.value = d.rowCounts.sharded
    broadcastCounts.value = d.rowCounts.broadcastPerDb
  } catch (e) {
    /* 拦截器提示 */
  }
  getSentinelInfo().then((d) => (sentinel.value = d)).catch(() => {})
}

async function verify() {
  verifying.value = true
  try {
    const params = {}
    if (form.value.userId || userStore.info?.userId) {
      params.userId = form.value.userId || userStore.info.userId
    }
    if (form.value.reservationNo) {
      params.reservationNo = form.value.reservationNo.trim()
    }
    route.value = await getShardingRoute(params)
  } catch (e) {
    ElMessage.error('验证失败：' + (e?.message || e))
  } finally {
    verifying.value = false
  }
}

onMounted(async () => {
  if (userStore.info?.userId) {
    form.value.userId = String(userStore.info.userId)
  }
  await loadAll()
  verify()
})
</script>

<style scoped>
.head {
  border-radius: 12px;
  background: linear-gradient(120deg, #eff6ff, #f0fdfa);
}
.head-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.head h3 {
  margin: 0 0 6px;
  color: #0f172a;
}
.sub {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}
.topo {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0 8px;
}
.tables .tbl-line {
  font-size: 12.5px;
  color: #475569;
  line-height: 2;
}
.tbl-line.gene {
  color: #2563eb;
}
.section {
  margin-top: 18px;
  border-radius: 12px;
}
.card-head {
  font-weight: 600;
  color: #1e293b;
}
.tbl-switch {
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-label {
  width: 220px;
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #475569;
  flex-shrink: 0;
}
.bar-track {
  flex: 1;
  background: #f1f5f9;
  border-radius: 6px;
  height: 18px;
  overflow: hidden;
}
.bar {
  height: 100%;
  background: linear-gradient(90deg, #60a5fa, #2563eb);
  border-radius: 6px;
  transition: width 0.4s;
}
.bar-num {
  width: 90px;
  text-align: right;
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #0f172a;
}
.bar-total {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}
.bw {
  margin-top: 10px;
  font-size: 12px;
  color: #64748b;
}
.bw-item {
  margin-right: 12px;
  font-family: Consolas, monospace;
}
.verify-form {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.verify-result {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px 14px;
}
.vr-row {
  font-size: 13px;
  color: #334155;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.same-tag {
  margin-left: 6px;
}
.vr-sql {
  font-size: 12px;
  color: #475569;
  margin: 6px 0;
  word-break: break-all;
}
.vr-sql code {
  background: #eef2ff;
  color: #3730a3;
  padding: 2px 6px;
  border-radius: 6px;
}
.vr-note {
  margin-top: 10px;
  font-size: 12px;
  color: #94a3b8;
}
.sentinel {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.s-item {
  flex: 1;
  min-width: 140px;
  text-align: center;
  background: #f8fafc;
  border-radius: 10px;
  padding: 12px 6px;
}
.s-item .s-n {
  font-size: 20px;
  font-weight: 700;
  color: #2563eb;
  font-family: Consolas, monospace;
}
.s-item .s-l {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 3px;
}
</style>
