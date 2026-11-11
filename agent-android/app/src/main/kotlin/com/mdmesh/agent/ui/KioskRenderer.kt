package com.mdmesh.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import com.mdmesh.agent.R

/**
 * Draws [KioskScreen]s into the kiosk launcher. It owns one root view, set once, and changes only its children:
 * a new screen replaces them, a grid update ([KioskScreen.Grid.apps]) updates the cells in place, and [rebuild]
 * redraws the current screen after a configuration change (rotation, font size, display size, dark mode) without
 * the activity being recreated. It has no kiosk behaviour: taps go to [Actions].
 */
class KioskRenderer(private val activity: ComponentActivity, private val actions: Actions) {

    /** What the launcher's views can ask for; KioskLauncherActivity decides. */
    interface Actions {
        fun openApp(packageName: String)
        fun openAgain()
        fun requestExit()
    }

    private val root = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
    }
    private var screen: KioskScreen? = null
    private var backdropBitmap: Bitmap? = null
    private var backdropInfo: BackdropInfo? = null

    /** The image is drawn under this screen. */
    private var onImage = false

    /** The configuration's fit mode and focal point; a new one is drawn at once, without a new decode. */
    var placement: Placement = Placement.DEFAULT
        set(next) {
            if (next == field) return
            field = next
            val s = screen ?: return
            onImage = activity.paintKioskBackground(root, s, backdropBitmap, backdropInfo, next)
            updatePlates()
        }
    private var grid: GridViews? = null

    private class GridViews(val views: KioskViews, val cells: AppGridLayout, val empty: View)

    init {
        activity.setContentView(root)
        // Android 15 draws edge to edge: the kiosk background runs under the bars, the content stays clear of them.
        SystemBars.fit(activity, root, KioskPalette.DEFAULT.background)
        // Plates follow the text: widths and opacities are worked out again after every layout.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePlates() }
    }

    fun render(next: KioskScreen) {
        val previous = screen
        screen = next
        val cells = grid
        if (cells != null && next is KioskScreen.Grid && sameGrid(previous, next)) {
            showApps(cells, next.apps)
        } else if (previous != next) {
            build(next)
        }
    }

    /** Draws the current screen again from scratch: sizes, fonts and resources may have changed. */
    fun rebuild() {
        screen?.let(::build)
    }

    /**
     * The kiosk's background image (null: none) and its [BackdropInfo]: painted behind every screen from now on,
     * the current one included. Text gets plates when an image comes or goes (a rebuild); otherwise the backdrop and
     * the plates' opacities are only worked out again.
     */
    fun setBackdrop(bitmap: Bitmap?, info: BackdropInfo?) {
        val platesChange = (bitmap != null) != (backdropBitmap != null)
        backdropBitmap = bitmap
        backdropInfo = info
        val s = screen ?: return
        if (platesChange) {
            build(s)
        } else {
            onImage = activity.paintKioskBackground(root, s, bitmap, info, placement)
            updatePlates()
        }
    }

    /** Everything that follows the layout: row plates, the dim, plate opacities, the bar icons. */
    private fun updatePlates() {
        val s = screen ?: return
        val painted = TextPlates.update(root, backdropInfo.takeIf { onImage }, s.palette, placement) ?: return
        activity.updateSystemBarIcons(painted.statusBar)
    }

    private fun build(s: KioskScreen) {
        onImage = activity.paintKioskBackground(root, s, backdropBitmap, backdropInfo, placement)
        root.removeAllViews()
        grid = null
        val v = KioskViews(activity, s.palette, onImage = backdropBitmap != null && s != KioskScreen.Idle)
        when (s) {
            KioskScreen.Idle -> root.addView(idle(v))
            is KioskScreen.Splash -> root.addView(v.splash(activity, s))
            is KioskScreen.Grid -> root.addView(grid(v, s))
            is KioskScreen.Hold -> root.addView(hold(v))
        }
        addExit(v, s.exit)
        // TalkBack announces the pane title when the screen changes (grid to "App closed", splash to grid).
        ViewCompat.setAccessibilityPaneTitle(root, s.paneTitle(activity))
    }

    private fun grid(v: KioskViews, s: KioskScreen.Grid): View {
        // Cells are at least the icon plus 24dp wide (wider with large fonts); the grid works out its columns from its
        // own width.
        val slot = GridColumns.slotDp(s.iconSize.dp, activity.resources.configuration.fontScale)
        val cells = AppGridLayout(activity).apply {
            slotPx = v.dp(slot)
            val side = v.gutter - v.dp(SPACE_8) // cells carry 8dp of their own, so icons sit in from the gutter
            setPadding(side, v.dp(SPACE_8), side, 0)
        }
        val empty = v.emptyNotice(activity.getString(R.string.kiosk_empty)).apply { visibility = View.GONE }
        // Room under the last row, so the floating Exit kiosk button never covers an app.
        val bottom = if (s.exit == KioskExit.VISIBLE) EXIT_BUTTON_RESERVE else SPACE_24
        val column = GridColumn(activity).apply {
            grid = cells
            iconPx = v.dp(s.iconSize.dp)
            setPadding(0, v.dp(SPACE_24), 0, v.dp(bottom))
            val header = v.text(activity.getString(R.string.kiosk_header), TITLE_SP, v.palette.text, true)
            ViewCompat.setAccessibilityHeading(header, true)
            addView(v.inGutter(header, 0))
            s.missingApp?.let { pkg ->
                val notice = v.emptyNotice(activity.getString(R.string.kiosk_app_missing, pkg))
                addView(v.inGutter(notice, v.dp(SPACE_16)))
            }
            addView(v.inGutter(empty, v.dp(SPACE_16)))
            addView(cells)
        }
        grid = GridViews(v, cells, empty).also { showApps(it, s.apps) }
        return ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(column)
        }
    }

    /**
     * The apps in the grid; with none installed, the empty notice instead, so the kiosk is never a bare screen.
     * Nothing while they load.
     */
    private fun showApps(g: GridViews, apps: List<KioskApp>?) {
        g.empty.visibility = if (apps?.isEmpty() == true) View.VISIBLE else View.GONE
        val iconPx = (screen as? KioskScreen.Grid)?.iconSize?.let { g.views.dp(it.dp) } ?: return
        g.cells.submit(
            apps.orEmpty(),
            key = { it.packageName },
            create = { app ->
                g.views.appCell(iconPx).apply { setOnClickListener { actions.openApp(app.packageName) } }
            },
            bind = g.views::bindAppCell,
        )
    }

    /** The no-kiosk screen: the v2 wordmark and "Managed device", in the launcher's default colours. */
    private fun idle(v: KioskViews): View {
        val mark = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val name = v.text("", WORDMARK_SP, v.palette.text)
        val wordmark = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }
            addView(mark)
            addView(name)
        }
        Wordmark.bind(mark, name, activity.getString(R.string.brand_name), v.palette.text, v.palette.secondary)
        return v.centredColumn().apply {
            addView(wordmark)
            addView(
                v.centredLine(activity.getString(R.string.kiosk_idle_subtitle), BODY_SP, v.palette.secondary, SPACE_8),
            )
        }
    }

    private fun hold(v: KioskViews): View = v.centredColumn().apply {
        addView(v.icon(R.drawable.ic_v2_warning, HOLD_ICON_DP, v.palette.text))
        val title =
            v.centredLine(activity.getString(R.string.kiosk_hold_title), TITLE_SP, v.palette.text, SPACE_16, true)
        ViewCompat.setAccessibilityHeading(title, true)
        addView(title)
        addView(v.centredLine(activity.getString(R.string.kiosk_hold_message), BODY_SP, v.palette.secondary, SPACE_8))
        addView(
            v.filledButton(activity.getString(R.string.kiosk_hold_retry)).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = v.dp(SPACE_16) }
                setOnClickListener { actions.openAgain() }
            },
        )
    }

    /** The screen's way out, over everything else (so TalkBack reaches it last). */
    private fun addExit(v: KioskViews, exit: KioskExit) {
        when (exit) {
            KioskExit.VISIBLE -> {
                val button = v.outlinedButton(activity.getString(R.string.kiosk_exit_button)).apply {
                    tag = TextPlates.CONTROL // the dim brings it to 3:1 over the image
                    setOnClickListener { actions.requestExit() }
                }
                root.addView(
                    button,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        setMargins(v.dp(SPACE_24), v.dp(SPACE_24), v.dp(SPACE_24), v.dp(SPACE_24))
                    },
                )
            }
            KioskExit.GESTURE -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt. Hidden from
                // TalkBack and keyboard focus: it is a secret way out (and 7 quick taps can't be made with TalkBack
                // on; such kiosks use the visible exit, or exit from the console).
                val target = View(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    var taps = 0
                    var first = 0L
                    setOnClickListener {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - first > GESTURE_WINDOW_MS) { taps = 0; first = nowMs }
                        if (++taps >= GESTURE_TAPS) { taps = 0; actions.requestExit() }
                    }
                    isFocusable = false
                }
                val size = v.dp(GESTURE_TARGET_DP)
                root.addView(target, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
            }
            KioskExit.NONE -> Unit // no exit PIN, or "remote": no on-device exit
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val GESTURE_TAPS = 7
        const val GESTURE_WINDOW_MS = 3_000L
        const val GESTURE_TARGET_DP = 72
        const val SPACE_8 = 8
        const val SPACE_16 = 16
        const val SPACE_24 = 24
        /** The visible Exit kiosk button's 48dp plus its 24dp margin, plus 8dp of air. */
        const val EXIT_BUTTON_RESERVE = 80
        const val HOLD_ICON_DP = 32
        const val TITLE_SP = KioskViews.TITLE_SP
        const val BODY_SP = KioskViews.BODY_SP
        const val WORDMARK_SP = 24f
    }
}

/** What TalkBack announces when [this] screen appears: its title, or the splash's line. */
private fun KioskScreen.paneTitle(context: Context): String = when (this) {
    KioskScreen.Idle -> context.getString(R.string.kiosk_idle_subtitle)
    is KioskScreen.Splash -> message(context)
    is KioskScreen.Grid -> context.getString(R.string.kiosk_header)
    is KioskScreen.Hold -> context.getString(R.string.kiosk_hold_title)
}

/** The splash's line: "Opening <app>…" once the app's name is known. */
private fun KioskScreen.Splash.message(context: Context): String =
    appName?.let { context.getString(R.string.kiosk_opening, it) } ?: context.getString(R.string.kiosk_loading)

/**
 * The grid screen's column: the header and the empty notice above [grid] start where its first icon starts, in any
 * orientation and column count. Their side margins are set from [AppGridLayout.iconStart] before each measure, so
 * they never lag a frame behind the grid.
 */
private class GridColumn(context: Context) : LinearLayout(context) {

    /** The grid the other children line up with, and the icon size its cells centre. */
    var grid: AppGridLayout? = null
    var iconPx: Int = 0

    init {
        orientation = VERTICAL
        clipToPadding = false // the header's plate reaches into the top padding
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = grid
        if (g != null) {
            val start = g.iconStart(MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd, iconPx)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child === g) continue
                // A plated header starts its plate's padding earlier, so its text still lines up with the icon.
                val inset = (child.background as? TextPlate)?.let { child.paddingLeft } ?: 0
                (child.layoutParams as MarginLayoutParams).apply {
                    marginStart = start - inset
                    marginEnd = start - inset
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** [next] is [previous] with other apps: update the cells, keep the rest. */
private fun sameGrid(previous: KioskScreen?, next: KioskScreen.Grid): Boolean =
    previous is KioskScreen.Grid && previous.copy(apps = null) == next.copy(apps = null)

/** The splash: a spinner and what is opening. */
private fun KioskViews.splash(activity: ComponentActivity, s: KioskScreen.Splash): View = centredColumn().apply {
    addView(spinner())
    addView(centredLine(s.message(activity), SPLASH_TEXT_SP, palette.text, topDp = SPLASH_GAP_DP))
}

private const val SPLASH_TEXT_SP = 16f
private const val SPLASH_GAP_DP = 16
