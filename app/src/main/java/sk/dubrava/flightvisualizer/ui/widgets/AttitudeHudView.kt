package sk.dubrava.flightvisualizer.ui.main

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class AttitudeHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {


    // --- Inputs ---
    var pitchDeg: Float = 0f
        set(v) { field = v; invalidate() }

    var rollDeg: Float = 0f
        set(v) { field = v; invalidate() }

    var headingDeg: Float = 0f
        set(v) { field = norm360(v); invalidate() }

    // If true, invert pitch/roll sign for display (pre vybrané logy, napr. Garmin)
    var invertAttitude: Boolean = false
        set(v) { field = v; invalidate() }

    // Aviation preferred
    var speedKts: Float? = null
        set(v) { field = v?.takeIf { it.isFinite() }; invalidate() }

    var altitudeFt: Float? = null
        set(v) { field = v?.takeIf { it.isFinite() }; invalidate() }

    var crsDeg: Float? = null
        set(v) { field = v?.takeIf { it.isFinite() }; invalidate() }

    var vsFpm: Float? = null
        set(v) {
            field = v?.takeIf { it.isFinite() }
            invalidate()
        }

   // --- Style tuning ---
    private val pSky = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 95, 190) }
    private val pGround = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 98, 30) }

    private val pHorizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
    }

    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }

    private val pLadder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pTextSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7) // amber
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Top bar (glass)
    private val pBarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 34, 55)
    }
    private val pBarText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pBarTextDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pCompassTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val pCompassPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7) // amber
        style = Paint.Style.FILL
    }


    // smoothing
    private var dispRoll = 0f
    private var dispPitch = 0f
    private var lastFrameMs = 0L

    private fun smoothToward(current: Float, target: Float, dtSec: Float, tauSec: Float): Float {
        val a = 1f - exp(-dtSec / tauSec)
        return current + (target - current) * a
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ===== Smooth gyro-like inertia =====
        val now = android.os.SystemClock.uptimeMillis()
        val dtSec = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs).coerceAtMost(50L)) / 1000f
        lastFrameMs = now

        val pIn = if (invertAttitude) -pitchDeg else pitchDeg
        val rIn = if (invertAttitude) -rollDeg else rollDeg

        val targetPitch = pIn.coerceIn(-20f, 20f)
        val targetRoll = rIn.coerceIn(-89f, 89f)

        val tauPitch = 0.18f
        val tauRoll = 0.14f

        if (dtSec == 0f) {
            dispPitch = targetPitch
            dispRoll = targetRoll
        } else {
            dispPitch = smoothToward(dispPitch, targetPitch, dtSec, tauPitch)
            dispRoll = smoothToward(dispRoll, targetRoll, dtSec, tauRoll)
        }

        // render aj medzi frame-ami
        postInvalidateOnAnimation()

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)

        // ===== TOP DATA BAR (full width, thin) =====
        val barH = dp(34f)                 // bolo veľké -> zmenšené
        val barTop = paddingTop.toFloat()
        val barRect = RectF(0f, barTop, w, barTop + barH)
        drawTopNavBar(canvas, barRect)

        val attTop = barRect.bottom + dp(4f)
        val attH = h - attTop
        val size = min(w, attH)
        val cx = w / 2f
        val cy = attTop + attH / 2f
        val radius = size * 0.46f


        val circleRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clipPath = Path().apply { addOval(circleRect, Path.Direction.CW) }

        // clip to circle
        canvas.save()
        canvas.clipPath(clipPath)

        // moving background (classic)
        val roll = dispRoll
        val pitch = dispPitch

        val pxPerDeg = (radius * 0.55f) / 20f
        val pitchOffsetPx = (-pitch) * pxPerDeg // ak máš opačne, zmeň znamienko tu

        canvas.save()
        // Background tilts opposite (instrument style):
        canvas.rotate(+roll, cx, cy)
        canvas.translate(0f, pitchOffsetPx)

        val pad = radius * 2.2f
        val horizonY = cy

        canvas.drawRect(cx - pad, cy - pad, cx + pad, horizonY, pSky)
        canvas.drawRect(cx - pad, horizonY, cx + pad, cy + pad, pGround)
        canvas.drawLine(cx - pad, horizonY, cx + pad, horizonY, pHorizon)

        drawPitchLadderCircle(canvas, cx, cy, radius, pxPerDeg)
        canvas.restore()

        // fixed marker (wings + arc)
        drawFixedAircraftMarkerWithArc(canvas, cx, cy, radius)

        canvas.restore()

        // bank scale on rim (index fixed, scale rotates)
        drawBankScaleOnRim(canvas, cx, cy, radius, dispRoll)

        // outer rim
        canvas.drawCircle(cx, cy, radius, pRing)



        // SPD/ALT hore
        val infoY = barRect.bottom + dp(24f)
        val leftX = dp(14f)
        val rightX = w - dp(14f)

        drawCornerValue(canvas, leftX, infoY, "SPD", speedKts, "KT", alignRight = false)
        drawCornerValue(canvas, rightX, infoY, "ALT", altitudeFt, "FT", alignRight = true)

// VS meter vedľa kruhu (bez textu)
        drawVsMeter(canvas, cx, cy, radius, barRect, infoY, vsFpm)

// VS text vpravo dole (ako SPD/ALT)
        val vsLabelY = (cy + radius) - dp(18f)          // trochu nad spodkom kruhu
        drawCornerValue(canvas, rightX, vsLabelY, "VS", vsFpm, "FPM", alignRight = true)


    }

    private fun drawVsMeter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        barRect: RectF,
        infoY: Float,      // Y kde začína SPD/ALT blok
        vsFpm: Float?
    ) {
        val gap = dp(8f)
        val meterW = dp(20f)          // menší, aby bol "medzi"
        val topSafe = infoY + dp(34f) // nech meter nezačne v oblasti ALT/SPD textu
        val bottomSafe = (cy + radius) - dp(40f) // nech meter nekoliduje s VS textom dole

        val top = max(barRect.bottom + dp(6f), topSafe)
        val bottom = max(top + dp(80f), bottomSafe)

        // napravo od kruhu, ale v rámci obrazovky
        var left = cx + radius + gap
        val maxRight = width.toFloat() - dp(8f)
        if (left + meterW > maxRight) left = maxRight - meterW

        val r = RectF(left, top, left + meterW, bottom)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        canvas.drawRoundRect(r, dp(8f), dp(8f), bg)
        canvas.drawRoundRect(r, dp(8f), dp(8f), stroke)

        val scaleTop = r.top + dp(10f)
        val scaleBottom = r.bottom - dp(10f)
        val midY = (scaleTop + scaleBottom) / 2f

        val range = 2000f
        val pxPerFpm = ((scaleBottom - scaleTop) * 0.45f) / range

        // 0 line
        canvas.drawLine(r.left + dp(5f), midY, r.right - dp(5f), midY, pLadder)

        // ticks
        for (v in -2000..2000 step 500) {
            val yy = midY - (v * pxPerFpm)
            if (yy < scaleTop || yy > scaleBottom) continue
            val len = if (v % 1000 == 0) dp(10f) else dp(7f)
            canvas.drawLine(r.right - dp(5f) - len, yy, r.right - dp(5f), yy, pLadder)
        }

        // pointer
        val value = (vsFpm ?: 0f).coerceIn(-range, range)
        val py = (midY - value * pxPerFpm).coerceIn(scaleTop, scaleBottom)

        val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 193, 7)
            strokeWidth = dp(4f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(r.left + dp(5f), py, r.right - dp(5f), py, pointer)
    }

    private fun drawCornerValue(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        value: Float?,
        unit: String,
        alignRight: Boolean
    ) {
        val vTxt = if (value != null && value.isFinite()) "${value.roundToInt()} $unit" else "N/A $unit"

        val labelPaint = Paint(pTextSmall).apply { textSize = sp(11f) }
        val valuePaint = Paint(pText).apply { textSize = sp(16f) }

        val labelW = labelPaint.measureText(label)
        val valueW = valuePaint.measureText(vTxt)

        val lx = if (alignRight) x - labelW else x
        val vx = if (alignRight) x - valueW else x

        canvas.drawText(label, lx, y, labelPaint)
        canvas.drawText(vTxt, vx, y + dp(20f), valuePaint)
    }

    // =========================
    // TOP DATA BAR
    // =========================
    private fun drawTopNavBar(canvas: Canvas, r: RectF) {
        canvas.drawRoundRect(r, dp(14f), dp(14f), pBarBg)

        val pad = dp(16f)

        val crsTxt = "CRS " + (crsDeg?.roundToInt()?.toString()?.padStart(3,'0') ?: "---") + "°"
        val hdgTxt = "HDG " + headingDeg.roundToInt().toString().padStart(3,'0') + "°"

        val yText = r.centerY() + pBarText.textSize * 0.35f

        // left CRS
        canvas.drawText(crsTxt, r.left + pad, yText, pBarTextDim)
        val crsW = pBarTextDim.measureText(crsTxt)

        // right HDG
        val hdgW = pBarText.measureText(hdgTxt)
        canvas.drawText(hdgTxt, r.right - pad - hdgW, yText, pBarText)

        // compass window (stred)
        val compLeft = r.left + pad + crsW + dp(12f)
        val compRight = r.right - pad - hdgW - dp(12f)
        if (compRight > compLeft + dp(60f)) {
            drawCompassStrip(canvas, RectF(compLeft, r.top, compRight, r.bottom), headingDeg)
        }
    }

    private fun drawCompassStrip(canvas: Canvas, r: RectF, hdgDeg: Float) {
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2f)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sp(12f)
        }

        val centerX = r.centerX()
        val midY = r.centerY()

        // koľko stupňov ukážeme v okne
        val degSpan = 120f                 // +/-45° okolo headingu
        val pxPerDeg = r.width() / degSpan

        // baseline pre písmená (vyššie) a ticky (nižšie)
        val yLetters = r.top + dp(13f)
        val yTickTop = r.bottom - dp(18f)
        val yTickBotMajor = r.bottom - dp(6f)
        val yTickBotMinor = r.bottom - dp(10f)

        val startDeg = hdgDeg - degSpan / 2f
        val endDeg = hdgDeg + degSpan / 2f

        // ticky každých 10°, major každých 30°
        var d = floor(startDeg / 10f) * 10f
        while (d <= endDeg) {
            val signed = ((d - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg
            if (x in r.left..r.right) {
                val isMajor = (d.roundToInt() % 30 == 0)
                canvas.drawLine(
                    x,
                    yTickTop,
                    x,
                    if (isMajor) yTickBotMajor else yTickBotMinor,
                    tickPaint
                )
            }
            d += 10f
        }


        // --- anchor ticks presne pod smerovými písmenami ---
        val anchorPaint = Paint(tickPaint).apply { strokeWidth = dp(2.5f) }

        val anchorTop = yTickTop
        val anchorBot = yTickBotMajor   // rovnaká dĺžka ako major (alebo ešte o 2dp dlhšie)

        val dirs = listOf(
            0f to "N", 45f to "NE", 90f to "E", 135f to "SE",
            180f to "S", 225f to "SW", 270f to "W", 315f to "NW"
        )

        for ((deg, label) in dirs) {
            val signed = ((deg - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg

            // len ak je bod v okne
            if (x < r.left || x > r.right) continue

            // vždy dokresli "anchor" tick presne pod label
            canvas.drawLine(x, anchorTop, x, anchorBot, anchorPaint)
        }

        for ((deg, label) in dirs) {
            val signed = ((deg - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg
            val tw = textPaint.measureText(label)
            if (x - tw/2f >= r.left && x + tw/2f <= r.right) {
                canvas.drawText(label, x - tw/2f, yLetters, textPaint)
            }
        }

        // malý “lubber line” marker v strede (zlatý)
        val lub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 193, 7)
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(centerX, r.top + dp(6f), centerX, r.bottom - dp(6f), lub)
    }

    // =========================
    // ATTITUDE ELEMENTS
    // =========================
    private fun drawPitchLadderCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        pxPerDeg: Float
    ) {
        for (deg in -20..20 step 5) {
            if (deg == 0) continue
            val y = cy - deg * pxPerDeg
            if (y < cy - radius || y > cy + radius) continue

            val isMajor = (abs(deg) % 10 == 0)
            val halfLen = if (isMajor) radius * 0.45f else radius * 0.28f
            canvas.drawLine(cx - halfLen, y, cx + halfLen, y, pLadder)

            if (isMajor) {
                val label = abs(deg).toString()
                canvas.drawText(label, cx - halfLen - dp(18f), y + pTextSmall.textSize * 0.35f, pTextSmall)
                canvas.drawText(label, cx + halfLen + dp(6f),  y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
        }
    }

    private fun drawFixedAircraftMarkerWithArc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val wingHalf = radius * 0.55f
        canvas.drawLine(cx - wingHalf, cy, cx + wingHalf, cy, pPointer)

        // smaller arc (as you wanted)
        val arcR = radius * 0.11f
        val arcRect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        canvas.drawArc(arcRect, 0f, 180f, false, pPointer)

        canvas.drawCircle(cx, cy, dp(3f), Paint(pPointer).apply { style = Paint.Style.FILL })
    }

    private fun drawBankScaleOnRim(canvas: Canvas, cx: Float, cy: Float, radius: Float, rollDeg: Float) {
        // rotate only the scale, keep index fixed
        canvas.save()
        canvas.rotate(-rollDeg.coerceIn(-90f, 90f), cx, cy)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(3f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        for (a in -60..60 step 10) {
            val isMajor = (a % 30 == 0)
            val isMid = (a % 20 == 0)
            val len = when {
                a == 0 -> dp(18f)
                isMajor -> dp(16f)
                isMid -> dp(12f)
                else -> dp(10f)
            }

            val ang = Math.toRadians((a - 90).toDouble())
            val r1 = radius - len
            val r2 = radius

            val x1 = cx + cos(ang).toFloat() * r1
            val y1 = cy + sin(ang).toFloat() * r1
            val x2 = cx + cos(ang).toFloat() * r2
            val y2 = cy + sin(ang).toFloat() * r2

            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        canvas.restore()

        // fixed index triangle
        val tri = Path().apply {
            val top = cy - radius + dp(6f)
            val bottom = top + dp(22f)
            moveTo(cx, top)
            lineTo(cx - dp(14f), bottom)
            lineTo(cx + dp(14f), bottom)
            close()
        }

        val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(tri, triPaint)
    }

    // =========================
    // HELPERS
    // =========================
    private fun norm360(d: Float): Float {
        var x = d % 360f
        if (x < 0f) x += 360f
        return x
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}


