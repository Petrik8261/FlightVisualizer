package sk.dubrava.flightvisualizer.ui.widgets

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class AttitudeHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class HudMode { FULL, LIMITED, TRAJECTORY }

    // --- Inputs ---
    private var pitchDeg:   Float  = 0f
    private var rollDeg:    Float  = 0f
    private var headingDeg: Float  = 0f
    private var crsDeg:     Float? = null
    private var speedKts:   Float? = null
    private var altitudeFt: Float? = null
    private var vsFpm:      Float? = null
    private var isAttitudeEstimated: Boolean = false
    private var hudMode: HudMode = HudMode.FULL

    private var isSpeedEstimated:   Boolean = false
    private var isAltEstimated:     Boolean = false
    private var isVsEstimated:      Boolean = false
    private var isHeadingEstimated: Boolean = false
    private var isCrsEstimated:     Boolean = false

    // --- Smoothing ---
    private var dispRoll  = 0f
    private var dispPitch = 0f
    private var lastFrameMs = 0L

    private fun smoothToward(current: Float, target: Float, dtSec: Float, tauSec: Float): Float {
        val a = 1f - exp(-dtSec / tauSec)
        return current + (target - current) * a
    }

    companion object {
        private val COLOR_NORMAL        = Color.WHITE
        private val COLOR_ESTIMATED     = Color.rgb(255, 80, 80)
        private val COLOR_DIM           = Color.argb(180, 255, 255, 255)
        private val COLOR_ESTIMATED_DIM = Color.argb(180, 255, 100, 100)
    }

    // --- Paints (identické s originálom) ---
    private val pSky    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 95, 190) }
    private val pGround = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 98, 30) }
    private val pHorizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = dp(3f); style = Paint.Style.STROKE
    }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val pLadder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
    }
    private val pTextSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7); strokeWidth = dp(4f)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pBarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 34, 55) }

    // LIMITED mode
    private val pLimitedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 24, 40); style = Paint.Style.FILL; alpha = 235
    }
    private val pLimitedBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 227, 240); style = Paint.Style.STROKE; strokeWidth = dp(3.5f)
    }

    // =========================
    // Public API
    // =========================

    fun setFlightData(
        headingDeg:          Double?,
        courseDeg:           Double?,
        pitchDeg:            Double?,
        rollDeg:             Double?,
        altitudeFt:          Double?,
        speedKt:             Double?,
        vsFpm:               Double?,
        isAttitudeEstimated: Boolean = false
    ) {
        this.headingDeg          = norm360((headingDeg ?: 0.0).toFloat())
        this.crsDeg              = courseDeg?.toFloat()?.takeIf { it.isFinite() }
        this.pitchDeg            = pitchDeg?.toFloat()  ?: 0f
        this.rollDeg             = rollDeg?.toFloat()   ?: 0f
        this.altitudeFt          = altitudeFt?.toFloat()?.takeIf { it.isFinite() }
        this.speedKts            = speedKt?.toFloat()?.takeIf { it.isFinite() }
        this.vsFpm               = vsFpm?.toFloat()?.takeIf { it.isFinite() }
        this.isAttitudeEstimated = isAttitudeEstimated

        hudMode = when {
            pitchDeg != null && rollDeg != null -> HudMode.FULL
            speedKt != null || altitudeFt != null || headingDeg != null || courseDeg != null -> HudMode.LIMITED
            else -> HudMode.TRAJECTORY
        }

        invalidate()
    }

    // =========================
    // onDraw
    // =========================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = android.os.SystemClock.uptimeMillis()
        val dtSec = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs).coerceAtMost(50L)) / 1000f
        lastFrameMs = now

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)

        // Top bar — rovnaká výška ako v origináli
        val barH    = dp(34f)
        val barTop  = paddingTop.toFloat()
        val barRect = RectF(0f, barTop, w, barTop + barH)
        drawTopNavBar(canvas, barRect)

        // Geometria kruhu — identická s originálom:
        // kruh = celý priestor pod top barom, centrovaný
        val attTop = barRect.bottom + dp(4f)
        val attH   = h - attTop
        val size   = min(w, attH)
        val cx     = w / 2f
        val cy     = attTop + attH / 2f
        val radius = size * 0.46f

        val infoY  = barRect.bottom + dp(24f)
        val leftX  = dp(14f)
        val rightX = w - dp(14f)

        when (hudMode) {
            HudMode.FULL -> {
                val targetPitch = pitchDeg.coerceIn(-20f, 20f)
                val targetRoll  = rollDeg.coerceIn(-89f, 89f)
                if (dtSec == 0f) { dispPitch = targetPitch; dispRoll = targetRoll }
                else {
                    dispPitch = smoothToward(dispPitch, targetPitch, dtSec, 0.18f)
                    dispRoll  = smoothToward(dispRoll,  targetRoll,  dtSec, 0.14f)
                }
                postInvalidateOnAnimation()

                drawAttitudeFull(canvas, cx, cy, radius)
                drawCornerValue(canvas, leftX,  infoY, "SPD", speedKts,   "KT",  false, isSpeedEstimated)
                drawCornerValue(canvas, rightX, infoY, "ALT", altitudeFt, "FT",  true,  isAltEstimated)
                drawVsMeter(canvas, cx, cy, radius, barRect, infoY, vsFpm, isVsEstimated)
                drawCornerValue(canvas, rightX, (cy + radius) - dp(18f), "VS", vsFpm, "FPM", true, isVsEstimated)

                if (isAttitudeEstimated) drawEstBadge(canvas, cx, cy, radius)
            }

            HudMode.LIMITED -> {
                drawAttitudeLimited(canvas, cx, cy, radius)
                drawCornerValue(canvas, leftX,  infoY, "SPD", speedKts,   "KT",  false, isSpeedEstimated)
                drawCornerValue(canvas, rightX, infoY, "ALT", altitudeFt, "FT",  true,  isAltEstimated)
                drawVsMeter(canvas, cx, cy, radius, barRect, infoY, vsFpm, isVsEstimated)
                drawCornerValue(canvas, rightX, (cy + radius) - dp(18f), "VS", vsFpm, "FPM", true, isVsEstimated)
                drawModeBadge(canvas, "LIMITED DATA", Color.rgb(91, 74, 18))
            }

            HudMode.TRAJECTORY -> {
                drawCornerValue(canvas, leftX,  infoY, "SPD", speedKts,   "KT",  false, false)
                drawCornerValue(canvas, rightX, infoY, "ALT", altitudeFt, "FT",  true,  false)
                drawModeBadge(canvas, "TRAJECTORY", Color.rgb(59, 43, 88))
            }
        }
    }

    // =========================
    // FULL attitude (z originálu — nezmenené)
    // =========================

    private fun drawAttitudeFull(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val circleRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clipPath   = Path().apply { addOval(circleRect, Path.Direction.CW) }

        val pxPerDeg      = (radius * 0.55f) / 20f
        val pitchOffsetPx = (-dispPitch) * pxPerDeg

        canvas.save()
        canvas.clipPath(clipPath)

        canvas.save()
        canvas.rotate(+dispRoll, cx, cy)
        canvas.translate(0f, pitchOffsetPx)

        val pad = radius * 2.2f
        val horizonY = cy
        canvas.drawRect(cx - pad, cy - pad, cx + pad, horizonY, pSky)
        canvas.drawRect(cx - pad, horizonY, cx + pad, cy + pad, pGround)
        canvas.drawLine(cx - pad, horizonY, cx + pad, horizonY, pHorizon)
        drawPitchLadderCircle(canvas, cx, cy, radius, pxPerDeg)
        canvas.restore()

        drawFixedAircraftMarkerWithArc(canvas, cx, cy, radius)
        canvas.restore()

        drawBankScaleOnRim(canvas, cx, cy, radius, dispRoll)
        canvas.drawCircle(cx, cy, radius, pRing)
    }

    // =========================
    // LIMITED circle
    // =========================

    private fun drawAttitudeLimited(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, pLimitedFill)
        canvas.drawCircle(cx, cy, radius, pLimitedBorder)

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(111, 122, 146); style = Paint.Style.STROKE
            strokeWidth = dp(2.2f)
            pathEffect = DashPathEffect(floatArrayOf(dp(14f), dp(10f)), 0f)
        }
        canvas.drawLine(cx - radius * 0.65f, cy, cx + radius * 0.65f, cy, guidePaint)
        canvas.drawLine(cx, cy - radius * 0.55f, cx, cy + radius * 0.55f, guidePaint)

        val tCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            textSize = sp(16f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tMuted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(184, 192, 216); textAlign = Paint.Align.CENTER
            textSize = sp(13f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("ATTITUDE",         cx, cy - dp(16f), tCenter)
        canvas.drawText("N/A",              cx, cy + dp(10f), tCenter)
        canvas.drawText("NO ATTITUDE DATA", cx, cy + dp(32f), tMuted)
    }

    // =========================
    // EST badge
    // =========================

    private fun drawEstBadge(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val text = "EST"
        val padH = dp(7f); val padV = dp(4f)
        val tPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(11f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT; color = Color.rgb(244, 193, 15)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 33, 0); style = Paint.Style.FILL; alpha = 220
        }
        val bw   = tPaint.measureText(text) + padH * 2
        val bh   = tPaint.textSize + padV * 2
        val left = cx - bw / 2
        val top  = cy + radius - bh - dp(10f)
        canvas.drawRoundRect(RectF(left, top, left + bw, top + bh), dp(6f), dp(6f), bgPaint)
        canvas.drawText(text, left + padH, top + bh - padV - dp(2f), tPaint)
    }

    // =========================
    // Mode badge
    // =========================

    private fun drawModeBadge(canvas: Canvas, label: String, bgColor: Int) {
        val padH = dp(10f); val padV = dp(6f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor; style = Paint.Style.FILL; alpha = 235
        }
        val tPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = sp(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val bw   = tPaint.measureText(label) + padH * 2
        val bh   = tPaint.textSize + padV * 2
        val left = dp(14f)
        val top  = height - bh - dp(18f)
        canvas.drawRoundRect(RectF(left, top, left + bw, top + bh), dp(12f), dp(12f), bgPaint)
        canvas.drawText(label, left + padH, top + bh - padV - dp(2f), tPaint)
    }

    // =========================
    // TOP NAV BAR (z originálu — nezmenené)
    // =========================

    private fun drawTopNavBar(canvas: Canvas, r: RectF) {
        canvas.drawRoundRect(r, dp(14f), dp(14f), pBarBg)

        val pad   = dp(16f)
        val yText = r.centerY() + sp(16f) * 0.35f

        val crsTxt = "CRS " + (crsDeg?.roundToInt()?.toString()?.padStart(3, '0') ?: "---") + "°"
        val hdgTxt = "HDG " + headingDeg.roundToInt().toString().padStart(3, '0') + "°"

        val crsPaint = makePaint(sp(16f), dim = true,  estimated = isCrsEstimated)
        canvas.drawText(crsTxt, r.left + pad, yText, crsPaint)
        val crsW = crsPaint.measureText(crsTxt)

        val hdgPaint = makePaint(sp(16f), dim = false, estimated = isHeadingEstimated)
        val hdgW = hdgPaint.measureText(hdgTxt)
        canvas.drawText(hdgTxt, r.right - pad - hdgW, yText, hdgPaint)

        val compLeft  = r.left + pad + crsW + dp(12f)
        val compRight = r.right - pad - hdgW - dp(12f)
        if (compRight > compLeft + dp(60f))
            drawCompassStrip(canvas, RectF(compLeft, r.top, compRight, r.bottom), headingDeg)
    }

    private fun drawCompassStrip(canvas: Canvas, r: RectF, hdgDeg: Float) {
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2f)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sp(12f)
        }

        val centerX  = r.centerX()
        val degSpan  = 120f
        val pxPerDeg = r.width() / degSpan
        val yLetters      = r.top + dp(13f)
        val yTickTop      = r.bottom - dp(18f)
        val yTickBotMajor = r.bottom - dp(6f)
        val yTickBotMinor = r.bottom - dp(10f)

        var d = floor((hdgDeg - degSpan / 2f) / 10f) * 10f
        while (d <= hdgDeg + degSpan / 2f) {
            val signed = ((d - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg
            if (x in r.left..r.right) {
                val isMajor = (d.roundToInt() % 30 == 0)
                canvas.drawLine(x, yTickTop, x, if (isMajor) yTickBotMajor else yTickBotMinor, tickPaint)
            }
            d += 10f
        }

        val dirs = listOf(0f to "N", 45f to "NE", 90f to "E", 135f to "SE",
            180f to "S", 225f to "SW", 270f to "W", 315f to "NW")
        val anchorPaint = Paint(tickPaint).apply { strokeWidth = dp(2.5f) }
        for ((deg, _) in dirs) {
            val signed = ((deg - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg
            if (x in r.left..r.right)
                canvas.drawLine(x, yTickTop, x, yTickBotMajor, anchorPaint)
        }
        for ((deg, label) in dirs) {
            val signed = ((deg - hdgDeg + 540f) % 360f) - 180f
            val x = centerX + signed * pxPerDeg
            val tw = textPaint.measureText(label)
            if (x - tw / 2f >= r.left && x + tw / 2f <= r.right)
                canvas.drawText(label, x - tw / 2f, yLetters, textPaint)
        }

        val lub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 193, 7); strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(centerX, r.top + dp(6f), centerX, r.bottom - dp(6f), lub)
    }

    // =========================
    // VS METER (z originálu — nezmenené)
    // =========================

    private fun drawVsMeter(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        barRect: RectF, infoY: Float, vsFpm: Float?, estimated: Boolean
    ) {
        val gap        = dp(8f)
        val meterW     = dp(20f)
        val topSafe    = infoY + dp(34f)
        val bottomSafe = (cy + radius) - dp(40f)
        val top        = max(barRect.bottom + dp(6f), topSafe)
        val bottom     = max(top + dp(80f), bottomSafe)
        var left = cx + radius + gap
        val maxRight = width.toFloat() - dp(8f)
        if (left + meterW > maxRight) left = maxRight - meterW

        val r  = RectF(left, top, left + meterW, bottom)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (estimated) Color.argb(200, 255, 80, 80) else Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE; strokeWidth = dp(2f)
        }
        canvas.drawRoundRect(r, dp(8f), dp(8f), bg)
        canvas.drawRoundRect(r, dp(8f), dp(8f), stroke)

        val scaleTop    = r.top + dp(10f)
        val scaleBottom = r.bottom - dp(10f)
        val midY        = (scaleTop + scaleBottom) / 2f
        val range       = 2000f
        val pxPerFpm    = ((scaleBottom - scaleTop) * 0.45f) / range

        canvas.drawLine(r.left + dp(5f), midY, r.right - dp(5f), midY, pLadder)
        for (v in -2000..2000 step 500) {
            val yy = midY - (v * pxPerFpm)
            if (yy < scaleTop || yy > scaleBottom) continue
            val len = if (v % 1000 == 0) dp(10f) else dp(7f)
            canvas.drawLine(r.right - dp(5f) - len, yy, r.right - dp(5f), yy, pLadder)
        }

        val value = (vsFpm ?: 0f).coerceIn(-range, range)
        val py    = (midY - value * pxPerFpm).coerceIn(scaleTop, scaleBottom)
        val pointerColor = if (estimated) Color.rgb(255, 80, 80) else Color.rgb(255, 193, 7)
        canvas.drawLine(r.left + dp(5f), py, r.right - dp(5f), py,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pointerColor; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND
            })
    }

    // =========================
    // CORNER VALUE (z originálu — nezmenené)
    // =========================

    private fun drawCornerValue(
        canvas: Canvas, x: Float, y: Float,
        label: String, value: Float?, unit: String,
        alignRight: Boolean, estimated: Boolean
    ) {
        val vTxt       = if (value != null && value.isFinite()) "${value.roundToInt()} $unit" else "N/A $unit"
        val labelPaint = makePaint(sp(11f), dim = true,  estimated = estimated)
        val valuePaint = makePaint(sp(16f), dim = false, estimated = estimated)
        val lx = if (alignRight) x - labelPaint.measureText(label) else x
        val vx = if (alignRight) x - valuePaint.measureText(vTxt)  else x
        canvas.drawText(label, lx, y,           labelPaint)
        canvas.drawText(vTxt,  vx, y + dp(20f), valuePaint)
    }

    // =========================
    // ATTITUDE ELEMENTS (z originálu — nezmenené)
    // =========================

    private fun drawPitchLadderCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, pxPerDeg: Float) {
        for (deg in -20..20 step 5) {
            if (deg == 0) continue
            val y = cy - deg * pxPerDeg
            if (y < cy - radius || y > cy + radius) continue
            val isMajor = (abs(deg) % 10 == 0)
            val halfLen = if (isMajor) radius * 0.45f else radius * 0.28f
            canvas.drawLine(cx - halfLen, y, cx + halfLen, y, pLadder)
            if (isMajor) {
                val lbl = abs(deg).toString()
                canvas.drawText(lbl, cx - halfLen - dp(18f), y + pTextSmall.textSize * 0.35f, pTextSmall)
                canvas.drawText(lbl, cx + halfLen + dp(6f),  y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
        }
    }

    private fun drawFixedAircraftMarkerWithArc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val wingHalf = radius * 0.55f
        canvas.drawLine(cx - wingHalf, cy, cx + wingHalf, cy, pPointer)
        val arcR = radius * 0.11f
        val arcRect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        canvas.drawArc(arcRect, 0f, 180f, false, pPointer)
        canvas.drawCircle(cx, cy, dp(3f), Paint(pPointer).apply { style = Paint.Style.FILL })
    }

    private fun drawBankScaleOnRim(canvas: Canvas, cx: Float, cy: Float, radius: Float, rollDeg: Float) {
        canvas.save()
        canvas.rotate(-rollDeg.coerceIn(-90f, 90f), cx, cy)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = dp(3f)
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        for (a in -60..60 step 10) {
            val isMajor = (a % 30 == 0)
            val isMid   = (a % 20 == 0)
            val len = when {
                a == 0  -> dp(18f)
                isMajor -> dp(16f)
                isMid   -> dp(12f)
                else    -> dp(10f)
            }
            val ang = Math.toRadians((a - 90).toDouble())
            val r1 = radius - len; val r2 = radius
            canvas.drawLine(
                cx + cos(ang).toFloat() * r1, cy + sin(ang).toFloat() * r1,
                cx + cos(ang).toFloat() * r2, cy + sin(ang).toFloat() * r2,
                tickPaint
            )
        }
        canvas.restore()

        val tri = Path().apply {
            val top = cy - radius + dp(6f)
            val bot = top + dp(22f)
            moveTo(cx, top); lineTo(cx - dp(14f), bot); lineTo(cx + dp(14f), bot); close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        })
    }

    // =========================
    // HELPERS (z originálu)
    // =========================

    private fun makePaint(textSizePx: Float, dim: Boolean, estimated: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                estimated && dim  -> COLOR_ESTIMATED_DIM
                estimated && !dim -> COLOR_ESTIMATED
                dim               -> COLOR_DIM
                else              -> COLOR_NORMAL
            }
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun norm360(d: Float): Float {
        var x = d % 360f; if (x < 0f) x += 360f; return x
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}