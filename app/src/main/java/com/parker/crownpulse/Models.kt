package com.parker.crownpulse

import android.graphics.Color
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.json.JSONObject

object NativeEngine {
    init {
        System.loadLibrary("crownpulse_engine")
    }

    external fun analyze(input: String, output: String): String
}

data class Note(val time: Float, val midi: Int, val duration: Float, val velocity: Float)

data class Onset(val time: Float, val strength: Float)

data class Beat(val time: Float, val strength: Float, val downbeat: Boolean)

class Analysis(
    val duration: Float,
    val fps: Float,
    val bpm: Float,
    val frames: Int,
    val bands: Int,
    val waves: Int,
    val data: FloatArray,
    val notes: List<Note>,
    val onsets: List<Onset>,
    /** Tracked beats from the optional SWBEAT01 section; empty for older analysis files. */
    val beats: List<Beat> = emptyList(),
) {
    val stride = 8 + bands + waves
    val flow = FloatArray(frames)
    /** Beat-grid times and flags as primitive arrays so per-frame lookups never allocate. */
    val beatTimes = FloatArray(beats.size) { beats[it].time }
    val beatStrengths = FloatArray(beats.size) { beats[it].strength }
    val beatIsDownbeat = BooleanArray(beats.size) { beats[it].downbeat }
    val downbeatTimes = beats.filter { it.downbeat }.map { it.time }.toFloatArray()
    /** Notes sorted by start time as primitive arrays, for the note color glow. */
    private val notesByTime = notes.sortedBy { it.time }
    val noteTimes = FloatArray(notes.size) { notesByTime[it].time }
    val noteMidi = IntArray(notes.size) { notesByTime[it].midi }
    val noteVelocity = FloatArray(notes.size) { notesByTime[it].velocity }
    /** Hits (salient onsets) sorted by time as primitive arrays, for the ripple on hits. */
    private val onsetsByTime = onsets.sortedBy { it.time }
    val onsetTimes = FloatArray(onsets.size) { onsetsByTime[it].time }
    val onsetStrengths = FloatArray(onsets.size) { onsetsByTime[it].strength }
    /**
     * Drops (sudden jumps to a loud part), found once from loudness and bass, as primitive
     * arrays for the flash on drops. See [BeatFx.findDrops].
     */
    private val dropPairs =
        BeatFx.findDrops(
            FloatArray(frames) { .6f * data[it * stride + 3] + .4f * data[it * stride] },
            fps,
            beatTimes,
        )
    val dropTimes = FloatArray(dropPairs.size / 2) { dropPairs[2 * it] }
    val dropStrengths = FloatArray(dropPairs.size / 2) { dropPairs[2 * it + 1] }

    init {
        var sum = 0f
        for (i in 0 until frames) {
            sum += (.60f + 1.15f * data[i * stride + 4] + .30f * data[i * stride]) / fps
            flow[i] = sum
        }
    }

    fun index(t: Float) = ((t * fps).toInt()).coerceIn(0, frames - 1)

    fun value(t: Float, k: Int): Float {
        val p = (t * fps).coerceIn(0f, (frames - 1).toFloat())
        val a = p.toInt()
        val b = min(a + 1, frames - 1)
        return data[a * stride + k] + (data[b * stride + k] - data[a * stride + k]) * (p - a)
    }

    fun noteStart(t: Float): Int {
        var l = 0
        var r = notes.size
        while (l < r) {
            val m = (l + r) / 2
            if (notes[m].time < t) l = m + 1 else r = m
        }
        return l
    }

    /** Index of the bar (counted in downbeats) containing [t], or null without a beat grid. */
    fun barIndex(t: Float): Int? {
        if (beats.none { it.downbeat }) return null
        var n = 0
        for (b in beats) {
            if (b.time > t) break
            if (b.downbeat) n++
        }
        return n
    }

    fun onsetStart(t: Float): Int {
        var l = 0
        var r = onsets.size
        while (l < r) {
            val m = (l + r) / 2
            if (onsets[m].time < t) l = m + 1 else r = m
        }
        return l
    }

    companion object {
        fun load(file: File): Analysis {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            buf.get(magic)
            require(String(magic) == "CPULSE01") { "Invalid music analysis" }
            val duration = buf.float
            val fps = buf.float
            val bpm = buf.float
            val frames = buf.float.toInt()
            val bands = buf.float.toInt()
            val waves = buf.float.toInt()
            require(frames in 1..200000 && bands == 80 && waves == 256)
            val data = FloatArray(frames * (8 + bands + waves))
            buf.asFloatBuffer().get(data)
            buf.position(buf.position() + data.size * 4)
            val notes = ArrayList<Note>()
            repeat(buf.int) { notes.add(Note(buf.float, buf.float.toInt(), buf.float, buf.float)) }
            val onsets = ArrayList<Onset>()
            repeat(buf.int) { onsets.add(Onset(buf.float, buf.float)) }
            return Analysis(duration, fps, bpm, frames, bands, waves, data, notes, onsets, beats(buf))
        }

        /** Reads the optional trailing beat grid; anything malformed is ignored. */
        private fun beats(buf: ByteBuffer): List<Beat> {
            if (buf.remaining() < 12) return emptyList()
            val magic = ByteArray(8)
            buf.get(magic)
            if (String(magic, Charsets.US_ASCII) != "SWBEAT01") return emptyList()
            val count = buf.int
            if (count < 0 || count > 200000 || buf.remaining() < count * 12L) return emptyList()
            return List(count) { Beat(buf.float, buf.float, buf.float > .5f) }
        }
    }
}

/**
 * One look. `Settings()` is a brand-new look in the current format; `Settings(json)` reads a
 * stored look (session, saved look or imported file) and upgrades it from its `lookVersion`.
 */
class Settings(var json: JSONObject = newLook()) {
    init {
        sanitize()
    }

    private fun sanitize() {
        // Looks stored before 1.4 have no lookVersion and were made without beat effects.
        // Keep their appearance: a missing effect switch there means off. New looks and
        // presets are written with the current version, so for them a missing switch still
        // falls back to DEFAULTS (on).
        // The same rule for the 1.5 glow effects, the 1.6 ripple and rainbow edge, the 1.7
        // drop flash and light rays, the 1.8 build-up and beat tilt, the 1.9 bounce and
        // orbit dots and the 1.10 sound blob and star warp: looks stored before them keep
        // them off.
        val stored = json.optInt(LOOK_VERSION_KEY, 1)
        if (stored < BEAT_EFFECTS_VERSION)
            for (k in BEAT_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < GLOW_EFFECTS_VERSION)
            for (k in GLOW_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < RIPPLE_EFFECTS_VERSION)
            for (k in RIPPLE_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < DROP_EFFECTS_VERSION)
            for (k in DROP_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < BUILD_EFFECTS_VERSION)
            for (k in BUILD_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < BOUNCE_EFFECTS_VERSION)
            for (k in BOUNCE_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < WARP_EFFECTS_VERSION)
            for (k in WARP_EFFECT_SWITCHES) if (!json.has(k)) json.put(k, false)
        if (stored < LOOK_VERSION) json.put(LOOK_VERSION_KEY, LOOK_VERSION)
        // 1.0/1.1 looks stored an on/off photo switch; 1.2 stores the background kind.
        if (json.has("backgroundImage")) {
            if (!json.has("backgroundType"))
                json.put(
                    "backgroundType",
                    if (json.optBoolean("backgroundImage")) BackgroundMath.PHOTO
                    else BackgroundMath.COLOR,
                )
            json.remove("backgroundImage")
        }
        val ranges =
            mapOf(
                "scale" to (.2..1.65),
                "opacity" to (.1..1.0),
                "positionX" to (-1.0..1.0),
                "positionY" to (-1.0..1.0),
                "speed" to (.15..2.5),
                "drift" to (0.0..2.0),
                "spin" to (-1.5..1.5),
                "zoom" to (0.0..2.0),
                "shake" to (0.0..1.0),
                "sensitivity" to (.2..2.5),
                "bassGain" to (0.0..2.5),
                "midGain" to (0.0..2.5),
                "highGain" to (0.0..2.5),
                "smooth" to (0.0..1.0),
                "particles" to (0.0..2.5),
                "particleSize" to (.3..2.5),
                "particleLife" to (.3..2.5),
                "trails" to (0.0..18.0),
                "echoes" to (0.0..10.0),
                "rings" to (0.0..30.0),
                "filaments" to (0.0..36.0),
                "noteLayers" to (1.0..3.0),
                "noteSize" to (.5..1.8),
                "noteSpeed" to (.3..2.0),
                "grain" to (0.0..1.0),
                "vignette" to (0.0..1.0),
                "glitch" to (0.0..1.0),
                "sceneBars" to (4.0..32.0),
                "seed" to (0.0..100000000.0),
                "fadeIn" to (0.0..5.0),
                "fadeOut" to (0.0..8.0),
                "trimStart" to (0.0..1800.0),
                "trimEnd" to (0.0..1800.0),
                "textSize" to (.5..1.8),
                "syncOffset" to (-500.0..500.0),
                "backgroundDim" to (0.0..1.0),
                "tempoOverride" to (0.0..240.0),
                "shockwaveAmount" to (0.0..2.0),
                "punchAmount" to (0.0..2.0),
                "colorSplitAmount" to (0.0..2.0),
                "noteGlowAmount" to (0.0..2.0),
                "sparkleAmount" to (0.0..2.0),
                "hitRippleAmount" to (0.0..2.0),
                "brightFringeAmount" to (0.0..2.0),
                "dropFlashAmount" to (0.0..2.0),
                "lightRaysAmount" to (0.0..2.0),
                "buildUpAmount" to (0.0..2.0),
                "beatTiltAmount" to (0.0..2.0),
                "beatBounceAmount" to (0.0..2.0),
                "beatOrbitAmount" to (0.0..2.0),
                "soundBlobAmount" to (0.0..2.0),
                "starWarpAmount" to (0.0..2.0),
            )
        for ((k, r) in ranges) if (json.has(k)) {
            val v = json.optDouble(k, Double.NaN)
            if (v.isFinite()) json.put(k, v.coerceIn(r.start, r.endInclusive)) else json.remove(k)
        }
        val enums =
            mapOf(
                "symbol" to SYMBOLS,
                "palette" to PALETTES,
                "aspect" to listOf("9:16", "1:1", "16:9", "4:5"),
                "resolution" to listOf("720p", "1080p"),
                "fps" to listOf("24", "30", "60"),
                "bitrate" to listOf("4 Mbps", "8 Mbps", "12 Mbps", "20 Mbps"),
                "reaction" to listOf("Full mix", "Bass", "Snare", "Highs", "Energy"),
                "quality" to listOf("Battery saver", "Balanced", "High"),
                "backgroundType" to BackgroundMath.TYPES,
                "backgroundFit" to BackgroundMath.FITS,
                "backgroundEnd" to BackgroundMath.ENDS,
                "renderQuality" to BackgroundMath.QUALITIES,
            )
        for ((k, allowed) in enums) if (json.has(k) && json.optString(k) !in allowed) json.remove(k)
        for (k in
            listOf("symbolColor", "background", "accent1", "accent2", "accent3", "textColor")) if (
            json.has(k)
        )
            try {
                Color.parseColor(json.getString(k))
            } catch (_: Exception) {
                json.remove(k)
            }
        if (json.has("backgroundUri")) {
            val uri = json.optString("backgroundUri")
            if (uri.length > 2048 || !(uri.isEmpty() || uri.startsWith("content://")))
                json.remove("backgroundUri")
        }
        for (k in listOf("title", "subtitle")) if (json.has(k))
            json.put(k, json.optString(k).take(if (k == "title") 48 else 80))
    }

    fun f(k: String) = json.optDouble(k, (DEFAULTS[k] as? Number)?.toDouble() ?: 0.0).toFloat()

    fun i(k: String) = json.optInt(k, (DEFAULTS[k] as? Number)?.toInt() ?: 0)

    fun b(k: String) = json.optBoolean(k, DEFAULTS[k] as? Boolean ?: false)

    fun s(k: String) = json.optString(k, DEFAULTS[k]?.toString() ?: "")

    fun set(k: String, v: Any) {
        json.put(k, v)
    }

    fun copy() = Settings(JSONObject(json.toString()))

    fun color(k: String): Int =
        try {
            Color.parseColor(s(k))
        } catch (_: Exception) {
            Color.WHITE
        }

    fun preset(name: String) {
        val symbol = s("symbol")
        val ink = s("symbolColor")
        val lockS = b("lockSymbol")
        val lockC = b("lockColor")
        val sessionKeys =
            listOf(
                "aspect",
                "resolution",
                "fps",
                "bitrate",
                "trimStart",
                "trimEnd",
                "fadeIn",
                "fadeOut",
                "quality",
                "syncOffset",
                "tempoOverride",
                "renderQuality",
                // The chosen background media belongs to the user, not to a preset.
                "backgroundType",
                "backgroundUri",
                "backgroundUriType",
                "backgroundFit",
                "backgroundEnd",
                "backgroundPulse",
            )
        val retained = sessionKeys.filter { json.has(it) }.associateWith { json.get(it) }
        json = newLook()
        set("preset", name)
        when (name) {
            "Neon orbit" -> {
                set("palette", "Neon")
                set("style", "Orbit")
                set("glow", true)
                set("rings", 22)
                set("particles", 1.4)
                set("notes", false)
                set("symbol", "Ring")
                set("speed", 1.2)
            }
            "Ink storm" -> {
                set("palette", "Monochrome")
                set("style", "Ink")
                set("grain", .75)
                set("rings", 0)
                set("filaments", 30)
                set("trails", 12)
                set("shake", .35)
                set("background", "#171717")
                // Black and white look: no colored glow or rainbow edge; sparkles, the
                // ripple, the drop flash, the light rays, the build-up, the beat tilt, the
                // bounce, the orbit dots, the sound blob and the star warp stay (they use the
                // look's own black and white colors or no color at all).
                set("noteGlow", false)
                set("sparkles", true)
                set("hitRipple", true)
                set("brightFringe", false)
                set("dropFlash", true)
                set("lightRays", true)
                set("buildUp", true)
                set("beatTilt", true)
                set("beatBounce", true)
                set("beatOrbit", true)
                set("soundBlob", true)
                set("starWarp", true)
            }
            "Cosmic bloom" -> {
                set("palette", "Aurora")
                set("style", "Bloom")
                set("symbol", "Star")
                set("particles", 1.7)
                set("spin", .65)
                set("rings", 24)
                set("filaments", 7)
                set("notes", false)
                set("glow", true)
            }
            "Blueprint" -> {
                set("palette", "Ice")
                set("style", "Grid")
                set("background", "#061629")
                set("symbol", "Diamond")
                set("rings", 5)
                set("filaments", 10)
                set("particles", .3)
                set("grid", true)
                set("grain", 0.0)
            }
            "Minimal pulse" -> {
                set("palette", "Monochrome")
                set("style", "Minimal")
                set("symbol", "Ring")
                set("echoes", 0)
                set("trails", 0)
                set("rings", 2)
                set("filaments", 0)
                set("particles", .2)
                set("notes", false)
                set("spokes", false)
                set("grain", .04)
                set("drift", .2)
                // Beat effects spelled out so the preset never depends on DEFAULTS or on
                // the stored-look upgrade: rings and bar punch on, color split off.
                set("shockwaves", true)
                set("downbeatPunch", true)
                set("colorSplit", false)
                set("noteGlow", false)
                set("sparkles", false)
                set("hitRipple", false)
                set("brightFringe", false)
                set("dropFlash", false)
                set("lightRays", false)
                set("buildUp", false)
                set("beatTilt", false)
                set("beatBounce", false)
                set("beatOrbit", false)
                set("soundBlob", false)
                set("starWarp", false)
            }
            "Spectrum city" -> {
                set("palette", "Sunset")
                set("style", "Spectrum")
                set("symbol", "Lightning")
                set("rings", 4)
                set("filaments", 6)
                set("notes", false)
                set("bars", true)
                set("spokes", false)
                set("echoes", 2)
            }
            "Liquid ribbons" -> {
                set("palette", "Candy")
                set("style", "Ribbon")
                set("filaments", 34)
                set("rings", 0)
                set("trails", 4)
                set("notes", true)
                set("noteLayers", 3)
                set("symbol", "Heart")
                set("speed", .75)
            }
        }
        if (lockS) set("symbol", symbol)
        if (lockC) set("symbolColor", ink)
        set("lockSymbol", lockS)
        set("lockColor", lockC)
        for ((key, value) in retained) set(key, value)
    }

    fun vary() {
        val old = this.copy()
        val next = old.i("seed") + 1
        val r = java.util.Random(next.toLong())
        preset(PRESETS[1 + r.nextInt(PRESETS.size - 1)])
        set("seed", next)
        set("preset", "Custom variation")
        if (!old.b("lockSymbol")) set("symbol", SYMBOLS[r.nextInt(SYMBOLS.size - 1)])
        if (!old.b("lockColor")) set("symbolColor", COLORS[r.nextInt(COLORS.size)].second)
        set("palette", PALETTES[r.nextInt(PALETTES.size)])
        set("speed", .55 + r.nextFloat() * 1.1)
        set("drift", .25 + r.nextFloat() * 1.3)
        set("spin", r.nextFloat() * .8)
        set("particles", .3 + r.nextFloat() * 1.5)
        set("scale", .65 + r.nextFloat() * .5)
        set("echoes", r.nextInt(7))
        set("trails", r.nextInt(13))
        set("mirror", r.nextBoolean())
        set("notes", r.nextBoolean())
        set("rings", r.nextInt(23))
        set("filaments", 5 + r.nextInt(28))
    }

    companion object {
        /**
         * Stored-look format. 1 (or missing): before 1.4. 2: 1.4 beat effects. 3: 1.5 note
         * color glow and hi-hat sparkles. 4: 1.6 ripple on hits and rainbow edge. 5: 1.7
         * flash on drops and light rays on bass. 6: 1.8 build-up before drops and tilt on
         * beats. 7: 1.9 bounce on beats and orbit dots. 8: 1.10 sound blob and star warp.
         */
        const val LOOK_VERSION_KEY = "lookVersion"
        const val BEAT_EFFECTS_VERSION = 2
        const val GLOW_EFFECTS_VERSION = 3
        const val RIPPLE_EFFECTS_VERSION = 4
        const val DROP_EFFECTS_VERSION = 5
        const val BUILD_EFFECTS_VERSION = 6
        const val BOUNCE_EFFECTS_VERSION = 7
        const val WARP_EFFECTS_VERSION = 8
        const val LOOK_VERSION = 8
        val BEAT_EFFECT_SWITCHES = listOf("shockwaves", "downbeatPunch", "colorSplit")
        val GLOW_EFFECT_SWITCHES = listOf("noteGlow", "sparkles")
        val RIPPLE_EFFECT_SWITCHES = listOf("hitRipple", "brightFringe")
        val DROP_EFFECT_SWITCHES = listOf("dropFlash", "lightRays")
        val BUILD_EFFECT_SWITCHES = listOf("buildUp", "beatTilt")
        val BOUNCE_EFFECT_SWITCHES = listOf("beatBounce", "beatOrbit")
        val WARP_EFFECT_SWITCHES = listOf("soundBlob", "starWarp")

        /** Empty JSON for a brand-new look, stamped with the current format version. */
        fun newLook(): JSONObject = JSONObject().put(LOOK_VERSION_KEY, LOOK_VERSION)

        val PRESETS =
            listOf(
                "Original crown",
                "Neon orbit",
                "Ink storm",
                "Cosmic bloom",
                "Blueprint",
                "Minimal pulse",
                "Spectrum city",
                "Liquid ribbons",
            )
        val SYMBOLS =
            listOf(
                "Drippy crown",
                "Lightning",
                "Star",
                "Heart",
                "Diamond",
                "Ring",
                "Butterfly",
                "Cross",
                "Equalizer",
                "None",
                "Custom artwork",
            )
        val PALETTES =
            listOf("Original", "Neon", "Aurora", "Sunset", "Ice", "Candy", "Monochrome", "Custom")
        val COLORS =
            listOf(
                "White" to "#F5F5F5",
                "Lime" to "#DDF75D",
                "Violet" to "#B36BEE",
                "Coral" to "#F04A37",
                "Cyan" to "#48E5F0",
                "Gold" to "#FFC857",
                "Pink" to "#FF75C3",
                "Black" to "#09090D",
            )
        val DEFAULTS =
            mapOf<String, Any>(
                "preset" to "Original crown",
                "style" to "Original",
                "symbol" to "Drippy crown",
                "symbolColor" to "#F5F5F5",
                "palette" to "Original",
                "background" to "#08090C",
                "accent1" to "#F04A37",
                "accent2" to "#B36BEE",
                "accent3" to "#DDF75D",
                "lockSymbol" to true,
                "lockColor" to true,
                "tintArtwork" to true,
                "backgroundType" to BackgroundMath.COLOR,
                "backgroundUri" to "",
                "backgroundFit" to BackgroundMath.FILL,
                "backgroundEnd" to BackgroundMath.LOOP,
                "backgroundPulse" to true,
                "renderQuality" to BackgroundMath.BALANCED,
                "backgroundDim" to .65,
                "scale" to 1.0,
                "opacity" to 1.0,
                "positionX" to 0.0,
                "positionY" to 0.0,
                "mirror" to false,
                "speed" to 1.0,
                "drift" to 1.0,
                "spin" to 0.0,
                "zoom" to 1.0,
                "shake" to .08,
                "sensitivity" to 1.0,
                "bassGain" to 1.0,
                "midGain" to 1.0,
                "highGain" to 1.0,
                "reaction" to "Full mix",
                "smooth" to .1,
                "particles" to 1.0,
                "particleSize" to 1.0,
                "particleLife" to 1.0,
                "trails" to 8,
                "echoes" to 5,
                "rings" to 15,
                "filaments" to 18,
                "notes" to true,
                "noteLayers" to 3,
                "noteSize" to 1.0,
                "noteSpeed" to 1.0,
                "spokes" to true,
                "bars" to false,
                "grid" to false,
                "grain" to .35,
                "glow" to false,
                "vignette" to .3,
                "glitch" to .35,
                "sceneChanges" to true,
                "sceneBars" to 16,
                "seed" to 8139,
                "aspect" to "9:16",
                "resolution" to "1080p",
                "fps" to "30",
                "bitrate" to "12 Mbps",
                "fadeIn" to .4,
                "fadeOut" to 2.3,
                "trimStart" to 0.0,
                "trimEnd" to 0.0,
                "quality" to "Balanced",
                "title" to "",
                "subtitle" to "",
                "textPosition" to "Bottom",
                "textSize" to 1.0,
                "textColor" to "#F5F5F5",
                "syncOffset" to 0.0,
                "tempoOverride" to 0.0,
                // 1.4 beat effects: on for new looks and presets. Looks stored before 1.4
                // (no lookVersion) get the three switches set to off when they load.
                "shockwaves" to true,
                "shockwaveAmount" to 1.0,
                "downbeatPunch" to true,
                "punchAmount" to 1.0,
                "colorSplit" to true,
                "colorSplitAmount" to 1.0,
                // 1.5 glow effects: on for new looks and presets, off for looks stored before.
                "noteGlow" to true,
                "noteGlowAmount" to 1.0,
                "sparkles" to true,
                "sparkleAmount" to 1.0,
                // 1.6 ripple and rainbow edge: on for new looks and presets, off for looks
                // stored before.
                "hitRipple" to true,
                "hitRippleAmount" to 1.0,
                "brightFringe" to true,
                "brightFringeAmount" to 1.0,
                // 1.7 drop flash and light rays: on for new looks and presets, off for looks
                // stored before.
                "dropFlash" to true,
                "dropFlashAmount" to 1.0,
                "lightRays" to true,
                "lightRaysAmount" to 1.0,
                // 1.8 build-up before drops and tilt on beats: on for new looks and presets,
                // off for looks stored before.
                "buildUp" to true,
                "buildUpAmount" to 1.0,
                "beatTilt" to true,
                "beatTiltAmount" to 1.0,
                // 1.9 bounce on beats and orbit dots: on for new looks and presets, off for
                // looks stored before.
                "beatBounce" to true,
                "beatBounceAmount" to 1.0,
                "beatOrbit" to true,
                "beatOrbitAmount" to 1.0,
                // 1.10 sound blob and star warp: on for new looks and presets, off for looks
                // stored before.
                "soundBlob" to true,
                "soundBlobAmount" to 1.0,
                "starWarp" to true,
                "starWarpAmount" to 1.0,
            )
    }
}

fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
