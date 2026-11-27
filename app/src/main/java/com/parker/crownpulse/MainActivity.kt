package com.parker.crownpulse

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import org.json.JSONObject

class MainActivity : Activity() {
    private val bg = Color.rgb(16, 16, 21)
    private val card = Color.rgb(27, 27, 35)
    private val muted = Color.rgb(157, 158, 175)
    private val acid = Color.rgb(221, 247, 93)
    private val worker = Executors.newSingleThreadExecutor()
    /** Opens/closes background media off the UI thread, independent of analysis/export. */
    private val mediaWorker = Executors.newSingleThreadExecutor()
    /** Runs the AI background sharpening off the UI thread. */
    private val aiWorker = Executors.newSingleThreadExecutor()
    /** Sharpened copy of the background photo (null = none, or nothing to gain). */
    private var aiPhoto: Bitmap? = null
    /** The background photo that [aiPhoto] was made (or is being made) from. */
    private var aiPhotoFor: Bitmap? = null
    /** Chip that made [aiPhoto]: "TPU", "GPU" or "CPU". */
    private var aiPhotoChip: String? = null
    private var mediaSignature = ""
    private var showBackgroundOptions = false
    private var showBeatOptions = false
    private val main = Handler(Looper.getMainLooper())
    private val cancel = AtomicBoolean(false)
    private lateinit var settings: Settings
    private lateinit var renderer: VisualRenderer
    private lateinit var root: LinearLayout
    private lateinit var preview: Preview
    private lateinit var previewFrame: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var play: Button
    private lateinit var seek: SeekBar
    private lateinit var clock: TextView
    private lateinit var trackLabel: TextView
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var busyRow: LinearLayout
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change -> if (change <= 0) pause() }, main)
            .build()
    }
    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                pause()
            }
        }

    private fun previewHeight() =
        (resources.configuration.screenHeightDp * .34f).toInt().coerceIn(120, 300)

    private var player: MediaPlayer? = null
    private var prepared = false
    private var track: Track? = null
    private var position = 0f
    private var page = "Studio"
    private var busy = false
    private var fullscreen = false
    private var scrubbing = false
    private var lastUri: Uri? = null
    private var pendingExport: Uri? = null
    private var pendingExportPicker = false
    private var pendingPack = false
    /** The last Release Pack's saved videos, kept across restarts for the results list. */
    private var lastPack: List<PackResult> = emptyList()
    private var lastPackFolder = ""
    private var closing = false
    private var savedPlayback = false
    private val prefs by lazy { getSharedPreferences("crownpulse", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        // First launch has no stored session and starts a new look; a stored session is
        // read as a look so one from before 1.4 keeps its appearance.
        settings =
            try {
                prefs.getString("settings", null)?.let { Settings(JSONObject(it)) } ?: Settings()
            } catch (_: Exception) {
                Settings()
            }
        renderer = VisualRenderer(this)
        restorePack()
        restoreImages()
        val exportJob = ExportSession.job
        // Leave cache alone while an export is still writing it. Otherwise drop leftover
        // AI frame JPEGs, export scratch, timing probes and stale imports.
        if (exportJob == null) AppCache.clean(cacheDir)
        buildUi()
        refreshBackground()
        if (exportJob != null) restoreTrack(exportJob.track) else loadDemo()
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED,
            )
        else registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    private fun shape(color: Int, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(
        value: String,
        size: Float = 14f,
        color: Int = Color.WHITE,
        bold: Boolean = false,
    ) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(if (primary) bg else Color.WHITE)
            background = shape(if (primary) acid else card, 12)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { action() }
        }

    private fun row(parent: LinearLayout = panel): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

    private fun addButtonRow(vararg buttons: Button) {
        val r = row()
        buttons.forEachIndexed { i, b ->
            r.addView(
                b,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) leftMargin = dp(8) },
            )
        }
    }

    private fun section(title: String, description: String? = null) {
        panel.addView(
            text(title.uppercase(), 11f, acid, true),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
            },
        )
        if (description != null)
            panel.addView(
                text(description, 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
            )
    }

    private fun changed() {
        prefs.edit().putString("settings", settings.json.toString()).apply()
        refreshBackground()
        preview.invalidate()
    }

    /**
     * Reopens the preview background when its kind, file, clip-end rule or photo changes.
     * Missing media (deleted file, revoked access) falls back to the plain color with a note.
     */
    private fun refreshBackground() {
        val type = settings.s("backgroundType")
        if (type == BackgroundMath.PHOTO && settings.b("aiEnhance")) enhancePhoto()
        val signature =
            when (type) {
                BackgroundMath.PHOTO ->
                    "$type|${System.identityHashCode(backgroundPhoto(settings))}"
                BackgroundMath.GIF,
                BackgroundMath.VIDEO ->
                    "$type|${settings.s("backgroundUri")}|${settings.s("backgroundEnd")}"
                else -> type
            }
        if (signature == mediaSignature) return
        mediaSignature = signature
        val old = renderer.background
        renderer.background = null
        val snapshot = settings.copy()
        val photo = backgroundPhoto(snapshot)
        mediaWorker.submit {
            old?.release()
            val opened =
                try {
                    BackgroundMedia.open(
                        this,
                        snapshot,
                        photo,
                        BackgroundMedia.PREVIEW_SHORT_SIDE,
                    )
                } catch (e: Exception) {
                    runOnUiThread {
                        if (mediaSignature == signature)
                            toast(
                                "Your background ${type.lowercase()} can't be opened (it may have been moved or deleted). Showing the background color instead."
                            )
                    }
                    null
                }
            val live =
                if (opened is VideoBackground)
                    LiveVideoBackground(opened) { preview.post { preview.invalidate() } }
                else opened
            runOnUiThread {
                if (mediaSignature == signature && !closing) {
                    renderer.background = live
                    preview.invalidate()
                } else
                    try {
                        mediaWorker.submit { live?.release() }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        live?.release()
                    }
            }
        }
    }

    /** The photo to show behind everything: the AI-sharpened copy when it is on and ready. */
    private fun backgroundPhoto(s: Settings): Bitmap? {
        val photo = renderer.backdrop
        return if (s.b("aiEnhance") && photo != null && aiPhotoFor === photo) aiPhoto ?: photo
        else photo
    }

    /**
     * Sharpens and cleans up the background photo once (at import, or when the switch is
     * turned on) and keeps the result in filesDir, so it is never redone. Any failure leaves
     * the original photo, with a log line.
     */
    private fun enhancePhoto() {
        val source = renderer.backdrop ?: return
        if (aiPhotoFor === source) return
        aiPhotoFor = source
        aiPhoto = null
        aiPhotoChip = null
        val app = applicationContext
        aiWorker.submit {
            var result: Bitmap? = null
            var chip: String? = null
            try {
                val ai = AiUpscaler.get(app)
                if (ai != null) {
                    result = ai.enhance(source, AI_PHOTO_SHORT_SIDE)
                    chip = ai.chip
                    val image = result
                    if (image != null)
                        File(filesDir, AI_PHOTO_FILE).outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    // "none" means the photo was already sharp enough: don't try again.
                    File(filesDir, AI_PHOTO_INFO).writeText(if (image != null) ai.chip else "none")
                }
            } catch (e: Throwable) {
                android.util.Log.w("SoundWaive", "AI photo sharpening skipped", e)
                result = null
            }
            val done = result
            val usedChip = chip
            runOnUiThread {
                if (!closing && renderer.backdrop === source && aiPhotoFor === source) {
                    aiPhoto = done
                    aiPhotoChip = usedChip
                    if (done != null) {
                        refreshBackground()
                        preview.invalidate()
                    }
                }
            }
        }
    }

    private fun forgetAiPhoto() {
        aiPhoto = null
        aiPhotoFor = null
        aiPhotoChip = null
        File(filesDir, AI_PHOTO_FILE).delete()
        File(filesDir, AI_PHOTO_INFO).delete()
    }

    private fun pickBackground(type: String) {
        if (busy) return
        when (type) {
            BackgroundMath.PHOTO -> importImage(true)
            BackgroundMath.GIF ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/gif"),
                    107,
                )
            BackgroundMath.VIDEO ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("video/*"),
                    108,
                )
        }
    }

    private fun buildUi() {
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bg)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(16), sys.top + dp(8), dp(16), sys.bottom + dp(8))
                insets
            }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        header.addView(
            text("SOUND / WAIVE", 19f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(text("MOTION STUDIO", 9f, acid, true))
        val help = button("?", false) { help() }
        header.addView(
            help,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(10) },
        )
        previewFrame =
            FrameLayout(this).apply {
                background = shape(Color.BLACK, 16)
                clipToOutline = true
            }
        preview = Preview()
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(previewHeight())))
        val expand = button("⛶", false) { toggleFullscreen() }
        expand.contentDescription = "Full-screen preview"
        previewFrame.addView(
            expand,
            FrameLayout.LayoutParams(dp(40), dp(36), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            },
        )
        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(transport, LinearLayout.LayoutParams(-1, dp(56)))
        play = button("▶", true) { togglePlay() }
        play.contentDescription = "Play or pause preview"
        transport.addView(play, LinearLayout.LayoutParams(dp(48), dp(40)))
        seek =
            SeekBar(this).apply {
                max = 10000
                progressTintList = ColorStateList.valueOf(acid)
                thumbTintList = ColorStateList.valueOf(acid)
                contentDescription = "Music position"
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(bar: SeekBar) {
                            scrubbing = true
                        }

                        override fun onStopTrackingTouch(bar: SeekBar) {
                            scrubbing = false
                            if (prepared)
                                player?.seekTo((position * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
                            preview.invalidate()
                        }

                        override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                            if (user) {
                                position = value / 10000f * (track?.analysis?.duration ?: 1f)
                                updateClock()
                                preview.invalidate()
                            }
                        }
                    }
                )
            }
        transport.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        clock = text("0:00 / 0:00", 11f, muted)
        transport.addView(clock)
        busyRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        status = text("", 12f, acid)
        busyRow.addView(status)
        progressBar =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(acid)
            }
        busyRow.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        busyRow.addView(
            button("Cancel", false) {
                if (ExportSession.isRunning()) ExportSession.cancel.set(true)
                else cancel.set(true)
            },
            LinearLayout.LayoutParams(-1, dp(36)),
        )
        root.addView(busyRow, LinearLayout.LayoutParams(-1, -2))
        tabs = LinearLayout(this)
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(44)))
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
            }
        panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(18))
            }
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        showPage("Studio")
        main.post(tick)
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (closing) return
                if (prepared && player?.isPlaying == true && !scrubbing) {
                    position = (player?.currentPosition ?: 0) / 1000f
                    seek.progress = (position / (track?.analysis?.duration ?: 1f) * 10000).toInt()
                    updateClock()
                    preview.invalidate()
                }
                main.postDelayed(this, if (settings.s("quality") == "Battery saver") 42L else 33L)
            }
        }

    private fun updateClock() {
        clock.text = "${formatTime(position)} / ${formatTime(track?.analysis?.duration?:0f)}"
    }

    private fun togglePlay() {
        if (!prepared || busy) return
        val p = player ?: return
        if (p.isPlaying) {
            pause()
        } else {
            val end = track?.analysis?.duration ?: 0f
            if (position >= end - .1f) {
                p.seekTo(0)
                position = 0f
            }
            if (
                audioManager.requestAudioFocus(focusRequest) !=
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) {
                toast("Audio is busy. Try Play again in a moment.")
                return
            }
            p.start()
            play.text = "Ⅱ"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun pause() {
        if (prepared && player?.isPlaying == true) player?.pause()
        if (::play.isInitialized) play.text = "▶"
        if (!busy) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setTrack(t: Track) {
        val old = track
        track = t
        position = 0f
        prepared = false
        player?.release()
        player =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MainActivity, t.uri)
                setOnPreparedListener {
                    prepared = true
                    updateClock()
                    preview.invalidate()
                }
                setOnCompletionListener {
                    play.text = "▶"
                    position = t.analysis.duration
                    updateClock()
                }
                setOnErrorListener { _, _, _ ->
                    toast("Audio playback failed. Try importing the file again.")
                    prepared = false
                    true
                }
                prepareAsync()
            }
        seek.progress = 0
        settings.set("trimStart", 0)
        settings.set("trimEnd", 0)
        settings.set("syncOffset", 0)
        changed()
        if (old?.pcm != null && old.pcm.parentFile != t.pcm?.parentFile)
            old.pcm.parentFile?.deleteRecursively()
        showPage(page)
    }

    private fun loadDemo() {
        if (busy) return
        startBusy("Loading demo beat")
        worker.submit {
            try {
                val t = AudioEngine(this).demo()
                runOnUiThread {
                    endBusy()
                    setTrack(t)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun importMusic() {
        if (busy) return
        val i =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("audio/*", "application/ogg", "application/octet-stream"),
                )
        startActivityForResult(i, 101)
    }

    private fun importImage(background: Boolean) {
        if (busy) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"),
            if (background) 103 else 102,
        )
    }

    private fun importTrack(uri: Uri) {
        pause()
        startBusy("Opening music")
        val name =
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported music"
        worker.submit {
            try {
                val t =
                    AudioEngine(this).decode(uri, name, cancel) { label, value ->
                        progress(label, value)
                    }
                runOnUiThread {
                    endBusy()
                    setTrack(t)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun startBusy(label: String) {
        busy = true
        cancel.set(false)
        status.text = label
        progressBar.progress = 0
        busyRow.visibility = View.VISIBLE
        play.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun progress(label: String, value: Float) {
        main.post {
            if (!closing) {
                status.text = label
                progressBar.progress = (value * 1000).toInt()
            }
        }
    }

    private fun endBusy() {
        busy = false
        busyRow.visibility = View.GONE
        play.isEnabled = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun fail(e: Exception) {
        android.util.Log.e("SoundWaive", "Operation failed", e)
        runOnUiThread {
            if (!closing) {
                endBusy()
                if (e !is InterruptedException)
                    AlertDialog.Builder(this)
                        .setTitle("Couldn’t finish")
                        .setMessage(e.message ?: "Please try again.")
                        .setPositiveButton("OK", null)
                        .show()
            }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun showPage(name: String) {
        val resetScroll = page != name
        page = name
        tabs.removeAllViews()
        for (tab in listOf("Studio", "Look", "Motion", "Export")) {
            val b = button(tab, tab == name) { showPage(tab) }
            tabs.addView(
                b,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (tab != "Studio") leftMargin = dp(6)
                },
            )
        }
        panel.removeAllViews()
        when (name) {
            "Studio" -> studio()
            "Look" -> look()
            "Motion" -> motion()
            else -> exportPage()
        }
        if (resetScroll)
            (panel.parent as? ScrollView)?.post { (panel.parent as? ScrollView)?.scrollTo(0, 0) }
    }

    private fun select(
        label: String,
        key: String,
        values: List<String>,
        after: (() -> Unit)? = null,
    ) {
        val holder =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(card, 12)
                setPadding(dp(12), dp(9), dp(5), dp(4))
            }
        panel.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        holder.addView(text(label, 11f, muted))
        val options = if (settings.s(key) in values) values else listOf(settings.s(key)) + values
        val spinner =
            Spinner(this).apply {
                setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(card))
                contentDescription = label
            }
        val adapter =
            object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
                override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                    return (super.getView(position, view, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        setPadding(0, dp(4), dp(4), dp(4))
                        minHeight = dp(36)
                    }
                }

                override fun getDropDownView(position: Int, view: View?, parent: ViewGroup): View {
                    return (super.getDropDownView(position, view, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        setPadding(dp(16), dp(12), dp(12), dp(12))
                        setBackgroundColor(card)
                        minHeight = dp(48)
                    }
                }
            }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(options.indexOf(settings.s(key)).coerceAtLeast(0))
        holder.addView(spinner, LinearLayout.LayoutParams(-1, dp(40)))
        var first = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>?) {}

                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) {
                        first = false
                        return
                    }
                    if (settings.s(key) != options[pos]) {
                        settings.set(key, options[pos])
                        changed()
                        after?.invoke()
                    }
                }
            }
    }

    private fun slider(
        label: String,
        key: String,
        min: Float,
        max: Float,
        integer: Boolean = false,
        suffix: String = "",
    ) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val caption = text("", 13f)
        fun update(value: Float) {
            caption.text =
                label +
                    "  ·  " +
                    (if (integer) value.roundToInt().toString() else "%.2f".format(value)) +
                    suffix
        }
        update(settings.f(key))
        r.addView(caption)
        val bar =
            SeekBar(this).apply {
                this.max = 1000
                progress =
                    (((settings.f(key) - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
                progressTintList = ColorStateList.valueOf(acid)
                thumbTintList = ColorStateList.valueOf(acid)
                contentDescription = label
                setPadding(dp(8), 0, dp(8), 0)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(b: SeekBar) {}

                        override fun onStopTrackingTouch(b: SeekBar) {
                            changed()
                        }

                        override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) {
                            if (user) {
                                val value = min + (max - min) * v / 1000f
                                settings.set(key, if (integer) value.roundToInt() else value)
                                update(settings.f(key))
                                preview.invalidate()
                            }
                        }
                    }
                )
            }
        r.addView(bar, LinearLayout.LayoutParams(-1, dp(38)))
    }

    private fun toggle(label: String, key: String) {
        val sw =
            Switch(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = settings.b(key)
                setPadding(dp(2), dp(9), dp(6), dp(9))
                minHeight = dp(48)
                thumbTintList =
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(acid, muted),
                    )
                setOnCheckedChangeListener { _, yes ->
                    settings.set(key, yes)
                    changed()
                }
            }
        panel.addView(sw, LinearLayout.LayoutParams(-1, -2))
    }

    private fun color(label: String, key: String) {
        val r = row()
        r.addView(text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        val b =
            button(settings.s(key), false) {
                val names = Settings.COLORS.map { it.first } + "Custom hex…"
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(names.toTypedArray()) { _, which ->
                        if (which < Settings.COLORS.size) {
                            settings.set(key, Settings.COLORS[which].second)
                            changed()
                            showPage(page)
                        } else {
                            val input =
                                EditText(this).apply {
                                    setText(settings.s(key))
                                    setSingleLine()
                                }
                            val d =
                                AlertDialog.Builder(this)
                                    .setTitle("Hex color, such as #B36BEE")
                                    .setView(input)
                                    .setPositiveButton("Apply", null)
                                    .setNegativeButton("Cancel", null)
                                    .create()
                            d.setOnShowListener {
                                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                    try {
                                        Color.parseColor(input.text.toString())
                                        settings.set(key, input.text.toString())
                                        changed()
                                        showPage(page)
                                        d.dismiss()
                                    } catch (_: Exception) {
                                        input.error = "Use a valid #RRGGBB color"
                                    }
                                }
                            }
                            d.show()
                        }
                    }
                    .show()
            }
        b.setTextColor(settings.color(key))
        r.addView(b, LinearLayout.LayoutParams(dp(130), dp(42)))
    }

    private fun input(label: String, key: String, numeric: Boolean = false) {
        val title = text(label, 12f, muted)
        panel.addView(title)
        val edit =
            EditText(this).apply {
                setText(settings.s(key))
                setTextColor(Color.WHITE)
                textSize = 15f
                setSingleLine()
                backgroundTintList = ColorStateList.valueOf(muted)
                inputType =
                    if (numeric)
                        android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else android.text.InputType.TYPE_CLASS_TEXT
                contentDescription = label
                setOnFocusChangeListener { _, has ->
                    if (!has) {
                        if (numeric) settings.set(key, text.toString().toDoubleOrNull() ?: 0.0)
                        else settings.set(key, text.toString())
                        changed()
                    }
                }
            }
        panel.addView(edit, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
    }

    private fun studio() {
        section("Your music")
        trackLabel = text(track?.name ?: "Loading…", 16f, Color.WHITE, true)
        trackLabel.maxLines = 2
        panel.addView(trackLabel)
        val a = track?.analysis
        panel.addView(
            text(
                if (a == null) "Offline music visualizer"
                else
                    "${formatTime(a.duration)}  ·  ${a.bpm.roundToInt()} BPM estimate  ·  ${a.notes.size} bass notes",
                12f,
                muted,
            ),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(12)
            },
        )
        addButtonRow(
            button("Import music", true) { importMusic() },
            button("Demo beat") { loadDemo() },
        )
        section("Start with a look")
        select("Preset", "preset", Settings.PRESETS) {
            val name = settings.s("preset")
            settings.preset(name)
            changed()
            showPage(page)
        }
        addButtonRow(
            button("✦ New variation", true) {
                settings.vary()
                changed()
                showPage(page)
            },
            button("Save look") { saveLook() },
        )
        addButtonRow(
            button("Load saved look") { loadLook() },
            button("Reset controls") {
                AlertDialog.Builder(this)
                    .setTitle("Reset to the original look?")
                    .setPositiveButton("Reset") { _, _ ->
                        settings = Settings()
                        changed()
                        showPage(page)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )
        toggle("Keep my symbol when changing looks", "lockSymbol")
        toggle("Keep my symbol color", "lockColor")
        section("Music timing")
        input("Tempo override · 0 = detected BPM", "tempoOverride", true)
        select("React to", "reaction", listOf("Full mix", "Bass", "Snare", "Highs", "Energy"))
        slider("Beat sensitivity", "sensitivity", .2f, 2.5f)
        slider("Bass sensitivity", "bassGain", 0f, 2.5f)
        slider("Visual sync offset", "syncOffset", -500f, 500f, true, " ms")
        select("Preview quality", "quality", listOf("Battery saver", "Balanced", "High"))
        section("Beat effects", "Locked to the beats and bars found in your song.")
        toggle("Rings on every beat", "shockwaves")
        toggle("Punch in on the first beat of each bar", "downbeatPunch")
        toggle("Color split on snares", "colorSplit")
        toggle("Glow that changes color with the notes", "noteGlow")
        toggle("Sparkles on hi-hats", "sparkles")
        toggle("Ripple on hits", "hitRipple")
        toggle("Rainbow edge on bright sounds", "brightFringe")
        toggle("Flash on drops", "dropFlash")
        toggle("Light rays on bass", "lightRays")
        toggle("Build-up before drops", "buildUp")
        toggle("Tilt on beats", "beatTilt")
        toggle("Bounce on beats", "beatBounce")
        toggle("Dots that orbit on the beat", "beatOrbit")
        toggle("Shape that moves with the sound", "soundBlob")
        toggle("Star warp", "starWarp")
        toggle("Sound wave line across the screen", "waveLine")
        toggle("Sound wave ring around the symbol", "waveRing")
        addButtonRow(
            button(if (showBeatOptions) "Fewer options" else "More options") {
                showBeatOptions = !showBeatOptions
                showPage(page)
            }
        )
        if (showBeatOptions) {
            slider("Ring strength", "shockwaveAmount", 0f, 2f)
            slider("Punch strength", "punchAmount", 0f, 2f)
            slider("Color split strength", "colorSplitAmount", 0f, 2f)
            slider("Glow strength", "noteGlowAmount", 0f, 2f)
            slider("Sparkle amount", "sparkleAmount", 0f, 2f)
            slider("Ripple strength", "hitRippleAmount", 0f, 2f)
            slider("Rainbow edge strength", "brightFringeAmount", 0f, 2f)
            slider("Drop flash strength", "dropFlashAmount", 0f, 2f)
            slider("Light ray strength", "lightRaysAmount", 0f, 2f)
            slider("Build-up strength", "buildUpAmount", 0f, 2f)
            slider("Tilt strength", "beatTiltAmount", 0f, 2f)
            slider("Bounce strength", "beatBounceAmount", 0f, 2f)
            slider("Orbit dot size", "beatOrbitAmount", 0f, 2f)
            slider("Sound shape strength", "soundBlobAmount", 0f, 2f)
            slider("Star warp strength", "starWarpAmount", 0f, 2f)
            slider("Sound wave line height", "waveLineAmount", 0f, 2f)
            slider("Sound wave ring height", "waveRingAmount", 0f, 2f)
        }
        panel.addView(
            text(
                "The demo beat is generated for SoundWaive. Imported tracks are analyzed locally by the Rust engine. Bass notes are an estimate for animation.",
                12f,
                muted,
            )
        )
    }

    private fun look() {
        section("Symbol")
        select("Choose a symbol", "symbol", Settings.SYMBOLS)
        addButtonRow(button("Import artwork", true) { importImage(false) })
        color("Symbol color", "symbolColor")
        toggle("Tint imported artwork", "tintArtwork")
        slider("Symbol size", "scale", .2f, 1.65f)
        slider("Symbol opacity", "opacity", .1f, 1f)
        slider("Horizontal position", "positionX", -1f, 1f)
        slider("Vertical position", "positionY", -1f, 1f)
        toggle("Mirror composition", "mirror")
        section("Colors & atmosphere")
        select("Palette", "palette", Settings.PALETTES)
        color("Background color", "background")
        color("Custom accent 1", "accent1")
        color("Custom accent 2", "accent2")
        color("Custom accent 3", "accent3")
        section("Background", "Put a color, photo, GIF or video behind everything.")
        select("Background", "backgroundType", BackgroundMath.TYPES) {
            val type = settings.s("backgroundType")
            val missing =
                when (type) {
                    BackgroundMath.PHOTO -> renderer.backdrop == null
                    BackgroundMath.GIF,
                    BackgroundMath.VIDEO ->
                        settings.s("backgroundUri").isEmpty() ||
                            !settings.s("backgroundUriType").equals(type)
                    else -> false
                }
            if (missing) pickBackground(type)
            showPage(page)
        }
        val type = settings.s("backgroundType")
        if (type != BackgroundMath.COLOR) {
            addButtonRow(button("Choose a different ${type.lowercase()}") { pickBackground(type) })
            slider("Dim", "backgroundDim", 0f, 1f)
            toggle("Pulse with the beat", "backgroundPulse")
            if (type == BackgroundMath.PHOTO || type == BackgroundMath.VIDEO) {
                if (AiUpscaler.supportsModel())
                    toggle("Sharpen & clean up background (AI)", "aiEnhance")
                else
                    panel.addView(text("AI sharpening requires SSE4.1 on this Intel device.", 13f, muted))
            }
            addButtonRow(
                button(if (showBackgroundOptions) "Fewer options" else "More options") {
                    showBackgroundOptions = !showBackgroundOptions
                    showPage(page)
                }
            )
            if (showBackgroundOptions) {
                select("Fit", "backgroundFit", BackgroundMath.FITS)
                if (type != BackgroundMath.PHOTO)
                    select("When the clip ends", "backgroundEnd", BackgroundMath.ENDS)
            }
        }
        section("Texture")
        slider("Film grain", "grain", 0f, 1f)
        slider("Vignette", "vignette", 0f, 1f)
        toggle("Soft symbol glow", "glow")
        section("Optional lettering", "Leave both fields empty for a visual with no text.")
        input("Title", "title")
        input("Subtitle", "subtitle")
        select("Text position", "textPosition", listOf("Top", "Center", "Bottom"))
        slider("Text size", "textSize", .5f, 1.8f)
        color("Text color", "textColor")
    }

    private fun motion() {
        section("Movement")
        slider("Animation speed", "speed", .15f, 2.5f)
        slider("Drift through the frame", "drift", 0f, 2f)
        slider("Symbol rotation", "spin", -1.5f, 1.5f)
        slider("Bass zoom", "zoom", 0f, 2f)
        slider("Beat shake", "shake", 0f, 1f)
        slider("Response smoothing", "smooth", 0f, 1f)
        slider("Bass response", "bassGain", 0f, 2.5f)
        slider("Mid response", "midGain", 0f, 2.5f)
        slider("High response", "highGain", 0f, 2.5f)
        section("Layers")
        slider("Symbol echoes", "echoes", 0f, 10f, true)
        slider("Crown trails / tunnel", "trails", 0f, 18f, true)
        slider("Reactive rings", "rings", 0f, 30f, true)
        slider("Flying filaments", "filaments", 0f, 36f, true)
        toggle("Spectral spokes", "spokes")
        toggle("Spectrum bars", "bars")
        toggle("Moving grid", "grid")
        slider("Glitch strips", "glitch", 0f, 1f)
        section("Particles")
        slider("Particle density", "particles", 0f, 2.5f)
        slider("Particle size", "particleSize", .3f, 2.5f)
        slider("Particle lifetime", "particleLife", .3f, 2.5f)
        section(
            "Flying sheet music",
            "Bass pitches and note attacks drive the notation. This is an expressive score, not a verified transcription.",
        )
        toggle("Flying staves and notes", "notes")
        slider("Staff layers", "noteLayers", 1f, 3f, true)
        slider("Note size", "noteSize", .5f, 1.8f)
        slider("Note scroll speed", "noteSpeed", .3f, 2f)
        section("Variation over time")
        toggle("Change scenes with the music", "sceneChanges")
        slider("Bars per scene", "sceneBars", 4f, 32f, true)
        input("Variation seed", "seed", true)
        addButtonRow(
            button("New variation", true) {
                settings.vary()
                changed()
                showPage(page)
            }
        )
    }

    private fun exportPage() {
        section("Video format")
        select("Frame shape", "aspect", listOf("9:16", "1:1", "16:9", "4:5"))
        select("Resolution", "resolution", listOf("720p", "1080p"))
        select("Frames per second", "fps", listOf("24", "30", "60"))
        select("Video quality", "bitrate", listOf("4 Mbps", "8 Mbps", "12 Mbps", "20 Mbps"))
        section("Range & fades")
        input("Start time in seconds", "trimStart", true)
        input("End time in seconds · 0 means full track", "trimEnd", true)
        slider("Fade in", "fadeIn", 0f, 5f, false, " s")
        slider("Fade out", "fadeOut", 0f, 8f, false, " s")
        val duration =
            ((if (settings.f("trimEnd") > settings.f("trimStart")) settings.f("trimEnd")
                else track?.analysis?.duration ?: 0f) - settings.f("trimStart"))
                .coerceAtLeast(0f)
        val mb =
            duration * ((settings.s("bitrate").substringBefore(' ').toIntOrNull() ?: 12) + .32f) / 8
        section(
            "Ready to render",
            "MP4 video with the full selected audio range. Estimated size: ${mb.toInt()} MB. You can leave the app; a notification shows the percentage.",
        )
        addButtonRow(button("Export video", true) { requestExport() })
        if (lastUri != null)
            addButtonRow(
                button("Play last export") { openExport() },
                button("Share video") { shareExport() },
            )
        section(
            "Release Pack",
            "One tap makes 7 videos from this song with this look: the full song for YouTube (16:9), three different 15 s vertical promos, a 30 s reel (9:16), a square promo and a silent 8 s looping Spotify Canvas (9:16). Clips start on a bar line at the song's strongest parts, with short fades. Saved to Movies/SoundWaive/<song>. The start and end times above don't apply. You can leave the app while it renders.",
        )
        select("YouTube resolution", "packYouTube", Settings.PACK_YOUTUBE)
        select("Square promo length", "packSquare", Settings.PACK_SQUARE)
        addButtonRow(button("Render Release Pack", true) { requestExport(pack = true) })
        if (lastPack.isNotEmpty()) {
            section("Last Release Pack", "Movies/SoundWaive/$lastPackFolder")
            for (result in lastPack) packRow(result)
            addButtonRow(button("Share all ${lastPack.size}") { shareVideos(lastPack.map { it.uri }) })
        }
        section("Your setup")
        addButtonRow(
            button("Save look") { saveLook() },
            button("Export settings") {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "SoundWaive-look.json"),
                    105,
                )
            },
        )
        addButtonRow(
            button("Import settings") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json"),
                    106,
                )
            }
        )
        val cacheBytes = AppCache.size(cacheDir)
        section(
            "Storage",
            "Temporary files (imported audio, export scratch and AI video frames) are using " +
                "${AppCache.format(cacheBytes)}. Clearing cache does not remove saved looks, " +
                "artwork, backgrounds or the demo. The song you have open is kept so you don’t " +
                "have to import it again.",
        )
        val clear =
            button("Clear cache") {
                if (ExportSession.isRunning()) {
                    toast("Can’t clear cache while an export is running.")
                    return@button
                }
                if (busy) return@button
                val keep = track?.pcm?.parentFile
                startBusy("Clearing cache")
                worker.submit {
                    val before = AppCache.size(cacheDir)
                    AppCache.clean(cacheDir, keep)
                    val freed = (before - AppCache.size(cacheDir)).coerceAtLeast(0L)
                    runOnUiThread {
                        if (closing) return@runOnUiThread
                        endBusy()
                        toast(
                            if (freed == 0L) "Nothing extra to clear."
                            else "Cleared ${AppCache.format(freed)}."
                        )
                        if (page == "Export") showPage(page)
                    }
                }
            }
        clear.isEnabled = !ExportSession.isRunning()
        addButtonRow(clear)
    }

    /** A single export ([pack] false, asks where to save) or a Release Pack (saves to Movies). */
    private fun requestExport(pack: Boolean = false) {
        currentFocus?.clearFocus()
        if (busy || track == null) return
        if (ExportSession.isRunning()) {
            toast("An export is already running.")
            return
        }
        val t = track!!
        if (!pack) {
            if (settings.f("trimStart") < 0 || settings.f("trimStart") >= t.analysis.duration) {
                toast("Start time must be inside the song.")
                return
            }
            if (settings.f("trimEnd") > 0 && settings.f("trimEnd") <= settings.f("trimStart")) {
                toast("End time must be after the start time.")
                return
            }
        }
        // Android 8/9 need permission to save Release Packs in public Movies.
        // Single-video export uses the document picker and needs no storage permission.
        if (pack && Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 110)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportPicker = !pack
            pendingPack = pack
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 109)
            return
        }
        if (pack) startJob(null, pack = true) else openExportPicker()
    }

    private fun openExportPicker() {
        pause()
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE, "SoundWaive-${System.currentTimeMillis()}.mp4"),
            104,
        )
    }

    private fun renderExport(uri: Uri) {
        if (track == null || ExportSession.isRunning()) return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {}
        startJob(uri, pack = false)
    }

    private fun startJob(uri: Uri?, pack: Boolean) {
        val original = track ?: return
        if (ExportSession.isRunning()) return
        val snapshot = settings.copy()
        pause()
        val job =
            ExportJob(
                output = uri,
                track = original,
                settings = snapshot,
                artwork = renderer.artwork,
                backdrop = renderer.backdrop,
                photo = backgroundPhoto(snapshot),
                aiPhoto = aiPhoto,
                aiPhotoChip = aiPhotoChip,
                pack = pack,
            )
        if (!ExportSession.begin(job)) {
            toast("An export is already running.")
            return
        }
        startBusy("Preparing export")
        try {
            startForegroundService(
                Intent(this, ExportService::class.java).setAction(ExportService.ACTION_START)
            )
        } catch (e: Exception) {
            android.util.Log.e("SoundWaive", "Could not start the export service", e)
            ExportSession.fail(e.message ?: "Couldn’t start the export.")
            showExportStatus(ExportSession.status)
        }
    }

    private val exportListener: (ExportStatus) -> Unit = { state ->
        main.post { showExportStatus(state) }
    }

    private fun showExportStatus(state: ExportStatus) {
        if (closing) return
        ExportSession.job?.track?.let { ready ->
            if (track?.pcm == null && ready.pcm != null) track = ready
        }
        when (state) {
            is ExportStatus.Idle -> {}
            is ExportStatus.Running -> {
                if (!busy) startBusy(state.label)
                this.status.text = state.label
                progressBar.progress = (state.progress * 1000).toInt()
            }
            is ExportStatus.Finished,
            is ExportStatus.PackFinished,
            is ExportStatus.Failed,
            is ExportStatus.Canceled -> {
                val result = ExportSession.consume() ?: return
                when (result) {
                    is ExportStatus.Finished -> {
                        lastUri = result.uri
                        endBusy()
                        showPage("Export")
                        AlertDialog.Builder(this)
                            .setTitle("Your video is ready")
                            .setMessage(
                                "${result.width} × ${result.height} · ${formatTime(result.duration)} · full audio" +
                                    (result.aiLine?.let { "\n\n$it" } ?: "") +
                                    (result.notice?.let { "\n\n$it" } ?: "")
                            )
                            .setPositiveButton("Play") { _, _ -> openExport() }
                            .setNeutralButton("Share") { _, _ -> shareExport() }
                            .setNegativeButton("Done", null)
                            .show()
                    }
                    is ExportStatus.Failed -> {
                        endBusy()
                        if (page == "Export") showPage("Export")
                        AlertDialog.Builder(this)
                            .setTitle("Couldn’t finish")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is ExportStatus.PackFinished -> {
                        endBusy()
                        showPackResult(result)
                    }
                    is ExportStatus.Canceled -> {
                        endBusy()
                        if (page == "Export") showPage("Export")
                    }
                    else -> {}
                }
            }
        }
    }

    /** Rebuild the player for a track already in memory, without resetting export trim times. */
    private fun restoreTrack(t: Track) {
        track = t
        position = 0f
        prepared = false
        player?.release()
        player =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MainActivity, t.uri)
                setOnPreparedListener {
                    prepared = true
                    updateClock()
                    if (::preview.isInitialized) preview.invalidate()
                }
                setOnCompletionListener {
                    play.text = "▶"
                    position = t.analysis.duration
                    updateClock()
                }
                setOnErrorListener { _, _, _ ->
                    toast("Audio playback failed. Try importing the file again.")
                    prepared = false
                    true
                }
                prepareAsync()
            }
        if (::seek.isInitialized) seek.progress = 0
        if (::preview.isInitialized) preview.invalidate()
        if (::clock.isInitialized) updateClock()
    }

    private fun openExport() {
        lastUri?.let { openVideo(it) }
    }

    private fun shareExport() {
        lastUri?.let { shareVideos(listOf(it)) }
    }

    private fun openVideo(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("Use your Files app to open the saved MP4.")
        }
    }

    /** Share sheet for one video or several (Release Pack "Share all"). */
    private fun shareVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val send =
            if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = "video/mp4"
        send.clipData =
            android.content.ClipData.newRawUri("", uris[0]).apply {
                for (uri in uris.drop(1)) addItem(android.content.ClipData.Item(uri))
            }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(
                Intent.createChooser(send, if (uris.size == 1) "Share your video" else "Share your videos")
            )
        } catch (_: Exception) {
            toast("No app on this phone can share videos.")
        }
    }

    /** One line in the Release Pack results: what it is, then Play and Share. */
    private fun packRow(result: PackResult) {
        val line = row()
        val details =
            "${result.width} × ${result.height} · ${"%.1f".format(java.util.Locale.US, result.duration)} s" +
                (if (result.silent) " · loop, no audio" else "") +
                (if (result.label.startsWith("YouTube")) "" else " · from ${formatTime(result.start)}")
        val label =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(result.label, 14f, Color.WHITE, true))
                addView(
                    text(details, 12f, muted),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) },
                )
            }
        line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(
            button("Play") { openVideo(result.uri) },
            LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) },
        )
        line.addView(
            button("Share") { shareVideos(listOf(result.uri)) },
            LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) },
        )
    }

    private fun showPackResult(result: ExportStatus.PackFinished) {
        if (result.results.isNotEmpty()) {
            lastPack = result.results
            lastPackFolder = result.folder
            savePack()
        }
        showPage("Export")
        if (result.canceled && result.results.isEmpty() && result.problems.isEmpty()) return
        val lines = StringBuilder()
        lines.append(
            "${result.results.size} of 7 videos saved to Movies/SoundWaive/${result.folder}."
        )
        for (r in result.results) lines.append("\n• ${r.label} · ${r.width} × ${r.height}")
        if (result.problems.isNotEmpty()) {
            lines.append("\n\nNot finished:")
            for (p in result.problems) lines.append("\n• $p")
        }
        for (n in result.notes) lines.append("\n\n$n")
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    when {
                        result.canceled -> "Release Pack stopped"
                        result.problems.isEmpty() -> "Your Release Pack is ready"
                        else -> "Release Pack finished with problems"
                    }
                )
                .setMessage(lines.toString())
                .setNegativeButton("Done", null)
        if (result.results.isNotEmpty())
            dialog.setPositiveButton("Share all") { _, _ ->
                shareVideos(result.results.map { it.uri })
            }
        dialog.show()
    }

    private fun savePack() {
        val list = org.json.JSONArray()
        for (r in lastPack)
            list.put(
                JSONObject()
                    .put("label", r.label)
                    .put("fileName", r.fileName)
                    .put("uri", r.uri.toString())
                    .put("width", r.width)
                    .put("height", r.height)
                    .put("duration", r.duration.toDouble())
                    .put("start", r.start.toDouble())
                    .put("silent", r.silent)
            )
        prefs
            .edit()
            .putString("lastPack", list.toString())
            .putString("lastPackFolder", lastPackFolder)
            .apply()
    }

    private fun restorePack() {
        try {
            val list = org.json.JSONArray(prefs.getString("lastPack", null) ?: return)
            lastPack =
                (0 until list.length()).map {
                    val o = list.getJSONObject(it)
                    PackResult(
                        label = o.getString("label"),
                        fileName = o.optString("fileName"),
                        uri = Uri.parse(o.getString("uri")),
                        width = o.getInt("width"),
                        height = o.getInt("height"),
                        duration = o.getDouble("duration").toFloat(),
                        start = o.optDouble("start", 0.0).toFloat(),
                        silent = o.optBoolean("silent"),
                    )
                }
            lastPackFolder = prefs.getString("lastPackFolder", "") ?: ""
        } catch (_: Exception) {
            lastPack = emptyList()
        }
    }

    private fun saveLook() {
        currentFocus?.clearFocus()
        val input =
            EditText(this).apply {
                setText(settings.s("preset"))
                setSingleLine()
            }
        AlertDialog.Builder(this)
            .setTitle("Save this look")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(60).ifEmpty { "My look" }
                val saved = JSONObject(prefs.getString("looks", "{}")!!)
                saved.put(name, settings.json)
                prefs.edit().putString("looks", saved.toString()).apply()
                toast("Saved $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadLook() {
        val saved = JSONObject(prefs.getString("looks", "{}")!!)
        val names = saved.keys().asSequence().toList().sorted()
        if (names.isEmpty()) {
            toast("Save a look first, then you can reuse it here.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Your saved looks")
            .setItems(names.toTypedArray()) { _, i ->
                settings = Settings(saved.getJSONObject(names[i]))
                changed()
                showPage(page)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreImages() {
        for (name in listOf("artwork", "background")) {
            val f = File(filesDir, "$name.png")
            if (f.exists()) {
                val bitmap = BitmapFactory.decodeFile(f.absolutePath)
                if (name == "artwork") renderer.artwork = bitmap else renderer.backdrop = bitmap
            }
        }
        // The sharpened background photo from an earlier run, if any.
        val photo = renderer.backdrop
        val info = File(filesDir, AI_PHOTO_INFO)
        if (photo != null && info.isFile) {
            val chip = info.readText().trim()
            val sharp =
                if (chip == "none") null
                else BitmapFactory.decodeFile(File(filesDir, AI_PHOTO_FILE).absolutePath)
            if (chip == "none" || sharp != null) {
                aiPhotoFor = photo
                aiPhoto = sharp
                aiPhotoChip = if (sharp != null) chip else null
            }
        }
    }

    private fun readImage(uri: Uri, background: Boolean) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            require(bounds.outWidth > 0) { "Choose a readable PNG, JPEG or WebP image." }
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > 2048) opts
                .inSampleSize *= 2
            val image =
                contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: throw IllegalArgumentException("Image could not be opened")
            val file = File(filesDir, if (background) "background.png" else "artwork.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (background) {
                forgetAiPhoto()
                renderer.backdrop = image
                settings.set("backgroundType", BackgroundMath.PHOTO)
            } else {
                renderer.artwork = image
                settings.set("symbol", "Custom artwork")
            }
            changed()
            showPage(page)
        } catch (e: Exception) {
            toast(e.message ?: "Image could not be imported")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 110) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestExport(pack = true)
            else toast("Storage permission is needed to save the Release Pack to Movies on this Android version.")
            return
        }
        if (requestCode == 109 && (pendingExportPicker || pendingPack)) {
            val pack = pendingPack
            pendingExportPicker = false
            pendingPack = false
            if (busy || track == null) return
            if (pack) startJob(null, pack = true) else openExportPicker()
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        when (request) {
            101 -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                importTrack(uri)
            }
            102 -> readImage(uri, false)
            103 -> readImage(uri, true)
            107,
            108 -> {
                try {
                    // Keep access across restarts so saved looks can reopen the file.
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                val type = if (request == 107) BackgroundMath.GIF else BackgroundMath.VIDEO
                settings.set("backgroundType", type)
                settings.set("backgroundUri", uri.toString())
                settings.set("backgroundUriType", type)
                changed()
                showPage(page)
            }
            104 -> renderExport(uri)
            105 -> {
                currentFocus?.clearFocus()
                contentResolver.openOutputStream(uri)?.use {
                    it.write(settings.json.toString(2).toByteArray())
                }
                toast("Settings saved")
            }
            106 -> {
                try {
                    val txt =
                        contentResolver.openInputStream(uri)!!.bufferedReader().use {
                            it.readText()
                        }
                    require(txt.length < 100000)
                    settings = Settings(JSONObject(txt))
                    changed()
                    showPage(page)
                } catch (_: Exception) {
                    toast("That file is not a valid SoundWaive settings file.")
                }
            }
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child != previewFrame)
                child.visibility =
                    if (fullscreen) View.GONE
                    else if (child == busyRow && !busy) View.GONE else View.VISIBLE
        }
        previewFrame.layoutParams =
            LinearLayout.LayoutParams(
                -1,
                if (fullscreen) 0 else dp(previewHeight()),
                if (fullscreen) 1f else 0f,
            )
        root.setPadding(
            if (fullscreen) 0 else dp(16),
            if (fullscreen) 0 else dp(8),
            if (fullscreen) 0 else dp(16),
            if (fullscreen) 0 else dp(8),
        )
        preview.invalidate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) {
            toggleFullscreen()
            return
        }
        if (busy) {
            if (ExportSession.isRunning()) {
                AlertDialog.Builder(this)
                    .setTitle("Export is still running")
                    .setMessage(
                        "You can leave SoundWaive. The export keeps going, and a notification shows the percentage."
                    )
                    .setPositiveButton("Leave") { _, _ -> moveTaskToBack(true) }
                    .setNegativeButton("Cancel export") { _, _ ->
                        ExportSession.cancel.set(true)
                    }
                    .setNeutralButton("Stay", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Work is still running")
                    .setMessage(
                        "Keep SoundWaive open until it finishes, or cancel this operation."
                    )
                    .setPositiveButton("Keep working", null)
                    .setNegativeButton("Cancel operation") { _, _ -> cancel.set(true) }
                    .show()
            }
            return
        }
        super.onBackPressed()
    }

    private fun help() {
        AlertDialog.Builder(this)
            .setTitle("Make every track its own world")
            .setMessage(
                "1. Import music, or try the original demo.\n2. Press Play and choose a look.\n3. Change symbols, colors, layers and motion.\n4. Save your look, then export an MP4.\n\nNew variation changes the arrangement while respecting your symbol and color locks. Custom symbols work best as transparent PNGs.\n\nEverything runs on your phone. Bass notation is estimated from the mix. Exports use the same renderer as the preview. Keep the app open during analysis. Exports keep running in the background, with a notification that shows the percentage.\n\nExport › Release Pack makes seven videos in one go (YouTube 16:9, three 15 s vertical promos, a 30 s reel, a square promo and a silent 8 s Spotify Canvas loop) and saves them to Movies/SoundWaive.\n\nSupported: Android 8+, mono/stereo music, up to 30 minutes. Storage for imported audio and exports is separate from the app size. Export › Storage shows temporary cache and lets you clear it; saved looks and photos stay."
            )
            .setPositiveButton("Let’s make something", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!fullscreen) {
            previewFrame.layoutParams = LinearLayout.LayoutParams(-1, dp(previewHeight()))
            preview.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        ExportSession.listen(exportListener)
    }

    override fun onPause() {
        super.onPause()
        ExportSession.stopListening(exportListener)
        pause()
        audioManager.abandonAudioFocusRequest(focusRequest)
        currentFocus?.clearFocus()
        changed()
    }

    override fun onDestroy() {
        closing = true
        ExportSession.stopListening(exportListener)
        if (!ExportSession.isRunning()) cancel.set(true)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        audioManager.abandonAudioFocusRequest(focusRequest)
        main.removeCallbacks(tick)
        player?.release()
        worker.shutdown()
        renderer.background.let { old ->
            renderer.background = null
            mediaWorker.submit { old?.release() }
        }
        mediaWorker.shutdown()
        aiWorker.shutdown()
        super.onDestroy()
    }

    inner class Preview : View(this@MainActivity) {
        init {
            contentDescription = "Beat reactive visual preview"
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = track ?: return
            val aspect =
                when (settings.s("aspect")) {
                    "1:1" -> 1f
                    "16:9" -> 16f / 9
                    "4:5" -> 4f / 5
                    else -> 9f / 16
                }
            val w = min(width.toFloat(), height * aspect).toInt()
            val h = (w / aspect).toInt()
            canvas.save()
            canvas.translate((width - w) / 2f, (height - h) / 2f)
            // Show a representative opening image while stopped at zero.
            // Playback and export always retain the exact music clock and opening fade.
            val displayTime =
                if (position == 0f && player?.isPlaying != true) min(1f, t.analysis.duration / 2)
                else position
            renderer.draw(canvas, w, h, displayTime, t.analysis, settings)
            canvas.restore()
        }
    }

    private companion object {
        /** The AI sharpens background photos for frames up to 1080 pixels on the short side. */
        const val AI_PHOTO_SHORT_SIDE = 1080
        const val AI_PHOTO_FILE = "background-ai.png"
        const val AI_PHOTO_INFO = "background-ai.txt"
    }
}
