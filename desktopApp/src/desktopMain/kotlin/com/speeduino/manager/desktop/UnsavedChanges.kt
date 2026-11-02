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

/** Declara [dirty] ao shell enquanto a tela está na composição e limpa ao sair. */
@Composable
fun ReportUnsavedChanges(dirty: Boolean) {
    val report by rememberUpdatedState(LocalUnsavedChangesReporter.current)
    DisposableEffect(dirty) {
        report(dirty)
        onDispose { report(false) }
    }
}
