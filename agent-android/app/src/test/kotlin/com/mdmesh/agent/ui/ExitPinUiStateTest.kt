package com.mdmesh.agent.ui

import com.mdmesh.agent.R
import com.mdmesh.agent.StringsXml
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.ExitPinGate.Outcome
import com.mdmesh.kiosk.InMemoryPinAttemptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Each ExitPinGate outcome maps to one Exit kiosk dialog state; the gate itself is tested in :kiosk. */
class ExitPinUiStateTest {

    @Test fun `right PIN closes the dialog`() {
        assertEquals(ExitPinUiState.Close, ExitPinUiState.of(Outcome.Open))
    }

    @Test fun `locked closes the dialog, started or not`() {
        assertEquals(ExitPinUiState.Close, ExitPinUiState.of(Outcome.Locked(30_000L, failures = 3, started = true)))
        assertEquals(ExitPinUiState.Close, ExitPinUiState.of(Outcome.Locked(10_000L, failures = 4, started = false)))
    }

    @Test fun `wrong PIN stays open, clears the field and says how many tries are left`() {
        for (left in 1..2) {
            assertEquals(
                ExitPinUiState.Error(UiText.Plural(R.plurals.kiosk_exit_wrong_pin, left), clearField = true),
                ExitPinUiState.of(Outcome.Wrong(attemptsLeft = left)),
            )
        }
    }

    @Test fun `unavailable stays open and keeps what was typed`() {
        assertEquals(
            ExitPinUiState.Error(UiText.Res(R.string.kiosk_exit_unavailable), clearField = false),
            ExitPinUiState.of(Outcome.Unavailable),
        )
        assertEquals("Exit isn't available right now. Try again.", StringsXml["kiosk_exit_unavailable"])
    }

    @Test fun `the gate's real outcomes walk the dialog from wrong to closed`() {
        val gate = ExitPinGate(InMemoryPinAttemptStore()) { 1_000L }
        val states = List(ExitPinGate.FREE_ATTEMPTS) { ExitPinUiState.of(gate.attempt("0000", "1234")) }
        assertEquals(
            listOf(
                ExitPinUiState.Error(UiText.Plural(R.plurals.kiosk_exit_wrong_pin, 2), clearField = true),
                ExitPinUiState.Error(UiText.Plural(R.plurals.kiosk_exit_wrong_pin, 1), clearField = true),
                ExitPinUiState.Close, // the third wrong PIN locks: Exit locked replaces the PIN dialog
            ),
            states,
        )
    }

    @Test fun `wrong PIN text names the tries left in both plural forms`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/values/strings.xml"))
        val plurals = doc.getElementsByTagName("plurals")
        val wrong = (0 until plurals.length).map { plurals.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "kiosk_exit_wrong_pin" }
        val items = wrong.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .filter { it.nodeName == "item" }
            .associate { it.attributes.getNamedItem("quantity").nodeValue to it.textContent }
        assertEquals(setOf("one", "other"), items.keys)
        assertEquals("Wrong PIN. %d more wrong PIN locks the exit.", items["one"])
        assertEquals("Wrong PIN. %d more wrong PINs lock the exit.", items["other"])
        assertTrue(items.values.all { it.contains("%d") })
    }
}
