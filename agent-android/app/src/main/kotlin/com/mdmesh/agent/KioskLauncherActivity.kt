package com.mdmesh.agent

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.agent.ui.DecodeLimits
import com.mdmesh.agent.ui.DialogSlot
import com.mdmesh.agent.ui.ExitPinDialog
import com.mdmesh.agent.ui.ExitPinUiState
import com.mdmesh.agent.ui.IconSize
import com.mdmesh.agent.ui.KioskAppLoader
import com.mdmesh.agent.ui.KioskBackdrop
import com.mdmesh.agent.ui.KioskExit
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskRenderer
import com.mdmesh.agent.ui.Placement
import com.mdmesh.agent.ui.KioskScreen
import com.mdmesh.agent.ui.MdmDialog
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskBackgroundImageStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.Boot
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.HoldStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskReturnPolicy
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.sameKioskAs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
    @Inject lateinit var holdStore: HoldStore
    @Inject lateinit var backgroundImages: KioskBackgroundImageStore
    @Inject lateinit var pinGate: ExitPinGate

    /** Last applied non-null kiosk state, so [onResume] can recover a bounced single-app pin. */
    private var active: KioskApplyPayload? = null

    /** The crash-loop hold is saved ([holdStore]), so it outlives this activity; it belongs to this boot. */
    private val returns by lazy {
        KioskReturnPolicy(crashGuard, holdStore, ::bootCount) { SystemClock.elapsedRealtime() }
    }

    /** The hold screen is showing (so its event is recorded once per hold). */
    private var holding = false

    /** Draws [KioskScreen]s; the activity decides which. */
    private lateinit var renderer: KioskRenderer
    private lateinit var apps: KioskAppLoader

    /** The kiosk's background image: fetched and decoded off Main, painted by the renderer behind every screen. */
    private lateinit var backdrop: KioskBackdrop

    /** The screen showing now. */
    private var screen: KioskScreen = KioskScreen.Idle

    /** Loads the grid's apps or the splash's app name for the screen showing; replaced with it. */
    private var loading: Job? = null

    /** Exit PIN, Exit locked or Exit unavailable; reopened after a recreation, once the kiosk state is back. */
    private val dialogs = DialogSlot("exitDialog")

    /** Acts once per return to the front, whichever of onResume and the kiosk delivery comes first. */
    private val resumeGate = HomeResumeGate()

    /** The display density the grid's icons were loaded for. */
    private var densityDpi = 0

    /** An allowed app installed, updated or removed while the grid shows: reload it (registered while started). */
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val p = active ?: return
            val missing = (screen as? KioskScreen.Grid)?.missingApp
            val pinnedOnSplash = p.mode == "single" && pkg == p.pinPackage && screen is KioskScreen.Splash
            val removed = intent.action == Intent.ACTION_PACKAGE_REMOVED
            when {
                // The single-app kiosk's missing app is back: the kiosk resumes with it.
                missing != null && pkg == missing && pinnedLaunchable(p) -> show(p)
                // The pinned app was updated (or reinstalled) while HOME showed its splash, its start may have failed
                // mid-update: open it again, uncounted. Removed for good: the grid says it isn't installed.
                pinnedOnSplash && !removed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ->
                    launchPinned(p)
                pinnedOnSplash && removed && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) -> showGrid(p)
                KioskAppLoader.affects(screen, pkg) -> showGrid(p)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialogs.restore(savedInstanceState)
        // A kiosk device boots straight into HOME (this); keep the command channel alive even if
        // the user never opens the status screen.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        apps = KioskAppLoader(this)
        densityDpi = resources.configuration.densityDpi
        renderer = KioskRenderer(this, Actions())
        backdrop = KioskBackdrop(lifecycleScope, backgroundImages, DecodeLimits.of(this)) { bitmap, info ->
            renderer.setBackdrop(bitmap, info)
        }
        render(KioskScreen.Idle)
        // React to kiosk.enter/kiosk.exit live: those run in the check-in service, not here, so we
        // observe the persisted state and re-render (enter → grid/pin, exit → unpin + idle) without
        // waiting for the user to touch the screen.
        lifecycleScope.launch {
            // First settle an exit that a process death cut off, so a half-exited kiosk isn't applied as it is.
            // Guarded: a failing step must not crash HOME on every start (the entry then settles in kiosk).
            val finished = withContext(NonCancellable) {
                runCatching { applier.resumeInterruptedExit() }
                    .onFailure { Log.w(TAG, "settling an interrupted kiosk exit failed", it) }
                    .getOrDefault(false)
            }
            if (finished) CheckInWorker.scheduleAfterCurrent(applicationContext)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // An image's equality leaves out its URL (it's the same image); a new URL must still get here, or
                // an image not yet downloaded is fetched from where the server no longer is.
                store.flow().distinctUntilChanged { a, b -> a == b && imageUrl(a) == imageUrl(b) }.collect(::applyState)
            }
        }
    }

    /**
     * The manifest's configChanges bring rotation, window size, font size, display size and dark mode here instead
     * of recreating the kiosk HOME (which drew the idle screen first, a visible flash, and replayed the saved kiosk,
     * reopening the pinned app). The screen is redrawn from its state in the new configuration; lock task, the
     * crash-loop count and an open Exit kiosk dialog carry on.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The open exit dialog is rebuilt too, so it takes the new colours and sizes.
        dialogs.onConfigurationChanged(newConfig) { tag, typed -> active?.let { reopenExitDialog(tag, it, typed) } }
        renderer.rebuild()
        // A new window size (rotation, split screen): the image is decoded again to cover it; the old one stays
        // until then.
        showBackdrop(active)
        // A display-size change: the grid's icons were decoded for the old density.
        if (newConfig.densityDpi != densityDpi) {
            densityDpi = newConfig.densityDpi
            active?.let { p -> if (screen is KioskScreen.Grid) showGrid(p) }
        }
    }

    override fun onStart() {
        // Before super: the collector restarts in super.onStart() and may deliver at once.
        resumeGate.onStart()
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Package broadcasts are protected (only the system sends them). Not exported on 33+, where a flag is
        // required; before 33 a plain registration, rather than ContextCompat's permission-guarded one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageChanges, filter)
        }
    }

    /**
     * The launcher left the screen: screen off, the pinned or a grid app in front. A PIN prompt closes, typed PIN
     * and Show PIN with it, so nobody finds it half typed; not for a config change, which rebuilds it.
     */
    override fun onStop() {
        if (!isChangingConfigurations) dialogs.dismiss(KioskExitDialogs.PIN)
        unregisterReceiver(packageChanges)
        super.onStop()
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
        if (resumeGate.onResume() == HomeResumeGate.Step.RETURN) onReturnedToFront()
    }

    /**
     * A single-app pin that returned us to HOME means the pinned app exited or crashed — re-pin
     * it (counting the bounce so a crash loop trips the guard). Enter/exit transitions are
     * handled by the flow collector, not here.
     */
    private fun onReturnedToFront() {
        val p = active ?: return
        if (p.mode != "single") return
        when {
            returns.held -> hold(p)
            // Not installed (or disabled): nothing was opened, so nothing closed or crashed; the grid says why.
            !pinnedLaunchable(p) -> showGrid(p)
            // The app wasn't open (it was missing, and is back): open it, without counting a return.
            screen !is KioskScreen.Splash -> launchPinned(p)
            else -> when (returns.onReturned()) {
                KioskReturnPolicy.Action.HOLD -> hold(p)
                KioskReturnPolicy.Action.REOPEN -> launchPinned(p)
            }
        }
    }

    /** The single-app kiosk's app is installed and enabled (PackageManager has a launch intent for it). */
    private fun pinnedLaunchable(p: KioskApplyPayload): Boolean =
        p.pinPackage?.let(::launchIntentFor) != null

    /**
     * How [pkg] is opened: its launcher entry, else its Leanback (TV) entry. The kiosk configuration names no
     * explicit activity, so these are all there is to try.
     */
    private fun launchIntentFor(pkg: String): Intent? =
        packageManager.getLaunchIntentForPackage(pkg) ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)

    private fun applyState(p: KioskApplyPayload?) {
        // Taken first, whatever is shown next: a dialog from before a recreation comes back on this state or never.
        val restored = dialogs.takeRestored()
        val previous = active
        active = p
        // On every kiosk state (each HomeResumeGate step, so a failed image is retried when HOME comes back): the
        // image only repaints the backdrop, it never reopens the pinned app or clears a hold.
        showBackdrop(p)
        // The kiosk an exit dialog was opened for is gone or replaced (a remote exit or re-entry, also one that
        // arrived while the launcher was stopped): close it rather than leave it on a kiosk it wasn't for.
        if (previous != null && p?.sameKioskAs(previous) != true) dialogs.dismiss()
        // Only the look changed (colours, icon size, image, fit, focal point): redrawn in place, below; the kiosk
        // itself is unchanged, so nothing is entered, pinned or opened again for it.
        val restyled = restyledOnly(previous, p)
        when (resumeGate.onDelivery(KioskDelivery.of(previous, p))) {
            HomeResumeGate.Step.IDLE -> {
                dialogs.dismiss()
                // The kiosk ended: a crash-loop hold ends with it, so a kiosk entered later starts afresh.
                returns.retry()
                holding = false
                stopLockTaskSafely()
                render(KioskScreen.Idle)
            }
            // The same kiosk again, because HOME came back to the front: keep the pin; the return is handled once,
            // here or in onResume, whichever came second.
            HomeResumeGate.Step.RETURN -> {
                startLockTaskSafely()
                onReturnedToFront()
            }
            HomeResumeGate.Step.NONE, HomeResumeGate.Step.WAIT -> if (p != null) startLockTaskSafely()
            HomeResumeGate.Step.APPLY -> {
                val kiosk = p ?: return
                // Pin first: a held device (a loop that tripped just before a reboot) is still in kiosk.
                startLockTaskSafely()
                val changed = previous != null && !kiosk.sameKioskAs(previous)
                val held = returns.onApplied(changed) == KioskReturnPolicy.Action.HOLD
                val shown = if (held) hold(kiosk) else show(kiosk)
                KioskExitDialogs.reopenOn(shown, restored)?.let { reopenExitDialog(it, kiosk) }
            }
        }
        if (restyled && p != null) restyle(p)
    }

    private fun imageUrl(p: KioskApplyPayload?): String? = p?.theme?.backgroundImage?.url

    private fun restyledOnly(previous: KioskApplyPayload?, p: KioskApplyPayload?): Boolean =
        previous != null && p != null && p != previous && p.sameKioskAs(previous)

    /** The current screen again in [p]'s look; no app is opened and no hold is cleared. */
    private fun restyle(p: KioskApplyPayload) {
        when (val now = screen) {
            is KioskScreen.Hold -> render(KioskScreen.Hold(palette(p), exitFor(p)))
            is KioskScreen.Splash -> render(now.copy(palette = palette(p)))
            is KioskScreen.Grid -> showGrid(p)
            KioskScreen.Idle -> Unit
        }
    }

    /**
     * Reopen the exit dialog [tag] after a recreation (rotation no longer causes one, but a locale change or process
     * death still can) or a handled config change. Rebuilt from the current kiosk and [pinGate], never from saved
     * state: the PIN check and the lockout stay where they are ([ExitPinGate] persists them), an expired lockout isn't
     * shown, and a kiosk whose exit PIN was removed meanwhile gets no PIN prompt. [typed] is the PIN being typed when
     * an in-place rebuild (a config change) replaced the dialog; it only ever passes through memory.
     */
    private fun reopenExitDialog(tag: String, p: KioskApplyPayload, typed: CharSequence? = null) {
        when (tag) {
            KioskExitDialogs.PIN -> promptExit(p, typed)
            KioskExitDialogs.LOCKED -> pinGate.remainingLockMs()?.let(::showExitLocked)
            KioskExitDialogs.UNAVAILABLE -> showExitUnavailable()
        }
    }

    private fun show(p: KioskApplyPayload): LauncherScreen {
        holding = false
        return if (p.mode == "single" && p.pinPackage != null) launchPinned(p) else showGrid(p)
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload): LauncherScreen {
        val pkg = p.pinPackage
        // Resolved here, not in the background: the app opens in the same step that decided to open it.
        val intent = pkg?.let(::launchIntentFor)
        if (pkg == null || intent == null) {
            return showGrid(p) // unknown package → fall back to the grid
        }
        val splash = KioskScreen.Splash(palette(p), exitFor(p), pkg, apps.cachedLabel(pkg))
        render(splash)
        runCatching { startActivity(intent) }
        if (splash.appName == null) {
            load { apps.label(pkg)?.let { name -> if (screen == splash) render(splash.copy(appName = name)) } }
        }
        return LauncherScreen.PINNED_APP
    }

    /** The app grid: drawn at once (with the apps already loaded for this allowlist), then refreshed off Main. */
    private fun showGrid(p: KioskApplyPayload): LauncherScreen {
        val packages = p.allowedPackages.distinct()
        val known = (screen as? KioskScreen.Grid)?.takeIf { it.packages == packages }?.apps
        // Single-app kiosk whose app can't be opened: the grid says so (and its other apps stay usable).
        val missing = p.pinPackage?.takeIf { p.mode == "single" && !pinnedLaunchable(p) }
        val grid = KioskScreen.Grid(palette(p), exitFor(p), IconSize.of(p.theme.iconSize), packages, known, missing)
        render(grid)
        load {
            val loaded = apps.load(packages)
            val now = screen
            if (now is KioskScreen.Grid && now.packages == packages) render(now.copy(apps = loaded))
        }
        return LauncherScreen.GRID
    }

    /** The app closed too often: stay in kiosk on the hold screen, without reopening it. */
    private fun hold(p: KioskApplyPayload): LauncherScreen {
        if (!holding) events.record("kioskCrashLoop", "kiosk app closed repeatedly; kiosk stays on, app not reopened")
        holding = true
        render(KioskScreen.Hold(palette(p), exitFor(p)))
        return LauncherScreen.HOLD
    }

    private fun showBackdrop(p: KioskApplyPayload?) {
        val image = p?.theme?.backgroundImage ?: return backdrop.clear()
        val metrics = resources.displayMetrics
        renderer.placement = Placement.of(image)
        backdrop.want(image, maxOf(metrics.widthPixels, metrics.heightPixels), palette(p).background)
    }

    private fun render(next: KioskScreen) {
        if (next::class != screen::class) loading?.cancel()
        screen = next
        renderer.render(next)
    }

    private fun load(block: suspend () -> Unit) {
        loading?.cancel()
        loading = lifecycleScope.launch { block() }
    }

    private fun palette(p: KioskApplyPayload): KioskPalette =
        KioskPalette.of(KioskPalette.parse(p.theme.backgroundColor), KioskPalette.parse(p.theme.textColor))

    private fun exitFor(p: KioskApplyPayload): KioskExit =
        KioskExit.of(p.exitMode, KioskExitDialogs.onDevicePin(p) != null)

    /** What the launcher's views can do. The exit goes through [promptExit] and the PIN check, as always. */
    private inner class Actions : KioskRenderer.Actions {
        override fun openApp(packageName: String) {
            runCatching { launchIntentFor(packageName)?.let { startActivity(it) } }
        }

        override fun openAgain() {
            val p = active ?: return
            returns.retry()
            show(p)
        }

        override fun requestExit() {
            active?.let(::promptExit)
        }
    }

    /** Which boot this is: a saved crash-loop hold belongs to the boot it started in. */
    private fun bootCount(): Long = Boot.count(this)

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

    private fun promptExit(p: KioskApplyPayload, typed: CharSequence? = null) {
        // No on-device exit (no PIN, or exit mode "remote"; the affordance isn't shown either): kiosk then ends only
        // from the console. A prompt restored or rebuilt onto such a kiosk doesn't open either.
        if (KioskExitDialogs.onDevicePin(p) == null) return
        pinGate.remainingLockMs()?.let { showExitLocked(it); return }
        val prompt = ExitPinDialog.create(this, typed, ::onPinEntered)
        // "N more wrong PINs lock the exit" comes from the gate's saved count, so a rebuilt or reopened prompt says
        // it too, not only the one the last wrong PIN was typed into.
        pinGate.attemptsLeft()?.let { prompt.render(ExitPinUiState.triesLeft(it)) }
        dialogs.show(KioskExitDialogs.PIN, prompt.dialog)
    }

    /**
     * The one place a PIN is checked, and the only caller of [doExit]. It checks against the kiosk applied now, not
     * the one the prompt was opened for: a PIN changed or removed meanwhile, or an exit switched to "remote" (console
     * only), must not still open the exit. No on-device PIN: the gate answers Unavailable and counts no try.
     * [dialog] only shows the result: it closes on Open and Locked, and shows Wrong and Unavailable under the field.
     */
    private fun onPinEntered(entered: String, dialog: ExitPinDialog) {
        val kiosk = active
        val pin = KioskExitDialogs.onDevicePin(kiosk)
        val outcome = pinGate.attempt(entered, pin)
        dialog.render(ExitPinUiState.of(outcome))
        when (outcome) {
            // Open implies a PIN, so a kiosk: the exit is bound to it.
            ExitPinGate.Outcome.Open -> doExit(kiosk ?: return)
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

    /**
     * "Exit locked, try again in N": N counts down while it is shown, and the dialog closes when the lockout ends (the
     * exit can be tried again) or, for a long lockout, after the PIN prompt's idle time, so it never stays over the
     * kiosk for good. Both from the gate's own clock, so a rebuilt or restored dialog says the same.
     */
    private fun showExitLocked(remainingMs: Long) {
        fun message(ms: Long) = getString(R.string.kiosk_exit_locked_message, ExitPinGate.describe(ms))
        val spec = MdmDialog.Spec(
            title = getString(R.string.kiosk_exit_locked_title),
            message = message(remainingMs),
            confirm = getString(R.string.action_ok),
            icon = R.drawable.ic_v2_lock,
        )
        val dialog = MdmDialog.create(this, spec) { it.dismiss() }
        val closeAt = SystemClock.elapsedRealtime() + ExitPinDialog.idleTimeoutMs(this)
        dialog.everyWhileShown(LOCKED_TICK_MS) {
            val left = pinGate.remainingLockMs()
            if (left == null || SystemClock.elapsedRealtime() >= closeAt) {
                dialog.dismiss()
            } else {
                dialog.updateMessage(message(left))
            }
        }
        dialogs.show(KioskExitDialogs.LOCKED, dialog)
    }

    /**
     * The right PIN, but the exit couldn't start (its journal entry couldn't be written: a full disk). The PIN prompt
     * has already closed, so this says why nothing happened. Like Exit locked, it closes after the PIN prompt's idle
     * time, so it never stays over the kiosk for good.
     */
    private fun showExitUnavailable() {
        val spec = MdmDialog.Spec(
            title = getString(R.string.kiosk_exit_title),
            message = getString(R.string.kiosk_exit_unavailable),
            confirm = getString(R.string.action_ok),
            icon = R.drawable.ic_v2_warning,
        )
        val dialog = MdmDialog.create(this, spec) { it.dismiss() }
        dialog.everyWhileShown(ExitPinDialog.idleTimeoutMs(this)) { dialog.dismiss() }
        dialogs.show(KioskExitDialogs.UNAVAILABLE, dialog)
    }

    /**
     * Exits [kiosk], the kiosk the PIN was checked against, and only it: the applier refuses inside its lock when
     * another kiosk was entered first (a `kiosk.enter` landing as the PIN was submitted), or when the exit's journal
     * entry can't be written (a full disk), and then nothing here changes either: lock task is left and the exit
     * recorded inside the applier's lock, only once the exit is going ahead, and only then does the launcher finish.
     * Refused for a full disk, the launcher says so ([showExitUnavailable]); refused for another kiosk, it says
     * nothing: that kiosk's delivery redraws the launcher.
     */
    private fun doExit(kiosk: KioskApplyPayload) {
        // Same exit as the remote kiosk.exit: releases the lock-task policy, the volume lock and the HOME claim,
        // then hands off to the OEM launcher. Forced: the admin PIN was entered, the user must get out.
        // NonCancellable: the launcher may be destroyed meanwhile (a recreate, the system); the exit still finishes,
        // or it would stop between its steps. A process death mid-exit is finished at the next start (ExitJournal).
        lifecycleScope.launch {
            // Null once the exit ran; otherwise why it was refused.
            val refused = withContext(NonCancellable) {
                var started = false
                val result = applier.exit(handBackHome = true, force = true, expected = kiosk) {
                    started = true
                    runCatching { if (isFinishing.not()) stopLockTask() }
                    // Lock task is already left: a failed log write must not throw out between the exit's steps
                    // (the next start would settle the half-exited kiosk back in kiosk). The exit goes on without it.
                    runCatching { events.record("kioskExit", "exited on-device") }
                        .onFailure { Log.w(TAG, "recording the on-device kiosk exit failed", it) }
                }
                // Refused before it started: still in this kiosk (or the one that replaced it), still pinned.
                if (!started) return@withContext result
                // The launcher has already left lock task; publish that state without waiting for the periodic
                // check-in.
                CheckInWorker.scheduleAfterCurrent(applicationContext)
                null
            }
            when {
                refused == null -> finish()
                // Superseded: the new kiosk's delivery redraws the launcher (and closes the prompt) as usual.
                refused == KioskResult.Failed(KioskApplier.EXIT_SUPERSEDED) -> Unit
                // Journal not writable (logged by the applier): the device stays in this kiosk, on this launcher.
                else -> showExitUnavailable()
            }
        }
    }

    private companion object {
        const val TAG = "KioskLauncher"

        /** How often the "Exit locked" countdown is redrawn (its finest unit is a second). */
        const val LOCKED_TICK_MS = 1_000L
    }
}
