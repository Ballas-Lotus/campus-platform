import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { getCourseStock } from '../api/stock'
import { StockStream } from '../realtime/sseClient'
import { TOKEN_KEY } from '../api/request'

/**
 * 阶段 20：名额实时同步 store（读侧）。
 *
 * 响应管线（信号与降级轮询共用同一套）：
 *   收到信号 → ① 立即乐观渲染（只接受更大版本号，乱序丢弃）
 *            → ② 防抖 150ms 合并 + 最大等待 500ms（高频信号永不饿死查询）
 *            → ③ 窗口结束按最新版本号查询校准（服务端为准，纠正不突兀）
 *   建连/重连成功 → 无条件全量查询拉齐
 *   长连接不可用 → 自动降级轮询（断开 15s / 已连 60s 校准；校准不带版本强制走 L2）
 *
 * 边界：remain 仅供展示优化；"能不能选上"一律以服务端（闸门 + DB）为准。
 */

const DEBOUNCE_MS = 150
const MAX_WAIT_MS = 500
const POLL_FALLBACK_MS = 15000
const POLL_CALIBRATE_MS = 60000

export const useStockStore = defineStore('stock', {
  state: () => ({
    conn: 'idle',            // idle | connecting | open | fallback
    classes: {},             // classId -> { remain, v, capacity, optimistic, correctedAt }
    classCourse: {},         // classId -> courseId（信号反查课程）
    courseCver: {},          // courseId -> 服务端当前课程版本
    courseLoaded: {},        // courseId -> 班级全量已加载（可推导聚合）
    courses: [],             // 订阅中的课程（抽屉打开时）
    stream: null,
    lastSettlement: null,    // 阶段 21：最近一条受理回执 { classId, ok, reason, ts }（视图 watch 它做回执处理）
    _pending: new Set(),
    _debounce: null,
    _maxWait: null,
    _poll: null
  }),

  getters: {
    /** 实时余量（无实时数据返回 null，由视图回退到快照值） */
    remainOf: (s) => (classId) => {
      const c = s.classes[classId]
      return c ? c.remain : null
    },
    /** 课程聚合（仅当该课程班级全量已知）：{ selected, capacity } —— 供课程列表实时补丁 */
    courseAgg: (s) => (courseId) => {
      if (!s.courseLoaded[courseId]) return null
      let selected = 0
      let capacity = 0
      for (const [cid, c] of Object.entries(s.classes)) {
        if (s.classCourse[cid] === courseId && c.capacity) {
          selected += Math.max(0, c.capacity - c.remain)
          capacity += c.capacity
        }
      }
      return capacity > 0 ? { selected, capacity } : null
    }
  },

  actions: {
    // ---------------- 生命周期 ----------------

    /** 订阅课程集合（抽屉打开时调用；空集合=停止） */
    setCourses(courseIds) {
      const list = [...new Set((courseIds || []).filter((x) => x != null))]
      if (!list.length) {
        this.stop()
        return
      }
      this.courses = list
      this._ensureStream()
      this.stream.subscribe(list)
      this._schedulePoll()
    },

    stop() {
      this.courses = []
      if (this.stream) this.stream.subscribe([])
      clearTimeout(this._debounce)
      clearTimeout(this._maxWait)
      clearTimeout(this._poll)
      this._debounce = null
      this._maxWait = null
      this._poll = null
      this._pending.clear()
    },

    _ensureStream() {
      if (this.stream) return
      this.stream = new StockStream({
        onReady: () => this._resync(),
        onSignal: (sig) => this.onSignal(sig),
        onSettled: (payload) => this.onSettled(payload),
        onState: (state) => this._onConnState(state),
        onAuthExpired: () => {
          localStorage.removeItem(TOKEN_KEY)
          ElMessage.warning('登录已过期，请重新登录')
          setTimeout(() => window.location.replace('/login'), 800)
        }
      })
    },

    /** 视图在加载完教学班列表后登记（建立 classId→courseId 映射与初值） */
    registerClasses(courseId, list) {
      for (const cls of list || []) {
        this.classCourse[cls.id] = courseId
        const cur = this.classes[cls.id]
        if (!cur) {
          this.classes[cls.id] = {
            remain: cls.available,
            v: -1,                 // 未知版本：任何服务端版本(≥0)都能覆盖
            capacity: cls.capacity,
            optimistic: false,
            correctedAt: 0
          }
        } else {
          cur.capacity = cls.capacity
        }
      }
      this.courseLoaded[courseId] = true
    },

    // ---------------- 信号与管线 ----------------

    /** 收到信号：① 立即渲染（按版本去重）→ ② 防抖合并 → ③ 窗口结束查询校准 */
    onSignal(sig) {
      if (!sig || sig.id == null || sig.v == null) return
      const classId = sig.id
      const cur = this.classes[classId]
      if (cur && sig.v <= cur.v) {
        // 等版本是重复帧（忽略）；低版本可能是"服务端结构重建后版本回落"（evict/重启），
        // 此时不能静默丢弃——触发一次防抖校准查询（查询是权威快照，会立即应用真相），
        // 否则页面可能一直停在旧数字直到 60s 轮询。
        if (cur && sig.v < cur.v) {
          const staleCourse = this.classCourse[classId]
          if (staleCourse != null) this._enqueueRefresh(staleCourse)
        }
        return
      }
      const courseId = this.classCourse[classId]
      if (!cur && courseId == null) return              // 未知班级且无法定位课程：忽略（不影响正确性）
      this.classes[classId] = {
        remain: sig.remain,
        v: sig.v,
        capacity: cur ? cur.capacity : null,
        optimistic: false,
        correctedAt: cur ? cur.correctedAt : 0
      }
      if (courseId != null) this._enqueueRefresh(courseId)
    },

    /** 自己的选课/退课成功后立即应用（乐观 ±1；随后信号/查询负责校准） */
    applyOwnDelta(classId, delta) {
      const c = this.classes[classId]
      const courseId = this.classCourse[classId]
      if (c && c.capacity != null) {
        c.remain = Math.min(c.capacity, Math.max(0, c.remain + delta))
        c.optimistic = true
      }
      if (courseId != null) this._enqueueRefresh(courseId)
    },

    /**
     * 阶段 21：受理响应携带的服务端真相（remain + 版本）——比 applyOwnDelta 更准（并发下不会偏）。
     * 版本只增不减：旧响应不覆盖新信号。
     */
    applyServerValue(classId, remain, version) {
      const c = this.classes[classId]
      const courseId = this.classCourse[classId]
      if (c && (version == null || c.v <= version)) {
        c.remain = remain
        if (version != null) c.v = version
        c.optimistic = false
      }
      if (courseId != null) this._enqueueRefresh(courseId)
    },

    /** 阶段 21：受理回执（settled）——视图 watch lastSettlement 做「保持已选 / 回滚渲染」 */
    onSettled(payload) {
      if (!payload || payload.classId == null) return
      this.lastSettlement = { ...payload, ts: Date.now() }
    },

    _enqueueRefresh(courseId) {
      this._pending.add(courseId)
      clearTimeout(this._debounce)
      this._debounce = setTimeout(() => this._flush(), DEBOUNCE_MS)
      if (!this._maxWait) {
        // 最大等待：信号持续高频时窗口会被反复重置，必须强制触发一次查询（防饿死）
        this._maxWait = setTimeout(() => this._flush(), MAX_WAIT_MS)
      }
    },

    async _flush() {
      clearTimeout(this._debounce)
      clearTimeout(this._maxWait)
      this._debounce = null
      this._maxWait = null
      const ids = [...this._pending]
      this._pending.clear()
      await Promise.all(ids.map((id) => this._refresh(id, this.courseCver[id])))
    },

    /** 重连/建连成功：无条件拉齐（不带版本，强制走 L2/L3） */
    async _resync() {
      await Promise.all(this.courses.map((id) => this._refresh(id, null)))
    },

    async _refresh(courseId, version) {
      try {
        const data = await getCourseStock(courseId, version)
        this.courseCver[courseId] = data.cver
        for (const cl of data.classes || []) {
          if (this.classCourse[cl.id] == null) this.classCourse[cl.id] = courseId
          const cur = this.classes[cl.id]
          if (!cur) {
            this.classes[cl.id] = {
              remain: cl.remain, v: cl.v, capacity: cl.capacity, optimistic: false, correctedAt: 0
            }
          } else {
            // 服务端查询 = 权威快照：数值永远采纳；版本只升高（高水位），不降低——
            // 阶段 20 潜在边界修复：结构被 evict 重建后版本从 0 重新计数，
            // 若按"老版本丢弃"处理，持有高版本的客户端会长期拒绝应用真相（显示卡死）。
            // 信号仍严格按版本去重（洪峰乱序保护），查询负责把客户端拉回真相。
            const changed = cur.remain !== cl.remain
            cur.remain = cl.remain
            cur.capacity = cl.capacity
            if (cl.v > cur.v) cur.v = cl.v
            cur.optimistic = false
            if (changed) {
              cur.correctedAt = Date.now()   // 供视图做平滑高亮
              setTimeout(() => {
                if (this.classes[cl.id] === cur) cur.correctedAt = 0
              }, 1300)
            }
          }
        }
        this.courseLoaded[courseId] = true
      } catch {
        /* 拦截器已提示；保留乐观值，等待信号/下一轮轮询 */
      }
    },

    // ---------------- 连接状态与降级轮询 ----------------

    _onConnState(state) {
      this.conn = state
      this._schedulePoll()
      if (state === 'open') this._resync()   // 状态切到 open 也拉齐一次（幂等，成本极低）
    },

    /** 轮询兜底：断开 15s（±抖动）；已连 60s 校准。两者复用同一 _refresh 管线 */
    _schedulePoll() {
      clearTimeout(this._poll)
      if (!this.courses.length) return
      const base = this.conn === 'open' ? POLL_CALIBRATE_MS : POLL_FALLBACK_MS
      const delay = base + Math.random() * base * 0.2
      this._poll = setTimeout(async () => {
        await Promise.all(this.courses.map((id) => this._refresh(id, null)))
        this._schedulePoll()
      }, delay)
    }
  }
})
