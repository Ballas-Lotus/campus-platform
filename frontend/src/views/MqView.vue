<template>
  <div>
    <h2 class="page-title">📨 MQ 演示（RocketMQ）</h2>
    <p class="page-sub">
      阶段 13：延时消息（超时判爽约）· 幂等消费（两态 Lua）· 站内信异步通知 —— 复用本机 9876，topic 前缀 campus_* 隔离。
      <b>建议使用测试账号体验</b>（超时演示会施加 24h 禁约，测试账号下次登录自动重置）。
    </p>

    <el-alert v-if="info" class="note" type="info" :closable="false" show-icon
      :title="`NameServer ${info.namesrv} · Producer ${info.producerGroup}`"
      :description="info.topics.map(t => `${t.topic} → ${t.purpose}`).join('　|　')"
    />

    <div class="mq-grid">
      <!-- 场景 A：站内信 -->
      <el-card shadow="hover" class="mq-card">
        <div class="card-head">
          <span class="card-icon">✉️</span>
          <div>
            <div class="card-name">场景 A · 站内信（异步通知）</div>
            <div class="card-desc">发一条消息到 <code>campus_notification_topic</code>，消费者异步落库到站内信</div>
          </div>
        </div>
        <el-button type="primary" :loading="notifyLoading" class="run-btn" @click="runNotify">发送一条站内信</el-button>
        <div v-if="notifyKey" class="result">
          <div class="ok">✓ 已投递，消息键：<code>{{ notifyKey }}</code></div>
          <div class="hint">看右上角 🔔 未读数 +1；预约/选课成功的通知走同一条通道。</div>
        </div>
      </el-card>

      <!-- 场景 B：幂等消费 -->
      <el-card shadow="hover" class="mq-card">
        <div class="card-head">
          <span class="card-icon">🔁</span>
          <div>
            <div class="card-name">场景 B · 幂等消费</div>
            <div class="card-desc">同一条消息（同 messageKey）连投 2 次，@MQIdempotent 两态 Lua 只放行一次</div>
          </div>
        </div>
        <el-button type="primary" :loading="idemLoading" class="run-btn" @click="runIdempotent">同一条消息投递 2 次</el-button>
        <div v-if="idem" class="result">
          <div>投递 <b>2</b> 条 → 落库 <b :class="{ ok: idem.count === 1, bad: idem.count !== 1 }">{{ idem.count }}</b> 条
            <el-tag v-if="idem.count === 1" type="success" size="small" class="tag">PASS · 幂等生效</el-tag>
            <el-tag v-else type="warning" size="small" class="tag">等待消费…</el-tag>
          </div>
          <div class="hint">消息键：<code>{{ idem.key }}</code>（消费中/已消费两态，重复投递直接跳过）</div>
        </div>
      </el-card>

      <!-- 阶段 19：批量消费 -->
      <el-card shadow="hover" class="mq-card wide">
        <div class="card-head">
          <span class="card-icon">📦</span>
          <div>
            <div class="card-name">场景 D · 批量消费（阶段 19）</div>
            <div class="card-desc">
              一次调用<b>突发投递</b> N 条消息 → 消费者"一次拉取、一次 ACK"处理一批
              （原生 DefaultMQPushConsumer 批量模式，batchMaxSize=20 —— rocketmq-spring 2.2.3 未暴露批量接口，
              故绕过 spring 封装；幂等语义与逐条版完全一致）
            </div>
          </div>
        </div>
        <div class="probe-row">
          <el-button type="primary" :loading="batchLoading" @click="runBatch">发送 50 条（一次调用）</el-button>
          <el-button :loading="statsLoading" @click="loadConsumerStats">刷新消费者指标</el-button>
        </div>
        <div class="batch-grid" v-if="batchStats">
          <div class="b-item"><b>{{ batchStats.batches }}</b><span>消费批次</span></div>
          <div class="b-item"><b>{{ batchStats.messages }}</b><span>消息总数</span></div>
          <div class="b-item"><b>{{ batchStats.inserted }}</b><span>落库成功</span></div>
          <div class="b-item"><b>{{ batchStats.duplicates }}</b><span>幂等跳过</span></div>
          <div class="b-item"><b>{{ batchStats.maxBatchSize }}</b><span>最大批大小</span></div>
          <div class="b-item"><b>{{ batchStats.avgBatchSize }}</b><span>平均批大小</span></div>
        </div>
        <div class="hint">发送后 1~2 秒自动刷新：批次数会明显小于消息数（如 50 条 → 3~4 批、批大小上限 20）；
        低流量下批大小自然退化为 1（与逐条消费等价）。</div>
      </el-card>

      <!-- 场景 C：延时消息 -->
      <el-card shadow="hover" class="mq-card wide">
        <div class="card-head">
          <span class="card-icon">⏱️</span>
          <div>
            <div class="card-name">场景 C · 延时消息 → 超时判爽约</div>
            <div class="card-desc">
              真实创建一笔预约 + 投递 <b>8 秒</b>延时消息到 <code>campus_reservation_timeout_topic</code>：
              到点未签到 → 判爽约（释放库存 + 位图 + 禁约 24h）
            </div>
          </div>
        </div>
        <el-button type="primary" :loading="timeoutLoading" class="run-btn" @click="runTimeout">开始演示（约 10 秒）</el-button>

        <div v-if="timeout" class="result">
          <div class="step-ok">✓ 已创建预约：{{ timeout.resourceName }} · {{ timeout.slotDate }} {{ timeout.startTime }}-{{ timeout.endTime }}
            （{{ timeout.reservationNo }}）</div>
          <div class="step-ok">✓ 延时消息已投递（{{ timeout.delayMs }}ms 后评估）</div>
          <div class="poll-box">
            <div class="poll-line">
              <span class="muted">实时状态（每 2s 轮询）：</span>
              <el-tag :type="statusTag(poll.status)" size="small">{{ statusLabel(poll.status) }}</el-tag>
              <span class="res-stat">片 booked：<b :class="{ ok: poll.booked === 0, bad: poll.booked > 0 }">{{ poll.booked }}</b></span>
              <span class="res-stat">禁约至：<b>{{ poll.penaltyUntil || '无' }}</b></span>
            </div>
            <el-tag v-if="poll.status === 'NO_SHOW'" type="success" class="tag">PASS · 延时到点已自动判爽约并回滚库存</el-tag>
            <el-tag v-else type="warning" class="tag">等待延时消息到点…</el-tag>
          </div>
          <div class="hint">
            对照：ReservationScheduler（每 60 秒轮询）保留为兜底 —— 消息丢失时仍能判爽约；
            两条路径同时到达也只处理一次（状态校验 + 幂等键）。
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getMqInfo, demoNotification, demoIdempotent, idempotentResult, demoTimeout, timeoutResult,
  sendBatchNotifications, getConsumerStats
} from '../api/mq'

const info = ref(null)

const batchLoading = ref(false)
const statsLoading = ref(false)
const batchStats = ref(null)

async function runBatch() {
  batchLoading.value = true
  try {
    const d = await sendBatchNotifications(50)
    ElMessage.success(`已批量投递 ${d.sent} 条（一次调用）`)
    setTimeout(loadConsumerStats, 1500)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    batchLoading.value = false
  }
}

async function loadConsumerStats() {
  statsLoading.value = true
  try {
    batchStats.value = await getConsumerStats()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    statsLoading.value = false
  }
}

const notifyLoading = ref(false)
const notifyKey = ref('')

const idemLoading = ref(false)
const idem = ref(null)
let idemTimer = null

const timeoutLoading = ref(false)
const timeout = ref(null)
const poll = ref({ status: '', booked: null, penaltyUntil: null })
let pollTimer = null

function statusLabel(s) {
  return { APPROVED: '已生效（等待到点）', NO_SHOW: '爽约（已释放）' }[s] || s || '—'
}

function statusTag(s) {
  return s === 'NO_SHOW' ? 'success' : s === 'APPROVED' ? 'warning' : 'info'
}

async function runNotify() {
  notifyLoading.value = true
  try {
    const d = await demoNotification()
    notifyKey.value = d.messageKey
    ElMessage.success('消息已投递（异步落库，稍后看铃铛）')
  } catch {
    /* 提示由拦截器统一弹出 */
  } finally {
    notifyLoading.value = false
  }
}

async function runIdempotent() {
  idemLoading.value = true
  if (idemTimer) clearInterval(idemTimer)
  try {
    const d = await demoIdempotent()
    idem.value = { key: d.messageKey, count: '…' }
    let attempts = 0
    idemTimer = setInterval(async () => {
      attempts += 1
      try {
        const r = await idempotentResult(idem.value.key)
        idem.value = { key: idem.value.key, count: r.count }
        if (r.count >= 1 || attempts >= 8) clearInterval(idemTimer)
      } catch {
        clearInterval(idemTimer)
      }
    }, 1200)
  } catch {
    /* 提示由拦截器统一弹出 */
  } finally {
    idemLoading.value = false
  }
}

async function runTimeout() {
  timeoutLoading.value = true
  if (pollTimer) clearInterval(pollTimer)
  try {
    const d = await demoTimeout()
    timeout.value = d
    poll.value = { status: d.before.status, booked: d.before.booked, penaltyUntil: null }
    pollTimer = setInterval(async () => {
      try {
        const r = await timeoutResult(d.reservationId)
        poll.value = r
        if (r.status === 'NO_SHOW') clearInterval(pollTimer)
      } catch {
        clearInterval(pollTimer)
      }
    }, 2000)
  } catch {
    /* 提示由拦截器统一弹出 */
  } finally {
    timeoutLoading.value = false
  }
}

onMounted(() => {
  getMqInfo().then((d) => (info.value = d)).catch(() => {})
  loadConsumerStats()
})

onUnmounted(() => {
  if (idemTimer) clearInterval(idemTimer)
  if (pollTimer) clearInterval(pollTimer)
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
  margin-bottom: 14px;
}

.note {
  border-radius: 10px;
  margin-bottom: 16px;
}

.mq-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(320px, 1fr));
  gap: 16px;
}

.mq-card {
  border-radius: 12px;
}

.mq-card.wide {
  grid-column: 1 / -1;
}

.card-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.card-icon {
  font-size: 26px;
}

.card-name {
  font-weight: 700;
  color: #1e293b;
  font-size: 15px;
}

.card-desc {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
  line-height: 1.6;
}

.run-btn {
  margin-top: 12px;
  width: 100%;
}

.result {
  margin-top: 14px;
  border-top: 1px dashed #e2e8f0;
  padding-top: 12px;
  font-size: 13px;
  color: #334155;
}

.ok {
  color: #15803d;
  font-weight: 600;
}

.bad {
  color: #dc2626;
  font-weight: 600;
}

.step-ok {
  color: #15803d;
  line-height: 2;
}

.poll-box {
  margin-top: 8px;
  background: #f8fafc;
  border-radius: 8px;
  padding: 10px 12px;
}

.poll-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.res-stat {
  font-size: 12.5px;
  color: #475569;
}

.muted {
  color: #94a3b8;
  font-size: 12.5px;
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.7;
}

.probe-row {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.batch-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  margin-top: 12px;
}

.b-item {
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 10px;
  padding: 10px;
  text-align: center;
}

.b-item b {
  display: block;
  font-size: 18px;
  color: #0369a1;
}

.b-item span {
  font-size: 11.5px;
  color: #64748b;
}

.tag {
  margin-top: 8px;
}

code {
  font-family: Consolas, monospace;
  color: #475569;
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 5px;
}
</style>
