package com.speeduino.manager.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Canal pelo qual a tela atual avisa o shell que tem edições não salvas (ISO 9241-110:
 * controlabilidade e tolerância a erros). Com `dirty`, trocar de tela pede confirmação antes de
 * descartar. O padrão é um no-op para telas fora do shell (previews e testes).
 * Espelha `LocalUnsavedChangesReporter` do app Android.
 */
val LocalUnsavedChangesReporter = compositionLocalOf<(Boolean) -> Unit> { {} }

/** Quantos campos a tela alterou, para o diálogo de descarte dizer o que se perde. */
val LocalUnsavedChangesCountReporter = compositionLocalOf<(Int) -> Unit> { {} }

/** Declara [dirty] ao shell enquanto a tela está na composição e limpa ao sair. */
@Composable
fun ReportUnsavedChanges(dirty: Boolean, changedCount: Int = 0) {
    val report by rememberUpdatedState(LocalUnsavedChangesReporter.current)
    DisposableEffect(dirty) {
        report(dirty)
        onDispose { report(false) }
    }
    val reportCount by rememberUpdatedState(LocalUnsavedChangesCountReporter.current)
    val count = if (dirty) changedCount else 0
    DisposableEffect(count) {
        reportCount(count)
        onDispose { reportCount(0) }
    }
}
