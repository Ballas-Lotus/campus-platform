<template>
  <div>
    <h2 class="page-title">⚡ 并发压测</h2>
    <p class="page-sub">
      服务端线程池同时发起 N 个真实事务请求（打真实数据库行锁 / 唯一索引 / 原子条件更新），
      压测结束后自动清理本轮写入，不污染演示数据。
    </p>

    <el-alert
      class="note"
      type="info"
      :closable="false"
      show-icon
      title="八个场景覆盖四道防线 + 三层新武器"
      description="防线：防超卖（原子条件更新）· 防重复（用户行锁 + 唯一索引）· 位图冲突（96 格位图）· 选课限额（条件更新 + 用户行锁）｜ 阶段 14~18 新增：Sentinel 限流 · 预扣闸门（Redis 挡量）· 接口幂等 · 分布式锁"
    />

    <div class="scenario-grid">
      <el-card v-for="s in scenarios" :key="s.key" shadow="hover" class="sc-card">
        <div class="sc-head">
          <div class="sc-icon">{{ s.icon }}</div>
          <div>
            <div class="sc-name">{{ s.name }}</div>
            <div class="sc-desc">{{ s.desc }}</div>
          </div>
        </div>
        <div class="sc-expected">预期：{{ s.expected }}</div>

        <el-button
          type="primary"
          class="sc-btn"
          :loading="running === s.key"
          :disabled="!!running && running !== s.key"
          @click="run(s)"
        >
          {{ running === s.key ? '压测进行中…' : '▶ 开始压测' }}
        </el-button>

        <div v-if="results[s.key]" class="sc-result">
          <div class="res-head">
            <el-tag :type="results[s.key].conclusion === 'PASS' ? 'success' : 'danger'" effect="dark" size="small">
              {{ results[s.key].conclusion }}
            </el-tag>
            <span class="res-stat">成功 <b>{{ results[s.key].success }}</b> / {{ results[s.key].requests }}</span>
            <span class="res-stat">总耗时 <b>{{ results[s.key].durationMs }}</b> ms</span>
          </div>

          <div class="res-line">
            时延：P50 {{ results[s.key].p50Ms }}ms · P95 {{ results[s.key].p95Ms }}ms · max {{ results[s.key].maxMs }}ms
          </div>

          <div class="res-line">
            失败分布：
            <template v-if="Object.keys(results[s.key].failures).length">
              <el-tag
                v-for="(v, code) in results[s.key].failures"
                :key="code"
                size="small"
                type="warning"
                effect="plain"
                class="fail-tag"
              >{{ label(code) }} × {{ v }}</el-tag>
            </template>
            <span v-else class="muted">无失败</span>
          </div>

          <div class="asserts">
            <div
              v-for="(a, i) in results[s.key].assertions"
              :key="i"
              class="assert"
              :class="{ fail: !a.pass }"
            >
              {{ a.pass ? '✓' : '✗' }} {{ a.name }}
              <span class="muted">（期望 {{ a.expected }} / 实际 {{ a.actual }}）</span>
            </div>
          </div>

          <div class="res-detail">
            <span v-for="(v, k) in results[s.key].detail" :key="k" class="detail-item">
              {{ k }}：<b>{{ v }}</b>
            </span>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { runStress } from '../api/stress'

const scenarios = [
  {
    key: 'OVERSELL',
    icon: '🎯',
    name: '防超卖',
    desc: '100 个不同用户并发抢「压测专用场地」10 个名额',
    expected: '恰好 10 人成功（booked=10），无超卖',
    threads: 100
  },
  {
    key: 'DUPLICATE',
    icon: '🔁',
    name: '防重复',
    desc: '同一预约并发重复提交 50 次（模拟反复点击/多端提交）',
    expected: '恰好 1 次成功（位图行锁 + uk_reserved 唯一索引）',
    threads: 50
  },
  {
    key: 'BITMAP',
    icon: '🧩',
    name: '位图冲突',
    desc: '同一用户并发抢 50 个不同座位的同一时段',
    expected: '恰好 1 个座位预约成功（96 格位图冲突检测）',
    threads: 50
  },
  {
    key: 'COURSE_OVERSELL',
    icon: '📖',
    name: '选课限额',
    desc: '100 个用户并发抢「压测班」10 个课额',
    expected: '恰好 10 人选中（selected=10），无超选',
    threads: 100
  },
  {
    key: 'RATE_LIMIT',
    icon: '🚦',
    name: 'Sentinel 限流',
    desc: '120 个用户并发抢课（QPS 阈值 20，经限流切面）',
    expected: '超阈值请求被限流快速失败（40055），放行部分照常抢名额',
    threads: 120
  },
  {
    key: 'PREDEDUCT',
    icon: '🔥',
    name: '预扣挡量',
    desc: '1000 个用户并发抢「压测班」10 个课额（先过 Redis 预扣闸门）',
    expected: '恰好 10 人放行到数据库、990 被 Redis 拦截（未占数据库连接）',
    threads: 1000
  },
  {
    key: 'IDEMPOTENT',
    icon: '♻️',
    name: '幂等重复提交',
    desc: '同一用户并发提交同一笔预约 100 次（模拟连点/重试）',
    expected: '恰好 1 次成功，其余被幂等拦截（40060）',
    threads: 100
  },
  {
    key: 'LOCK',
    icon: '🔒',
    name: '分布式锁',
    desc: '100 个线程同时抢同一把 Redis 分布式锁',
    expected: '互斥性违例 = 0，同一时刻只有 1 个线程进入临界区',
    threads: 100
  }
]

const CODE_LABELS = {
  40023: '时间冲突（业务拒绝）',
  40024: '名额已满（业务拒绝）',
  40026: '重复预约（业务拒绝）',
  40029: '惩罚受限（业务拒绝）',
  40052: '课额已满（业务拒绝）',
  40055: 'Sentinel 限流（快速失败）',
  40060: '幂等拦截（重复提交）',
  LOCK_NOT_ACQUIRED: '未抢到锁（立即放弃）',
  50000: '系统繁忙'
}

function label(code) {
  if (CODE_LABELS[code]) return CODE_LABELS[code]
  return code.startsWith('EX:') ? '系统异常' : code
}

const running = ref('')
const results = reactive({})

async function run(s) {
  running.value = s.key
  try {
    results[s.key] = await runStress(s.key, s.threads)
  } catch {
    /* 错误提示由 request.js 统一弹出 */
  } finally {
    running.value = ''
  }
}
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

.scenario-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.sc-card {
  border-radius: 12px;
}

.sc-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.sc-icon {
  font-size: 28px;
}

.sc-name {
  font-weight: 700;
  color: #1e293b;
  font-size: 15px;
}

.sc-desc {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.sc-expected {
  margin-top: 10px;
  font-size: 12px;
  color: #2563eb;
  background: #eff6ff;
  border-radius: 8px;
  padding: 6px 10px;
}

.sc-btn {
  margin-top: 12px;
  width: 100%;
}

.sc-result {
  margin-top: 14px;
  border-top: 1px dashed #e2e8f0;
  padding-top: 12px;
}

.res-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.res-stat {
  font-size: 12.5px;
  color: #475569;
}

.res-stat b {
  color: #2563eb;
  font-family: Consolas, monospace;
}

.res-line {
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
}

.fail-tag {
  margin-right: 6px;
}

.asserts {
  margin-top: 10px;
}

.assert {
  font-size: 12.5px;
  color: #15803d;
  line-height: 1.9;
}

.assert.fail {
  color: #dc2626;
  font-weight: 600;
}

.muted {
  color: #94a3b8;
  font-weight: 400;
  font-size: 12px;
  margin-left: 4px;
}

.res-detail {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: #64748b;
}

.detail-item b {
  color: #334155;
}
</style>
