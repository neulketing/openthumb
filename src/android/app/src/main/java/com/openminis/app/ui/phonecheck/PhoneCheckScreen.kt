package com.openminis.app.ui.phonecheck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.phonecheck.PhoneCheck
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * [onContinue] is set on first run, where this screen is the front door; from
 * Settings it is null and [onBack] takes over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCheckScreen(
    hasModel: () -> Boolean,
    onBack: (() -> Unit)?,
    onContinue: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    onAddModel: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val findings = remember { PhoneCheck.judge(PhoneCheck.read(context), LocalDate.now()) }
    val score = remember(findings) { PhoneCheck.score(findings) }
    var starting by remember { mutableStateOf<String?>(null) }
    val fixLabel = stringResource(R.string.phone_check_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fixLabel, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (onContinue != null) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.phone_check_continue))
                    }
                    if (onSkip != null) {
                        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.phone_check_skip))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.phone_check_score_label), style = MaterialTheme.typography.bodyMedium)
                    Text("$score", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = colorFor(score))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.phone_check_read_here),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(findings.sortedByDescending { it.level.ordinal }, key = { it.id }) { f ->
                FindingRow(
                    finding = f,
                    busy = starting == f.id,
                    onFix = {
                        if (!hasModel()) {
                            onAddModel()
                        } else {
                            starting = f.id
                            scope.launch {
                                val session = PhoneCheck.fix(context.applicationContext, f, fixLabel)
                                starting = null
                                session?.let(onOpenChat)
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun FindingRow(finding: PhoneCheck.Finding, busy: Boolean, onFix: () -> Unit) {
    val args = finding.args.toTypedArray()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colorFor(finding.level)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(stringResource(finding.title, *args), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                stringResource(finding.detail, *args),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (finding.fixPrompt != null && finding.level != PhoneCheck.Level.OK) {
            TextButton(onClick = onFix, enabled = !busy) {
                Text(stringResource(R.string.phone_check_fix))
            }
        }
    }
}

private fun colorFor(level: PhoneCheck.Level): Color = when (level) {
    PhoneCheck.Level.OK -> Color(0xFF34C759)
    PhoneCheck.Level.WARN -> Color(0xFFFF9500)
    PhoneCheck.Level.BAD -> Color(0xFFFF3B30)
}

private fun colorFor(score: Int): Color = when {
    score >= 85 -> Color(0xFF34C759)
    score >= 60 -> Color(0xFFFF9500)
    else -> Color(0xFFFF3B30)
}
