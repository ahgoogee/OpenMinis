package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.ContextCompressionPrefs
import kotlin.math.roundToInt

/**
 * [T-ctx-compression-config] Settings > Agent Runtime > Context Compaction.
 *
 * The soft line: a line below the hard threshold whose crossing appends a
 * runtime reminder inviting the model to compact at a break it picks. The
 * `compact_context` tool is what lets it act on that.
 *
 * The hard threshold is not configurable: it is the backstop that keeps a
 * session from overflowing.
 */
@Composable
fun ContextCompactionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var softEnabled by remember {
        mutableStateOf(ContextCompressionPrefs.isSoftCompactEnabled())
    }
    var softPercent by remember {
        mutableIntStateOf(ContextCompressionPrefs.softCompactPercent())
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_context_compaction),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.ctx_compaction_soft_header),
            footer = stringResource(R.string.ctx_compaction_soft_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.ctx_compaction_soft_switch),
                subtitle = stringResource(R.string.ctx_compaction_soft_switch_subtitle),
                checked = softEnabled,
                onCheckedChange = {
                    softEnabled = it
                    ContextCompressionPrefs.setSoftCompactEnabled(context, it)
                },
                icon = Icons.Outlined.Tune,
                iconColor = Color(0xFF32ADE6),
                showDivider = softEnabled,
            )
            if (softEnabled) {
                SoftLimitSlider(
                    percent = softPercent,
                    onValueChange = {
                        softPercent = it
                        ContextCompressionPrefs.setSoftCompactPercent(context, it)
                    },
                )
            }
        }
    }
}

/**
 * The soft line as a percentage of the model's context window. Expressed as a
 * share rather than a token count because the setting is global while the
 * window is per-model — 80K tokens means something very different on an 8K
 * model and a 1M one.
 */
@Composable
private fun SoftLimitSlider(percent: Int, onValueChange: (Int) -> Unit) {
    val min = ContextCompressionPrefs.MIN_SOFT_PERCENT
    val max = ContextCompressionPrefs.MAX_SOFT_PERCENT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.ctx_compaction_soft_line_label, percent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = percent.toFloat(),
            onValueChange = { onValueChange((it / 5f).roundToInt() * 5) },
            valueRange = min.toFloat()..max.toFloat(),
            // 5% stops across the range: (max - min) / 5 - 1 interior steps.
            steps = ((max - min) / 5) - 1,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$min%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$max%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 0.dp),
            )
        }
    }
}
