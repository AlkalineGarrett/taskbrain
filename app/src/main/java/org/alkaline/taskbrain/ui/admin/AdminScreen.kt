package org.alkaline.taskbrain.ui.admin

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.data.FirestoreUsage
import org.alkaline.taskbrain.ui.components.ActionButton
import org.alkaline.taskbrain.ui.components.ActionButtonBar
import android.util.Log

@Composable
fun AdminScreen() {
    var usageReport by remember { mutableStateOf<String?>(null) }

    usageReport?.let { report ->
        FirestoreUsageDialog(
            report = report,
            onClose = { usageReport = null },
            onReset = {
                FirestoreUsage.reset()
                usageReport = FirestoreUsage.getReport()
            },
        )
    }

    ActionButtonBar {
        ActionButton(
            text = stringResource(R.string.action_firestore_usage),
            icon = Icons.Filled.Assessment,
            onClick = {
                val report = FirestoreUsage.getReport()
                Log.i("FirestoreUsage", "\n$report")
                usageReport = report
            },
        )
    }
}

@Composable
private fun FirestoreUsageDialog(
    report: String,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.firestore_usage_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.firestore_usage_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.firestore_usage_reset))
            }
        },
    )
}
