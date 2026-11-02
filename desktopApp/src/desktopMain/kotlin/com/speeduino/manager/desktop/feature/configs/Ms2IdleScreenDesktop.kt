package com.speeduino.manager.desktop.feature.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ui.IniCurveEditor
import com.speeduino.manager.desktop.ui.parseCurveNumber
import io.ecucore.EcuLiveData
import io.ecucore.model.IniTemperature
import io.ecucore.model.Ms2IdleError
import io.ecucore.model.Ms2IdleInput
import io.ecucore.model.Ms2IdleValidator
import io.ecucore.model.Ms2WritableFields
import kotlinx.coroutines.launch
import kotlin.math.round

private val STEPPER_FIELDS = listOf(
    Ms2WritableFields.IAC_TSTEP to "ms2_idle_f_tstep",
    Ms2WritableFields.IAC_MINSTEP to "ms2_idle_f_minstep",
    Ms2WritableFields.IAC_START to "ms2_idle_f_start",
    Ms2WritableFields.IAC_FULL_OPEN to "ms2_idle_f_fullopen",
    Ms2WritableFields.IAC_CRANK_XT to "ms2_idle_f_crankxt",
    Ms2WritableFields.IDLE_HYST to "ms2_idle_f_hyst",
    Ms2WritableFields.PWMIDLE_OPEN_STEPS to "ms2_idle_f_open_steps",
    Ms2WritableFields.PWMIDLE_CLOSED_STEPS to "ms2_idle_f_closed_steps",
)

private val ALL_FIELDS = listOf(Ms2WritableFields.IDLE_CTL, Ms2WritableFields.IDLE_CTL_ALG, Ms2WritableFields.IDLE_CTL_HOME) +
    STEPPER_FIELDS.map { it.first }

private val ACTUATOR_LABELS = listOf(
    "ms2_idle_act_none", "ms2_idle_act_onoff", "ms2_idle_act_pwm", "ms2_idle_act_stepper",
)

private fun errorRes(code: Ms2IdleError.Code): String = when (code) {
    Ms2IdleError.Code.IAC_STEP_POINT_COUNT -> "ms2_idle_err_count_10"
    Ms2IdleError.Code.TARGET_RPM_POINT_COUNT, Ms2IdleError.Code.TARGET_TEMP_POINT_COUNT -> "ms2_idle_err_count_8"
    Ms2IdleError.Code.VALUE_INVALID -> "ms2_wue_invalid_number"
    Ms2IdleError.Code.IAC_START_NOT_ABOVE_FULL_OPEN -> "ms2_idle_err_start_full_open"
    Ms2IdleError.Code.IAC_STEP_ABOVE_LIMIT -> "ms2_idle_err_step_limit"
    Ms2IdleError.Code.OPEN_STEPS_ABOVE_LIMIT -> "ms2_idle_err_open_limit"
    Ms2IdleError.Code.CLOSED_STEPS_ABOVE_LIMIT -> "ms2_idle_err_closed_limit"
    Ms2IdleError.Code.CLOSED_NOT_BELOW_OPEN -> "ms2_idle_err_closed_below_open"
    Ms2IdleError.Code.TARGET_RPM_OUT_OF_RANGE -> "ms2_idle_err_rpm_range"
    Ms2IdleError.Code.IAC_TEMP_NOT_INCREASING, Ms2IdleError.Code.TARGET_TEMP_NOT_INCREASING -> "ms2_wue_err_temp_increasing"
}

/** Curva (Y) com eixo de temperatura em °F na ECU e °C na UI; mantém o bruto dos pontos não editados. */
private class TempCurve(val tempsF: List<Double>, val y: List<Double>) {
    val tempsC = IniTemperature.fahrenheitToCelsius(tempsF).map { round(it * 10.0) / 10.0 }
}

/** Marcha lenta com motor de passo da MS2: atuador, parâmetros do passo, curva de warmup e RPM alvo. */
@Composable
internal fun Ms2IdleScreen(
    isConnected: Boolean,
    backend: Ms2CurveBackend,
    liveData: EcuLiveData?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    var fields by remember { mutableStateOf<Map<String, Double>?>(null) }
    var texts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var warmup by remember { mutableStateOf<TempCurve?>(null) }
    var target by remember { mutableStateOf<TempCurve?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var pendingActuator by remember { mutableStateOf<Int?>(null) }
    val loadError = strings["ms2_idle_load_error"]
    val savedMsg = strings["ms2_idle_saved"]
    val saveError = strings["ms2_idle_save_error"]
    val invalidNumber = strings["ms2_wue_invalid_number"]
    val errTexts = Ms2IdleError.Code.entries.associateWith { strings[errorRes(it)] }
    fun errorText(e: Ms2IdleError) = errTexts.getValue(e.code).let { t -> if (t.contains("%1\$d")) t.format((e.index ?: 0) + 1) else t }

    LaunchedEffect(isConnected, reloadKey) {
        if (!isConnected) return@LaunchedEffect
        loading = true
        val f = backend.readFields(ALL_FIELDS)
        val st = backend.read(Ms2WritableFields.IAC_STEP_TABLE)
        val tt = backend.read(Ms2WritableFields.TEMP_TABLE)
        val rp = backend.read(Ms2WritableFields.PWMIDLE_TARGET_RPMS)
        val rt = backend.read(Ms2WritableFields.PWMIDLE_CLT_TEMPS)
        val failure = listOf(f.exceptionOrNull(), st.exceptionOrNull(), tt.exceptionOrNull(), rp.exceptionOrNull(), rt.exceptionOrNull())
            .firstNotNullOfOrNull { it }
        if (failure == null) {
            fields = f.getOrThrow()
            texts = STEPPER_FIELDS.associate { (n, _) ->
                n to Ms2WritableFields.byName(n)!!.let { spec -> String.format(java.util.Locale.getDefault(), "%.${spec.decimals}f", fields!![n]) }
            }
            warmup = TempCurve(tt.getOrThrow(), st.getOrThrow())
            target = TempCurve(rt.getOrThrow(), rp.getOrThrow())
        } else {
            message = loadError + " " + (failure.message ?: "")
        }
        loading = false
    }

    pendingActuator?.let { novo ->
        AlertDialog(
            onDismissRequest = { pendingActuator = null },
            title = { Text(strings["ms2_idle_confirm_title"]) },
            text = { Text(strings.format("ms2_idle_confirm_text", strings[ACTUATOR_LABELS[novo]])) },
            confirmButton = {
                TextButton(onClick = {
                    pendingActuator = null
                    scope.launch {
                        val r = backend.writeField(Ms2WritableFields.IDLE_CTL, novo.toDouble())
                        message = r.fold({ savedMsg }, { saveError + " " + (it.message ?: "") })
                        if (r.isSuccess) reloadKey++
                    }
                }) { Text(strings["ms2_idle_confirm_ok"]) }
            },
            dismissButton = { TextButton(onClick = { pendingActuator = null }) { Text(strings["ms2_idle_cancel"]) } },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings["ms2_idle_title"], style = MaterialTheme.typography.headlineSmall)
        Text(strings["ms2_idle_desc"], style = MaterialTheme.typography.bodyMedium)
        if (liveData != null) {
            Text(strings.format("ms2_idle_live", liveData.rpm, liveData.coolantTemp), style = MaterialTheme.typography.labelLarge)
        }
        val f = fields
        when {
            !isConnected -> Text(strings["ms2_idle_connect_first"])
            loading -> CircularProgressIndicator()
            f != null && warmup != null && target != null -> {
                val ctl = f.getValue(Ms2WritableFields.IDLE_CTL).toInt()
                val closedLoop = f[Ms2WritableFields.IDLE_CTL_ALG] == 1.0
                val homeOpen = f[Ms2WritableFields.IDLE_CTL_HOME] == 1.0
                val stepper = ctl == 3

                Text(strings["ms2_idle_sec_actuator"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACTUATOR_LABELS.forEachIndexed { i, res ->
                        FilterChip(selected = ctl == i, onClick = { if (ctl != i) pendingActuator = i }, label = { Text(strings[res]) })
                    }
                }
                if (ctl > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ms2_idle_alg_open", "ms2_idle_alg_closed").forEachIndexed { i, res ->
                            FilterChip(
                                selected = closedLoop == (i == 1),
                                onClick = {
                                    scope.launch {
                                        val r = backend.writeField(Ms2WritableFields.IDLE_CTL_ALG, i.toDouble())
                                        message = r.fold({ savedMsg }, { saveError + " " + (it.message ?: "") })
                                        if (r.isSuccess) reloadKey++
                                    }
                                },
                                label = { Text(strings[res]) },
                            )
                        }
                    }
                }

                if (!stepper) {
                    Text(strings["ms2_idle_not_stepper"], style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(strings["ms2_idle_sec_stepper"], style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ms2_idle_home_closed", "ms2_idle_home_open").forEachIndexed { i, res ->
                            FilterChip(
                                selected = homeOpen == (i == 1),
                                onClick = {
                                    scope.launch {
                                        val r = backend.writeField(Ms2WritableFields.IDLE_CTL_HOME, i.toDouble())
                                        message = r.fold({ savedMsg }, { saveError + " " + (it.message ?: "") })
                                        if (r.isSuccess) reloadKey++
                                    }
                                },
                                label = { Text(strings[res]) },
                            )
                        }
                    }
                    val visible = STEPPER_FIELDS.filter { (n, _) ->
                        when (n) {
                            Ms2WritableFields.IAC_FULL_OPEN -> homeOpen
                            Ms2WritableFields.IAC_CRANK_XT, Ms2WritableFields.IDLE_HYST -> !closedLoop
                            Ms2WritableFields.PWMIDLE_OPEN_STEPS, Ms2WritableFields.PWMIDLE_CLOSED_STEPS -> closedLoop
                            else -> true
                        }
                    }
                    val parsed = visible.associate { (n, _) -> n to parseCurveNumber(texts[n] ?: "") }
                    visible.forEach { (n, res) ->
                        val spec = Ms2WritableFields.byName(n)!!
                        OutlinedTextField(
                            value = texts[n] ?: "",
                            onValueChange = { v -> texts = texts + (n to v) },
                            label = { Text(strings[res] + if (spec.unit.isNotEmpty()) " (${spec.unit})" else "") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = parsed[n] == null || parsed[n]!! < spec.min || parsed[n]!! > spec.max,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    val rangeErrors = visible.mapNotNull { (n, res) ->
                        val spec = Ms2WritableFields.byName(n)!!
                        val v = parsed[n]
                        if (v == null || v < spec.min || v > spec.max) strings[res] + ": " + spec.min.toInt() + ".." + spec.max.toInt() else null
                    }
                    val idleErrors = if (rangeErrors.isEmpty()) {
                        Ms2IdleValidator.validate(
                            Ms2IdleInput(
                                iacStart = parsed[Ms2WritableFields.IAC_START],
                                iacFullOpen = if (homeOpen) parsed[Ms2WritableFields.IAC_FULL_OPEN] else null,
                                openSteps = if (closedLoop) parsed[Ms2WritableFields.PWMIDLE_OPEN_STEPS] else null,
                                closedSteps = if (closedLoop) parsed[Ms2WritableFields.PWMIDLE_CLOSED_STEPS] else null,
                            )
                        ).map(::errorText)
                    } else emptyList()
                    val errors = rangeErrors + idleErrors
                    val changed = visible.filter { (n, _) -> parsed[n] != null && parsed[n] != f[n] }
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        enabled = errors.isEmpty() && changed.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                val r = runCatching {
                                    changed.forEach { (n, _) -> backend.writeField(n, parsed.getValue(n)!!).getOrThrow() }
                                }
                                message = r.fold({ savedMsg }, { saveError + " " + (it.message ?: "") })
                                if (r.isSuccess) reloadKey++
                            }
                        },
                    ) { Text(strings["ms2_idle_save"]) }

                    val fullOpen = if (homeOpen) f[Ms2WritableFields.IAC_FULL_OPEN] else null
                    if (!closedLoop) {
                        CurveSection(
                            title = strings["ms2_idle_sec_warmup"],
                            curve = warmup!!,
                            yLabel = strings["ms2_idle_col_steps"],
                            yName = Ms2WritableFields.IAC_STEP_TABLE,
                            xName = Ms2WritableFields.TEMP_TABLE,
                            backend = backend,
                            invalidNumber = invalidNumber,
                            validate = { tf, ys ->
                                Ms2IdleValidator.validate(Ms2IdleInput(iacFullOpen = fullOpen, iacStepTable = ys, iacStepTempsF = tf)).map(::errorText)
                            },
                            onResult = { ok, msg -> message = if (ok) savedMsg else saveError + " " + msg; if (ok) reloadKey++ },
                        )
                    } else {
                        CurveSection(
                            title = strings["ms2_idle_sec_target"],
                            curve = target!!,
                            yLabel = strings["ms2_idle_col_rpm"],
                            yName = Ms2WritableFields.PWMIDLE_TARGET_RPMS,
                            xName = Ms2WritableFields.PWMIDLE_CLT_TEMPS,
                            backend = backend,
                            invalidNumber = invalidNumber,
                            validate = { tf, ys ->
                                Ms2IdleValidator.validate(Ms2IdleInput(targetRpms = ys, targetTempsF = tf)).map(::errorText)
                            },
                            onResult = { ok, msg -> message = if (ok) savedMsg else saveError + " " + msg; if (ok) reloadKey++ },
                        )
                    }
                }
                OutlinedButton(onClick = { reloadKey++ }) { Text(strings["ms2_wue_reload"]) }
            }
            else -> OutlinedButton(onClick = { reloadKey++ }) { Text(strings["ms2_wue_reload"]) }
        }
        message?.let { Text(it) }
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text(strings["ms2_wue_back"]) }
    }
}

@Composable
private fun CurveSection(
    title: String,
    curve: TempCurve,
    yLabel: String,
    yName: String,
    xName: String,
    backend: Ms2CurveBackend,
    invalidNumber: String,
    validate: (tempsF: List<Double>, ys: List<Double>) -> List<String>,
    onResult: (ok: Boolean, msg: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    IniCurveEditor(
        xLabel = strings["ms2_wue_col_temp"],
        yLabel = yLabel,
        initialX = curve.tempsC,
        initialY = curve.y,
        saveLabel = strings["ms2_wue_save"],
        xDecimals = 1,
        yDecimals = 0,
        invalidNumberMessage = invalidNumber,
        validate = { xs, ys -> validate(IniTemperature.mergeEditedCelsius(curve.tempsF, xs), ys.map { round(it) }) },
        onSave = { xs, ys ->
            scope.launch {
                val tf = IniTemperature.mergeEditedCelsius(curve.tempsF, xs)
                val r = runCatching {
                    // Só grava o eixo se algum ponto mudou (bruto original preservado).
                    if (tf != curve.tempsF) backend.write(xName, tf).getOrThrow()
                    backend.write(yName, ys.map { round(it) }).getOrThrow()
                }
                onResult(r.isSuccess, r.exceptionOrNull()?.message ?: "")
            }
        },
    )
}
