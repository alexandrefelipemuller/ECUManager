package com.speeduino.manager.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.DesktopSettingsStore
import com.speeduino.manager.desktop.LocalStrings
import kotlinx.coroutines.delay

// Espelha os componentes de usabilidade (ISO 9241-110) do app Android.

/** Texto não vazio que não é um número válido. */
internal fun isInvalidNumber(text: String, integerOnly: Boolean = false): Boolean =
    text.isNotBlank() && if (integerOnly) {
        text.trim().toIntOrNull() == null
    } else {
        text.trim().replace(',', '.').toDoubleOrNull() == null
    }

/** Inteiro válido fora da [range]. */
internal fun isOutOfRange(text: String, range: IntRange): Boolean =
    text.isNotBlank() && text.trim().toIntOrNull()?.let { it !in range } == true

/** Algum dos campos tem número inválido ou fora da faixa (campo em branco conta como inválido). */
internal fun anyInvalidNumber(vararg fields: Triple<String, IntRange?, Boolean>): Boolean =
    fields.any { (text, range, integerOnly) ->
        text.isBlank() || isInvalidNumber(text, integerOnly) || (range != null && isOutOfRange(text, range))
    }

/** Fator aplicado ao tamanho das células das tabelas (configuração "Tamanho das células"). */
internal val LocalTableCellScale = compositionLocalOf { 1f }

/** Diz por que o botão Salvar está desabilitado; some quando salvar é possível. */
@Composable
internal fun SaveDisabledReason(
    hasChanges: Boolean,
    dataAvailable: Boolean,
    hasInvalidFields: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val reason = when {
        !dataAvailable -> "save_disabled_unavailable"
        hasInvalidFields -> "save_disabled_invalid"
        !hasChanges -> "save_disabled_no_changes"
        else -> return
    }
    Text(
        text = strings[reason],
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

private const val LOAD_TIMEOUT_MS = 8_000L

/**
 * Estado "carregando da ECU" de um formulário. A leitura é disparada sem retorno, então o estado
 * termina quando os dados chegam ([result] muda) ou por tempo, para nunca ficar preso.
 */
@Stable
internal class EcuLoadState {
    var loading by mutableStateOf(false)
        internal set

    fun start() {
        loading = true
    }
}

@Composable
internal fun rememberEcuLoadState(result: Any?): EcuLoadState {
    val state = remember { EcuLoadState() }
    LaunchedEffect(result) { state.loading = false }
    LaunchedEffect(state.loading) {
        if (state.loading) {
            delay(LOAD_TIMEOUT_MS)
            state.loading = false
        }
    }
    return state
}

@Composable
internal fun EcuLoadProgress(state: EcuLoadState, modifier: Modifier = Modifier) {
    if (!state.loading) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(LocalStrings.current["loading_from_ecu"], style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Dica mostrada na primeira abertura de uma tela; depois de dispensada não aparece mais. */
@Composable
internal fun FirstUseHint(hintKey: String, text: String, modifier: Modifier = Modifier) {
    var dismissed by remember(hintKey) { mutableStateOf(DesktopSettingsStore.isHintDismissed(hintKey)) }
    if (dismissed) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                dismissed = true
                DesktopSettingsStore.dismissHint(hintKey)
            }) { Text(LocalStrings.current["hint_dismiss"]) }
        }
    }
}

/** Algum dos campos está em branco ou não é um número válido. */
internal fun invalidNumbers(vararg texts: String): Boolean =
    texts.any { it.isBlank() || isInvalidNumber(it) }
