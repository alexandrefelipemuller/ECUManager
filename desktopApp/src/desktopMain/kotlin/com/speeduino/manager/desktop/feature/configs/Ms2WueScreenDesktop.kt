package com.speeduino.manager.desktop.feature.configs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ui.IniCurveEditor
import io.ecucore.model.IniTemperature
import io.ecucore.model.Ms2WritableFields
import io.ecucore.model.Ms2WueError
import io.ecucore.model.Ms2WueValidator
import kotlinx.coroutines.launch
import kotlin.math.round

/** Acesso às curvas .ini da MS2 (ver [io.ecucore.transport.EcuTransport.readIniCurve]). */
internal class Ms2CurveBackend(
    val read: suspend (name: String) -> Result<List<Double>>,
    val write: suspend (name: String, values: List<Double>) -> Result<Unit>,
    /** Campos escalares de [Ms2WritableFields.scalars] (unidade do usuário). */
    val readFields: suspend (names: List<String>) -> Result<Map<String, Double>> =
        { Result.failure(UnsupportedOperationException("unavailable")) },
    val writeField: suspend (name: String, value: Double) -> Result<Unit> =
        { _, _ -> Result.failure(UnsupportedOperationException("unavailable")) },
) {
    companion object {
        val Unavailable = Ms2CurveBackend(
            read = { Result.failure(UnsupportedOperationException("unavailable")) },
            write = { _, _ -> Result.failure(UnsupportedOperationException("unavailable")) },
        )
    }
}

/** Warm-Up Enrichment da MS2: `wueBins` (%) x `tempTable` (°F na ECU, °C na UI). */
@Composable
internal fun Ms2WueScreen(
    isConnected: Boolean,
    backend: Ms2CurveBackend,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    var tempsF by remember { mutableStateOf<List<Double>?>(null) }
    var tempsC by remember { mutableStateOf<List<Double>?>(null) }
    var wue by remember { mutableStateOf<List<Double>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val loadError = strings["ms2_wue_load_error"]
    val savedMsg = strings["ms2_wue_saved"]
    val saveError = strings["ms2_wue_save_error"]

    LaunchedEffect(isConnected, reloadKey) {
        if (!isConnected) return@LaunchedEffect
        loading = true
        val w = backend.read(Ms2WritableFields.WUE_BINS)
        val t = backend.read(Ms2WritableFields.TEMP_TABLE)
        if (w.isSuccess && t.isSuccess) {
            wue = w.getOrThrow()
            tempsF = t.getOrThrow()
            tempsC = IniTemperature.fahrenheitToCelsius(tempsF!!).map { round(it * 10.0) / 10.0 }
        } else {
            message = loadError + " " + ((w.exceptionOrNull() ?: t.exceptionOrNull())?.message ?: "")
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(strings["ms2_wue_title"], style = MaterialTheme.typography.headlineSmall)
        Text(strings["ms2_wue_desc"], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        when {
            !isConnected -> Text(strings["ms2_wue_connect_first"])
            loading -> CircularProgressIndicator()
            tempsC != null && wue != null -> {
                val invalidNumber = strings["ms2_wue_invalid_number"]
                val errLast = strings["ms2_wue_err_last_100"]
                val errRange = strings["ms2_wue_err_range"]
                val errCount = strings["ms2_wue_err_count"]
                val errTemp = strings["ms2_wue_err_temp_increasing"]
                IniCurveEditor(
                    xLabel = strings["ms2_wue_col_temp"],
                    yLabel = strings["ms2_wue_col_wue"],
                    initialX = tempsC!!,
                    initialY = wue!!,
                    saveLabel = strings["ms2_wue_save"],
                    xDecimals = 1,
                    yDecimals = 0,
                    invalidNumberMessage = invalidNumber,
                    validate = { xs, ys ->
                        val tf = IniTemperature.mergeEditedCelsius(tempsF!!, xs)
                        Ms2WueValidator.validate(ys, tf).map { e ->
                            val n = (e.index ?: 0) + 1
                            when (e.code) {
                                Ms2WueError.Code.WUE_LAST_NOT_100 -> errLast
                                Ms2WueError.Code.WUE_OUT_OF_RANGE -> errRange.format(n)
                                Ms2WueError.Code.TEMP_NOT_INCREASING -> errTemp.format(n)
                                Ms2WueError.Code.TEMP_INVALID -> invalidNumber
                                else -> errCount
                            }
                        }
                    },
                    onSave = { xs, ys ->
                        scope.launch {
                            val tf = IniTemperature.mergeEditedCelsius(tempsF!!, xs)
                            val r = runCatching {
                                // Só grava a temperatura se algum ponto mudou (bruto original preservado).
                                if (tf != tempsF) backend.write(Ms2WritableFields.TEMP_TABLE, tf).getOrThrow()
                                backend.write(Ms2WritableFields.WUE_BINS, ys.map { round(it) }).getOrThrow()
                            }
                            message = r.fold({ savedMsg }, { saveError + " " + (it.message ?: "") })
                            if (r.isSuccess) reloadKey++
                        }
                    },
                )
                OutlinedButton(onClick = { reloadKey++ }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(strings["ms2_wue_reload"])
                }
            }
            else -> OutlinedButton(onClick = { reloadKey++ }) { Text(strings["ms2_wue_reload"]) }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(strings["ms2_wue_back"]) }
    }
}
