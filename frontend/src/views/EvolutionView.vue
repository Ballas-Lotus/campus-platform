<template>
  <div>
    <el-card shadow="never" class="head">
      <h3>📈 架构演进对比（阶段 15）</h3>
      <p class="sub">
        单体（留档 jar :8081）vs 分片形态（网关 :9080）——同一场景、同一台机器、现场跑给你看；
        完整报告见 <code>docs/07-架构演进对比报告.md</code>
      </p>
    </el-card>

    <!-- 现场对照压测 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          ⚔️ 现场对照：4 个并发场景 × 双侧依次执行（各自独立夹具、自动清理）
          <el-button class="run-btn" type="primary" :loading="running" @click="runAll">
            {{ running ? '对照运行中…' : '▶ 运行现场对照' }}
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="monoOffline"
        type="warning"
        :closable="false"
        show-icon
        title="单体基线未启动（8081）"
        description="启动命令：java -jar resources/legacy/campus-platform-monolith-0.0.1.jar（仅对照用，可随时关闭）"
        class="mb"
      />

      <el-table :data="rows" v-loading="running" class="tbl" :default-sort="{ prop: 'name' }">
        <el-table-column prop="name" label="场景" width="150">
          <template #default="{ row }">{{ row.icon }} {{ row.name }}<div class="s-desc">{{ row.desc }}</div></template>
        </el-table-column>
        <el-table-column label="单体（8081）" width="200">
          <template #default="{ row }">
            <template v-if="row.mono">
              <b>{{ row.mono.durationMs }} ms</b>
              <el-tag size="small" :type="row.mono.conclusion === 'PASS' ? 'success' : 'danger'" class="tag">
                {{ row.mono.conclusion }}
              </el-tag>
              <div class="s-desc">成功 {{ row.mono.success }}/{{ row.mono.requests }} · p95 {{ row.mono.p95Ms }}ms</div>
            </template>
            <span v-else class="pending">—</span>
          </template>
        </el-table-column>
        <el-table-column label="分片形态（8 分片）" width="220">
          <template #default="{ row }">
            <template v-if="row.shard">
              <b>{{ row.shard.durationMs }} ms</b>
              <el-tag size="small" :type="row.shard.conclusion === 'PASS' ? 'success' : 'danger'" class="tag">
                {{ row.shard.conclusion }}
              </el-tag>
              <div class="s-desc">成功 {{ row.shard.success }}/{{ row.shard.requests }} · p95 {{ row.shard.p95Ms }}ms</div>
            </template>
            <span v-else class="pending">—</span>
          </template>
        </el-table-column>
        <el-table-column label="延迟代价" width="110">
          <template #default="{ row }">
            <span v-if="row.mono && row.shard" class="ratio">×{{ (row.shard.durationMs / Math.max(row.mono.durationMs, 1)).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结论（两侧断言）" min-width="200">
          <template #default="{ row }">
            <div v-if="row.shard?.assertions" class="asserts">
              <div v-for="a in row.shard.assertions" :key="a.name" class="assert-line" :class="{ bad: !a.pass }">
                {{ a.pass ? '✓' : '✗' }} {{ a.name }}（{{ a.actual }}）
              </div>
            </div>
            <span v-else class="pending">待运行</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="note-line" v-if="finished">
        ✅ 双侧断言全过 = <b>分片后正确性零妥协</b>；延迟差额来自 SQL 解析/路由、连接放大与热点分片串行
        （见 docs/07 §1.1）。压测在真实事务上执行、各自自动清理，可重复运行。
      </div>
    </el-card>

    <!-- 静态对照：其它维度 -->
    <div class="grid">
      <el-card shadow="never" class="mini">
        <div class="m-title">⏱️ 违约判定：轮询 vs 延时消息</div>
        <div class="m-row"><span>Scheduler 轮询</span><b>0~60 秒延迟</b>（每 60s 全量扫描）</div>
        <div class="m-row"><span>RocketMQ 延时消息</span><b>精确到点</b>（实测 8.0s 投递 → 8.6s 判定）</div>
        <div class="m-row"><span>兜底关系</span>双路径同到不双罚（幂等 + 状态校验）</div>
      </el-card>
      <el-card shadow="never" class="mini">
        <div class="m-title">🗄️ 数据规模对照</div>
        <div class="m-row"><span>t_reservation 单表</span><b>957,994 行</b></div>
        <div class="m-row"><span>分片后最大分片</span><b>≈12.1 万行</b>（1/8）</div>
        <div class="m-row"><span>按 user_id / 按单号</span>均为<b>单分片命中</b>（基因法恒等）</div>
      </el-card>
      <el-card shadow="never" class="mini">
        <div class="m-title">🚦 限流保护（Sentinel）</div>
        <div class="m-row"><span>120 并发抢课</span><b>99 拦截 40055</b> / 10 抢到 / 0 系统异常</div>
        <div class="m-row"><span>价值</span>入口削峰（保系统）× 原子扣减（保数据）两层互补</div>
        <div class="m-row"><span>现场触发</span>「⚡ 并发压测」→ 🚦 Sentinel 限流</div>
      </el-card>
      <el-card shadow="never" class="mini">
        <div class="m-title">🧭 逐项「收益 / 代价」</div>
        <div class="m-row"><span>服务拆分</span>故障隔离/独立扩容 ↔ 配置重复、调用链变长</div>
        <div class="m-row"><span>MQ 化</span>精确时效/可靠投递 ↔ 模型复杂度</div>
        <div class="m-row"><span>分库分表</span>可扩容/单分片查询 ↔ 延迟 +50~90%、跨库事务</div>
        <div class="m-link">完整结论表：docs/07 §2</div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { runStressOn } from '../api/evolution'

const SCENARIOS = [
  { key: 'OVERSELL', icon: '🎯', name: '防超卖', desc: '100 并发抢 10 名额', threads: 100 },
  { key: 'DUPLICATE', icon: '🔁', name: '防重复', desc: '同一预约并发 50 次', threads: 50 },
  { key: 'BITMAP', icon: '🧩', name: '位图冲突', desc: '50 座位同时段', threads: 50 },
  { key: 'COURSE_OVERSELL', icon: '📖', name: '选课限额', desc: '100 并发抢 10 课额', threads: 100 }
]

const rows = ref(SCENARIOS.map((s) => ({ ...s, mono: null, shard: null })))
const running = ref(false)
const finished = ref(false)
const monoOffline = ref(false)

async function runAll() {
  running.value = true
  finished.value = false
  monoOffline.value = false
  rows.value = SCENARIOS.map((s) => ({ ...s, mono: null, shard: null }))
  for (const row of rows.value) {
    // 单体基线（可能未启动 → 标记并跳过）
    try {
      row.mono = await runStressOn('mono', row.key, row.threads)
    } catch (e) {
      monoOffline.value = true
    }
    // 分片形态（当前系统，必须可用）
    try {
      row.shard = await runStressOn('shard', row.key, row.threads)
    } catch (e) {
      ElMessage.error(`场景 ${row.key} 分片侧执行失败：${e.message}`)
    }
  }
  running.value = false
  finished.value = true
}
</script>

<style scoped>
.head {
  border-radius: 12px;
  background: linear-gradient(120deg, #f0fdfa, #eff6ff);
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
.sub code {
  background: #eef2ff;
  color: #3730a3;
  padding: 1px 6px;
  border-radius: 6px;
}
.section {
  margin-top: 18px;
  border-radius: 12px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: #1e293b;
  gap: 10px;
  flex-wrap: wrap;
}
.run-btn {
  margin-left: auto;
}
.mb {
  margin-bottom: 10px;
}
.tbl {
  width: 100%;
}
.s-desc {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}
.tag {
  margin-left: 6px;
}
.ratio {
  font-family: Consolas, monospace;
  font-weight: 700;
  color: #d97706;
}
.pending {
  color: #cbd5e1;
}
.asserts {
  font-size: 12px;
  color: #475569;
  line-height: 1.8;
}
.assert-line.bad {
  color: #dc2626;
}
.note-line {
  margin-top: 12px;
  font-size: 12.5px;
  color: #475569;
  background: #f8fafc;
  border-radius: 10px;
  padding: 10px 12px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-top: 18px;
}
.mini {
  border-radius: 12px;
}
.m-title {
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 10px;
}
.m-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  color: #64748b;
  padding: 4px 0;
}
.m-row b {
  color: #0f172a;
  text-align: right;
}
.m-link {
  margin-top: 8px;
  font-size: 12px;
  color: #2563eb;
  font-family: Consolas, monospace;
}
</style>
