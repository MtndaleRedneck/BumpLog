package com.bumplog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val BUG_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSeMQ-YmqqkIgqvvp3tb9Yj0BG-LtE2BUXoV1IBtsrl8MidsSA/viewform"
private const val MAX_TEXT = 3000
private const val MAX_ENCODED = 4500

/** The only things a bug report carries besides what the user typed. No location, spot, address or damage data. */
data class BugContext(val appVersion: String, val androidVersion: String, val phoneModel: String)

object BugReport {
    /** Trims the text so the encoded link stays well under the roughly 8 KB web servers accept. The preview shows this same trimmed text. */
    fun clean(text: String): String {
        var t = text.trim().take(MAX_TEXT)
        while (t.isNotEmpty() && enc(t).length > MAX_ENCODED) t = t.dropLast(50).trimEnd()
        return t
    }

    /** Exactly what is sent, shown to the user before they tap. */
    fun preview(text: String, c: BugContext): String =
        "Message:\n${clean(text).ifEmpty { "(nothing written yet)" }}\n\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}"

    fun githubUrl(text: String, c: BugContext): String {
        val t = clean(text)
        val title = "Feedback: " + t.lineSequence().firstOrNull().orEmpty().take(60)
        val body = "$t\n\n---\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}"
        return "$REPO_ISSUES/new?title=${enc(title)}&body=${enc(body)}"
    }

    fun formUrl(text: String, c: BugContext): String =
        "$BUG_FORM_URL?usp=pp_url&entry.2115677078=${enc(clean(text))}&entry.2070006552=${enc(c.phoneModel)}" +
            "&entry.1192035271=${enc(c.androidVersion)}&entry.1144301841=${enc(c.appVersion)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun context() = BugContext(BuildConfig.VERSION_NAME, "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.joinToString(" "))
}

@Composable
fun BugReportScreen(done: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val c = remember { BugReport.context() }
    fun open(url: String) = ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Feedback")
        Card {
            Muted("Tell us what is on your mind: a bug, an idea, or general feedback. Reports may become public issues on GitHub, so leave out personal details.", 13)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(text, { text = it.take(MAX_TEXT) }, label = { Text("Your message") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.card2, focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent))
        }
        Card {
            Txt("What will be sent", 16, FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Muted(BugReport.preview(text, c), 13)
            Spacer(Modifier.height(6.dp))
            Muted("No location, saved spots, addresses, or damage records are included. Nothing is sent until you tap a button below, and then it opens in your browser for you to review and submit.", 12)
        }
        Card {
            Txt("GitHub issue", 16, FontWeight.Bold)
            Muted("Public and easiest to follow up.", 12)
            Spacer(Modifier.height(8.dp))
            SmallButton("Open GitHub issue", Modifier.fillMaxWidth()) { if (text.isBlank()) toast(ctx, "Write your message first.") else open(BugReport.githubUrl(text, c)) }
        }
        Card {
            Txt("Private form", 16, FontWeight.Bold)
            Muted("No name needed. A reply email is optional.", 12)
            Spacer(Modifier.height(8.dp))
            SmallButton("Open private form", Modifier.fillMaxWidth()) { if (text.isBlank()) toast(ctx, "Write your message first.") else open(BugReport.formUrl(text, c)) }
        }
        SmallButton("Done", Modifier.fillMaxWidth(), onClick = done)
    }
}
