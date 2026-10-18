package com.touristsafety.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.touristsafety.app.diagnostics.CrashLog

/**
 * Surfaces the stack trace [CrashLog] recorded, the next time the app is opened.
 *
 * It exists because "the app closed by itself" cannot be investigated without the trace and a phone
 * offers no logcat. The dialog is impossible to miss, keeps the trace selectable, and can copy it in one
 * tap. Nothing is ever uploaded — the user decides what to do with the text.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }

    val text = report ?: return

    AlertDialog(
        // Not dismissable by tapping away: an unnoticed crash report helps nobody.
        onDismissRequest = { },
        title = { Text("Last run ended unexpectedly") },
        text = {
            Column {
                Text(
                    text = "This error was recorded after the app stopped by itself. Copy it and send " +
                        "it on — on a phone there are no logs to hand over, so this text is the only " +
                        "way to find out what happened.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Tourist Safety crash report", text),
                    )
                    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    CrashLog.clear(context)
                    report = null
                },
            ) {
                Text("Dismiss")
            }
        },
    )
}
