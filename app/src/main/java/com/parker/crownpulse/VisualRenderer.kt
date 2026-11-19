package com.parker.crownpulse

import android.content.Context
import android.graphics.*
import java.util.Random
import kotlin.math.*

/** Preview and export share this exact deterministic drawing engine and music clock. */
class VisualRenderer(context: Context) {
    private val crown = BitmapFactory.decodeStream(context.assets.open("crown.png"))
    private val paper = BitmapFactory.decodeStream(context.assets.open("paper.png"))
    var artwork: Bitmap? = null
    /** Full-size imported photo (source for [PhotoBackground]). */
    var backdrop: Bitmap? = null
    /** Bottom layer: color, photo, GIF or video frame chosen by music time. */
    var background: BackgroundSource? = null
    private val blur = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    private val blurCanvas = Canvas(blur)
    private val blurRect = RectF()
    private val bgRect = RectF()
    private var crownInk: Int? = null
    private var crownFilter: ColorFilter? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stampRect = RectF()
    // Beat effects: tints for the snare color split and the beat clock for the current frame.
    // All preallocated so the effects add no per-frame allocation.
    private val splitRed = Color.rgb(255, 59, 48)
    private val splitCyan = Color.rgb(40, 228, 255)
    private val splitRedFilter = PorterDuffColorFilter(splitRed, PorterDuff.Mode.SRC_IN)
    private val splitCyanFilter = PorterDuffColorFilter(splitCyan, PorterDuff.Mode.SRC_IN)
    /** Seconds since the latest (0) and previous (1) beat; -1 when there is none. */
    private val beatAge = FloatArray(2)
    /** Strength 0..1 of those beats. */
    private val beatPower = FloatArray(2)
    /** Whether those beats start a bar. */
    private val beatDown = BooleanArray(2)
    /** Seconds since the latest (0) and previous (1) bar line; -1 when there is none. */
    private val barAge = FloatArray(2)
    // Note color glow: a soft white disc kept as an alpha mask (ALPHA_8), so drawing it takes
    // its color from the paint and any hue costs no new bitmap, shader or filter per frame.
    private val halo: Bitmap = run {
        val size = 128
        val disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader =
            RadialGradient(
                size / 2f,
                size / 2f,
                size / 2f,
                intArrayOf(Color.WHITE, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, .42f, 1f),
                Shader.TileMode.CLAMP,
            )
        Canvas(disc).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        disc.extractAlpha().also { disc.recycle() }
    }
    private val haloRect = RectF()
    /** Hue, agreement and presence of the recent notes (see [BeatFx.noteColor]). */
    private val glowNote = FloatArray(3)
    private val glowHsv = FloatArray(3)
    private val screenRect = RectF()
    // The vignette only changes when its strength or the frame height does, so keep it.
    private var vignetteShader: Shader? = null
    private var vignetteKey = Float.NaN
    private var vignetteHeight = Float.NaN
    private val path = Path()
    private var h = 1920f
    private val tau = (Math.PI * 2).toFloat()
    private val white = Color.rgb(229, 228, 222)
    private val originals =
        arrayOf(
            intArrayOf(0x08090C, 0xF04A37, 0xB36BEE, 0xDDF75D),
            intArrayOf(0x10080D, 0xDDF75D, 0xF04A37, 0xB36BEE),
            intArrayOf(0x0E0818, 0xB36BEE, 0xDDF75D, 0xF04A37),
            intArrayOf(0x080D0D, 0xDDF75D, 0xB36BEE, 0xF04A37),
            intArrayOf(0x21070E, 0xF04A37, 0xB36BEE, 0xDDF75D),
        )

    private fun ink(color: Int, alpha: Float = 1f, width: Float = 1f, fill: Boolean = false) {
        p.reset()
        p.isAntiAlias = true
        p.isFilterBitmap = true
        p.color = color or (0xff shl 24)
        p.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        p.strokeWidth = width
        p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    private fun line(
        c: Canvas,
        x: Float,
        y: Float,
        x2: Float,
        y2: Float,
        col: Int,
        width: Float = 1f,
        a: Float = 1f,
    ) {
        ink(col, a, width)
        c.drawLine(x, y, x2, y2, p)
    }

    private fun circle(
        c: Canvas,
        x: Float,
        y: Float,
        r: Float,
        col: Int,
        width: Float = 1f,
        a: Float = 1f,
        fill: Boolean = false,
    ) {
        if (r <= 0) return
        ink(col, a, width, fill)
        c.drawCircle(x, y, r, p)
    }

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, hh: Float, col: Int, a: Float = 1f) {
        ink(col, a, fill = true)
        c.drawRect(x, y, x + w, y + hh, p)
    }

    private fun yy(y: Float) = y * h / 1920f

    private fun palette(s: Settings, mode: Int): IntArray {
        return when (s.s("palette")) {
            "Original" -> originals[mode]
            "Neon" -> intArrayOf(0x050712, 0x48E5F0, 0xFF48CB, 0xDDF75D)
            "Aurora" -> intArrayOf(0x060A1B, 0x7BF1CF, 0x9E7BFF, 0x4D90FF)
            "Sunset" -> intArrayOf(0x1A090F, 0xFF6B45, 0xFFCA60, 0xD15AE0)
            "Ice" -> intArrayOf(0x061629, 0xA3EEFF, 0x459AFF, 0xFFFFFF)
            "Candy" -> intArrayOf(0x170A20, 0xFF75C3, 0xAA7EFF, 0x63EFE0)
            "Monochrome" -> intArrayOf(0x09090C, 0xBBBBBB, 0xFFFFFF, 0x777777)
            else ->
                intArrayOf(
                    s.color("background"),
                    s.color("accent1"),
                    s.color("accent2"),
                    s.color("accent3"),
                )
        }
    }

    private fun mode(t: Float, a: Analysis, s: Settings): Int {
        if (!s.b("sceneChanges")) return 0
        if (s.s("preset") == "Original crown" && abs(a.duration - 197.79f) < .1f) {
            val times =
                floatArrayOf(
                    0f,
                    19.19f,
                    46.78f,
                    73.89f,
                    88.86f,
                    117.86f,
                    147.34f,
                    160.07f,
                    181.73f,
                    192.11f,
                )
            val modes = intArrayOf(0, 1, 3, 2, 1, 4, 0, 3, 1, 0)
            var j = 0
            for (i in times.indices) if (t >= times[i]) j = i
            return modes[j]
        }
        // Change scenes on tracked bar lines when the analysis has a beat grid; a manual
        // tempo override (or an older analysis file) keeps the original fixed-length bars.
        val bar = if (s.f("tempoOverride") >= 30) null else a.barIndex(t)
        if (bar != null)
            return ((bar - 1).coerceAtLeast(0) / s.i("sceneBars").coerceAtLeast(1) +
                s.i("seed") % 5) % 5
        return ((t /
                (60f / (if (s.f("tempoOverride") >= 30) s.f("tempoOverride") else a.bpm) *
                    4 *
                    s.i("sceneBars").coerceAtLeast(1)))
            .toInt() + s.i("seed") % 5) % 5
    }

    /**
     * Fills [beatAge], [beatPower], [beatDown] and [barAge] for music time [t]. Uses the tracked
     * beat grid when there is one; a manual tempo override or an older analysis file without a
     * grid uses a fixed 4/4 grid from 0 s, matching how scene changes pick their bars.
     */
    private fun updateBeatClock(t: Float, a: Analysis, s: Settings) {
        val override = s.f("tempoOverride")
        if (override >= 30f || a.beatTimes.isEmpty()) {
            val period = BeatFx.period(if (override >= 30f) override else a.bpm)
            val latest = if (period > 0f) floor(t / period).toInt() else -1
            for (k in 0..1) {
                beatAge[k] = BeatFx.gridAge(t, period, k)
                barAge[k] = BeatFx.gridAge(t, period * 4, k)
                beatPower[k] = .8f
                beatDown[k] = latest - k >= 0 && (latest - k) % 4 == 0
            }
            return
        }
        val i = BeatFx.lastAtOrBefore(a.beatTimes, t)
        for (k in 0..1) {
            val j = i - k
            if (j >= 0) {
                beatAge[k] = t - a.beatTimes[j]
                beatPower[k] = a.beatStrengths[j].coerceIn(0f, 1f)
                beatDown[k] = a.beatIsDownbeat[j]
            } else {
                beatAge[k] = -1f
                beatPower[k] = 0f
                beatDown[k] = false
            }
            barAge[k] = BeatFx.ageOf(a.downbeatTimes, t, k)
        }
    }

    /**
     * Thin rings that leave the symbol on every beat, with a bolder double ring on the first
     * beat of each bar. Rings ease out (fast start, gentle stop) and fade with a cubic decay.
     */
    private fun shockwaves(
        c: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        amount: Float,
        beatInk: Int,
        barInk: Int,
    ) {
        val base = size * .5f
        for (k in 0..1) {
            val age = barAge[k]
            val fade = BeatFx.decay(age, BeatFx.DOWNBEAT_RING_LIFE)
            if (fade < .01f) continue
            val r = base + BeatFx.ringTravel(age, BeatFx.DOWNBEAT_RING_LIFE) * 760f
            val alpha = (.7f * fade * amount).coerceIn(0f, 1f)
            circle(c, cx, cy, r, barInk, 1.5f + 6f * fade, alpha)
            circle(c, cx, cy, r * .86f, barInk, 1f + 2.5f * fade, alpha * .5f)
        }
        for (k in 0..1) {
            if (beatDown[k]) continue // the bar ring already marks this beat
            val age = beatAge[k]
            val fade = BeatFx.decay(age, BeatFx.BEAT_RING_LIFE)
            if (fade < .01f) continue
            val r = base + BeatFx.ringTravel(age, BeatFx.BEAT_RING_LIFE) * 430f
            val alpha = (.45f * fade * amount * (.5f + .5f * beatPower[k])).coerceIn(0f, 1f)
            circle(c, cx, cy, r, beatInk, 1.2f + 3f * fade, alpha)
        }
    }

    /**
     * A soft glow behind the symbol whose color follows the notes: each note's place on the
     * circle of fifths sets the hue, so a key or chord change slowly turns the glow. Clear
     * chords give a deep color, busy passages a paler one, and the bass makes it brighter
     * and bigger. Without detected notes it glows in the palette's main color.
     */
    private fun noteGlow(
        c: Canvas,
        t: Float,
        a: Analysis,
        cx: Float,
        cy: Float,
        size: Float,
        bass: Float,
        amount: Float,
        fallback: Int,
    ) {
        val alpha = BeatFx.glowAlpha(bass, amount)
        if (alpha < .01f) return
        BeatFx.noteColor(a.noteTimes, a.noteMidi, a.noteVelocity, t, glowNote)
        val presence = glowNote[2]
        val color =
            if (presence < .05f) fallback
            else {
                glowHsv[0] = glowNote[0]
                glowHsv[1] = (.3f + .6f * glowNote[1]) * min(1f, presence * 2f)
                glowHsv[2] = 1f
                Color.HSVToColor(glowHsv)
            }
        val r = size * (.62f + .2f * bass.coerceIn(0f, 1f))
        haloRect.set(cx - r, cy - r * 1.12f, cx + r, cy + r * 1.12f)
        ink(color, alpha, fill = true)
        c.drawBitmap(halo, null, haloRect, p)
    }

    /**
     * Small four-point glints around the symbol on hi-hat hits. Places come from a hash of
     * the music time (a new set 15 times a second), so preview and export match; each set
     * fades a little before the next one replaces it.
     */
    private fun sparkles(
        c: Canvas,
        t: Float,
        s: Settings,
        cx: Float,
        cy: Float,
        size: Float,
        hat: Float,
        amount: Float,
        accent: Int,
    ) {
        val count = BeatFx.sparkleCount(hat, amount)
        if (count == 0) return
        val clock = t * 15f
        val slot = clock.toInt()
        val fade = 1f - .6f * (clock - slot)
        val strength = ((hat - BeatFx.SPARKLE_THRESHOLD) / (1f - BeatFx.SPARKLE_THRESHOLD))
            .coerceIn(0f, 1f)
        for (j in 0 until count) {
            val n = slot * 7919 + j * 131 + s.i("seed") * 17
            val angle = BeatFx.hash01(n) * tau
            val rad = size * (.34f + .5f * BeatFx.hash01(n + 1))
            val x = cx + cos(angle) * rad
            val y = cy + sin(angle) * rad * 1.15f
            val len = (9f + 20f * BeatFx.hash01(n + 2)) * (.6f + .4f * strength)
            val alpha = (.5f + .5f * BeatFx.hash01(n + 3)) * fade * min(1f, .4f + strength)
            val col = if (j % 3 == 0) accent else white
            line(c, x - len, y, x + len, y, col, 2f, alpha)
            line(c, x, y - len, x, y + len, col, 2f, alpha)
            val d = len * .35f
            line(c, x - d, y - d, x + d, y + d, col, 1.2f, alpha * .6f)
            line(c, x - d, y + d, x + d, y - d, col, 1.2f, alpha * .6f)
        }
    }

    fun draw(
        c: Canvas,
        width: Int,
        height: Int,
        time: Float,
        a: Analysis,
        s: Settings,
        export: Boolean = false,
    ) {
        h = 1080f * height / width
        val t = (time + s.f("syncOffset") / 1000).coerceIn(0f, a.duration)
        val index = a.index(t)
        val frame = (t * 30).toInt()
        val phase = if (s.i("seed") == 8139) 0f else (s.i("seed") % 997) * .073f
        var flow = a.flow[index] * s.f("speed") + phase
        fun feature(k: Int): Float {
            val current = a.value(t, k)
            val past = a.value((t - s.f("smooth") * .35f).coerceAtLeast(0f), k)
            return (current * (1 - s.f("smooth") * .5f) + past * s.f("smooth") * .5f) *
                s.f("sensitivity")
        }
        val bass = (feature(0) * s.f("bassGain")).coerceIn(0f, 1.8f)
        val energy = feature(3).coerceIn(0f, 1.5f)
        val high = feature(2) * s.f("highGain")
        val kick =
            when (s.s("reaction")) {
                "Bass" -> bass
                "Snare" -> feature(6) * s.f("midGain")
                "Highs" -> feature(7) * s.f("highGain")
                "Energy" -> energy
                else -> feature(5) * s.f("bassGain")
            }.coerceIn(0f, 1.7f)
        val snare = (feature(6) * s.f("midGain")).coerceIn(0f, 1.3f)
        val hat = (feature(7) * s.f("highGain")).coerceIn(0f, 1.3f)
        val md = mode(t, a, s)
        val pal = palette(s, md)
        val bg = if (s.s("background") == "#08090C") pal[0] else s.color("background")
        val primary = pal[1]
        val secondary = pal[2]
        val tertiary = pal[3]
        val detail =
            if (export || s.s("quality") == "High") 1f
            else if (s.s("quality") == "Battery saver") .5f else .75f
        c.save()
        c.scale(width / 1080f, width / 1080f)
        c.clipRect(0f, 0f, 1080f, h)
        c.drawColor(bg or (0xff shl 24))
        if (s.s("backgroundType") != BackgroundMath.COLOR)
            background?.frame(time)?.let { drawBackground(c, it, s, bass, kick) }
        if (s.f("grain") > 0) {
            ink(white, s.f("grain") * .09f, fill = true)
            screenRect.set(0f, 0f, 1080f, h)
            c.drawBitmap(paper, null, screenRect, p)
        }
        var cx =
            540 +
                (sin(flow * .48f) * 105 + sin(flow * .19f) * 55) * s.f("drift") +
                s.f("positionX") * 400
        var cy = yy(910 + cos(flow * .37f) * 210 * s.f("drift")) + s.f("positionY") * h * .35f
        if (md == 2) {
            cx += sin(flow * .7f) * 60 * s.f("drift")
            cy += yy(sin(flow * .57f) * 130 * s.f("drift"))
        }
        c.translate(
            sin(frame * 2f) * snare * s.f("shake") * 30,
            cos(frame * 1.7f) * snare * s.f("shake") * 20,
        )
        if (s.b("mirror")) {
            c.translate(1080f, 0f)
            c.scale(-1f, 1f)
        }
        updateBeatClock(t, a, s)
        // Punch in on the first beat of each bar: a short zoom around the symbol that settles
        // within half a second. The layers zoom; background, grain, vignette and text do not.
        val punchSave = c.save()
        if (s.b("downbeatPunch")) {
            val zoom = BeatFx.punchScale(barAge[0], s.f("punchAmount"))
            if (zoom > 1.0005f) c.scale(zoom, zoom, cx, cy)
        }
        if (s.b("grid")) grid(c, flow, pal, energy)
        filaments(c, t, a, s, flow, energy, kick, md, pal, detail)
        rings(c, t, a, s, flow, cx, cy, energy, kick, md, pal, detail)
        val tunnel = s.i("trails").coerceIn(0, 18)
        for (j in 0 until tunnel) {
            val z = (j.toFloat() / tunnel + flow * .032f) % 1
            val size = 200 + z.pow(1.8f) * 2400
            val angle = sin(flow * .23f) * .44f + (z - .5f) * (if (md == 3) .9f else .45f)
            symbolOutline(
                c,
                s,
                t - j * .035f,
                size,
                cx + sin(flow * .6f + z * 3) * z * 170,
                cy + yy(cos(flow * .44f + z * 2) * z * 330),
                angle,
                1.15f + z * 2.1f,
                (.13f + .25f * (1 - z)) * (.5f + energy * .65f),
            )
        }
        if (s.b("notes")) {
            for (r in 0 until min(2, s.i("noteLayers"))) score(c, t, a, s, flow, pal, r, detail)
        }
        if (s.b("spokes")) {
            for (j in 0 until 60) {
                val angle = j / 60f * tau + flow * .11f
                val v = a.value(t, 8 + (j * 7) % 80)
                val rad = 360 + 80 * sin(angle * 3 + flow * .4f)
                val len = 70 + v * (180 + 220 * energy) + hat * 70
                line(
                    c,
                    cx + cos(angle) * rad,
                    cy + yy(sin(angle) * rad * 1.7f),
                    cx + cos(angle) * (rad + len),
                    cy + yy(sin(angle) * (rad + len) * 1.7f),
                    if (j % 3 == 0) tertiary else secondary,
                    if (j % 5 == 0) 2.6f else 1.3f,
                    .18f + .35f * v,
                )
            }
        }
        if (s.b("bars")) bars(c, t, a, s, pal, energy)
        if (s.s("style") == "Bloom") bloom(c, t, a, s, flow, cx, cy, pal, energy)
        val count = s.i("echoes").coerceIn(0, 10)
        for (j in 0 until count) {
            val ph = flow * (.32f + j * .012f) + j * tau / count
            val sx = 540 + cos(ph) * (440 + 40 * sin(flow * .3f + j))
            val sy = yy(960 + sin(ph * .89f + j * .35f) * (850 + 70 * cos(flow * .3f)))
            val size =
                (130 + 80 * (.5f + .5f * sin(flow * .7f + j))) * (1 + .22f * kick) * s.f("scale")
            stamp(
                c,
                s,
                t - j * .08f,
                size,
                sx,
                sy,
                .4f * sin(flow * .6f + j) + ph * .16f,
                .3f + .3f * energy,
            )
        }
        val angle =
            .12f * sin(flow * .65f) +
                .055f * sin(flow * 1.4f) +
                snare * .018f * sin(frame * 2f) +
                flow * s.f("spin") * .15f
        var size = 690 * (.83f + .18f * bass + .22f * kick * s.f("zoom")) * s.f("scale")
        if (md == 2) size *= .89f
        if (s.s("preset") == "Original crown" && t in 148f..160f) size *= .83f
        for (j in tunnel downTo 1) {
            val q = j.toFloat() / tunnel
            symbolOutline(
                c,
                s,
                t - j * .05f,
                size * (1 + q * .65f),
                cx + sin(flow * .8f - j * .12f) * q * 290,
                cy + yy(cos(flow * .61f - j * .14f) * q * 580),
                angle - j * .025f,
                1.6f + q * 1.8f,
                .16f + .25f * (1 - q),
            )
        }
        if (s.b("noteGlow") && s.f("noteGlowAmount") > 0f)
            noteGlow(c, t, a, cx, cy, size, bass, s.f("noteGlowAmount"), primary)
        if (s.b("colorSplit")) {
            // Snare hits split the symbol into red and cyan copies behind it (RGB split).
            val offset = BeatFx.splitOffset(snare, s.f("colorSplitAmount"))
            if (offset > .5f) {
                val ghost = s.f("opacity") * .6f
                stamp(c, s, t, size, cx + offset, cy, angle, ghost, splitRed)
                stamp(c, s, t, size, cx - offset, cy, angle, ghost, splitCyan)
            }
        }
        stamp(c, s, t, size, cx, cy, angle, s.f("opacity"))
        if (s.b("shockwaves") && s.f("shockwaveAmount") > 0f)
            shockwaves(c, cx, cy, size, s.f("shockwaveAmount"), secondary, tertiary)
        if (s.b("sparkles")) sparkles(c, t, s, cx, cy, size, hat, s.f("sparkleAmount"), tertiary)
        if (kick > .16f && s.i("rings") > 0) {
            val age = -.15f * ln(max(kick, .001f))
            for (j in 0..2) {
                c.save()
                c.translate(cx, cy)
                c.rotate(angle * 180 / PI.toFloat())
                c.scale(1f, 1.28f)
                circle(
                    c,
                    0f,
                    0f,
                    size * .62f + age * 1500 + j * 27,
                    tertiary,
                    2.5f - j * .5f,
                    kick * (.43f - j * .09f),
                )
                c.restore()
            }
        }
        bursts(c, t, a, s, energy, pal, detail)
        if (s.b("notes")) {
            if (s.i("noteLayers") >= 3) score(c, t, a, s, flow, pal, 2, detail)
            detached(c, t, a, s, pal)
        }
        if (snare > .38f && s.f("glitch") > 0) {
            for (j in 0..4) {
                val y = yy(((frame * 19 + j * 359) % 1920).toFloat())
                val x = ((frame * 31 + j * 197) % 1080).toFloat()
                rect(
                    c,
                    x - 300,
                    y,
                    150 + snare * 260,
                    (1 + j % 3).toFloat(),
                    secondary,
                    snare * s.f("glitch"),
                )
                rect(c, x - 70, y + 6, 40 + hat * 110, 2f, white, snare * s.f("glitch") * .6f)
            }
        }
        c.restoreToCount(punchSave)
        if (s.f("grain") > 0) {
            // Hashed positions instead of a new Random every frame; same result in preview
            // and export.
            val base = (frame / 2 + 813 + s.i("seed")) * 977
            repeat((120 * detail).toInt()) {
                val n = base + it * 3
                val x = BeatFx.hash01(n) * 1080
                val y = BeatFx.hash01(n + 1) * h
                val v = 1 + BeatFx.hash01(n + 2) * 2
                rect(c, x, y, v, v, white, (.09f + .13f * high) * s.f("grain") * 2)
            }
        }
        if (s.f("vignette") > 0) {
            ink(Color.BLACK, fill = true)
            if (vignetteShader == null || vignetteKey != s.f("vignette") || vignetteHeight != h) {
                vignetteKey = s.f("vignette")
                vignetteHeight = h
                vignetteShader =
                    RadialGradient(
                        540f,
                        h * .5f,
                        max(700f, h * .68f),
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb((vignetteKey * 200).toInt(), 0, 0, 0),
                        ),
                        null,
                        Shader.TileMode.CLAMP,
                    )
            }
            p.shader = vignetteShader
            c.drawRect(0f, 0f, 1080f, h, p)
            p.shader = null
        }
        drawText(c, s)
        val start = s.f("trimStart")
        val end =
            if (s.f("trimEnd") > start) s.f("trimEnd").coerceAtMost(a.duration) else a.duration
        val fadeIn =
            if (s.f("fadeIn") > 0) ((time - start) / s.f("fadeIn")).coerceIn(0f, 1f) else 1f
        val fadeOut =
            if (s.f("fadeOut") > 0) ((end - time) / s.f("fadeOut")).coerceIn(0f, 1f) else 1f
        val fade = 1 - min(fadeIn, fadeOut)
        if (fade > 0) rect(c, 0f, 0f, 1080f, h, bg, fade * fade * (3 - 2 * fade))
        c.restore()
    }

    /**
     * Draws the background media as the bottom layer, inside the single render pass, so
     * dimming, beat reactions, grain, glow, vignette and fades all apply to it.
     */
    private fun drawBackground(c: Canvas, img: Bitmap, s: Settings, bass: Float, beat: Float) {
        val pulse = s.b("backgroundPulse")
        val fit = s.s("backgroundFit")
        val alpha = BackgroundMath.visibleAlpha(s.f("backgroundDim"), beat, pulse)
        val zoom = if (pulse) BackgroundMath.pulseZoom(bass) else 1f
        if (fit == BackgroundMath.FIT) {
            // Cheap blur for the empty edges: shrink to 48 px, then stretch with filtering.
            blurRect.set(0f, 0f, blur.width.toFloat(), blur.height.toFloat())
            p.reset()
            p.isFilterBitmap = true
            blurCanvas.drawBitmap(img, null, blurRect, p)
            BackgroundMath.place(1, 1, 1080f, h, BackgroundMath.FILL, zoom).let {
                bgRect.set(it[0], it[1], it[2], it[3])
            }
            ink(Color.WHITE, alpha * .7f, fill = true)
            c.drawBitmap(blur, null, bgRect, p)
        }
        BackgroundMath.place(img.width, img.height, 1080f, h, fit, zoom).let {
            bgRect.set(it[0], it[1], it[2], it[3])
        }
        ink(Color.WHITE, alpha, fill = true)
        c.drawBitmap(img, null, bgRect, p)
    }

    private fun symbolPath(type: String) {
        path.reset()
        when (type) {
            "Drippy crown" -> {
                path.moveTo(-.49f, -.34f)
                path.lineTo(-.246f, -.06f)
                path.lineTo(0f, -.515f)
                path.lineTo(.246f, -.06f)
                path.lineTo(.49f, -.34f)
                path.lineTo(.366f, .2f)
                path.quadTo(0f, .01f, -.366f, .2f)
                path.close()
            }
            "Lightning" -> {
                path.moveTo(.08f, -.55f)
                path.lineTo(-.36f, .02f)
                path.lineTo(-.05f, .02f)
                path.lineTo(-.15f, .5f)
                path.lineTo(.37f, -.15f)
                path.lineTo(.06f, -.15f)
                path.close()
            }
            "Star" -> {
                for (i in 0..9) {
                    val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
                    val r = if (i % 2 == 0) .5f else .21f
                    if (i == 0) path.moveTo(cos(a) * r, sin(a) * r)
                    else path.lineTo(cos(a) * r, sin(a) * r)
                }
                path.close()
            }
            "Heart" -> {
                path.moveTo(0f, .44f)
                path.cubicTo(-.8f, -.05f, -.37f, -.65f, 0f, -.27f)
                path.cubicTo(.37f, -.65f, .8f, -.05f, 0f, .44f)
                path.close()
            }
            "Diamond" -> {
                path.moveTo(0f, -.52f)
                path.lineTo(.38f, 0f)
                path.lineTo(0f, .52f)
                path.lineTo(-.38f, 0f)
                path.close()
            }
            "Butterfly" -> {
                path.moveTo(0f, 0f)
                path.cubicTo(-.55f, -.75f, -.7f, .1f, -.17f, .14f)
                path.cubicTo(-.55f, .64f, -.02f, .58f, 0f, 0f)
                path.cubicTo(.02f, .58f, .55f, .64f, .17f, .14f)
                path.cubicTo(.7f, .1f, .55f, -.75f, 0f, 0f)
            }
            "Cross" -> {
                path.moveTo(-.1f, -.5f)
                path.lineTo(.1f, -.5f)
                path.lineTo(.1f, -.16f)
                path.lineTo(.4f, -.16f)
                path.lineTo(.4f, .02f)
                path.lineTo(.1f, .02f)
                path.lineTo(.1f, .5f)
                path.lineTo(-.1f, .5f)
                path.lineTo(-.1f, .02f)
                path.lineTo(-.4f, .02f)
                path.lineTo(-.4f, -.16f)
                path.lineTo(-.1f, -.16f)
                path.close()
            }
            else -> path.addCircle(0f, 0f, .42f, Path.Direction.CW)
        }
    }

    private fun stamp(
        c: Canvas,
        s: Settings,
        t: Float,
        size: Float,
        cx: Float,
        cy: Float,
        angle: Float,
        alpha: Float,
        /** When not 0, draws a flat copy in this color (the snare color split) without glow. */
        ghost: Int = 0,
    ) {
        val type = s.s("symbol")
        if (type == "None") return
        val inkColor = if (ghost != 0) ghost else s.color("symbolColor")
        c.save()
        c.translate(cx, cy)
        c.rotate((angle + .055f * sin(t * .69f) + .026f * sin(t * 1.3f)) * 180 / PI.toFloat())
        c.scale(size * (1 - .08f * sin(t * .43f)), size)
        val img =
            if (type == "Drippy crown") crown else if (type == "Custom artwork") artwork else null
        if (img != null) {
            val side = if (type == "Drippy crown") 1500 / 1100f else 1.1f
            val ratio = img.height.toFloat() / img.width
            val rect = stampRect
            rect.set(-side / 2, -side * ratio / 2, side / 2, side * ratio / 2)
            ink(inkColor, alpha, fill = true)
            if (ghost != 0) {
                p.colorFilter = if (ghost == splitRed) splitRedFilter else splitCyanFilter
            } else if (type == "Drippy crown") {
                val color = s.color("symbolColor")
                if (crownInk != color) {
                    crownInk = color
                    // Preserve the original distressed ink instead of flattening its texture.
                    crownFilter =
                        ColorMatrixColorFilter(
                            floatArrayOf(
                                Color.red(color) / 245f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                Color.green(color) / 245f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                Color.blue(color) / 245f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                            )
                        )
                }
                p.colorFilter = crownFilter
            } else if (s.b("tintArtwork"))
                p.colorFilter =
                    PorterDuffColorFilter(s.color("symbolColor"), PorterDuff.Mode.SRC_IN)
            if (ghost == 0 && s.b("glow")) {
                p.alpha = (alpha * 65).toInt().coerceIn(0, 255)
                c.save()
                c.scale(1.025f, 1.025f)
                c.drawBitmap(img, null, rect, p)
                c.restore()
                p.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            }
            c.drawBitmap(img, null, rect, p)
            p.colorFilter = null
        } else if (type == "Equalizer") {
            for (i in 0..4) {
                val height = .2f + .4f * abs(sin(t * 3 + i))
                ink(inkColor, alpha, fill = true)
                c.drawRoundRect(
                    -.4f + i * .17f,
                    -height / 2,
                    -.30f + i * .17f,
                    height / 2,
                    .025f,
                    .025f,
                    p,
                )
            }
        } else {
            symbolPath(type)
            ink(inkColor, alpha, .025f)
            c.drawPath(path, p)
        }
        c.restore()
    }

    private fun symbolOutline(
        c: Canvas,
        s: Settings,
        t: Float,
        size: Float,
        x: Float,
        y: Float,
        angle: Float,
        width: Float,
        alpha: Float,
    ) {
        if (s.s("symbol") == "None") return
        if (s.s("symbol") == "Custom artwork") {
            stamp(c, s, t, size, x, y, angle, alpha * .22f)
            return
        }
        c.save()
        c.translate(x, y)
        c.rotate((angle + .055f * sin(t * .69f) + .026f * sin(t * 1.3f)) * 180 / PI.toFloat())
        c.scale(size * (1 - .08f * sin(t * .43f)), size)
        symbolPath(s.s("symbol"))
        ink(s.color("symbolColor"), alpha * s.f("opacity"), width / size)
        c.drawPath(path, p)
        c.restore()
    }

    private fun filaments(
        c: Canvas,
        t: Float,
        a: Analysis,
        s: Settings,
        flow: Float,
        energy: Float,
        kick: Float,
        mode: Int,
        pal: IntArray,
        detail: Float,
    ) {
        val count = s.i("filaments")
        val steps = (160 * detail).toInt()
        for (j in 0 until count) {
            val base = -260 + j * (2400f / max(1, count)) + (flow * 60) % 145
            val bend = sin(flow * .37f + j * .31f) * 210
            path.reset()
            for (i in 0..steps) {
                val x = -100 + i / steps.toFloat() * 1280
                val v = a.value(t, 8 + (i * 79 / steps))
                val wave = a.value(t, 88 + (i * 255 / steps))
                var y = base + sin(x * .006f + flow * .9f + j * .23f) * (72 + energy * 115) + bend
                y += wave * (75 + 90 * energy) + v * sin(x * .018f - j * .17f) * (35 + kick * 75)
                val xx = x + if (mode == 1 || mode == 3) sin(y * .004f + flow) * 65 else 0f
                if (i == 0) path.moveTo(xx, yy(y)) else path.lineTo(xx, yy(y))
            }
            ink(
                if (j % 3 == 0) pal[2] else pal[1],
                .16f + .18f * energy,
                if (j % 4 == 0) 2.3f else 1.15f,
            )
            c.drawPath(path, p)
        }
    }

    private fun rings(
        c: Canvas,
        t: Float,
        a: Analysis,
        s: Settings,
        flow: Float,
        cx: Float,
        cy: Float,
        energy: Float,
        kick: Float,
        mode: Int,
        pal: IntArray,
        detail: Float,
    ) {
        val count = s.i("rings")
        val steps = (190 * detail).toInt()
        for (j in 0 until count) {
            val z = (j.toFloat() / count + flow * .058f) % 1
            val radius = 90 + z * z * 1610
            val angle = .28f * sin(flow * .3f) + .3f * mode
            path.reset()
            for (i in 0..steps) {
                val th = i.toFloat() / steps * tau
                val spec = a.value(t, 8 + i * 79 / steps)
                val dist = 1 + .11f * sin(th * 3 + flow * .6f + j * .17f) + .13f * spec
                val x = cos(th + flow * .2f) * radius * dist
                val y = sin(th + flow * .2f) * radius * dist * (1.17f + .18f * sin(flow * .23f))
                val xx = (x * cos(angle) - y * sin(angle)) * (1 + kick * .1f) + cx
                val yyy = (x * sin(angle) + y * cos(angle)) * (1 + kick * .1f) + cy
                if (i == 0) path.moveTo(xx, yyy) else path.lineTo(xx, yyy)
            }
            ink(
                if (j % 3 == 0) pal[2] else pal[1],
                (.12f + .31f * (1 - z)) * (.55f + energy * .6f),
                1.1f + z * 1.8f,
            )
            c.drawPath(path, p)
        }
    }

    private fun grid(c: Canvas, flow: Float, pal: IntArray, e: Float) {
        for (j in -2..22) {
            val x = j * 80f + (flow * 18) % 80
            line(c, x, 0f, x, h, pal[2], 1f, .12f + e * .1f)
        }
        for (j in -2..30) {
            val y = j * 80f + (flow * 24) % 80
            line(c, 0f, y, 1080f, y, pal[2], 1f, .12f + e * .1f)
        }
    }

    private fun bars(c: Canvas, t: Float, a: Analysis, s: Settings, pal: IntArray, e: Float) {
        for (i in 0..47) {
            val v = a.value(t, 8 + i * 79 / 47)
            val x = i * 22.5f
            val bh = 40 + v * h * .4f
            rect(c, x, h - bh, 14f, bh, pal[1 + i % 3], .25f + e * .35f)
            rect(c, x, 0f, 14f, bh * .6f, pal[1 + i % 3], .15f + e * .25f)
        }
    }

    private fun bloom(
        c: Canvas,
        t: Float,
        a: Analysis,
        s: Settings,
        flow: Float,
        cx: Float,
        cy: Float,
        pal: IntArray,
        e: Float,
    ) {
        for (i in 0..11) {
            c.save()
            c.translate(cx, cy)
            c.rotate(i * 30f + flow * 5)
            ink(pal[1 + i % 3], .12f + e * .12f, 2f)
            c.drawOval(RectF(-90f, -620f - e * 250, 90f, 60f), p)
            c.restore()
        }
    }

    private fun bursts(
        c: Canvas,
        t: Float,
        a: Analysis,
        s: Settings,
        e: Float,
        pal: IntArray,
        detail: Float,
    ) {
        val life = 1.15f * s.f("particleLife").coerceAtLeast(.2f)
        var k = a.onsetStart(t - life)
        val count = (24 * s.f("particles") * detail).toInt().coerceIn(0, 80)
        while (k < a.onsets.size && a.onsets[k].time <= t) {
            val on = a.onsets[k]
            val age = t - on.time
            if (on.strength > .3f) {
                val rng = Random((s.i("seed").toLong() * 31 + k * 1831))
                val ox = 80 + rng.nextFloat() * 920
                val oy = yy(110 + rng.nextFloat() * 1700)
                val opacity = (1 - age / life).coerceAtLeast(0f).pow(1.6f) * on.strength * .86f
                repeat(count) { j ->
                    val angle = rng.nextFloat() * tau
                    val speed = .45f + rng.nextFloat() * 1.2f
                    val dist = (40 + age * (380 + e * 380)) * speed
                    val x = ox + cos(angle) * dist
                    val y = oy + sin(angle) * dist
                    val len =
                        (8 + rng.nextFloat() * 26) *
                            (1 - age / life * .55f) *
                            (1 + e) *
                            s.f("particleSize")
                    if (j % 4 != 0)
                        line(
                            c,
                            x,
                            y,
                            x - cos(angle) * len,
                            y - sin(angle) * len,
                            pal[1 + j % 3],
                            (1.3f + j % 3 * .7f) * s.f("particleSize"),
                            opacity,
                        )
                    else
                        circle(
                            c,
                            x,
                            y,
                            (2.4f + (1 - age / life) * 2.4f) * s.f("particleSize"),
                            white,
                            a = opacity * .9f,
                            fill = true,
                        )
                }
            }
            k++
        }
    }

    private data class Ribbon(val x: Float, val y: Float, val angle: Float, val spacing: Float)

    private fun ribbon(u: Float, flow: Float, r: Int): Ribbon {
        val perspective = 1 / (1.04f + .24f * cos(u * 4.2f + flow * .16f + r))
        val x = (u - .5f) * 1870
        val y = sin(u * 5.6f + flow * .48f + r * 1.3f) * (155 + 30 * r)
        val angle = floatArrayOf(-.43f, .42f, -.21f)[r] + .32f * sin(flow * .19f + r)
        val xx = (x * cos(angle) - y * sin(angle)) * perspective + 540 + sin(flow * .29f + r) * 90
        val yyy =
            (x * sin(angle) + y * cos(angle)) * perspective +
                floatArrayOf(0f, 650f, 1260f)[r] +
                180 +
                cos(flow * .27f + r) * 230
        return Ribbon(
            xx,
            yy(yyy),
            angle + .18f * sin(u * 5 + flow * .6f + r),
            (23 + 4 * r) * perspective * (1 + .12f * sin(u * 4 + flow * .3f)),
        )
    }

    private fun diatonic(midi: Int) =
        midi / 12 * 7 + intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)[((midi % 12) + 12) % 12]

    private fun note(
        c: Canvas,
        r: Ribbon,
        midi: Int,
        duration: Float,
        color: Int,
        alpha: Float,
        pulse: Float,
        size: Float,
        ledger: Boolean = true,
    ) {
        val ss = r.spacing * size
        val step = diatonic(midi) - diatonic(50)
        c.save()
        c.translate(r.x, r.y)
        c.rotate(r.angle * 180 / PI.toFloat())
        if (ledger) {
            if (step > 4) {
                for (v in 6..step step 2) line(
                    c,
                    -ss * .65f,
                    -v * ss * .5f,
                    ss * .65f,
                    -v * ss * .5f,
                    color,
                    1.5f,
                    alpha * .85f,
                )
            } else if (step < -4) {
                for (v in -6 downTo step step 2) line(
                    c,
                    -ss * .65f,
                    -v * ss * .5f,
                    ss * .65f,
                    -v * ss * .5f,
                    color,
                    1.5f,
                    alpha * .85f,
                )
            }
        }
        c.translate(0f, -step * ss * .5f)
        if (pulse > .1f) {
            ink(color, pulse * .28f, 2f)
            c.drawOval(RectF(-ss * 1.2f, -ss * .8f, ss * 1.2f, ss * .8f), p)
        }
        c.save()
        c.rotate(-13f)
        ink(color, alpha, max(2f, ss * .13f), duration <= .64f)
        c.drawOval(RectF(-ss * .43f, -ss * .29f, ss * .43f, ss * .29f), p)
        c.restore()
        val direction = if (step >= 0) 1 else -1
        val stemx = if (direction == 1) -ss * .36f else ss * .36f
        val end = direction * ss * 2.95f
        line(c, stemx, 0f, stemx, end, color, max(1.5f, ss * .085f), alpha)
        if (duration < .34f) {
            repeat(if (duration < .18f) 2 else 1) { j ->
                val y = end - direction * j * ss * .56f
                path.reset()
                path.moveTo(stemx, y)
                path.cubicTo(
                    stemx + ss * .96f,
                    y + direction * ss * .28f,
                    stemx + ss * .96f,
                    y + direction * ss * .95f,
                    stemx + ss * .42f,
                    y + direction * ss * 1.32f,
                )
                ink(color, alpha, max(2f, ss * .17f))
                c.drawPath(path, p)
            }
        }
        if (midi % 12 in intArrayOf(1, 3, 6, 8, 10)) {
            val ax = -ss * 1.15f
            for (dx in floatArrayOf(-.15f, .15f)) line(
                c,
                ax + dx * ss,
                -ss * .7f,
                ax + dx * ss,
                ss * .65f,
                color,
                max(1.4f, ss * .06f),
                alpha,
            )
            for (dy in floatArrayOf(-.23f, .23f)) line(
                c,
                ax - ss * .37f,
                dy * ss + ss * .09f,
                ax + ss * .37f,
                dy * ss - ss * .09f,
                color,
                max(2f, ss * .11f),
                alpha,
            )
        }
        c.restore()
    }

    private fun clef(c: Canvas, r: Ribbon, col: Int, alpha: Float) {
        val ss = r.spacing
        c.save()
        c.translate(r.x, r.y)
        c.rotate(r.angle * 180 / PI.toFloat())
        circle(c, -ss * .1f, -ss, ss * .19f, col, a = alpha, fill = true)
        path.reset()
        path.moveTo(-ss * .1f, -ss)
        path.cubicTo(ss * .15f, -ss * 1.8f, ss * 1.25f, -ss * 1.5f, ss * 1.1f, -ss * .55f)
        path.cubicTo(ss * .95f, ss * .3f, ss * .35f, ss * .85f, -ss * .23f, ss * 1.1f)
        ink(col, alpha, ss * .14f)
        c.drawPath(path, p)
        for (dy in floatArrayOf(-1.5f, -.5f)) circle(
            c,
            ss * 1.5f,
            dy * ss,
            ss * .14f,
            col,
            a = alpha,
            fill = true,
        )
        c.restore()
    }

    private fun score(
        c: Canvas,
        t: Float,
        a: Analysis,
        s: Settings,
        flow: Float,
        pal: IntArray,
        r: Int,
        detail: Float,
    ) {
        val color = intArrayOf(white, pal[2], pal[3])[r]
        val alpha = floatArrayOf(.52f, .69f, .84f)[r]
        val speed = floatArrayOf(.145f, .17f, .19f)[r] * s.f("noteSpeed").coerceAtLeast(.2f)
        val musicTime = t + floatArrayOf(-.7f, .35f, 0f)[r]
        val steps = (180 * detail).toInt()
        for (row in -2..2) {
            path.reset()
            for (i in 0..steps) {
                val rr = ribbon(-.3f + i / steps.toFloat() * 1.6f, flow, r)
                val x = rr.x - sin(rr.angle) * row * rr.spacing
                val y = rr.y + cos(rr.angle) * row * rr.spacing
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            ink(color, alpha * .71f, if (r == 0) 1.2f else 1.65f)
            c.drawPath(path, p)
        }
        val bar = 60f / (if (s.f("tempoOverride") >= 30) s.f("tempoOverride") else a.bpm) * 4
        val lo = floor((musicTime - 6) / bar).toInt()
        val hi = ceil((musicTime + 6) / bar).toInt()
        for (k in lo..hi) {
            val u = .5f + (k * bar - musicTime) * speed
            if (u in -.25f..1.25f) {
                val rr = ribbon(u, flow, r)
                line(
                    c,
                    rr.x + sin(rr.angle) * rr.spacing * 2,
                    rr.y - cos(rr.angle) * rr.spacing * 2,
                    rr.x - sin(rr.angle) * rr.spacing * 2,
                    rr.y + cos(rr.angle) * rr.spacing * 2,
                    color,
                    1.6f,
                    alpha * .8f,
                )
            }
        }
        val u = ((.11f - flow * .018f + r * .23f) % 1.25f + 1.25f) % 1.25f - .08f
        clef(c, ribbon(u, flow, r), color, alpha)
        var k = a.noteStart(musicTime - 6)
        while (k < a.notes.size && a.notes[k].time < musicTime + 6) {
            val n = a.notes[k]
            val uu = .5f + (n.time - musicTime) * speed
            if (uu in -.25f..1.25f)
                note(
                    c,
                    ribbon(uu, flow, r),
                    n.midi,
                    n.duration,
                    color,
                    alpha,
                    exp(-abs(t - n.time) / .12f),
                    s.f("noteSize"),
                )
            k++
        }
    }

    private fun detached(c: Canvas, t: Float, a: Analysis, s: Settings, pal: IntArray) {
        var k = a.noteStart(t - 1.6f)
        while (k < a.notes.size && a.notes[k].time <= t) {
            if (k % 3 == 0) {
                val n = a.notes[k]
                val age = t - n.time
                val angle = k * 2.39996f
                val x = 540 + cos(angle) * (120 + age * (260 + n.velocity * 260))
                val y = yy(960 + sin(angle) * (190 + age * (430 + n.velocity * 250)))
                note(
                    c,
                    Ribbon(x, y, angle + age * 1.4f, 17 + age * 10),
                    n.midi,
                    n.duration,
                    pal[1 + k % 3],
                    (1 - age / 1.6f) * .66f,
                    0f,
                    s.f("noteSize"),
                    false,
                )
            }
            k++
        }
    }

    private fun drawText(c: Canvas, s: Settings) {
        if (s.s("title").isEmpty() && s.s("subtitle").isEmpty()) return
        val y =
            when (s.s("textPosition")) {
                "Top" -> yy(160f)
                "Center" -> h * .5f
                else -> h - yy(160f)
            }
        ink(s.color("textColor"), fill = true)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textSize = 55 * s.f("textSize")
        c.drawText(s.s("title").take(48), 540f, y, p)
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.textSize = 25 * s.f("textSize")
        c.drawText(s.s("subtitle").take(80), 540f, y + 50 * s.f("textSize"), p)
    }
}
