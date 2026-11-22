package com.localcaption.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localcaption.app.asr.LiveCaption
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.data.prefs.sanitized
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating caption card shown above other apps while a caption session runs.
 *
 * The overlay uses a fixed-size, user-resizable window (4 corner drag handles) with an
 * internal [ScrollView] so the window height never jumps as new captions arrive and the
 * user can scroll up/down through recent captions at any time.
 *
 * All public methods are safe to call from any thread; view work is always posted to
 * the main looper.
 */
class FloatingOverlayManager(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit,
    private val onLockChange: (Boolean) -> Unit = {},
) {

    private enum class ResizeCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var cardView: LinearLayout? = null
    private var headerView: LinearLayout? = null
    private var titleView: TextView? = null
    private var audioLevelView: MiniAudioBarsView? = null
    private var lockButton: TextView? = null
    private var pauseButton: TextView? = null
    private var stopButton: TextView? = null
    private var captionsScrollView: ScrollView? = null
    private var captionsContainer: LinearLayout? = null
    private var emptyView: TextView? = null
    private val cornerHandles = mutableListOf<View>()

    private var layoutParams: WindowManager.LayoutParams? = null

    private var settings: AppSettings = AppSettings.DEFAULTS
    private var captions: List<LiveCaption> = emptyList()
    private var renderedIds: Set<Long> = emptySet()

    private var locked = false
    private var paused = false
    private var showing = false

    /** `true` while the card is attached to the window manager. */
    val isShowing: Boolean
        get() = showing

    /** `true` while dragging/resizing is disabled through the lock button. */
    val isLocked: Boolean
        get() = locked

    /**
     * Attaches the overlay.
     *
     * @return `true` when the overlay is (or already was) visible; `false` when the
     * "display over other apps" permission is missing or the window could not be added
     */
    fun show(): Boolean {
        if (showing) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is not granted")
            return false
        }
        if (isMainThread()) {
            return addOverlay()
        }
        mainHandler.post { addOverlay() }
        return true
    }

    /** Detaches the overlay and releases its views. */
    fun hide() {
        if (isMainThread()) {
            removeOverlay()
        } else {
            mainHandler.post { removeOverlay() }
        }
    }

    /** Alias for [hide] used when the owning service is destroyed. */
    fun release() = hide()

    /** Applies new preferences (font size, opacity, timestamps). */
    fun updateSettings(newSettings: AppSettings) {
        val sanitized = newSettings.sanitized()
        onMain {
            if (settings == sanitized) return@onMain
            settings = sanitized
            applyCardBackground()
            renderCaptions()
        }
    }

    /** Updates the rendered captions (up to [MAX_OVERLAY_CAPTIONS] scrollable items). */
    fun updateCaptions(newCaptions: List<LiveCaption>) {
        onMain {
            if (captions == newCaptions) return@onMain
            captions = newCaptions
            renderCaptions()
        }
    }

    /** Updates the pause button icon after the service paused or resumed capture. */
    fun updatePlaybackState(isPaused: Boolean) {
        onMain {
            if (paused == isPaused) return@onMain
            paused = isPaused
            updatePauseButton()
        }
    }

    /** Updates the header title (for example with the current status message). */
    fun updateTitle(title: String) {
        onMain { titleView?.text = title }
    }

    /** Updates the compact 3-bar audio input level meter in the overlay header. */
    fun updateAudioLevel(level: Float) {
        onMain { audioLevelView?.setLevel(level) }
    }

    // region Window management

    private fun addOverlay(): Boolean {
        if (showing) return true
        if (!Settings.canDrawOverlays(context)) return false

        return runCatching {
            val view = rootView ?: buildOverlayView().also { rootView = it }
            val params = layoutParams ?: createLayoutParams().also { layoutParams = it }
            windowManager.addView(view, params)
            showing = true
            renderCaptions(forceScrollToBottom = true)
            true
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to add the caption overlay", throwable)
            clearViewRefs()
            layoutParams = null
            showing = false
        }.getOrDefault(false)
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "Unable to remove the caption overlay", it) }
        clearViewRefs()
        layoutParams = null
        renderedIds = emptySet()
        showing = false
    }

    private fun clearViewRefs() {
        rootView = null
        cardView = null
        headerView = null
        titleView = null
        audioLevelView = null
        lockButton = null
        pauseButton = null
        stopButton = null
        captionsScrollView = null
        captionsContainer = null
        emptyView = null
        cornerHandles.clear()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val (screenWidth, screenHeight) = screenSize()
        val isLandscape = screenWidth > screenHeight
        val widthFraction = if (isLandscape) LANDSCAPE_WIDTH_FRACTION else PORTRAIT_WIDTH_FRACTION
        val maxInitialWidth = if (isLandscape) dp(MAX_LANDSCAPE_INIT_WIDTH_DP) else screenWidth
        val cardWidth = (screenWidth * widthFraction).roundToInt()
            .coerceIn(dp(MIN_CARD_WIDTH_DP), maxInitialWidth.coerceAtLeast(dp(MIN_CARD_WIDTH_DP)))

        val maxInitialHeight = (screenHeight * MAX_INIT_HEIGHT_FRACTION).roundToInt()
            .coerceAtLeast(dp(MIN_CARD_HEIGHT_DP))
        val cardHeight = dp(DEFAULT_CARD_HEIGHT_DP).coerceIn(dp(MIN_CARD_HEIGHT_DP), maxInitialHeight)

        return WindowManager.LayoutParams(
            cardWidth,
            cardHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - cardWidth) / 2).coerceAtLeast(dp(4))
            y = (screenHeight - cardHeight - dp(INITIAL_BOTTOM_MARGIN_DP))
                .coerceIn(dp(24), (screenHeight - cardHeight).coerceAtLeast(0))
        }
    }

    // endregion

    // region View construction

    private fun buildOverlayView(): FrameLayout {
        val root = FrameLayout(context)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBackground()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        cardView = card

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        headerView = header
        attachDragHandler(header)

        audioLevelView = MiniAudioBarsView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(14)).apply {
                marginEnd = dp(6)
            }
        }
        header.addView(audioLevelView)

        titleView = TextView(context).apply {
            text = DEFAULT_TITLE
            setTextColor(TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), SHADOW_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)

        lockButton = createHeaderButton(if (locked) ICON_UNLOCKED else ICON_LOCKED).apply {
            contentDescription = CONTENT_DESC_LOCK
            setOnClickListener {
                locked = !locked
                this.text = if (locked) ICON_UNLOCKED else ICON_LOCKED
                updateCornerHandlesVisibility()
                onLockChange(locked)
            }
        }
        header.addView(lockButton)

        pauseButton = createHeaderButton(ICON_PAUSE).apply {
            contentDescription = CONTENT_DESC_PAUSE
            setOnClickListener { onTogglePause() }
        }
        header.addView(pauseButton)

        stopButton = createHeaderButton(ICON_STOP).apply {
            contentDescription = CONTENT_DESC_STOP
            setOnClickListener { onStop() }
        }
        header.addView(stopButton)

        card.addView(header)

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        captionsScrollView = scrollView

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        captionsContainer = container
        scrollView.addView(container)
        card.addView(scrollView)

        emptyView = TextView(context).apply {
            text = EMPTY_TEXT
            setTextColor(DIM_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSizeSp * 0.8f).coerceIn(11f, 18f))
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(emptyView)

        root.addView(card)

        // Add 4 corner resize handles on top of the card.
        cornerHandles.clear()
        ResizeCorner.entries.forEach { corner ->
            val handle = createCornerHandle(corner)
            cornerHandles.add(handle)
            root.addView(handle)
        }
        updateCornerHandlesVisibility()

        applyCardBackground()
        updatePauseButton()
        updateTitle(DEFAULT_TITLE)
        return root
    }

    private fun createHeaderButton(label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(TEXT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_BUTTON_TEXT_SP)
        gravity = Gravity.CENTER
        background = roundedBackground(HEADER_BUTTON_COLOR, 10f)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            marginStart = dp(6)
        }
    }

    private fun createCornerHandle(corner: ResizeCorner): View {
        val handleSize = dp(CORNER_HANDLE_TOUCH_DP)
        val gravity = when (corner) {
            ResizeCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
            ResizeCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
            ResizeCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            ResizeCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        return CornerGripView(context, corner).apply {
            layoutParams = FrameLayout.LayoutParams(handleSize, handleSize, gravity)
            contentDescription = CONTENT_DESC_RESIZE
            attachResizeHandler(this, corner)
        }
    }

    private fun updateCornerHandlesVisibility() {
        val visibility = if (locked) View.GONE else View.VISIBLE
        cornerHandles.forEach { it.visibility = visibility }
    }

    private fun attachDragHandler(header: View) {
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                if (locked) return false
                val params = layoutParams ?: return false
                val root = rootView ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + (event.rawX - touchX)).roundToInt()
                        params.y = (startY + (event.rawY - touchY)).roundToInt()
                        clampPositionToScreen(params)
                        runCatching { windowManager.updateViewLayout(root, params) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun attachResizeHandler(handle: View, corner: ResizeCorner) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var startW = 0
            private var startH = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                if (locked) return false
                val params = layoutParams ?: return false
                val root = rootView ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        startW = if (params.width > 0) params.width else root.width
                        startH = if (params.height > 0) params.height else root.height
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val (screenW, screenH) = screenSize()
                        val minW = dp(MIN_CARD_WIDTH_DP)
                        val maxW = (screenW * MAX_CARD_WIDTH_FRACTION).roundToInt().coerceAtLeast(minW)
                        val minH = dp(MIN_CARD_HEIGHT_DP)
                        val maxH = (screenH * MAX_CARD_HEIGHT_FRACTION).roundToInt().coerceAtLeast(minH)

                        val dx = (event.rawX - touchX).roundToInt()
                        val dy = (event.rawY - touchY).roundToInt()

                        var newX = startX
                        var newY = startY
                        var newW = startW
                        var newH = startH

                        when (corner) {
                            ResizeCorner.TOP_LEFT -> {
                                val rightEdge = startX + startW
                                val bottomEdge = startY + startH
                                newW = (startW - dx).coerceIn(minW, minOf(maxW, rightEdge))
                                newH = (startH - dy).coerceIn(minH, minOf(maxH, bottomEdge))
                                newX = rightEdge - newW
                                newY = bottomEdge - newH
                            }

                            ResizeCorner.TOP_RIGHT -> {
                                val bottomEdge = startY + startH
                                newW = (startW + dx).coerceIn(minW, minOf(maxW, screenW - startX))
                                newH = (startH - dy).coerceIn(minH, minOf(maxH, bottomEdge))
                                newY = bottomEdge - newH
                            }

                            ResizeCorner.BOTTOM_LEFT -> {
                                val rightEdge = startX + startW
                                newW = (startW - dx).coerceIn(minW, minOf(maxW, rightEdge))
                                newH = (startH + dy).coerceIn(minH, minOf(maxH, screenH - startY))
                                newX = rightEdge - newW
                            }

                            ResizeCorner.BOTTOM_RIGHT -> {
                                newW = (startW + dx).coerceIn(minW, minOf(maxW, screenW - startX))
                                newH = (startH + dy).coerceIn(minH, minOf(maxH, screenH - startY))
                            }
                        }

                        params.x = newX
                        params.y = newY
                        params.width = newW
                        params.height = newH
                        clampPositionToScreen(params)
                        runCatching { windowManager.updateViewLayout(root, params) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun clampPositionToScreen(params: WindowManager.LayoutParams) {
        val (screenWidth, screenHeight) = screenSize()
        val width = if (params.width > 0) params.width else (rootView?.width ?: 0)
        val height = if (params.height > 0) params.height else (rootView?.height ?: 0)
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    // endregion

    // region Rendering

    private fun renderCaptions(forceScrollToBottom: Boolean = false) {
        val container = captionsContainer ?: return
        val scrollView = captionsScrollView
        val shouldAutoScroll = forceScrollToBottom || isNearBottom(scrollView, container)

        val visible = captions.takeLast(MAX_OVERLAY_CAPTIONS)
        val previousIds = renderedIds

        container.removeAllViews()
        emptyView?.let { placeholder ->
            placeholder.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (settings.fontSizeSp * SECONDARY_TEXT_SCALE).coerceIn(10f, 18f),
            )
            placeholder.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            container.addView(placeholder)
        }

        visible.forEach { caption ->
            val row = createCaptionRow(caption)
            container.addView(row)
            if (caption.segmentId !in previousIds) {
                animateIn(row)
            }
        }
        renderedIds = visible.map { it.segmentId }.toSet()

        if (shouldAutoScroll && scrollView != null) {
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun isNearBottom(scrollView: ScrollView?, container: LinearLayout): Boolean {
        if (scrollView == null) return true
        val contentHeight = container.height
        val viewportHeight = scrollView.height
        if (contentHeight <= viewportHeight || viewportHeight == 0) return true
        val distanceFromBottom = contentHeight - (scrollView.scrollY + viewportHeight)
        return distanceFromBottom <= dp(AUTO_SCROLL_THRESHOLD_DP)
    }

    private fun createCaptionRow(caption: LiveCaption): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(2), 0, dp(2))
        }

        if (settings.showTimestamps) {
            row.addView(
                TextView(context).apply {
                    text = caption.timestamp
                    setTextColor(DIM_TEXT_COLOR)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        (settings.fontSizeSp * SECONDARY_TEXT_SCALE).coerceIn(9f, 18f),
                    )
                    setPadding(0, dp(2), dp(6), 0)
                },
            )
        }

        val speakerLabel = caption.speakerLabel?.takeIf { it.isNotBlank() }
        if (speakerLabel != null) {
            row.addView(createSpeakerBadge(speakerLabel))
        }

        row.addView(
            TextView(context).apply {
                text = caption.text
                setTextColor(TEXT_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), SHADOW_COLOR)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )
        return row
    }

    private fun createSpeakerBadge(label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (settings.fontSizeSp * BADGE_TEXT_SCALE).coerceIn(9f, 16f),
        )
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = roundedBackground(badgeColor(label), 8f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(6)
            topMargin = dp(2)
        }
    }

    private fun animateIn(row: View) {
        row.alpha = 0f
        row.translationY = dp(6).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(POP_IN_DURATION_MS)
            .start()
    }

    private fun applyCardBackground() {
        cardView?.background = cardBackground()
    }

    private fun updatePauseButton() {
        pauseButton?.text = if (paused) ICON_RESUME else ICON_PAUSE
        pauseButton?.contentDescription = if (paused) CONTENT_DESC_RESUME else CONTENT_DESC_PAUSE
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(cardColor(settings.overlayBgAlpha))
        cornerRadius = dp(CARD_CORNER_RADIUS_DP).toFloat()
        setStroke(dp(1), CARD_STROKE_COLOR)
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun badgeColor(label: String): Int =
        BADGE_COLORS[abs(label.hashCode()) % BADGE_COLORS.size]

    private fun cardColor(alpha: Float): Int {
        val clamped = alpha.coerceIn(0f, 1f)
        return Color.argb(
            (clamped * 255f).roundToInt(),
            Color.red(CARD_COLOR),
            Color.green(CARD_COLOR),
            Color.blue(CARD_COLOR),
        )
    }

    // endregion

    // region Corner grip view

    private inner class CornerGripView(
        context: Context,
        private val corner: ResizeCorner,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CORNER_GRIP_COLOR
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(4).toFloat()
            val len = dp(8).toFloat()
            val w = width.toFloat()
            val h = height.toFloat()

            when (corner) {
                ResizeCorner.TOP_LEFT -> {
                    canvas.drawLine(inset, inset + len, inset, inset, paint)
                    canvas.drawLine(inset, inset, inset + len, inset, paint)
                }

                ResizeCorner.TOP_RIGHT -> {
                    canvas.drawLine(w - inset - len, inset, w - inset, inset, paint)
                    canvas.drawLine(w - inset, inset, w - inset, inset + len, paint)
                }

                ResizeCorner.BOTTOM_LEFT -> {
                    canvas.drawLine(inset, h - inset - len, inset, h - inset, paint)
                    canvas.drawLine(inset, h - inset, inset + len, h - inset, paint)
                }

                ResizeCorner.BOTTOM_RIGHT -> {
                    canvas.drawLine(w - inset - len, h - inset, w - inset, h - inset, paint)
                    canvas.drawLine(w - inset, h - inset - len, w - inset, h - inset, paint)
                }
            }
        }
    }

    private inner class MiniAudioBarsView(context: Context) : View(context) {
        private var level = 0f
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2FBF8F")
            style = Paint.Style.FILL
        }
        private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55FFFFFF
            style = Paint.Style.FILL
        }

        fun setLevel(newLevel: Float) {
            val clamped = newLevel.coerceIn(0f, 1f)
            if (abs(clamped - level) < 0.02f && (clamped > 0f || level == 0f)) return
            level = clamped
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val barCount = 3
            val gap = dp(2).toFloat()
            val barW = ((width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
            val factors = floatArrayOf(0.65f, 1.0f, 0.78f)
            val paint = if (level > 0.04f) activePaint else idlePaint
            val radius = barW / 2f

            for (i in 0 until barCount) {
                val minH = height * 0.22f
                val barH = (minH + (height - minH) * (level * factors[i]).coerceIn(0f, 1f))
                val left = i * (barW + gap)
                val top = (height - barH) / 2f
                canvas.drawRoundRect(left, top, left + barW, top + barH, radius, radius, paint)
            }
        }
    }

    // endregion

    // region Helpers

    private fun onMain(block: () -> Unit) {
        if (isMainThread()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also {
            windowManager.defaultDisplay.getRealMetrics(it)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    // endregion

    private companion object {
        const val TAG = "FloatingOverlay"

        const val DEFAULT_TITLE = "实时字幕"
        const val EMPTY_TEXT = "等待字幕…"

        const val ICON_LOCKED = "🔒"
        const val ICON_UNLOCKED = "🔓"
        const val ICON_PAUSE = "⏸"
        const val ICON_RESUME = "▶"
        const val ICON_STOP = "✕"

        const val CONTENT_DESC_LOCK = "锁定浮窗"
        const val CONTENT_DESC_PAUSE = "暂停字幕"
        const val CONTENT_DESC_RESUME = "继续字幕"
        const val CONTENT_DESC_STOP = "停止字幕"
        const val CONTENT_DESC_RESIZE = "拖拽缩放浮窗"

        const val TITLE_TEXT_SP = 12f
        const val HEADER_BUTTON_TEXT_SP = 13f
        const val SECONDARY_TEXT_SCALE = 0.6f
        const val BADGE_TEXT_SCALE = 0.55f
        const val LINE_SPACING_MULTIPLIER = 1.15f

        const val CARD_CORNER_RADIUS_DP = 16f
        const val MIN_CARD_WIDTH_DP = 200
        const val MAX_LANDSCAPE_INIT_WIDTH_DP = 580
        const val MIN_CARD_HEIGHT_DP = 96
        const val DEFAULT_CARD_HEIGHT_DP = 136
        const val INITIAL_BOTTOM_MARGIN_DP = 32
        const val CORNER_HANDLE_TOUCH_DP = 24
        const val AUTO_SCROLL_THRESHOLD_DP = 36

        const val PORTRAIT_WIDTH_FRACTION = 0.88f
        const val LANDSCAPE_WIDTH_FRACTION = 0.68f
        const val MAX_INIT_HEIGHT_FRACTION = 0.42f
        const val MAX_CARD_WIDTH_FRACTION = 0.98f
        const val MAX_CARD_HEIGHT_FRACTION = 0.72f

        const val MAX_OVERLAY_CAPTIONS = 50
        const val POP_IN_DURATION_MS = 160L

        val CARD_COLOR = 0xFF12151C.toInt()
        val TEXT_COLOR = 0xFFFFFFFF.toInt()
        val DIM_TEXT_COLOR = 0xFFB4BECC.toInt()
        val SHADOW_COLOR = 0xAA000000.toInt()
        val CARD_STROKE_COLOR = 0x33FFFFFF
        val HEADER_BUTTON_COLOR = 0x33FFFFFF
        val CORNER_GRIP_COLOR = 0x99FFFFFF.toInt()

        val BADGE_COLORS = intArrayOf(
            Color.parseColor("#4C8DFF"),
            Color.parseColor("#2FBF8F"),
            Color.parseColor("#F2A33A"),
            Color.parseColor("#B075FF"),
            Color.parseColor("#F2617A"),
            Color.parseColor("#25B9D6"),
        )
    }
}
