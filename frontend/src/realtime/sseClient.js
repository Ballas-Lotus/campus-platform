import { TOKEN_KEY } from '../api/request'

/**
 * 阶段 20：名额信号 SSE 客户端。
 *
 * 为什么不用原生 EventSource：
 *   ① 它无法携带 Authorization 头（本项目的鉴权头是必须的）；
 *   ② 需要内容类型守卫（网关鉴权失败会返回 HTTP 200 + JSON 业务码）。
 * 因此用 fetch + ReadableStream 手写 SSE 解析（协议仍是 text/event-stream）。
 *
 * 特性：
 *   - 指数退避重连（1s→30s + 抖动），45s 看门狗（任何字节到达即重置），25s 心跳注释帧由服务端发；
 *   - 鉴权失效（40100/40101）停止重连并回调；
 *   - 多标签页共享：BroadcastChannel 选主——主标签持有连接，从标签经通道收信号（防 HTTP/1.1 六连接被吃满）；
 *     任何共享异常都 fail-open（各标签各自连，功能不受影响）。
 */

const BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000]
const WATCHDOG_MS = 45000
const CHANNEL_NAME = 'campus-stock-sse'
const HB_INTERVAL_MS = 5000     // 主标签心跳间隔
const HB_TIMEOUT_MS = 12000     // 从标签判定"主标签失联"的阈值
const FOLLOWER_CHECK_MS = 4000

export class StockStream {
  /**
   * @param handlers { onReady(payload), onSignal(signal), onSettled(payload), onState(state), onAuthExpired() }
   *   state: 'connecting' | 'open' | 'fallback'（降级轮询由调用方据此启动）
   *   onSettled：阶段 21 受理回执 { classId, ok, reason? }（多标签共享时经 BroadcastChannel 转发）
   */
  constructor(handlers) {
    this.h = handlers || {}
    this.courses = []
    this.state = 'idle'
    this.closed = true
    this.role = 'solo'          // solo（未启用共享）| leader | follower
    this.retry = 0
    this.abort = null
    this.watchdog = null
    this.reconnectTimer = null
    this.followerTimer = null
    this.hbTimer = null
    this.lastPeerBeat = 0
    this.bc = null
    this.gen = 0                 // 连接代际：切课程/选主切换时作废旧连接回调
  }

  /** 订阅课程集合（变化时重连；主标签用并集，从标签上报需求） */
  subscribe(courses) {
    const next = [...new Set((courses || []).filter((x) => x != null))].sort((a, b) => a - b)
    const same = next.length === this.courses.length && next.every((v, i) => v === this.courses[i])
    this.courses = next
    if (this.closed) {
      this.start()
      return
    }
    if (this.role === 'leader' || this.role === 'solo') {
      if (!same) this.reconnect()
    } else if (this.role === 'follower' && this.bc) {
      this.bc.postMessage({ type: 'courses', list: next })   // 上报需求，主标签合并后按需重连
    }
  }

  start() {
    this.closed = false
    this._initBroadcast()
    if (this.bc) {
      // 有他人在主（近期有心跳）→ 先做从标签；否则 150~350ms 后自宣为主
      const waited = Date.now() - this.lastPeerBeat < HB_TIMEOUT_MS
      if (waited) {
        this._becomeFollower()
      } else {
        setTimeout(() => {
          if (!this.closed && this.role !== 'leader' && Date.now() - this.lastPeerBeat >= HB_TIMEOUT_MS) {
            this._becomeLeader()
          }
        }, 150 + Math.random() * 200)
      }
    } else {
      this.role = 'solo'
      this._connect()
    }
    this._scheduleFollowerCheck()
  }

  close() {
    this.closed = true
    this.gen++
    this._clearTimers()
    if (this.abort) {
      try { this.abort.abort() } catch { /* ignore */ }
      this.abort = null
    }
    if (this.bc) {
      try { this.bc.close() } catch { /* ignore */ }
      this.bc = null
    }
    this._setState('idle')
  }

  // ------------------------------------------------------------------
  // 多标签共享（fail-open：任何异常退回单连接模式）
  // ------------------------------------------------------------------

  _initBroadcast() {
    if (this.bc || typeof BroadcastChannel === 'undefined') return
    try {
      this.bc = new BroadcastChannel(CHANNEL_NAME)
      this.bc.onmessage = (ev) => this._onPeerMessage(ev.data)
    } catch {
      this.bc = null
    }
  }

  _onPeerMessage(msg) {
    if (!msg || this.closed) return
    if (msg.type === 'hb' || msg.type === 'signal' || msg.type === 'settled' || msg.type === 'courses') {
      this.lastPeerBeat = Date.now()
    }
    if (msg.type === 'signal' && this.role === 'follower') {
      this.h.onSignal && this.h.onSignal(msg.data)      // 从标签：复用与主标签完全相同的处理管线
    } else if (msg.type === 'settled' && this.role === 'follower') {
      this.h.onSettled && this.h.onSettled(msg.data)    // 阶段 21：受理回执同样广播给从标签
    } else if (msg.type === 'hb' && this.role === 'follower') {
      // 主标签在岗
    } else if (msg.type === 'courses' && this.role === 'leader') {
      this._mergeCourses(msg.list)
    }
  }

  _becomeLeader() {
    if (this.closed || this.role === 'leader') return
    this.role = 'leader'
    if (this.bc) this.bc.postMessage({ type: 'hb' })
    this._connect()
    this.hbTimer = setInterval(() => {
      if (this.bc && !this.closed) this.bc.postMessage({ type: 'hb' })
    }, HB_INTERVAL_MS)
  }

  _becomeFollower() {
    this.role = 'follower'
    this._setState('connecting')
    // 把本标签的需求报给主标签（并集由主标签维护）
    if (this.bc) this.bc.postMessage({ type: 'courses', list: this.courses })
    if (this.abort) {
      try { this.abort.abort() } catch { /* ignore */ }
      this.abort = null
    }
  }

  _mergeCourses(list) {
    const merged = [...new Set([...this.courses, ...(list || [])])].sort((a, b) => a - b)
    const changed = merged.length !== this.courses.length || merged.some((v, i) => v !== this.courses[i])
    this.courses = merged
    if (changed) this.reconnect()
  }

  _scheduleFollowerCheck() {
    clearInterval(this.followerTimer)
    this.followerTimer = setInterval(() => {
      if (this.closed || !this.bc) return
      const stale = Date.now() - this.lastPeerBeat >= HB_TIMEOUT_MS
      if (this.role === 'follower' && stale) {
        this._becomeLeader()   // 主标签失联（关闭/崩溃）→ 自宣为主
      }
    }, FOLLOWER_CHECK_MS)
  }

  // ------------------------------------------------------------------
  // 连接与解析
  // ------------------------------------------------------------------

  reconnect() {
    if (this.abort) {
      try { this.abort.abort() } catch { /* ignore */ }
      this.abort = null
    }
    clearTimeout(this.reconnectTimer)
    this.gen++
    this._connect()
  }

  async _connect() {
    if (this.closed) return
    const gen = ++this.gen
    const url = `/api/course/stock/stream?courses=${this.courses.join(',')}`
    const controller = new AbortController()
    this.abort = controller
    this._setState('connecting')
    try {
      const token = localStorage.getItem(TOKEN_KEY)
      const resp = await fetch(url, {
        headers: token ? { Authorization: token } : {},
        signal: controller.signal
      })
      const ctype = resp.headers.get('content-type') || ''
      if (!ctype.includes('text/event-stream')) {
        // 网关鉴权失败等会以 HTTP 200 + JSON 返回
        const body = await resp.json().catch(() => ({}))
        if (body && (body.code === 40100 || body.code === 40101)) {
          this.close()
          this.h.onAuthExpired && this.h.onAuthExpired()
          return
        }
        throw new Error(body.message || '连接被拒绝')
      }
      this.retry = 0
      this._setState('open')
      this._resetWatchdog(gen)
      await this._readLoop(resp, gen)
      // 服务端正常结束（emitter 30 分钟上限）→ 立即重连
      this._scheduleRetry(0)
    } catch (e) {
      if (this.closed || gen !== this.gen) return
      this._setState('fallback')
      this._scheduleRetry()
    }
  }

  async _readLoop(resp, gen) {
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      if (this.closed || gen !== this.gen) {
        try { await reader.cancel() } catch { /* ignore */ }
        return
      }
      this._resetWatchdog(gen)
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        this._handleFrame(frame)
      }
    }
  }

  _handleFrame(frame) {
    let event = 'message'
    const dataLines = []
    for (const line of frame.split('\n')) {
      if (!line || line.startsWith(':')) continue      // 心跳注释帧
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    if (event === 'ready') {
      let payload = null
      try { payload = JSON.parse(raw) } catch { /* ignore */ }
      this._relay({ type: 'heartbeat' })               // 让从标签知道主在岗
      this.h.onReady && this.h.onReady(payload)        // 建连/重连成功 → 调用方无条件拉齐
    } else if (event === 'stock') {
      let data = null
      try { data = JSON.parse(raw) } catch { return }
      if (this.role === 'leader' && this.bc) this.bc.postMessage({ type: 'signal', data })
      this.h.onSignal && this.h.onSignal(data)
    } else if (event === 'settled') {
      // 阶段 21（受理制）：受理结果回执（按用户定向推送）——ok=true 已落库 / ok=false 已拒绝且名额退回
      let data = null
      try { data = JSON.parse(raw) } catch { return }
      if (this.role === 'leader' && this.bc) this.bc.postMessage({ type: 'settled', data })
      this.h.onSettled && this.h.onSettled(data)
    }
  }

  _relay(msg) {
    if (this.role === 'leader' && this.bc && msg.type === 'heartbeat') {
      this.bc.postMessage({ type: 'hb' })
    }
  }

  _resetWatchdog(gen) {
    clearTimeout(this.watchdog)
    this.watchdog = setTimeout(() => {
      if (!this.closed && gen === this.gen) {
        this._setState('fallback')
        this.reconnect()   // 45s 无任何字节：视作断线
      }
    }, WATCHDOG_MS)
  }

  _scheduleRetry(forcedDelay) {
    if (this.closed) return
    const base = forcedDelay != null ? forcedDelay : BACKOFF_MS[Math.min(this.retry, BACKOFF_MS.length - 1)]
    this.retry++
    const delay = base + Math.random() * base * 0.3     // 抖动：防重连风暴
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => this.reconnect(), delay)
  }

  _setState(s) {
    if (this.state === s) return
    this.state = s
    this.h.onState && this.h.onState(s)
  }

  _clearTimers() {
    clearTimeout(this.watchdog)
    clearTimeout(this.reconnectTimer)
    clearInterval(this.followerTimer)
    clearInterval(this.hbTimer)
  }
}
