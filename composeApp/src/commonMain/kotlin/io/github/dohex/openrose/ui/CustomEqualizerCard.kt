package io.github.dohex.openrose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
internal fun CustomEqualizerCard(
    state: ControlState,
    device: DeviceState,
    draft: List<Int>?,
    onDraftChange: (List<Int>) -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.equalizer?.custom ?: return
    if (DeviceSetting.CUSTOM_EQ !in device.capabilities.writableSettings) return
    if (capabilities.bandsHz.isEmpty() || capabilities.minGain > capabilities.maxGain) return

    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    val confirmed = (device.knownValue(DeviceField.CUSTOM_EQ) as? FieldValue.IntegerList)?.values
    val confirmedValid = confirmed != null && confirmed.size == capabilities.bandsHz.size &&
        confirmed.all { it in capabilities.minGain..capabilities.maxGain }
    val draftValid = draft != null && draft.size == capabilities.bandsHz.size &&
        draft.all { it in capabilities.minGain..capabilities.maxGain }
    val bandCount = capabilities.bandsHz.size
    val title = if (bandCount == 10) "自定义十段 EQ" else "自定义 EQ · $bandCount 段"

    AppGroup(title = title) {
        AppInfoRow("设备均衡器模式", confirmedSettingStatus(DeviceField.EQ, device))
        AppInfoRow("设备曲线", confirmedSettingStatus(DeviceField.CUSTOM_EQ, device))
        AppDivider()

        if (draft != null) {
            AppButton(
                text = "重置",
                onClick = { onDraftChange(List(bandCount) { 0.coerceIn(capabilities.minGain, capabilities.maxGain) }) },
                enabled = enabled,
                kind = AppButtonKind.Secondary,
            )
        }

        val confirmedCurve = confirmed?.takeIf {
            it.size == bandCount && it.all { gain -> gain in capabilities.minGain..capabilities.maxGain }
        }
        if (confirmedValid && confirmedCurve != null) {
            AppButton(
                text = if (draft == null) "复制设备曲线到草稿" else "恢复设备曲线到草稿",
                onClick = { onDraftChange(confirmedCurve.toList()) },
                enabled = enabled,
                kind = AppButtonKind.Secondary,
            )
        }

        if (draft == null) {
            AppButton(
                text = "创建平直曲线草稿",
                onClick = { onDraftChange(List(bandCount) { 0.coerceIn(capabilities.minGain, capabilities.maxGain) }) },
                enabled = enabled,
                kind = AppButtonKind.Primary,
            )
        } else if (!draftValid) {
            AppText("草稿与当前型号的频段数或增益范围不符，暂不能应用。", role = AppTextRole.Summary)
        } else {
            AppText("曲线预览 · ${capabilities.minGain}～${capabilities.maxGain} dB", role = AppTextRole.Summary)
            EqualizerCurve(
                values = draft,
                bandsHz = capabilities.bandsHz,
                minGain = capabilities.minGain,
                maxGain = capabilities.maxGain,
                modifier = Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            )

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 600.dp) 2 else 1
                val rows = capabilities.bandsHz.indices.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEach { rowIndices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowIndices.forEach { index ->
                                val hz = capabilities.bandsHz[index]
                                val frequency = equalizerFrequencyLabel(hz)
                                val value = draft[index]
                                Box(Modifier.weight(1f)) {
                                    AppSliderRow(
                                        label = frequency,
                                        valueText = "${if (value > 0) "+" else ""}$value dB",
                                        value = value.toFloat(),
                                        range = capabilities.minGain.toFloat()..capabilities.maxGain.toFloat(),
                                        steps = (capabilities.maxGain - capabilities.minGain - 1).coerceAtLeast(0),
                                        enabled = enabled,
                                        onChange = { changed ->
                                            val updated = draft.toMutableList()
                                            updated[index] = changed.roundToInt().coerceIn(capabilities.minGain, capabilities.maxGain)
                                            onDraftChange(updated)
                                        },
                                    )
                                }
                            }
                            if (rowIndices.size < columns) repeat(columns - rowIndices.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            AppButton(
                text = "应用草稿到耳机",
                onClick = { onSet(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(draft.toList())) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                kind = AppButtonKind.Primary,
            )
        }
    }
}

@Composable
private fun EqualizerCurve(
    values: List<Int>,
    bandsHz: List<Int>,
    minGain: Int,
    maxGain: Int,
    modifier: Modifier = Modifier,
) {
    val primary = appColor(AppColorRole.Primary)
    val divider = appColor(AppColorRole.Divider)
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val stroke = 1.dp.toPx()
        for (line in 0..4) {
            val y = height * line / 4f
            drawLine(divider.copy(alpha = 0.3f), Offset(0f, y), Offset(width, y), stroke)
        }
        val range = (maxGain - minGain).coerceAtLeast(1).toFloat()
        val zeroY = height * (maxGain.toFloat() / range)
        drawLine(divider.copy(alpha = 0.55f), Offset(0f, zeroY), Offset(width, zeroY), stroke)

        val minFrequency = bandsHz.first().coerceAtLeast(1).toFloat()
        val maxFrequency = bandsHz.last().coerceAtLeast(1).toFloat()
        val minLog = ln(minFrequency)
        val logRange = (ln(maxFrequency) - minLog).takeIf { it > 0f } ?: 1f
        val points = values.indices.map { index ->
            val frequency = bandsHz[index].coerceAtLeast(1).toFloat()
            val x = if (values.size == 1) width / 2f else width * ((ln(frequency) - minLog) / logRange)
            val normalizedGain = ((values[index] - minGain) / range).coerceIn(0f, 1f)
            Offset(x, height * (1f - normalizedGain))
        }
        if (points.isNotEmpty()) {
            val curve = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val area = Path().apply {
                moveTo(points.first().x, zeroY.coerceIn(0f, height))
                lineTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, zeroY.coerceIn(0f, height))
                close()
            }
            drawPath(area, primary.copy(alpha = 0.1f))
            drawPath(curve, primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEach { point -> drawCircle(primary, radius = 2.5.dp.toPx(), center = point) }
        }
    }
}

private fun equalizerFrequencyLabel(hz: Int): String = when {
    hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
    hz >= 1000 -> "${hz / 1000.0} kHz"
    else -> "$hz Hz"
}
