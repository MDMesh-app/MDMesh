package com.mdmesh.agent.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.mdmesh.agent.R
import com.mdmesh.kiosk.ExitPinGate

/**
 * What the Exit kiosk dialog shows after a PIN attempt. UI only: [ExitPinGate] decides, the launcher's
 * `onPinEntered` acts on the outcome (exit, lockout report, Exit locked dialog), and this only says whether the
 * PIN dialog closes or stays open with an error under the field.
 */
sealed interface ExitPinUiState {

    /** Right PIN (the launcher exits) or locked (the launcher shows Exit locked): the PIN dialog closes. */
    data object Close : ExitPinUiState

    /** The dialog stays open with [message] under the field; [clearField] empties it for the next try. */
    data class Error(val message: UiText, val clearField: Boolean) : ExitPinUiState

    companion object {
        /** The tries-left warning, shown again from the gate's state when the prompt is (re)opened. */
        fun triesLeft(attemptsLeft: Int) =
            Error(UiText.Plural(R.plurals.kiosk_exit_wrong_pin, attemptsLeft), clearField = false)

        fun of(outcome: ExitPinGate.Outcome): ExitPinUiState = when (outcome) {
            ExitPinGate.Outcome.Open -> Close
            is ExitPinGate.Outcome.Locked -> Close
            is ExitPinGate.Outcome.Wrong -> triesLeft(outcome.attemptsLeft).copy(clearField = true)
            // Not counted and not checked: keep what was typed, so trying again is one tap.
            ExitPinGate.Outcome.Unavailable -> Error(UiText.Res(R.string.kiosk_exit_unavailable), clearField = false)
        }
    }
}

/** A string resource, resolved only when shown, so the mapping above stays testable on the JVM. */
sealed interface UiText {
    fun resolve(res: Resources): String

    data class Res(@StringRes val id: Int) : UiText {
        override fun resolve(res: Resources): String = res.getString(id)
    }

    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText {
        override fun resolve(res: Resources): String = res.getQuantityString(id, count, count)
    }
}
