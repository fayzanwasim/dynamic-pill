package com.personal.dynamicpill

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pill itself. It sits on the camera cutout and morphs between resting, compact and expanded
 * shapes using spring physics, so it overshoots and settles like Apple's Dynamic Island.
 * Its measured size is driven entirely by [pillWidth] and [pillHeight], so the hosting
 * WRAP_CONTENT window follows the animation.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(context: Context, private val metrics: IslandMetrics) : FrameLayout(context) {

    enum class State { HIDDEN, IDLE, COMPACT, EXPANDED }

    enum class Swipe { UP, LEFT, RIGHT }

    var state = State.HIDDEN
        private set

    var onPillClick: ((State) -> Unit)? = null

    /** Called when the user touches anywhere outside the pill's window. */
    var onOutsideTouch: (() -> Unit)? = null

    var onSwipe: ((Swipe) -> Unit)? = null

    var onLongPress: (() -> Unit)? = null

    val isExpanded: Boolean
        get() = state == State.EXPANDED

    private val dampingScale = BOUNCE_BASE - metrics.bounciness / BOUNCINESS_MAX

    // Spring frequency grows with the square root of stiffness, so stiffness scales with speed squared
    // to make everything (springs, fades, delays) run at exactly `speed` times the baseline rate.
    private val speed = metrics.animationSpeed / SPEED_PERCENT
    private val compactCorner = min(metrics.compactRadius.toFloat(), metrics.compactHeight / 2f)
    private val pillBackground = GradientDrawable().apply { setColor(Color.BLACK) }
    private var pillWidth = metrics.restingWidth.toFloat()
    private var pillHeight = metrics.compactHeight.toFloat()
    private var cornerRadius = compactCorner
    private var compactSideWidth = metrics.compactSide
    private var reveal = 1f

    // The icon hugs the pill's left edge and the visualizer its right edge.
    private val leftGlance = GlanceView(context, metrics.accentColor, Gravity.START, metrics.edgePadding)
    private val rightGlance = GlanceView(context, metrics.accentColor, Gravity.END, metrics.edgePadding)
    private val cutoutSpacer = View(context)
    private val compactRow = LinearLayout(context)

    private val expandedIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val expandedTitle = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = TITLE_SP
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val expandedSubtitle = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = SUBTITLE_SP
        maxLines = SUBTITLE_MAX_LINES
        ellipsize = TextUtils.TruncateAt.END
    }
    private val headerGlance = GlanceView(context, metrics.accentColor, Gravity.END, 0)
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = PROGRESS_MAX
        progressTintList = ColorStateList.valueOf(metrics.accentColor)
        progressBackgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
    }
    private val actionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val secondaryRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    // Music position: a draggable seek bar with elapsed and total time that advances on its own while playing.
    private val elapsedLabel = TextView(context).apply { styleAsTime(this) }
    private val totalLabel = TextView(context).apply { styleAsTime(this) }
    private var mediaProgress: MediaProgress? = null
    private var seeking = false
    private val seekBar = SeekBar(context).apply {
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsedLabel.text = formatTime(progress * MS_PER_SECOND)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    mediaProgress?.onSeek?.invoke(bar.progress * MS_PER_SECOND)
                }
            },
        )
    }
    private val mediaRow = LinearLayout(context)
    private val mediaTick = object : Runnable {
        override fun run() {
            updateMediaPosition()
            postDelayed(this, MEDIA_TICK_MS)
        }
    }

    private val expandedColumn = LinearLayout(context)

    private val widthSpring = SpringAnimation(this, WidthProperty, pillWidth).setMinValue(MIN_SIZE_PX)
    private val heightSpring = SpringAnimation(this, HeightProperty, pillHeight).setMinValue(MIN_SIZE_PX)
    private val radiusSpring = SpringAnimation(this, RadiusProperty, cornerRadius).setMinValue(0f)
    private val revealSpring = SpringAnimation(this, RevealProperty, 1f)
    private val contentScaleXSpring = SpringAnimation(expandedColumn, DynamicAnimation.SCALE_X, 1f)
    private val contentScaleYSpring = SpringAnimation(expandedColumn, DynamicAnimation.SCALE_Y, 1f)
    private val pressScaleXSpring = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f)
    private val pressScaleYSpring = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f)

    // Idle effects (glow, orbit, chomp, heartbeat) redraw at about 30 fps while active.
    private var effect = PillEffect.NONE
    private val effectRenderer = PillEffectRenderer(resources.displayMetrics.density, metrics.accentColor)
    private var lastEffectFrameAt = 0L
    private val effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = EFFECT_TICK_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastEffectFrameAt >= EFFECT_FRAME_INTERVAL_MS) {
                lastEffectFrameAt = now
                invalidate()
            }
        }
    }

    private val swipeDistance = ViewConfiguration.get(context).scaledTouchSlop * SWIPE_SLOP_MULTIPLIER
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val swipe = swipeFor(e2.x - start.x, e2.y - start.y) ?: return false
                onSwipe?.invoke(swipe)
                return true
            }
        },
    )

    init {
        background = pillBackground
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        setOnClickListener { onPillClick?.invoke(state) }
        // Consuming the long click stops the release from also counting as a tap.
        setOnLongClickListener {
            val handler = onLongPress ?: return@setOnLongClickListener false
            handler()
            true
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressTo(PRESSED_SCALE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(1f)
                MotionEvent.ACTION_OUTSIDE -> onOutsideTouch?.invoke()
            }
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) gestures.onTouchEvent(event)
            false
        }

        buildCompactRow()
        buildExpandedColumn()
        addView(compactRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            expandedColumn,
            LayoutParams(metrics.expandedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        expandedColumn.pivotX = metrics.expandedWidth / 2f
        expandedColumn.pivotY = 0f
        compactRow.alpha = 0f
        expandedColumn.alpha = 0f
        expandedColumn.visibility = INVISIBLE

        val hideWhenSettled = DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
            if (state == State.HIDDEN && !widthSpring.isRunning && !heightSpring.isRunning) visibility = INVISIBLE
        }
        widthSpring.addEndListener(hideWhenSettled)
        heightSpring.addEndListener(hideWhenSettled)
    }

    /**
     * Shows [content]. When [secondary] is given the compact pill is split: [content]'s glance on the left
     * of the camera and [secondary]'s on the right.
     */
    fun show(content: IslandContent, expanded: Boolean, secondary: IslandContent? = null) {
        bind(content, secondary)
        val previous = state
        state = if (expanded) State.EXPANDED else State.COMPACT
        setEffect(PillEffect.NONE)
        if (previous == State.HIDDEN) startFromResting()
        if (expanded && previous != State.EXPANDED && metrics.haptics) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        if (expanded) expand() else collapseToCompact(reveal = previous == State.HIDDEN || previous == State.IDLE)
        refreshMediaTicker()
    }

    /**
     * Shows the always-on resting pill: just the black pill around the camera, optionally decorated with
     * [left] and [right] glances and an animated [effect]. It widens from here when an activity starts.
     */
    fun showIdle(left: Glance, right: Glance, effect: PillEffect) {
        // Effects drawn beside the camera need a little room even when there is no glance to size the sides.
        val minSide = if (effect == PillEffect.NONE || effect == PillEffect.GLOW) metrics.gap else dp(EFFECT_MIN_SIDE_DP)
        bindCompactGlances(left, right, minSide, fixedSide = metrics.idleSide.takeIf { it > 0 })
        val previous = state
        state = State.IDLE
        setEffect(effect)
        if (previous == State.HIDDEN) startFromResting()
        collapseToCompact(reveal = previous != State.IDLE)
    }

    /** Width of the compact pill for the content it was last bound with. */
    val compactWidthPx: Int
        get() = metrics.cutoutWidth + 2 * compactSideWidth

    fun hide() {
        if (state == State.HIDDEN) return
        state = State.HIDDEN
        setEffect(PillEffect.NONE)
        removeCallbacks(mediaTick)
        leftGlance.bind(Glance.None)
        rightGlance.bind(Glance.None)
        headerGlance.bind(Glance.None)
        fade(compactRow, 0f, delay = 0L)
        fade(expandedColumn, 0f, delay = 0L) { if (state != State.EXPANDED) expandedColumn.visibility = INVISIBLE }
        scaleContentTo(ENTER_CONTENT_SCALE)
        morphTo(metrics.restingWidth.toFloat(), metrics.compactHeight.toFloat(), compactCorner, HIDE_SPRING, HIDE_SPRING)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (effect == PillEffect.NONE) return
        effectRenderer.draw(
            effect,
            canvas,
            width,
            height,
            cornerRadius,
            compactSideWidth,
            metrics.cutoutWidth,
            SystemClock.uptimeMillis(),
        )
    }

    override fun onDetachedFromWindow() {
        effectAnimator.cancel()
        removeCallbacks(mediaTick)
        super.onDetachedFromWindow()
    }

    /** The music position only needs to advance while the card is open and playing. */
    private fun refreshMediaTicker() {
        removeCallbacks(mediaTick)
        val playing = (mediaProgress?.speed ?: 0f) > 0f
        if (state == State.EXPANDED && playing) post(mediaTick)
    }

    private fun updateMediaPosition() {
        val media = mediaProgress ?: return
        val advanced = (SystemClock.elapsedRealtime() - media.elapsedRealtimeAtPosition) * media.speed
        val position = (media.positionMs + advanced.toLong()).coerceIn(0L, media.durationMs)
        if (seeking) return
        seekBar.progress = (position / MS_PER_SECOND).toInt()
        elapsedLabel.text = formatTime(position)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / MS_PER_SECOND
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
    }

    private fun styleAsTime(label: TextView) {
        label.setTextColor(Color.LTGRAY)
        label.textSize = TIME_SP
    }

    private fun setEffect(newEffect: PillEffect) {
        if (effect == newEffect) return
        effect = newEffect
        if (newEffect == PillEffect.NONE) effectAnimator.cancel() else if (!effectAnimator.isRunning) effectAnimator.start()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(pillWidth.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pillHeight.roundToInt(), MeasureSpec.EXACTLY),
        )
    }

    private fun swipeFor(dx: Float, dy: Float): Swipe? = when {
        abs(dy) > abs(dx) && dy < -swipeDistance -> Swipe.UP
        abs(dx) > abs(dy) && dx > swipeDistance -> Swipe.RIGHT
        abs(dx) > abs(dy) && dx < -swipeDistance -> Swipe.LEFT
        else -> null
    }

    private fun startFromResting() {
        widthSpring.cancel()
        heightSpring.cancel()
        radiusSpring.cancel()
        pillWidth = metrics.restingWidth.toFloat()
        pillHeight = metrics.compactHeight.toFloat()
        cornerRadius = compactCorner
        onShapeChanged()
        visibility = VISIBLE
    }

    private fun expand() {
        val height = measureExpandedHeight()
        expandedColumn.layoutParams = expandedColumn.layoutParams.apply { this.height = height }
        if (expandedColumn.visibility != VISIBLE) {
            expandedColumn.scaleX = ENTER_CONTENT_SCALE
            expandedColumn.scaleY = ENTER_CONTENT_SCALE
            expandedColumn.visibility = VISIBLE
        }
        fade(compactRow, 0f, delay = 0L)
        fade(expandedColumn, 1f, delay = FADE_IN_DELAY_MS)
        scaleContentTo(1f)
        morphTo(
            metrics.expandedWidth.toFloat(),
            height.toFloat(),
            min(metrics.expandedRadius.toFloat(), height / 2f),
            EXPAND_WIDTH_SPRING,
            EXPAND_HEIGHT_SPRING,
        )
    }

    /**
     * Morphs to the compact shape. With [reveal] the content is not swapped in place: the pill springs open from
     * where it was while the new content pops outward from the camera towards the pill's edges.
     */
    private fun collapseToCompact(reveal: Boolean) {
        val targetWidth = (metrics.cutoutWidth + 2 * compactSideWidth).toFloat()
        val springy = reveal || targetWidth > pillWidth
        if (reveal) {
            compactRow.animate().cancel()
            compactRow.alpha = 0f
            applyReveal(0f)
            revealSpring.springTo(1f, REVEAL_SPRING)
            kick()
        }
        fade(compactRow, 1f, delay = if (reveal) REVEAL_FADE_DELAY_MS else FADE_IN_DELAY_MS)
        fade(expandedColumn, 0f, delay = 0L) { if (state != State.EXPANDED) expandedColumn.visibility = INVISIBLE }
        scaleContentTo(ENTER_CONTENT_SCALE)
        morphTo(
            targetWidth,
            metrics.compactHeight.toFloat(),
            compactCorner,
            if (springy) EXPAND_WIDTH_SPRING else COLLAPSE_SPRING,
            if (springy) EXPAND_HEIGHT_SPRING else COLLAPSE_SPRING,
        )
    }

    /** Gives the width and height springs an outward push so the pill visibly swells before settling. */
    private fun kick() {
        if (!widthSpring.isRunning) widthSpring.setStartVelocity(KICK_WIDTH_VELOCITY * speed)
        if (!heightSpring.isRunning) heightSpring.setStartVelocity(KICK_HEIGHT_VELOCITY * speed)
    }

    /** At 0 both glances sit tucked towards the camera and small; at 1 they rest at the pill's edges. */
    private fun applyReveal(progress: Float) {
        reveal = progress
        val shift = compactSideWidth * REVEAL_SHIFT_RATIO * (1f - progress)
        val scale = REVEAL_MIN_SCALE + (1f - REVEAL_MIN_SCALE) * progress
        leftGlance.translationX = shift
        rightGlance.translationX = -shift
        leftGlance.scaleX = scale
        leftGlance.scaleY = scale
        rightGlance.scaleX = scale
        rightGlance.scaleY = scale
    }

    private fun morphTo(width: Float, height: Float, radius: Float, widthSpec: SpringSpec, heightSpec: SpringSpec) {
        widthSpring.springTo(width, widthSpec)
        heightSpring.springTo(height, heightSpec)
        radiusSpring.springTo(radius, RADIUS_SPRING)
    }

    private fun scaleContentTo(scale: Float) {
        contentScaleXSpring.springTo(scale, CONTENT_SPRING)
        contentScaleYSpring.springTo(scale, CONTENT_SPRING)
    }

    private fun pressTo(scale: Float) {
        pressScaleXSpring.springTo(scale, PRESS_SPRING)
        pressScaleYSpring.springTo(scale, PRESS_SPRING)
    }

    private fun SpringAnimation.springTo(target: Float, spec: SpringSpec) {
        val damping = if (spec.bouncy) (spec.dampingRatio * dampingScale).coerceIn(MIN_DAMPING_RATIO, 1f) else spec.dampingRatio
        spring.setStiffness(spec.stiffness * speed * speed).setDampingRatio(damping)
        animateToFinalPosition(target)
    }

    private fun onShapeChanged() {
        invalidateOutline()
        requestLayout()
    }

    private fun buildCompactRow() {
        compactRow.orientation = LinearLayout.HORIZONTAL
        compactRow.gravity = Gravity.CENTER_VERTICAL
        compactRow.addView(leftGlance, LinearLayout.LayoutParams(compactSideWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        compactRow.addView(cutoutSpacer, LinearLayout.LayoutParams(metrics.cutoutWidth, 1))
        compactRow.addView(rightGlance, LinearLayout.LayoutParams(compactSideWidth, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildExpandedColumn() {
        val padding = dp(EXPANDED_PADDING_DP)
        expandedColumn.orientation = LinearLayout.VERTICAL
        expandedColumn.setPadding(padding, metrics.cutoutBottom + dp(EXPANDED_TOP_GAP_DP), padding, padding)

        val iconSize = dp(EXPANDED_ICON_DP)
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(expandedTitle)
            addView(expandedSubtitle)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(expandedIcon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(
                titles,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(HEADER_GAP_DP) },
            )
            addView(
                headerGlance,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(EXPANDED_ICON_DP))
                    .apply { marginStart = dp(HEADER_GAP_DP) },
            )
        }
        expandedColumn.addView(header)

        val times = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(elapsedLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(totalLabel)
        }
        mediaRow.orientation = LinearLayout.VERTICAL
        mediaRow.addView(seekBar)
        mediaRow.addView(times)
        expandedColumn.addView(
            mediaRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ACTION_ROW_TOP_DP) },
        )
        expandedColumn.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PROGRESS_HEIGHT_DP))
                .apply { topMargin = dp(ACTION_ROW_TOP_DP) },
        )
        expandedColumn.addView(
            actionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ACTION_ROW_TOP_DP) },
        )
        expandedColumn.addView(
            secondaryRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ACTION_ROW_TOP_DP) },
        )
    }

    private fun bind(content: IslandContent, secondary: IslandContent?) {
        expandedIcon.setImageDrawable(content.icon)
        expandedTitle.text = content.title
        expandedSubtitle.text = content.subtitle
        expandedSubtitle.visibility = if (content.subtitle.isEmpty()) GONE else VISIBLE
        headerGlance.bind(content.glance)
        progressBar.visibility = if (content.progress == null) GONE else VISIBLE
        progressBar.progress = content.progress ?: 0

        actionRow.removeAllViews()
        content.actions.forEach { actionRow.addView(createActionButton(it)) }
        actionRow.visibility = if (content.actions.isEmpty()) GONE else VISIBLE
        secondaryRow.removeAllViews()
        content.secondaryActions.forEach { secondaryRow.addView(createLabelButton(it)) }
        secondaryRow.visibility = if (content.secondaryActions.isEmpty()) GONE else VISIBLE

        bindAccent(content.accent ?: metrics.accentColor)
        bindMedia(content.media)
        contentDescription = listOf(content.title, content.subtitle).filter { it.isNotEmpty() }.joinToString(", ")

        val left = if (secondary != null) glanceOf(content) else Glance.Icon(content.icon)
        val right = if (secondary != null) glanceOf(secondary) else content.glance
        bindCompactGlances(left, right, metrics.compactSide)
    }

    private fun bindAccent(accent: Int) {
        leftGlance.setAccent(accent)
        rightGlance.setAccent(accent)
        headerGlance.setAccent(accent)
        val tint = ColorStateList.valueOf(accent)
        progressBar.progressTintList = tint
        seekBar.progressTintList = tint
        seekBar.thumbTintList = tint
    }

    private fun bindMedia(media: MediaProgress?) {
        mediaProgress = media
        mediaRow.visibility = if (media == null) GONE else VISIBLE
        if (media == null) return
        seekBar.max = (media.durationMs / MS_PER_SECOND).toInt()
        totalLabel.text = formatTime(media.durationMs)
        updateMediaPosition()
    }

    /** Fills both sides of the compact pill; both stay the same width so the camera remains centred. */
    private fun bindCompactGlances(left: Glance, right: Glance, minSide: Int, fixedSide: Int? = null) {
        leftGlance.bind(left)
        rightGlance.bind(right)
        compactSideWidth = fixedSide ?: max(minSide, max(leftGlance.preferredWidth(), rightGlance.preferredWidth()))
        leftGlance.layoutParams = leftGlance.layoutParams.apply { width = compactSideWidth }
        rightGlance.layoutParams = rightGlance.layoutParams.apply { width = compactSideWidth }
        // Scale around the edge nearest the camera so the glances grow outwards from it.
        leftGlance.pivotX = compactSideWidth.toFloat()
        rightGlance.pivotX = 0f
        leftGlance.pivotY = metrics.compactHeight / 2f
        rightGlance.pivotY = metrics.compactHeight / 2f
    }

    private fun glanceOf(content: IslandContent): Glance =
        if (content.glance != Glance.None) content.glance else Glance.Icon(content.icon)

    private fun createActionButton(action: IslandAction): View =
        if (action.iconRes != null) createIconButton(action.iconRes, action.onClick) else createLabelButton(action)

    private fun createIconButton(iconRes: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(iconRes)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), dp(ACTION_BUTTON_HEIGHT_DP))
    }

    private fun createLabelButton(action: IslandAction) = Button(context).apply {
        text = action.label
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(colorFor(action.style))
            cornerRadius = dp(LABEL_BUTTON_RADIUS_DP).toFloat()
        }
        setOnClickListener { action.onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(ACTION_BUTTON_HEIGHT_DP), 1f)
            .apply { marginStart = dp(ACTION_GAP_DP); marginEnd = dp(ACTION_GAP_DP) }
    }

    private fun colorFor(style: ActionStyle): Int = when (style) {
        ActionStyle.NEUTRAL -> COLOR_NEUTRAL
        ActionStyle.POSITIVE -> COLOR_POSITIVE
        ActionStyle.DESTRUCTIVE -> COLOR_DESTRUCTIVE
    }

    private fun measureExpandedHeight(): Int {
        expandedColumn.measure(
            MeasureSpec.makeMeasureSpec(metrics.expandedWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return expandedColumn.measuredHeight
    }

    private fun fade(target: View, alpha: Float, delay: Long, onEnd: (() -> Unit)? = null) {
        target.animate().cancel()
        target.animate()
            .alpha(alpha)
            .setStartDelay((delay / speed).toLong())
            .setDuration((FADE_MS / speed).toLong())
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    private fun dp(value: Int): Int = context.dp(value)

    private class SpringSpec(val stiffness: Float, val dampingRatio: Float, val bouncy: Boolean = true)

    private object WidthProperty : FloatPropertyCompat<IslandView>("pillWidth") {
        override fun getValue(view: IslandView) = view.pillWidth

        override fun setValue(view: IslandView, value: Float) {
            view.pillWidth = value
            view.onShapeChanged()
        }
    }

    private object HeightProperty : FloatPropertyCompat<IslandView>("pillHeight") {
        override fun getValue(view: IslandView) = view.pillHeight

        override fun setValue(view: IslandView, value: Float) {
            view.pillHeight = value
            view.onShapeChanged()
        }
    }

    private object RevealProperty : FloatPropertyCompat<IslandView>("reveal") {
        override fun getValue(view: IslandView) = view.reveal

        override fun setValue(view: IslandView, value: Float) {
            view.applyReveal(value)
        }
    }

    private object RadiusProperty : FloatPropertyCompat<IslandView>("cornerRadius") {
        override fun getValue(view: IslandView) = view.cornerRadius

        override fun setValue(view: IslandView, value: Float) {
            view.cornerRadius = value
            view.invalidateOutline()
        }
    }

    private companion object {
        // Low damping ratios overshoot and bounce; width is bouncier than height so the pill "stretches".
        // The bounciness setting scales every bouncy spring's damping.
        val EXPAND_WIDTH_SPRING = SpringSpec(stiffness = 380f, dampingRatio = 0.58f)
        val EXPAND_HEIGHT_SPRING = SpringSpec(stiffness = 320f, dampingRatio = 0.66f)
        val COLLAPSE_SPRING = SpringSpec(stiffness = 520f, dampingRatio = 0.72f)
        val HIDE_SPRING = SpringSpec(stiffness = 600f, dampingRatio = 0.85f)
        val RADIUS_SPRING = SpringSpec(stiffness = 450f, dampingRatio = 1f, bouncy = false)
        val CONTENT_SPRING = SpringSpec(stiffness = 320f, dampingRatio = 0.6f)
        val PRESS_SPRING = SpringSpec(stiffness = 800f, dampingRatio = 0.55f)

        // Content pops out of the camera area slightly bouncier than the pill itself.
        val REVEAL_SPRING = SpringSpec(stiffness = 420f, dampingRatio = 0.5f)
        const val REVEAL_SHIFT_RATIO = 0.9f
        const val REVEAL_MIN_SCALE = 0.4f
        const val REVEAL_FADE_DELAY_MS = 40L

        // Initial spring velocities (px/s) that make the pill swell outwards when it starts expanding.
        const val KICK_WIDTH_VELOCITY = 1400f
        const val KICK_HEIGHT_VELOCITY = 500f
        const val EFFECT_TICK_MS = 1_000L
        const val EFFECT_FRAME_INTERVAL_MS = 33L
        const val EFFECT_MIN_SIDE_DP = 24
        const val SPEED_PERCENT = 100f
        const val BOUNCE_BASE = 1.5f
        const val BOUNCINESS_MAX = 100f
        const val MIN_DAMPING_RATIO = 0.2f
        const val MIN_SIZE_PX = 1f
        const val PRESSED_SCALE = 0.94f
        const val ENTER_CONTENT_SCALE = 0.85f
        const val SWIPE_SLOP_MULTIPLIER = 3
        const val FADE_MS = 160L
        const val FADE_IN_DELAY_MS = 120L
        const val TITLE_SP = 15f
        const val SUBTITLE_SP = 13f
        const val SUBTITLE_MAX_LINES = 2
        const val PROGRESS_MAX = 100
        const val PROGRESS_HEIGHT_DP = 4
        const val EXPANDED_PADDING_DP = 20
        const val EXPANDED_TOP_GAP_DP = 8
        const val EXPANDED_ICON_DP = 36
        const val HEADER_GAP_DP = 12
        const val ACTION_ROW_TOP_DP = 8
        const val MEDIA_TICK_MS = 500L
        const val MS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60
        const val TIME_SP = 11f
        const val ACTION_GAP_DP = 4
        const val ACTION_BUTTON_WIDTH_DP = 56
        const val ACTION_BUTTON_HEIGHT_DP = 44
        const val LABEL_BUTTON_RADIUS_DP = 22
        val COLOR_NEUTRAL = Color.parseColor("#3A3A3C")
        val COLOR_POSITIVE = Color.parseColor("#30D158")
        val COLOR_DESTRUCTIVE = Color.parseColor("#FF453A")
    }
}
