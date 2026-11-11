package com.mdmesh.agent

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.agent.ui.DialogSlot
import com.mdmesh.agent.ui.ExitPinDialog
import com.mdmesh.agent.ui.ExitPinUiState
import com.mdmesh.agent.ui.MdmDialog
import com.mdmesh.agent.ui.SystemBars
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskReturnPolicy
import com.mdmesh.proto.KioskApplyPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MDMesh kiosk HOME. This is the device's persistent launcher (`CATEGORY_HOME`), repointed to
 * by [KioskController.enter] via `addPersistentPreferredActivity`. It renders the last-applied
 * [KioskApplyPayload] persisted in [KioskStateStore]:
 *
 *  - `mode == "single"` → launch + pin the single allowed app ([KioskApplyPayload.pinPackage]).
 *  - `mode == "launcher"` → a themed grid of [KioskApplyPayload.allowedPackages].
 *  - no payload → an idle "managed device" screen (the agent is not in kiosk).
 *
 * Exit affordance is driven by [KioskApplyPayload.exitMode] (`gesture` 7-tap corner / `visible`
 * button / `remote` none) and gated by [KioskApplyPayload.password] through [ExitPinGate]: no PIN
 * means no on-device exit, and wrong PINs lock the exit for longer and longer.
 *
 * A [CrashLoopGuard] protects against a crashing pinned app bouncing back to HOME in a tight
 * loop: each return to HOME counts, and once the loop trips the launcher stops reopening the app
 * and holds on its own screen ([KioskReturnPolicy]). Kiosk stays on: only [doExit] (the admin
 * PIN) or the server ends it, because a user can close the app on purpose as often as it crashes.
 */
@AndroidEntryPoint
class KioskLauncherActivity : ComponentActivity() {

    @Inject lateinit var store: KioskStateStore
    /** Every on-device exit goes through [KioskApplier.exit], which also releases the kiosk's volume lock. */
    @Inject lateinit var applier: KioskApplier
    @Inject lateinit var events: EventSink
    @Inject lateinit var crashGuard: CrashLoopGuard
    @Inject lateinit var pinGate: ExitPinGate

    /** Last applied non-null kiosk state, so [onResume] can recover a bounced single-app pin. */
    private var active: KioskApplyPayload? = null

    private val returns by lazy { KioskReturnPolicy(crashGuard) }

    /** The hold screen is showing (so its event is recorded once per hold). */
    private var holding = false

    /** Exit PIN or Exit locked; reopened after a rotation once the kiosk state is back (see [applyState]). */
    private val dialogs = DialogSlot("exitDialog")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialogs.restore(savedInstanceState)
        // A kiosk device boots straight into HOME (this); keep the command channel alive even if
        // the user never opens the status screen.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        setScreen(idleView())
        // React to kiosk.enter/kiosk.exit live: those run in the check-in service, not here, so we
        // observe the persisted state and re-render (enter → grid/pin, exit → unpin + idle) without
        // waiting for the user to touch the screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.flow().distinctUntilChanged().collect(::applyState)
            }
        }
    }

    /** Show [root] as the whole screen, clear of the system bars (its background runs under them). */
    private fun setScreen(root: View) {
        setContentView(root)
        SystemBars.fit(this, root, (root.background as? ColorDrawable)?.color ?: INK)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        dialogs.save(outState)
    }

    override fun onDestroy() {
        dialogs.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // A single-app pin that returned us to HOME means the pinned app exited or crashed — re-pin
        // it (counting the bounce so a crash loop trips the guard). Enter/exit transitions are
        // handled by the flow collector, not here.
        val p = active ?: return
        if (p.mode == "single") {
            when (returns.onReturned()) {
                KioskReturnPolicy.Action.HOLD -> hold(p)
                KioskReturnPolicy.Action.REOPEN -> launchPinned(p)
            }
        }
    }

    private fun applyState(p: KioskApplyPayload?) {
        // Taken first, whatever is shown next: a dialog from before a recreation comes back on this state or never.
        val restored = dialogs.takeRestored()
        val previous = active
        active = p
        if (p == null) {
            dialogs.dismiss()
            holding = false
            stopLockTaskSafely()
            setScreen(idleView())
            return
        }
        // Pin first: a held device (a loop that tripped just before a reboot) is still in kiosk.
        startLockTaskSafely()
        val action = returns.onApplied(changed = previous != null && previous != p)
        val screen = if (action == KioskReturnPolicy.Action.HOLD) hold(p) else show(p)
        KioskExitDialogs.reopenOn(screen, restored)?.let { reopenExitDialog(it, p) }
    }

    /**
     * Reopen the exit dialog a rotation closed. Rebuilt from the current kiosk and [pinGate], never from saved state:
     * the PIN check and the lockout stay where they are ([ExitPinGate] persists them), an expired lockout isn't shown,
     * and a kiosk whose exit PIN was removed meanwhile gets no PIN prompt.
     */
    private fun reopenExitDialog(tag: String, p: KioskApplyPayload) {
        when (tag) {
            KioskExitDialogs.PIN -> promptExit(p)
            KioskExitDialogs.LOCKED -> pinGate.remainingLockMs()?.let(::showExitLocked)
        }
    }

    private fun show(p: KioskApplyPayload): LauncherScreen {
        holding = false
        return if (p.mode == "single" && p.pinPackage != null) {
            launchPinned(p)
        } else {
            setScreen(launcherGrid(p))
            LauncherScreen.GRID
        }
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload): LauncherScreen {
        val intent = p.pinPackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            setScreen(launcherGrid(p)) // unknown package → fall back to the grid
            return LauncherScreen.GRID
        }
        setScreen(splashView(p))
        runCatching { startActivity(intent) }
        return LauncherScreen.PINNED_APP
    }

    /** The app closed too often: stay in kiosk on the hold screen, without reopening it. */
    private fun hold(p: KioskApplyPayload): LauncherScreen {
        if (!holding) events.record("kioskCrashLoop", "kiosk app closed repeatedly; kiosk stays on, app not reopened")
        holding = true
        setScreen(holdView(p))
        return LauncherScreen.HOLD
    }

    private fun startLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        }
    }

    private fun stopLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }
    }

    // --- Exit flow ---------------------------------------------------------------------------

    private fun promptExit(p: KioskApplyPayload) {
        // No PIN, no on-device exit (the affordance isn't shown either): kiosk then ends only from the console.
        if (!pinGate.isAvailable(p.password)) return
        pinGate.remainingLockMs()?.let { showExitLocked(it); return }
        dialogs.show(KioskExitDialogs.PIN, ExitPinDialog.create(this, ::onPinEntered).dialog)
    }

    /**
     * The one place a PIN is checked, and the only caller of [doExit]. It checks against the kiosk applied now, not
     * the one the prompt was opened for: a PIN changed or removed meanwhile must not still open the exit.
     * [dialog] only shows the result: it closes on Open and Locked, and shows Wrong and Unavailable under the field.
     */
    private fun onPinEntered(entered: String, dialog: ExitPinDialog) {
        val pin = active?.password
        val outcome = pinGate.attempt(entered, pin)
        dialog.render(ExitPinUiState.of(outcome))
        when (outcome) {
            ExitPinGate.Outcome.Open -> doExit()
            is ExitPinGate.Outcome.Locked -> {
                if (outcome.started) reportLockout(outcome)
                showExitLocked(outcome.remainingMs)
            }
            // Shown in the dialog. Unavailable: the attempt couldn't be saved, so it wasn't checked (or the PIN was
            // removed meanwhile).
            is ExitPinGate.Outcome.Wrong, ExitPinGate.Outcome.Unavailable -> Unit
        }
    }

    /** Tell the server now, so administrators hear about it while it's happening (it emails them). */
    private fun reportLockout(lockout: ExitPinGate.Outcome.Locked) {
        events.record(
            "kioskPinLockout",
            "${lockout.failures} wrong exit PINs; exit locked for ${ExitPinGate.describe(lockout.remainingMs)}",
        )
        CheckInWorker.scheduleNow(applicationContext)
    }

    private fun showExitLocked(remainingMs: Long) {
        val spec = MdmDialog.Spec(
            title = getString(R.string.kiosk_exit_locked_title),
            message = getString(R.string.kiosk_exit_locked_message, ExitPinGate.describe(remainingMs)),
            confirm = getString(R.string.action_ok),
            icon = R.drawable.ic_v2_lock,
        )
        dialogs.show(KioskExitDialogs.LOCKED, MdmDialog.create(this, spec) { it.dismiss() })
    }

    private fun doExit() {
        runCatching { if (isFinishing.not()) stopLockTask() }
        events.record("kioskExit", "exited on-device")
        // Same exit as the remote kiosk.exit: releases the lock-task policy, the volume lock and the HOME claim,
        // then hands off to the OEM launcher. Forced: the admin PIN was entered, the user must get out.
        lifecycleScope.launch {
            applier.exit(handBackHome = true, force = true)
            // The launcher has already left lock task; publish that state without waiting for the periodic check-in.
            CheckInWorker.scheduleAfterCurrent(applicationContext)
            finish()
        }
    }

    // --- Views -------------------------------------------------------------------------------

    private fun splashView(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        return frame(bg).apply {
            addView(centeredText(getString(R.string.kiosk_loading), 18f, fg))
            addExitAffordance(p, this)
        }
    }

    private fun launcherGrid(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        val cell = iconCellPx(p.theme.iconSize)
        val cols = maxOf(2, (resources.displayMetrics.widthPixels - dp(24)) / (cell + dp(24)))

        val grid = GridLayout(this).apply {
            columnCount = cols
            setPadding(dp(12), dp(16), dp(12), dp(28))
        }
        var rendered = 0
        for (pkg in p.allowedPackages.distinct()) {
            val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: continue
            val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            grid.addView(appCell(pkg, label, icon, cell, fg))
            rendered++
        }

        // Always render a header + (when nothing resolved) an empty-state, so kiosk is never a
        // bare black screen — that previously happened whenever the allowlist was empty or none of
        // the packages were installed on the device.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(12))
            layoutParams = ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        column.addView(text(getString(R.string.kiosk_header), 20f, fg, bold = true))
        if (rendered == 0) {
            column.addView(
                text(
                    getString(R.string.kiosk_empty),
                    14f,
                    MUTED,
                ).apply { setPadding(0, dp(10), 0, 0) },
            )
        }
        column.addView(grid)

        val root = frame(bg)
        root.addView(
            ScrollView(this).apply {
                addView(column)
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            },
        )
        addExitAffordance(p, root)
        return root
    }

    private fun appCell(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable,
        cellPx: Int,
        fg: Int,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        addView(
            ImageView(this@KioskLauncherActivity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
            },
        )
        addView(
            text(label, 12f, fg).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            },
        )
        setOnClickListener {
            runCatching {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    private fun idleView(): View = frame(INK).apply {
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText(getString(R.string.brand_name), 28f, SIGNAL, bold = true))
        col.addView(centeredText(getString(R.string.kiosk_idle_subtitle), 14f, MUTED))
        addView(col)
    }

    private fun holdView(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText(getString(R.string.kiosk_hold_title), 22f, fg, bold = true))
        col.addView(
            centeredText(
                getString(R.string.kiosk_hold_message),
                14f,
                MUTED,
            ).apply { setPadding(0, dp(12), 0, dp(24)) },
        )
        col.addView(
            Button(this).apply {
                setText(R.string.kiosk_hold_retry)
                setOnClickListener {
                    returns.retry()
                    show(p)
                }
            },
        )
        val root = frame(bg)
        root.addView(col)
        addExitAffordance(p, root)
        return root
    }

    /** Add the per-[KioskApplyPayload.exitMode] exit affordance to [parent]; none without an exit PIN. */
    private fun addExitAffordance(p: KioskApplyPayload, parent: ViewGroup) {
        if (!pinGate.isAvailable(p.password)) return
        when (p.exitMode) {
            "visible" -> {
                val btn = Button(this).apply {
                    setText(R.string.kiosk_exit_button)
                    setOnClickListener { promptExit(p) }
                }
                parent.addView(
                    FrameWrap(this, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(24)),
                )
            }
            "gesture" -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt.
                val target = View(this).apply {
                    var taps = 0
                    var first = 0L
                    setOnClickListener {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - first > GESTURE_WINDOW_MS) { taps = 0; first = nowMs }
                        if (++taps >= GESTURE_TAPS) { taps = 0; promptExit(p) }
                    }
                }
                parent.addView(
                    FrameWrap(this, target, Gravity.TOP or Gravity.END, 0, dp(72), dp(72)),
                )
            }
            else -> Unit // "remote": no on-device exit
        }
    }

    // --- View helpers ------------------------------------------------------------------------

    private fun frame(bg: Int): android.widget.FrameLayout =
        android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

    private fun centeredText(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        text(s, sizeSp, color, bold).apply { gravity = Gravity.CENTER }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun iconCellPx(size: String?): Int = when (size?.uppercase()) {
        "LARGE" -> dp(96)
        "MEDIUM" -> dp(72)
        else -> dp(56)
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        value?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val GESTURE_TAPS = 7
        const val GESTURE_WINDOW_MS = 3_000L
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val SIGNAL = Color.parseColor("#F4B942")
    }
}

/** A [android.widget.FrameLayout.LayoutParams]-positioned wrapper, kept tiny for the launcher's
 *  programmatic UI (no XML). Places [child] at [gravity] with optional margins/size. */
private class FrameWrap(
    activity: ComponentActivity,
    child: View,
    gravity: Int,
    marginPx: Int,
    widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
) : android.widget.FrameLayout(activity) {
    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            child,
            android.widget.FrameLayout.LayoutParams(widthPx, heightPx, gravity).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            },
        )
    }
}
