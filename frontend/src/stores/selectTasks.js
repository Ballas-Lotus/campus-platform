import { defineStore } from 'pinia'

/**
 * 阶段 21.1 · 选课任务队列（回执确认制）
 *
 * 背景：受理制下"提交 → 确认"之间有一小段异步窗口（落库校验：时间冲突等）。
 * 旧前端在窗口内就把班级标成"已选（处理中）"，被拒时再回滚 —— 观感差、易误解。
 * 新模型：
 *   ① 提交后只生成一条"选课任务"，显示在任务面板（排队中 → 提交中 → 已受理 → 结果）；
 *   ② 班级卡片**不再**提前标"已选"，"我的课表"也不提前变化；
 *   ③ 回执（settled）到达后才落定任务：成功 → 提示并刷新（此时服务端才显示"已选"）；
 *      失败 → 提示原因，名额已由服务端自动退回，页面无需回滚（因为从未提前标记）。
 *
 * 串行语义（任务队列的本意）：同一时刻只有一个任务在途，前一个拿到回执（或超时兜底）后
 * 才提交下一个 —— 好处是第二个任务的时间冲突判定能基于第一个已落库的结果，结果顺序也可预期。
 *
 * 去重：同一教学班、同一课程（任一教学班）存在未终结任务时，拒绝再次入队。
 */

const ACTIVE = ['queued', 'submitting', 'accepted', 'unknown']
const FINAL = ['success', 'failed']

let seq = 0

function isActive(t) {
  return !!t && ACTIVE.includes(t.status)
}

export const useSelectTasksStore = defineStore('selectTasks', {
  state: () => ({
    /** 任务列表（按入队顺序）；字段：id/classId/courseId/courseName/className/status/reason/remain/version/createdAt/settledAt */
    tasks: []
  }),

  getters: {
    /** 某教学班是否有未终结任务 */
    activeOf: (s) => (classId) => s.tasks.find((t) => t.classId === classId && isActive(t)) || null,
    /** 某课程下是否有未终结任务（防止同一课程换班重复提交） */
    activeOfCourse: (s) => (courseId) => s.tasks.find((t) => t.courseId === courseId && isActive(t)) || null,
    pendingCount: (s) => s.tasks.filter((t) => isActive(t)).length,
    hasTasks: (s) => s.tasks.length > 0
  },

  actions: {
    /** 入队（去重）。返回 null = 已存在未终结的同班/同课程任务 */
    enqueue({ classId, courseId, courseName, className }) {
      if (this.activeOf(classId) || (courseId != null && this.activeOfCourse(courseId))) {
        return null
      }
      const task = {
        id: ++seq,
        classId,
        courseId,
        courseName: courseName || '未知课程',
        className: className || '',
        status: 'queued',
        reason: '',
        remain: null,
        version: null,
        createdAt: Date.now(),
        settledAt: 0
      }
      this.tasks.push(task)
      return task
    },

    /** 下一个待提交任务（串行队列的队首） */
    nextQueued() {
      return this.tasks.find((t) => t.status === 'queued') || null
    },

    markSubmitting(id) {
      const t = this._find(id)
      if (t) t.status = 'submitting'
    },

    /** 受理成功（收到 accepted 回执）：记录服务端返回的真实余量/版本用于展示。
     *  注意：若 settled 先于 HTTP 响应到达，任务可能已终结——绝不把终结状态改回 accepted。 */
    markAccepted(id, { remain, version } = {}) {
      const t = this._find(id)
      if (!t || FINAL.includes(t.status)) return
      t.status = 'accepted'
      if (remain != null) t.remain = remain
      if (version != null) t.version = version
    },

    /** 回执超时（SSE 断开等）：标记"确认中"，由随后刷新按数据库真相收敛 */
    markUnknown(id, reason) {
      const t = this._find(id)
      if (!t || FINAL.includes(t.status)) return
      t.status = 'unknown'
      t.reason = reason || '回执未到，请稍后刷新确认'
    },

    /**
     * 回执落定：把该班最早一条未终结任务置为成功/失败。
     * @returns 被更新的任务（无匹配任务时返回 null）
     */
    settle(classId, ok, reason) {
      // 包含 submitting：回执可能先于 HTTP 响应到达（落库与响应是并行的）
      const t = this.tasks.find(
        (x) => x.classId === classId && (x.status === 'submitting' || x.status === 'accepted' || x.status === 'unknown')
      )
      if (!t) return null
      t.status = ok ? 'success' : 'failed'
      t.reason = ok ? '' : (reason || '名额已退回，可重新选课')
      t.settledAt = Date.now()
      return t
    },

    /** 直接落定（老链路同步成功 / 提交阶段业务异常） */
    markDone(id, ok, reason) {
      const t = this._find(id)
      if (!t) return
      t.status = ok ? 'success' : 'failed'
      t.reason = ok ? '' : (reason || '提交失败')
      t.settledAt = Date.now()
    },

    dismiss(id) {
      const i = this.tasks.findIndex((t) => t.id === id)
      if (i >= 0) this.tasks.splice(i, 1)
    },

    clearFinished() {
      this.tasks = this.tasks.filter((t) => !FINAL.includes(t.status))
    },

    _find(id) {
      return this.tasks.find((t) => t.id === id) || null
    }
  }
})
