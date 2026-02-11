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

    // --- Inputs (deg / units) ---
    var pitchDeg: Float = 0f
        set(value) { field = value; invalidate() }

    var rollDeg: Float = 0f
        set(value) { field = value; invalidate() }

    var headingDeg: Float = 0f
        set(value) { field = norm360(value); invalidate() }

    var speed: Float? = null
        set(value) { field = value; invalidate() }

    var altitude: Float? = null
        set(value) { field = value; invalidate() }

    var vsMps: Float? = null
        set(value) { field = value; invalidate() }


    // --- Style tuning ---
    private val pitchPxPerDeg: Float get() = height * 0.0095f // ~ dobrý štart
    private val maxPitchDraw = 45f

    // Paints
    private val pSky = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 95, 190) }
    private val pGround = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 98, 30) }

    private val pHorizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
    }

    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }

    private val pRingTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val pRingText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pRingPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7) // amber
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }


    private val pLadder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
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

    private val pFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
    }

    private val pPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7) // amber
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val pPanelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }

    private val pValueBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val pValueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var dispRoll = 0f
    private var dispPitch = 0f
    private var lastFrameMs = 0L

    private fun smoothToward(current: Float, target: Float, dtSec: Float, tauSec: Float): Float {
        // 1st order low-pass, tau ~ “inertia”
        val a = 1f - exp(-dtSec / tauSec)
        return current + (target - current) * a
    }

    private fun smoothAngleToward(current: Float, target: Float, dtSec: Float, tauSec: Float): Float {
        // shortest path for angles
        var diff = (target - current) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val a = 1f - exp(-dtSec / tauSec)
        return current + diff * a
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = android.os.SystemClock.uptimeMillis()
        val dtSec = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs).coerceAtMost(50L)) / 1000f
        lastFrameMs = now

// TARGETS = surové vstupy z MainActivity
        val targetPitch = pitchDeg.coerceIn(-20f, 20f)
        val targetRoll = rollDeg.coerceIn(-89f, 89f)

// “gyro inertia” – doladíš tau (0.10–0.25s je fajn)
        val tauPitch = 0.18f
        val tauRoll = 0.14f

        if (dtSec == 0f) {
            dispPitch = targetPitch
            dispRoll = targetRoll
        } else {
            dispPitch = smoothToward(dispPitch, targetPitch, dtSec, tauPitch)
            dispRoll = smoothToward(dispRoll, targetRoll, dtSec, tauRoll)
        }

// ak chceš, nech sa to renderuje aj medzi frame-ami:
        postInvalidateOnAnimation()

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)

        // Heading strip
        val headingH = h * 0.16f
        drawHeadingBar(canvas, RectF(0f, 0f, w, headingH))

        // Attitude area
        val attTop = headingH
        val attH = h - attTop

        // Circle instrument centered in available area
        val size = min(w, attH)
        val cx = w / 2f
        val cy = attTop + attH / 2f
        val radius = size * 0.46f

        val circleRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clipPath = Path().apply { addOval(circleRect, Path.Direction.CW) }

        // ===== CLIP to circle =====
        canvas.save()
        canvas.clipPath(clipPath)

        // ===== MOVING BACKGROUND (classic attitude indicator) =====
        val roll = dispRoll
        val pitch = dispPitch


        // pxPerDeg tuned to circle size
        val pxPerDeg = (radius * 0.55f) / 20f

        // IMPORTANT: decide sign once:
        // If +pitch should move horizon DOWN (nose up), keep +pitch => +offset (down).
        // If it's opposite in your data, flip sign here.
        val pitchOffsetPx = (-pitch) * pxPerDeg

        // Rotate + translate the BACKGROUND (horizon disk)
        canvas.save()
        canvas.rotate(+roll, cx, cy)           // background tilts opposite to aircraft bank
        canvas.translate(0f, pitchOffsetPx)    // background moves for pitch

        // Draw sky/ground big enough to cover circle during rotation
        val pad = radius * 2f
        val horizonY = cy

        canvas.drawRect(cx - pad, cy - pad, cx + pad, horizonY, pSky)
        canvas.drawRect(cx - pad, horizonY, cx + pad, cy + pad, pGround)
        canvas.drawLine(cx - pad, horizonY, cx + pad, horizonY, pHorizon)

        // Pitch ladder limited to +/- 20
        drawPitchLadderCircle(canvas, cx, cy, radius, pxPerDeg)

        canvas.restore() // end background transform

        // ===== FIXED AIRCRAFT MARKER (wings + arc) =====
        drawFixedAircraftMarkerWithArc(canvas, cx, cy, radius)

        canvas.restore() // end clip

        // ===== BANK SCALE ON RIM (fixed) =====
        drawBankScaleOnRim(canvas, cx, cy, radius, rollDeg)

        drawSpeedTapeLeft(canvas, cx, cy, radius)
        drawAltTapeRight(canvas, cx, cy, radius)

        // Outer rim
        canvas.drawCircle(cx, cy, radius, pRing)
    }


    private fun drawPitchLadderCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        pxPerDeg: Float
    ) {
        // draw +/- 20 deg, step 5, label only 10 and 20
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
        // Wings line (fixed)
        val wingHalf = radius * 0.55f
        val wingY = cy
        canvas.drawLine(cx - wingHalf, wingY, cx + wingHalf, wingY, pPointer)

        // Center notch (small caret)
        //val notchW = dp(14f)
       // val notchH = dp(10f)
       // canvas.drawLine(cx - notchW, wingY, cx, wingY - notchH, pPointer)
       // canvas.drawLine(cx + notchW, wingY, cx, wingY - notchH, pPointer)

        // Arc ("oblúčik") under wings like in real instrument
        val arcR = radius * 0.15f
        val arcRect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        // lower half arc
        canvas.drawArc(arcRect, 0f, 180f, false, pPointer)

        // Small center dot
        canvas.drawCircle(cx, cy, dp(3f), Paint(pPointer).apply { style = Paint.Style.FILL })
    }

    private fun drawBankScaleOnRim(canvas: Canvas, cx: Float, cy: Float, radius: Float, rollDeg: Float) {
        // ===== 1) ROTUJEME LEN STUPNICU (dieliky), INDEX (trojuholník) zostane pevný =====
        canvas.save()
        // stupnica sa posúva opačne než roll, aby index ukazoval správny dielik
        canvas.rotate(-rollDeg.coerceIn(-90f, 90f), cx, cy)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(3f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        // dieliky po 10°
        for (a in -60..60 step 10) {
            val isMajor = (a % 30 == 0) // 30, 60
            val isMid = (a % 20 == 0)   // 20, 40 (voliteľné)
            val len = when {
                a == 0 -> dp(18f)
                isMajor -> dp(16f)
                isMid -> dp(12f)
                else -> dp(10f)
            }

            // uhol: 0° je hore (12h). Preto -90 posun.
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

        // ===== 2) PEVNÝ INDEX (trojuholník) – kreslíme bez rotácie =====
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


    private fun drawHeadingBar(canvas: Canvas, r: RectF) {
        // background
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 34, 55) }
        canvas.drawRoundRect(r, dp(12f), dp(12f), bg)

        // text centered
        val txt = "Heading: ${headingDeg.roundToInt()}°"
        val tw = pText.measureText(txt)
        val x = (r.width() - tw) / 2f
        val y = r.centerY() + pText.textSize * 0.35f
        canvas.drawText(txt, x, y, pText)
    }

    private fun drawPitchLadder(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        attTop: Float,
        w: Float,
        h: Float,
        pxPerDeg: Float,
        pitchOffset: Float
    ) {
        val halfLenMajor = w * 0.22f
        val halfLenMinor = w * 0.14f

        for (deg in -40..40 step 5) {
            if (deg == 0) continue
            val y = cy + pitchOffset - deg * pxPerDeg
            if (y < attTop - dp(40f) || y > h + dp(40f)) continue

            val major = (deg % 10 == 0)
            val halfLen = if (major) halfLenMajor else halfLenMinor

            canvas.drawLine(cx - halfLen, y, cx + halfLen, y, pLadder)

            if (major) {
                val label = abs(deg).toString()
                canvas.drawText(label, cx - halfLen - dp(28f), y + pTextSmall.textSize * 0.35f, pTextSmall)
                canvas.drawText(label, cx + halfLen + dp(10f), y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
        }
    }




    private fun drawSpeedTapeLeft(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val w = width.toFloat()
        val tapeW = radius * 0.42f
        val tapeH = radius * 1.35f

        val left = (cx - radius) - tapeW - dp(10f)
        val top = cy - tapeH / 2f
        val r = RectF(left, top, left + tapeW, top + tapeH)

        drawTapeBox(canvas, r, title = "SPD", value = speed, unit = "km/h")
    }
    private fun drawAltTapeRight(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val w = width.toFloat()
        val tapeW = radius * 0.42f
        val tapeH = radius * 1.35f

        val left = (cx + radius) + dp(10f)
        val top = cy - tapeH / 2f
        val r = RectF(left, top, left + tapeW, top + tapeH)

        // ALT tape
        drawTapeBox(canvas, r, title = "ALT", value = altitude, unit = "m")

        // VS malý box vedľa/na spodku ALT
        val vsBoxH = dp(34f)
        val vsR = RectF(r.left, r.bottom + dp(8f), r.right, r.bottom + dp(8f) + vsBoxH)
        drawSmallValueBox(canvas, vsR, title = "VS", value = vsMps, unit = "m/s")
    }
    private fun drawAltTape(canvas: Canvas, r: RectF) {
        drawTape(canvas, r, "ALT", altitude, unit = "m", highlightColor = Color.rgb(255, 193, 7))
    }
    private fun drawTapeBox(canvas: Canvas, r: RectF, title: String, value: Float?, unit: String) {
        // panel
        canvas.drawRoundRect(r, dp(10f), dp(10f), pPanel)
        canvas.drawRoundRect(r, dp(10f), dp(10f), pPanelStroke)

        // title
        val tx = r.left + dp(10f)
        val ty = r.top + dp(22f)
        canvas.drawText(title, tx, ty, pText)

        // ticks
        val midY = r.centerY()
        val stepPx = dp(26f)

        if (value == null || !value.isFinite()) {
            canvas.drawText("N/A", tx, midY, pText)
            return
        }

        val v = value
        val base = (v / 10f).roundToInt() * 10

        val tickPaint = Paint(pLadder).apply { strokeWidth = dp(2f) }

        var y = midY - 5 * stepPx
        for (i in -5..5) {
            val tickVal = base + i * 10
            val isMajor = (tickVal % 20 == 0)

            val x1 = r.left + dp(10f)
            val x2 = r.left + if (isMajor) dp(46f) else dp(32f)
            canvas.drawLine(x1, y, x2, y, tickPaint)

            if (isMajor) {
                canvas.drawText(
                    tickVal.toString(),
                    r.left + dp(54f),
                    y + pTextSmall.textSize * 0.35f,
                    pTextSmall
                )
            }
            y += stepPx
        }

        // value window
        val boxW = r.width() * 0.78f
        val boxH = dp(36f)
        val box = RectF(
            r.centerX() - boxW / 2f,
            midY - boxH / 2f,
            r.centerX() + boxW / 2f,
            midY + boxH / 2f
        )
        canvas.drawRoundRect(box, dp(8f), dp(8f), pValueBox)

        val valTxt = v.roundToInt().toString()
        val tw = pValueText.measureText(valTxt)
        canvas.drawText(valTxt, box.centerX() - tw / 2f, box.centerY() + pValueText.textSize * 0.35f, pValueText)

        // unit
        canvas.drawText(unit, box.right + dp(6f), box.centerY() + pTextSmall.textSize * 0.35f, pTextSmall)
    }

    private fun drawSmallValueBox(canvas: Canvas, r: RectF, title: String, value: Float?, unit: String) {
        canvas.drawRoundRect(r, dp(10f), dp(10f), pPanel)
        canvas.drawRoundRect(r, dp(10f), dp(10f), pPanelStroke)

        val t = "$title:"
        canvas.drawText(t, r.left + dp(10f), r.centerY() + pTextSmall.textSize * 0.35f, pTextSmall)

        val txt = if (value == null || !value.isFinite()) "N/A" else String.format("%.1f", value)
        val tw = pText.measureText(txt)
        canvas.drawText(txt, r.right - dp(10f) - tw, r.centerY() + pText.textSize * 0.35f, pText)

        // unit vpravo (malé)
        canvas.drawText(unit, r.right + dp(6f), r.centerY() + pTextSmall.textSize * 0.35f, pTextSmall)
    }
    private fun drawTape(
        canvas: Canvas,
        r: RectF,
        title: String,
        value: Float?,
        unit: String,
        highlightColor: Int
    ) {
        // panel
        canvas.drawRoundRect(r, dp(8f), dp(8f), pPanel)
        canvas.drawRoundRect(r, dp(8f), dp(8f), pPanelStroke)

        // title
        val titlePaint = Paint(pText).apply { textSize = sp(14f) }
        val tx = r.left + dp(10f)
        val ty = r.top + dp(22f)
        canvas.drawText(title, tx, ty, titlePaint)

        // scale ticks
        val midY = r.centerY()
        val stepPx = dp(28f)
        val tickPaint = Paint(pLadder).apply { strokeWidth = dp(2f) }

        // If no value, just show N/A
        if (value == null || !value.isFinite()) {
            canvas.drawText("N/A", tx, midY, pText)
            return
        }

        val v = value

        // draw a few ticks around the current value
        val base = (v / 10f).roundToInt() * 10
        var y = midY - 5 * stepPx
        for (i in -5..5) {
            val tickVal = base + i * 10
            val isMajor = (tickVal % 20 == 0)

            val x1 = r.left + dp(10f)
            val x2 = r.left + if (isMajor) dp(44f) else dp(30f)
            canvas.drawLine(x1, y, x2, y, tickPaint)

            if (isMajor) {
                val label = tickVal.toString()
                canvas.drawText(label, r.left + dp(52f), y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
            y += stepPx
        }

        // value box
        val boxW = r.width() * 0.75f
        val boxH = dp(36f)
        val box = RectF(r.centerX() - boxW / 2f, midY - boxH / 2f, r.centerX() + boxW / 2f, midY + boxH / 2f)

        // small colored accent
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = highlightColor; style = Paint.Style.FILL }
        canvas.drawRoundRect(RectF(box.left - dp(8f), box.top, box.left + dp(6f), box.bottom), dp(6f), dp(6f), accent)

        canvas.drawRoundRect(box, dp(8f), dp(8f), pValueBox)

        val valTxt = v.roundToInt().toString()
        val tw = pValueText.measureText(valTxt)
        canvas.drawText(valTxt, box.centerX() - tw / 2f, box.centerY() + pValueText.textSize * 0.35f, pValueText)

        // unit (small)
        canvas.drawText(unit, box.right + dp(6f), box.centerY() + pTextSmall.textSize * 0.35f, pTextSmall)
    }

    private fun norm360(d: Float): Float {
        var x = d % 360f
        if (x < 0f) x += 360f
        return x
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
