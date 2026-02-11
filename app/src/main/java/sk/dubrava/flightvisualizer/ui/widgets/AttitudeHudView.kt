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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)

        // Heading strip (ak chceš ponechať)
        val headingH = h * 0.16f
        drawHeadingBar(canvas, RectF(0f, 0f, w, headingH))

        // Attitude area
        val attTop = headingH
        val attH = h - attTop

        // Kruh v strede dostupnej plochy
        val size = min(w, attH)
        val cx = w / 2f
        val cy = attTop + attH / 2f
        val radius = size * 0.46f

        val circleRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clipPath = Path().apply { addOval(circleRect, Path.Direction.CW) }

        // ===== CLIP do kruhu =====
        canvas.save()
        canvas.clipPath(clipPath)

        // ===== POZADIE (ATTITUDE BALL) – TU SA HÝBE HORIZONT =====
        val pitchClamped = pitchDeg.coerceIn(-maxPitchDraw, maxPitchDraw)

        // citlivosť pitch – odporúčam viazať na radius, nie na height
        val pxPerDeg = (radius * 0.70f) / maxPitchDraw

        // Ak máš pitch teraz správne, nechaj to takto:
        // +pitch (nose up) => horizont ide dole
        val pitchOffsetPx = (+pitchClamped) * pxPerDeg

        // Roll: pozadie sa otáča opačne ako lietadlo (klasický AI)
        canvas.save()
        canvas.rotate(+rollDeg, cx, cy)
        canvas.translate(0f, pitchOffsetPx)

        // Sky/Ground (kreslíme „cez“ celý kruh, orez spraví clip)
        val big = radius * 3f
        val horizonY = cy

        canvas.drawRect(cx - big, cy - big, cx + big, horizonY, pSky)
        canvas.drawRect(cx - big, horizonY, cx + big, cy + big, pGround)
        canvas.drawLine(cx - big, horizonY, cx + big, horizonY, pHorizon)

        // Pitch ladder sa kreslí v rovnakom transformovanom priestore
        drawPitchLadderCircle(canvas, cx, cy, radius, pxPerDeg)

        canvas.restore() // end rotate+translate pozadia

        // ===== FIXNÝ MARKER (KRÍDELKÁ STOJA) =====
        drawFixedAircraftMarker(canvas, cx, cy, radius)

        canvas.restore() // end clip kruhu

        // ===== RIM: roll dieliky okolo kruhu (pevné) =====
        drawBankScaleOnRim(canvas, cx, cy, radius)

        // (voliteľne) tenký okraj kruhu
        canvas.drawOval(circleRect, pRing)
    }
    private fun drawPitchLadderCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        pxPerDeg: Float
    ) {
        val majorHalf = radius * 0.55f
        val minorHalf = radius * 0.35f

        for (deg in -40..40 step 5) {
            if (deg == 0) continue

            val y = cy - deg * pxPerDeg
            if (y < cy - radius - dp(20f) || y > cy + radius + dp(20f)) continue

            val major = (deg % 10 == 0)
            val half = if (major) majorHalf else minorHalf

            canvas.drawLine(cx - half, y, cx + half, y, pLadder)

            if (major) {
                val label = abs(deg).toString()
                canvas.drawText(label, cx - half - dp(24f), y + pTextSmall.textSize * 0.35f, pTextSmall)
                canvas.drawText(label, cx + half + dp(8f),  y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
        }
    }


    private fun drawFixedAircraftMarker(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val wingHalf = radius * 0.45f
        val wingY = cy + dp(8f)

        // dlhé krídla
        canvas.drawLine(cx - wingHalf, wingY, cx + wingHalf, wingY, pPointer)

        // stredový “U”/zárez (jednoduchá verzia)
        val notchW = radius * 0.10f
        val notchH = radius * 0.08f
        canvas.drawLine(cx - notchW, wingY, cx, wingY - notchH, pPointer)
        canvas.drawLine(cx + notchW, wingY, cx, wingY - notchH, pPointer)

        // malý trojuholník hore v strede
        val caretW = radius * 0.04f
        val caretH = radius * 0.05f
        canvas.drawLine(cx - caretW, wingY - dp(3f), cx, wingY - dp(3f) - caretH, pPointer)
        canvas.drawLine(cx + caretW, wingY - dp(3f), cx, wingY - dp(3f) - caretH, pPointer)
    }


    private fun drawPitchLadderCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        pxPerDeg: Float,
        pitchOffsetPx: Float
    ) {
        val halfMajor = radius * 0.55f
        val halfMinor = radius * 0.35f

        for (deg in -40..40 step 5) {
            if (deg == 0) continue

            val y = cy + pitchOffsetPx - deg * pxPerDeg
            if (y < cy - radius * 1.1f || y > cy + radius * 1.1f) continue

            val major = (deg % 10 == 0)
            val half = if (major) halfMajor else halfMinor

            canvas.drawLine(cx - half, y, cx + half, y, pLadder)

            if (major) {
                val label = abs(deg).toString()
                val tw = pTextSmall.measureText(label)
                canvas.drawText(label, cx - half - dp(10f) - tw, y + pTextSmall.textSize * 0.35f, pTextSmall)
                canvas.drawText(label, cx + half + dp(10f), y + pTextSmall.textSize * 0.35f, pTextSmall)
            }
        }
    }
    private fun drawAircraftReferenceMarkerCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        rollDeg: Float,
        pitchDeg: Float,
        pxPerDeg: Float
    ) {
        val pitchClamped = pitchDeg.coerceIn(-maxPitchDraw, maxPitchDraw)

        // +pitch -> marker hore (mínus)
        val pitchOffsetPx = pitchClamped * pxPerDeg

        canvas.save()
        canvas.translate(0f, pitchOffsetPx)

        // Roll: ak bude stále opačne, zmeň na canvas.rotate(-rollDeg,...)
        canvas.rotate(+rollDeg, cx, cy)

        val y = cy
        val wingHalf = radius * 0.62f

        // Wings (rozrezané v strede)
        val gap = dp(28f)
        canvas.drawLine(cx - wingHalf, y, cx - gap, y, pPointer)
        canvas.drawLine(cx + gap, y, cx + wingHalf, y, pPointer)

        // „zárez“ do stredu (ako avionika)
        val notchW = dp(14f)
        val notchH = dp(10f)
        canvas.drawLine(cx - notchW, y, cx, y - notchH, pPointer)
        canvas.drawLine(cx + notchW, y, cx, y - notchH, pPointer)

        // malý caret v strede
        //val caretW = dp(6f)
       // val caretH = dp(6f)
       // canvas.drawLine(cx - caretW, y - dp(2f), cx, y - dp(2f) - caretH, pPointer)
        //canvas.drawLine(cx + caretW, y - dp(2f), cx, y - dp(2f) - caretH, pPointer)

        // dolné chevróny (2x)
        val chW = dp(10f)
        val chH = dp(10f)
        val chGap = dp(8f)

        val ch1Y = y + dp(16f)
        canvas.drawLine(cx - chW, ch1Y - chH, cx, ch1Y, pPointer)
        canvas.drawLine(cx + chW, ch1Y - chH, cx, ch1Y, pPointer)

        val ch2Y = ch1Y + chGap + chH
        canvas.drawLine(cx - chW, ch2Y - chH, cx, ch2Y, pPointer)
        canvas.drawLine(cx + chW, ch2Y - chH, cx, ch2Y, pPointer)

        canvas.restore()
    }
    private fun drawBankScaleOnRim(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val tickOuter = radius
        val tickMajor = radius * 0.12f
        val tickMinor = radius * 0.07f

        // klasické bank značky
        val marks = listOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)

        for (a in marks) {
            val isMajor = (abs(a) == 60 || abs(a) == 45 || abs(a) == 30 || abs(a) == 20 || abs(a) == 10)
            val len = if (a == 0) tickMajor else if (isMajor) tickMajor else tickMinor

            // 0° je hore
            val ang = Math.toRadians((a - 90).toDouble())
            val x1 = cx + cos(ang).toFloat() * (tickOuter - len)
            val y1 = cy + sin(ang).toFloat() * (tickOuter - len)
            val x2 = cx + cos(ang).toFloat() * tickOuter
            val y2 = cy + sin(ang).toFloat() * tickOuter

            canvas.drawLine(x1, y1, x2, y2, pRingTick)
        }

        // pevný "index" trojuholník hore
        val tri = Path().apply {
            moveTo(cx, cy - radius - dp(2f))
            lineTo(cx - dp(12f), cy - radius + dp(18f))
            lineTo(cx + dp(12f), cy - radius + dp(18f))
            close()
        }
        val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawPath(tri, triPaint)
    }



    private fun drawBankRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        rollDeg: Float
    ) {
        // outer circle
        canvas.drawCircle(cx, cy, radius, pRing)

        // ticks/labels (bank angles)
        val angles = listOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)
        val tickMajor = setOf(60, 45, 30, 20, 10)

        for (a in angles) {
            // map bank angle to circle position: 0 at top (12 o'clock)
            val angRad = Math.toRadians((a - 90).toDouble())

            val len = if (a == 0) dp(16f) else if (abs(a) in tickMajor) dp(12f) else dp(8f)
            val x1 = cx + cos(angRad).toFloat() * (radius - len)
            val y1 = cy + sin(angRad).toFloat() * (radius - len)
            val x2 = cx + cos(angRad).toFloat() * radius
            val y2 = cy + sin(angRad).toFloat() * radius

            canvas.drawLine(x1, y1, x2, y2, pRingTick)

            // labels (upright, only for 10/20/30/45/60, not 0)
            val ab = abs(a)
            if (a != 0 && ab in tickMajor) {
                val label = ab.toString()
                val tw = pRingText.measureText(label)

                val rText = radius - len - dp(14f)
                val lx = cx + cos(angRad).toFloat() * rText - tw / 2f
                val ly = cy + sin(angRad).toFloat() * rText + pRingText.textSize * 0.35f
                canvas.drawText(label, lx, ly, pRingText)
            }
        }

        // pointer: roll + = right bank => pointer rotates clockwise (+)
        canvas.save()
        canvas.rotate(rollDeg.coerceIn(-60f, 60f), cx, cy)

        val yTop = cy - radius + dp(6f)
        val yBottom = yTop + dp(22f)
        canvas.drawLine(cx - dp(10f), yBottom, cx, yTop, pRingPointer)
        canvas.drawLine(cx + dp(10f), yBottom, cx, yTop, pRingPointer)

        canvas.restore()
    }



    private fun drawAircraftReferenceMarker(canvas: Canvas, cx: Float, cy: Float) {
        val pitchClamped = pitchDeg.coerceIn(-maxPitchDraw, maxPitchDraw)

        // +pitch -> symbol ide hore (preto mínus)
        val pitchOffsetPx = (-pitchClamped) * pitchPxPerDeg

        canvas.save()
        canvas.translate(0f, pitchOffsetPx)

        // roll + = bank doprava => symbol clockwise (+)
        canvas.rotate(rollDeg, cx, cy)

        val wingHalf = width * 0.18f
        val y = cy

        // wings
        canvas.drawLine(cx - wingHalf, y, cx + wingHalf, y, pPointer)

        // center notch (malý “V” hore z krídla)
        val notchW = dp(14f)
        val notchH = dp(10f)
        val notchTopY = y - notchH
        canvas.drawLine(cx - notchW, y, cx, notchTopY, pPointer)
        canvas.drawLine(cx + notchW, y, cx, notchTopY, pPointer)

        // small center caret (tiny ^ in the middle)
        val caretW = dp(6f)
        val caretH = dp(6f)
        canvas.drawLine(cx - caretW, y - dp(2f), cx, y - dp(2f) - caretH, pPointer)
        canvas.drawLine(cx + caretW, y - dp(2f), cx, y - dp(2f) - caretH, pPointer)

        // lower chevrons (dve šípky dole ako na obrázku)
        val chW = dp(10f)
        val chH = dp(10f)
        val chGap = dp(8f)

        val ch1Y = y + dp(16f)
        canvas.drawLine(cx - chW, ch1Y - chH, cx, ch1Y, pPointer)
        canvas.drawLine(cx + chW, ch1Y - chH, cx, ch1Y, pPointer)

        val ch2Y = ch1Y + chGap + chH
        canvas.drawLine(cx - chW, ch2Y - chH, cx, ch2Y, pPointer)
        canvas.drawLine(cx + chW, ch2Y - chH, cx, ch2Y, pPointer)

        canvas.restore()
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

    private fun drawRollScale(canvas: Canvas, cx: Float, y: Float, radius: Float) {
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }

        val oval = RectF(cx - radius, y - radius, cx + radius, y + radius)
        canvas.drawArc(oval, 200f, 140f, false, arcPaint)

        // ticks at -60..+60, major at 30
        for (a in -60..60 step 10) {
            val ang = Math.toRadians((a - 90).toDouble()) // shift for drawing
            val isMajor = (a % 30 == 0)
            val r1 = radius
            val r2 = radius - if (isMajor) dp(18f) else dp(10f)
            val x1 = cx + cos(ang).toFloat() * r1
            val y1 = y + sin(ang).toFloat() * r1
            val x2 = cx + cos(ang).toFloat() * r2
            val y2 = y + sin(ang).toFloat() * r2
            canvas.drawLine(x1, y1, x2, y2, arcPaint)
        }

        // top pointer triangle
        val tri = Path().apply {
            moveTo(cx, y - radius + dp(6f))
            lineTo(cx - dp(10f), y - radius + dp(26f))
            lineTo(cx + dp(10f), y - radius + dp(26f))
            close()
        }
        val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawPath(tri, triPaint)
    }

    private fun drawFixedMarker(canvas: Canvas, cx: Float, cy: Float) {
        // horizontálna "wings" čiara (dlhšia, jemne vyššie)
        val wingHalf = width * 0.16f
        val wingY = cy + dp(6f)
        canvas.drawLine(cx - wingHalf, wingY, cx + wingHalf, wingY, pPointer)

        // jasné "V" (väčšie, vrchol vyššie)
        val vTopY = cy - dp(18f)
        val vBottomY = cy + dp(26f)
        val vHalf = dp(26f)

        canvas.drawLine(cx - vHalf, vBottomY, cx, vTopY, pPointer)
        canvas.drawLine(cx + vHalf, vBottomY, cx, vTopY, pPointer)

        // malé "ramená" pri vrchole (aby to vyzeralo ako v avionike)
        val arm = dp(18f)
        val armY = vTopY + dp(10f)
        canvas.drawLine(cx - arm, armY, cx - dp(6f), armY, pPointer)
        canvas.drawLine(cx + dp(6f), armY, cx + arm, armY, pPointer)
    }


    private fun drawSpeedTape(canvas: Canvas, r: RectF) {
        drawTape(canvas, r, "SPEED", speed, unit = "km/h", highlightColor = Color.rgb(33, 150, 243))
    }

    private fun drawAltTape(canvas: Canvas, r: RectF) {
        drawTape(canvas, r, "ALT", altitude, unit = "m", highlightColor = Color.rgb(255, 193, 7))
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
