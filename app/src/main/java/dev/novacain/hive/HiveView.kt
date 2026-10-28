package dev.novacain.hive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Соты как на Apple Watch. Круглые значки лежат шестиугольной спиралью от центра; у краёв экрана — «рыбий глаз»:
 * значок, заходящий за внутреннюю рамку, сжимается к краю и уменьшается ровно настолько, насколько сжато расстояние
 * (сжатие r → F·(1 − e^(−r/F)), масштаб e^(−r/F)), поэтому соседи у края не наезжают друг на друга.
 * Поле тянется пальцем в любую сторону, отпустил — катится по инерции и упруго возвращается от границ; щипок — масштаб.
 * Рисуется на Canvas готовыми круглыми картинками (аппаратные битмапы); кадры считаются, только пока что-то движется.
 */
class HiveView(context: Context) : View(context), Choreographer.FrameCallback {

    interface Listener {
        fun onLaunch(app: App, bounds: android.graphics.Rect)
        fun onLongPress(app: App, x: Float, y: Float)
    }

    var listener: Listener? = null

    private class Cell(val app: App, val bx: Float, val by: Float, val ring: Int)

    private val d = resources.displayMetrics.density
    private val iconD = 70f * d                         // диаметр значка при масштабе 1
    private val gap = 1.16f                             // шаг сот относительно диаметра
    private val fringe = 72f * d                        // ширина края с «рыбьим глазом»
    private var cells: List<Cell> = emptyList()
    private var minX = 0f; private var maxX = 0f; private var minY = 0f; private var maxY = 0f

    private var insetTop = 0f
    private var insetBottom = 0f

    // положение поля и масштаб; скорости — для инерции и пружин
    private var panX = 0f; private var panY = 0f; private var zoom = 1f
    private var vx = 0f; private var vy = 0f; private var vz = 0f
    private var tracking = false
    private var homing = false                          // «домой»: к центру и масштабу 1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val holder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setApps(apps: List<App>) {
        // спираль по шестиугольным кольцам: центр, затем кольцо 1 (6 мест), кольцо 2 (12)…
        val out = ArrayList<Cell>(apps.size)
        val step = iconD * gap
        var i = 0
        var ring = 0
        // осевые координаты (q, r); обход кольца радиуса R: от (−R, R) по шести сторонам, каждый шаг — на расстоянии R
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1))
        while (i < apps.size) {
            if (ring == 0) {
                out += Cell(apps[i++], 0f, 0f, 0)
            } else {
                var q = -ring; var r = ring
                for (side in 0 until 6) {
                    val dq = dirs[side][0]; val dr = dirs[side][1]
                    for (k in 0 until ring) {
                        if (i >= apps.size) break
                        out += Cell(apps[i++], step * (q + r / 2f), step * (r * sqrt(3f) / 2f), ring)
                        q += dq; r += dr
                    }
                }
            }
            ring++
        }
        cells = out
        minX = out.minOfOrNull { it.bx } ?: 0f; maxX = out.maxOfOrNull { it.bx } ?: 0f
        minY = out.minOfOrNull { it.by } ?: 0f; maxY = out.maxOfOrNull { it.by } ?: 0f
        settle()
        invalidate()
    }

    /** Поле сразу в допустимое положение (без движения) — при смене списка, размера и отступов. */
    private fun settle() {
        if (tracking || width == 0) return
        panX = panX.coerceIn(boundsX()[0], boundsX()[1])
        panY = panY.coerceIn(boundsY()[0], boundsY()[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        settle()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        insetTop = b.top.toFloat()
        insetBottom = b.bottom.toFloat()
        settle()
        return insets
    }

    // ───────── анимации: появление после разблокировки, запуск, возврат ─────────

    private var introStart = -1L                         // соты прорастают волной от центра
    private var hidden = false                           // ждём разблокировки — ничего не рисуем
    private var launchStart = -1L; private var launchX = 0f; private var launchY = 0f
    private var returnStart = -1L

    fun armIntro() {
        hidden = true
        introStart = -1L
        invalidate()
    }

    fun playIntro() {
        hidden = false
        launchStart = -1L; returnStart = -1L
        introStart = SystemClock.uptimeMillis()
        kick()
    }

    fun playReturn() {
        if (introStart >= 0 || hidden) return
        launchStart = -1L
        returnStart = SystemClock.uptimeMillis()
        kick()
    }

    fun resetLaunch() {
        launchStart = -1L
        invalidate()
    }

    /** «Домой» на столе — поле плывёт к центру и к масштабу 1. */
    fun goHome() {
        homing = true
        kick()
    }

    // ───────── кадр ─────────

    private var ticking = false
    private var lastFrame = 0L

    private fun kick() {
        if (!ticking) {
            ticking = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrame == 0L) 1f / 120f else min((frameTimeNanos - lastFrame) / 1e9f, 1f / 30f)
        lastFrame = frameTimeNanos
        var busy = step(dt)
        val now = SystemClock.uptimeMillis()
        if (introStart >= 0) { if (now - introStart > introLength()) introStart = -1L else busy = true }
        if (returnStart >= 0) { if (now - returnStart > 520) returnStart = -1L else busy = true }
        if (launchStart >= 0) busy = true
        invalidate()
        if (busy) Choreographer.getInstance().postFrameCallback(this) else ticking = false
    }

    /**
     * Границы сдвига: край сот не отходит от края экрана дальше внутренней рамки (как на часах — пустоты за сотами
     * не видно); если соты уже экрана — они по центру.
     */
    private fun boundsX() = bounds(minX, maxX, width / 2f - fringe * 0.55f)
    private fun boundsY() = bounds(minY, maxY, (height - insetTop - insetBottom) / 2f - fringe * 0.55f)

    private fun bounds(lo: Float, hi: Float, inner: Float): FloatArray {
        val a = inner - hi * zoom; val b = -inner - lo * zoom
        return if (a <= b) floatArrayOf(a, b) else floatArrayOf((a + b) / 2f, (a + b) / 2f)
    }

    /** Инерция, пружины от границ, «домой». true — ещё движется. */
    private fun step(dt: Float): Boolean {
        if (tracking) return true
        var busy = false
        if (homing) {
            // центр сот — на центр экрана, насколько позволяют границы
            val hx = 0f.coerceIn(boundsX()[0], boundsX()[1]); val hy = 0f.coerceIn(boundsY()[0], boundsY()[1])
            val (x, a) = spring(panX, vx, hx, dt, 0.5f, 0.86f); panX = x; vx = a
            val (y, b) = spring(panY, vy, hy, dt, 0.5f, 0.86f); panY = y; vy = b
            val (z, c) = spring(zoom, vz, 1f, dt, 0.5f, 0.86f); zoom = z; vz = c
            if (abs(panX - hx) < 0.5f && abs(panY - hy) < 0.5f && abs(zoom - 1f) < 0.001f && abs(vx) + abs(vy) < 5f) {
                panX = hx; panY = hy; zoom = 1f; vx = 0f; vy = 0f; vz = 0f; homing = false
            } else busy = true
            return busy
        }
        // масштаб — пружина обратно в допустимый диапазон
        val zt = zoom.coerceIn(ZMIN, ZMAX)
        if (zoom != zt || abs(vz) > 0.001f) {
            val (z, c) = spring(zoom, vz, zt, dt, 0.4f, 1f); zoom = z; vz = c
            if (abs(zoom - zt) < 0.0005f && abs(vz) < 0.002f) { zoom = zt; vz = 0f } else busy = true
        }
        busy = axis(0, dt) or busy
        busy = axis(1, dt) or busy
        return busy
    }

    private fun axis(which: Int, dt: Float): Boolean {
        val b = if (which == 0) boundsX() else boundsY()
        var x = if (which == 0) panX else panY
        var v = if (which == 0) vx else vy
        val target = x.coerceIn(b[0], b[1])
        val moving: Boolean
        if (x != target) {                               // за границей — пружина к ней (скорость сохраняется)
            val (nx, nv) = spring(x, v, target, dt, 0.42f, 1f)
            x = nx; v = nv
            moving = !(abs(x - target) < 0.5f && abs(v) < 8f)
            if (!moving) { x = target; v = 0f }
        } else {                                         // внутри — инерция с плавным торможением
            v *= exp(-2.4f * dt)
            x += v * dt
            moving = abs(v) > 6f
            if (!moving) v = 0f
        }
        if (which == 0) { panX = x; vx = v } else { panY = x; vy = v }
        return moving
    }

    /** Шаг пружины (response — период, с; damping — доля критического) полунеявным Эйлером мелкими шагами. */
    private fun spring(x0: Float, v0: Float, target: Float, dt: Float, response: Float, damping: Float): Pair<Float, Float> {
        val w = (2 * PI / response).toFloat()
        val k = w * w; val c = 2 * damping * w
        var x = x0; var v = v0
        val n = max(1, (dt / 0.004f).toInt() + 1)
        val h = dt / n
        repeat(n) {
            v += (-k * (x - target) - c * v) * h
            x += v * h
        }
        return x to v
    }

    // ───────── рисование ─────────

    private fun introLength() = 420L + (cells.lastOrNull()?.ring ?: 0) * 34L

    override fun onDraw(canvas: Canvas) {
        if (hidden || cells.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val cx = width / 2f
        val top = insetTop; val bottom = height - insetBottom
        val cy = (top + bottom) / 2f
        val ax = width / 2f - fringe
        val ay = (bottom - top) / 2f - fringe

        // общий слой: влёт в значок при запуске, отъезд при возврате
        var gScale = 1f; var gAlpha = 1f; var gx = cx; var gy = cy
        if (launchStart >= 0) {
            val t = ((now - launchStart) / 300f).coerceIn(0f, 1f)
            val e = t * t * (3 - 2 * t)
            // не гаснут целиком: пока приложение запускается, соты остаются за растущим окном
            gScale = 1f + 1.4f * e; gAlpha = 1f - 0.7f * e; gx = launchX; gy = launchY
        } else if (returnStart >= 0) {
            val t = ((now - returnStart) / 520f).coerceIn(0f, 1f)
            gScale = 1f + 0.35f * (1f - springCurve(t * 0.52f, 0.42f, 0.86f))
            gAlpha = min(1f, t * 3f)
        }
        canvas.save()
        if (gScale != 1f) canvas.scale(gScale, gScale, gx, gy)

        for (c in cells) {
            // прорастание: кольцо за кольцом, каждое — пружиной с небольшим перелётом
            var grow = 1f
            if (introStart >= 0) {
                val t = (now - introStart - c.ring * 34L) / 1000f
                grow = if (t <= 0f) 0f else springCurve(t, 0.46f, 0.68f)
                if (grow <= 0.001f) continue
            }
            var u = panX + c.bx * zoom
            var v = panY + c.by * zoom
            if (introStart >= 0) { u *= 0.86f + 0.14f * min(1f, grow); v *= 0.86f + 0.14f * min(1f, grow) }
            // «рыбий глаз»: сколько значок зашёл за внутреннюю рамку
            val ex = max(abs(u) - ax, 0f); val ey = max(abs(v) - ay, 0f)
            val r = hypot(ex, ey)
            var s = 1f
            if (r > 0f) {
                val t = r / fringe
                val k = fringe * (1f - exp(-t)) / r
                u = sign(u) * (min(abs(u), ax) + ex * k)
                v = sign(v) * (min(abs(v), ay) + ey * k)
                s = exp(-t)
                if (s < 0.06f) continue
            }
            val size = iconD * zoom * s * grow
            val px = cx + u; val py = cy + v
            val half = size / 2f
            if (px + half < 0 || px - half > width || py + half < 0 || py - half > height) continue
            val alpha = (gAlpha * min(1f, s / 0.28f) * min(1f, grow * 1.6f)).coerceIn(0f, 1f)
            rect.set(px - half, py - half, px + half, py + half)
            val bmp = c.app.icon
            if (bmp != null) {
                paint.alpha = (255 * alpha).toInt()
                canvas.drawBitmap(bmp, null, rect, paint)
            } else {
                holder.color = c.app.tint
                holder.alpha = (90 * alpha).toInt()
                canvas.drawOval(rect, holder)
            }
        }
        canvas.restore()
    }

    private fun springCurve(t: Float, response: Float, damping: Float): Float {
        val w0 = 2 * PI.toFloat() / response
        val wd = w0 * sqrt(1 - damping * damping)
        return 1f - exp(-damping * w0 * t) * (cos(wd * t) + damping * w0 / wd * sin(wd * t))
    }

    /** Значок под точкой — с учётом «рыбьего глаза» (то же преобразование, что при рисовании). */
    private fun hit(x: Float, y: Float, out: RectF): App? {
        val cx = width / 2f
        val top = insetTop; val bottom = height - insetBottom
        val cy = (top + bottom) / 2f
        val ax = width / 2f - fringe; val ay = (bottom - top) / 2f - fringe
        var best: App? = null; var bestD = Float.MAX_VALUE
        for (c in cells) {
            var u = panX + c.bx * zoom; var v = panY + c.by * zoom
            val ex = max(abs(u) - ax, 0f); val ey = max(abs(v) - ay, 0f)
            val r = hypot(ex, ey)
            var s = 1f
            if (r > 0f) {
                val t = r / fringe; val k = fringe * (1f - exp(-t)) / r
                u = sign(u) * (min(abs(u), ax) + ex * k); v = sign(v) * (min(abs(v), ay) + ey * k)
                s = exp(-t)
            }
            if (s < 0.3f) continue                       // совсем маленькие у края — не нажимаются
            val half = iconD * zoom * s / 2f
            val dd = hypot(x - (cx + u), y - (cy + v))
            if (dd <= half * 1.08f && dd < bestD) {
                bestD = dd; best = c.app
                out.set(cx + u - half, cy + v - half, cx + u + half, cy + v + half)
            }
        }
        return best
    }

    // ───────── касания ─────────

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var vt: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var pinchDist = 0f
    private var pressed: App? = null
    private val pressedRect = RectF()
    private var longFired = false
    private val longPress = Runnable {
        val app = pressed ?: return@Runnable
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onLongPress(app, pressedRect.centerX(), pressedRect.bottom)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (launchStart >= 0 || hidden) return true
        if (vt == null) vt = VelocityTracker.obtain()
        vt!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true; homing = false
                vx = 0f; vy = 0f; vz = 0f
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                dragging = false; longFired = false
                pressed = hit(e.x, e.y, pressedRect)
                if (pressed != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                kick()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPress()
                dragging = true
                pinchDist = span(e)
                lastX = focusX(e); lastY = focusY(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // остался один палец — продолжаем тянуть от него, без скачка
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
                pinchDist = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val fx = focusX(e); val fy = focusY(e)
                if (!dragging && hypot(fx - downX, fy - downY) > slop) {
                    dragging = true
                    cancelPress()
                    lastX = fx; lastY = fy
                }
                if (dragging) {
                    if (e.pointerCount >= 2) {
                        val dist = span(e)
                        if (pinchDist > 0f && dist > 0f) zoomBy(dist / pinchDist, fx, fy)
                        pinchDist = dist
                    }
                    panBy(fx - lastX, fy - lastY)
                    lastX = fx; lastY = fy
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                tracking = false
                if (dragging) {
                    vt!!.computeCurrentVelocity(1000, 12000f)
                    vx = vt!!.xVelocity; vy = vt!!.yVelocity
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                    pressed?.let { launch(it) }
                }
                pressed = null
                vt?.recycle(); vt = null
                kick()
            }
        }
        return true
    }

    private fun cancelPress() {
        removeCallbacks(longPress)
        pressed = null
    }

    private fun span(e: MotionEvent) = if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    /** Сдвиг с резиной за границами: чем дальше за край, тем туже. */
    private fun panBy(dx: Float, dy: Float) {
        val bx = boundsX(); val by = boundsY()
        panX += dx * resist(panX, bx); panY += dy * resist(panY, by)
    }

    private fun resist(x: Float, b: FloatArray): Float {
        val over = if (x < b[0]) b[0] - x else if (x > b[1]) x - b[1] else 0f
        val r = 150f * d
        return r / (r + over * 1.4f)
    }

    /** Масштаб щипком вокруг точки между пальцами; за пределами — туго. */
    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        var k = f
        if ((zoom > ZMAX && k > 1f) || (zoom < ZMIN && k < 1f)) k = 1f + (k - 1f) * 0.3f
        val nz = (zoom * k).coerceIn(ZMIN * 0.7f, ZMAX * 1.3f)
        val real = nz / zoom
        val cx = width / 2f; val cy = (insetTop + height - insetBottom) / 2f
        // точка под пальцами остаётся на месте
        panX = (fx - cx) - (fx - cx - panX) * real
        panY = (fy - cy) - (fy - cy - panY) * real
        zoom = nz
    }

    private fun launch(app: App) {
        val r = android.graphics.Rect()
        pressedRect.round(r)
        launchX = pressedRect.centerX(); launchY = pressedRect.centerY()
        launchStart = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        kick()
        listener?.onLaunch(app, r)
    }

    companion object {
        private const val ZMIN = 0.72f
        private const val ZMAX = 1.5f
    }
}
