package com.bumplog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Brings BumpLog to the front with the log form when the user taps "Log it" on a major-bump alert.
 * Reached from the alert notification button or the overlay button, both of which are user taps, so Android allows the launch. */
class LogItActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val id = intent.getLongExtra("spot", 0)
        val spot = Db.get(this).spot(id)
        Notifier.cancel(this, 1000 + id.toInt())
        Notifier.removeOverlay(this)
        if (spot == null) { finish(); return }
        setContent {
            val live by Live.state.collectAsState()
            val theme = remember(live.tick) { Prefs.theme }
            val accent = remember(live.tick) { Prefs.accent }
            BumpLogTheme(theme, accent) { LogForm(spot) }
        }
    }

    @Composable
    private fun LogForm(spot: Spot) {
        val p = LocalPal.current
        val guess = if (spot.typeUser) spot.type else spot.type
        val draft = remember { Prefs.logDraft(spot.id).split("|") }
        val hasDraft = draft.size == 4
        var type by remember { mutableStateOf(if (hasDraft) DefectType.parse(draft[0]) else guess) }
        var confirmed by remember { mutableStateOf(if (hasDraft) draft[1] == "1" else spot.typeUser) }
        var severity by remember { mutableStateOf(if (hasDraft) draft[2] else if (spot.typeUser) spot.severity else "MAJOR") }
        var fileTrip by remember { mutableStateOf(if (hasDraft) draft[3] == "1" else true) }
        // Autosave the half-filled form so it survives leaving the screen. There is no text field, so the keyboard never opens by itself.
        LaunchedEffect(type, confirmed, severity, fileTrip) { Prefs.setLogDraft(spot.id, "${type.name}|${if (confirmed) 1 else 0}|$severity|${if (fileTrip) 1 else 0}") }
        Box(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(p.card).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Log this bump", 22, FontWeight.Bold)
                Muted("%.2fg near ${spot.title}".format(spot.maxG), 14)
                Dropdown("What is it?", type.name, DefectType.values().map { it.name to (it.label + if (!confirmed && it == guess) " (guess)" else "") }) {
                    type = DefectType.valueOf(it); confirmed = true
                }
                if (!confirmed) {
                    Muted("BumpLog guessed from your speed and how hard the hit was. It could be wrong.", 13)
                    SmallButton("Yes, it is: ${guess.label}", Modifier.fillMaxWidth().heightIn(min = 56.dp), outline = false) { confirmed = true }
                } else Muted("Type confirmed.", 13)
                Txt("How bad?", 16, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MAJOR" to "Major", "MINOR" to "Minor").forEach { (k, label) ->
                        val on = severity == k
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) p.accent else p.card2)
                            .clickable { severity = k }.heightIn(min = 56.dp).padding(14.dp), contentAlignment = Alignment.Center) {
                            Txt(label, 16, FontWeight.Bold, if (on) p.onAccent else p.text)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { fileTrip = !fileTrip }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fileTrip, { fileTrip = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent))
                    Txt("File report at end of trip", 16)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Cancel", Modifier.weight(1f).heightIn(min = 56.dp)) { Prefs.setLogDraft(spot.id, ""); finish() }
                    BigButton("Save", modifier = Modifier.weight(1f).heightIn(min = 56.dp), enabled = confirmed) {
                        Db.get(this@LogItActivity).logSpot(spot.id, type, severity, fileTrip); Prefs.setLogDraft(spot.id, "")
                        Live.bump()
                        android.widget.Toast.makeText(this@LogItActivity, ConfirmationNotice.message(fileTrip), android.widget.Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                if (!confirmed) Muted("Confirm the type to save.", 12)
            }
        }
    }
}
