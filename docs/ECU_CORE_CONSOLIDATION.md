# Consolidação de código duplicado no ecu-core

Objetivo: tirar do app Android (`~/Projects/EcuManager`) e do Desktop (este repo) o código que é igual nos dois e
colocar no submódulo `ecu-core`, para os dois apps evoluírem juntos. Esta página registra o que foi feito e
onde parar/retomar.

## Itens do plano original

1. **Desktop usa os transports do core** (OBD2/PSA/Renault/VAG/AutoDetect) em vez de cópias antigas. **Feito.**
   Cadeia de detecção igual à do Android (inclui Sirius32). Falta validar com simulador OBD2: o
   `obd2_tcp_simulator.py` é citado em `simulator_suite.py`, mas o arquivo não existe no repo do Android.
2. **Kotlin puro idêntico nos dois apps → core.** **Feito**, exceto os stores de persistência (abaixo).
3. **Lógica que divergiu → core.** **Parcial.**
4. **Orquestração em casos de uso no `SessionController`.** **Em andamento, por partes** (ver abaixo).

## O que já está no ecu-core

| Pacote / arquivo | Origem |
|---|---|
| `io.ecucore.io` (IoModels, IoExpressionEvaluator, IoConfigEngine) | idêntico nos dois |
| `io.ecucore.realdash` (RealDashFile, RealDashSignalMap) | idêntico nos dois |
| `io.ecucore.dashboard` (`resolveSignalSourceValue`, constantes de boost, unidades) | Android |
| `io.ecucore.safety` (WriteSafetyGate, VehicleMotion, TableUndoStore, TableChangeConfirmation) | Android + `DWELL` do Desktop |
| `io.ecucore.definition.IniCatalogMatcher` (parse do manifest + casamento por versão mais próxima) | Android + correção de espaço ao redor de `*` do Desktop |
| `io.ecucore.model.basemap.BaseMapAdapter` | idêntico nos dois |
| `io.ecucore.tuning.TuningAssistantAnalyzer` + `TuningAnalyzerLabels` | lógica do Android, aliases `o2`/`ego` do core, rótulos injetáveis |
| `io.ecucore.model.logging.LiveLogExporter` + `LiveLogExportFormat` (CSV/MSL) | Android (**item 4, parte 1**) |
| `io.ecucore.safety.TableWriteWithUndo` (+ `TableWriteMode`, `TableWriteOutcome`) | Android (**item 4, parte 2**) |

O app Android passou a depender também de `core-tuning` (antes só `core-runtime`).

## Item 4 — partes

| Parte | Conteúdo | Estado |
|---|---|---|
| 1 | Exportação de log CSV/MSL | **Feita** |
| 2 | Caminho de gravação de tabelas (VE/ignição/AFR/dwell): backup antes, ponto de retorno lido da ECU, gravar, registrar/remover desfazer só após sucesso | **Feita** (ver notas abaixo) |
| 3 | Leitura de tabelas/configurações (pausar stream, invalidar cache, ler, fallback para sessão local, retomar) | **Próxima** |
| 4 | Backup e pontos de restauração (baixar tudo, criar/restaurar ponto, importar e restaurar, backup de segurança MS3) | pendente |
| 5 | Ciclo de conexão (reconexão automática com `ConnectionRetryPolicy`, desligamento, pausa do stream) | pendente |
| 6 | `SessionController` vira o dono real do estado (Desktop passa a usá-lo; Android troca as 38 chamadas `syncSessionController*`) | pendente, a mais invasiva |

Decisões de desenho já tomadas:
- Casos de uso **sem texto**: devolvem resultados tipados / chaves de mensagem; cada app traduz
  (`R.string`/`AppStrings` no Android, `Strings` no Desktop).
- O core expõe `StateFlow`; o estado do Compose continua sendo da UI do Android.
- Antes de substituir código de um app, **comparar com o antigo** (teste "oráculo": copiar a implementação
  antiga para o teste e exigir saída idêntica), como foi feito no `LiveLogExporterTest`.

## Itens do 2/3 ainda não movidos

- `Odometer`, `ImportedDashboardStore`, `RestorePointStore`, `IoConfigRepository`: lógica igual, persistência
  diferente por plataforma (SharedPreferences/JSON no Android, `Properties` no Desktop). Precisam de uma
  interface de persistência; `RestorePointStore` ainda tem modelos de dados diferentes.
- Antes/depois (`BeforeAfterLogRepository`, `Comparator`, `Models` no Android; `BeforeAfterLogCompare` no core,
  só desktop).
- Dyno virtual: dados, CSV, suavização e relatório são iguais; só o preditor difere (TFLite no Android, modelo
  linear no Desktop) → lógica no core, preditor plugável.
- `ConfigManager` (Android 904 linhas × core 440, só desktop).
- Enums de configurações (`IniSelectionMode`, `IniSelectionSource`, `InitialScreen`, `TableDensity`,
  `AppProtocol`) e ~10 chaves de persistência repetidas.
- UI e textos: `Localization.kt` do Desktop é uma segunda cópia dos textos do Android (só 157 de 961 chaves
  coincidem); decidir sobre fonte única de strings / módulo de UI compartilhado.

## Notas da parte 2

- `TableWriteWithUndo` não pausa stream, não mostra texto e não decide confirmação: cada app chama dentro do
  seu "stream pausado"; a confirmação de mudança grande continua no transporte.
- Mudanças de comportamento no **Desktop** (passou a seguir o Android): o ponto de retorno vem da ECU (antes
  era o estado da tela) e só entra na pilha depois da gravação dar certo (antes entrava antes de gravar, e uma
  falha deixava um desfazer falso).
- Diferenças que ficaram por decisão de cada app:
  - Desfazer no Desktop pula o diálogo de mudança grande (`withoutConfirmation`); no Android passa por ele.
  - Falha do backup MS3: Android aborta a gravação; Desktop só registra aviso e grava.
  - Desfazer da Dwell só no Desktop (o Android nunca grava Dwell em modo com desfazer).
- Snapshots de VE (`veTuneSnapshotStore`) e Tuning Assistant continuam no Android (o Desktop não tem).
- O pin novo do core trouxe o burn por página do Speeduino (`b` + página); o teste
  `EcuClientRestoreConfigurationTest` do Android foi ajustado a esse contrato.

## Estado dos repositórios (2026-11-21)

Tudo enviado ao GitHub. O Desktop agora está em `github.com/alexandrefelipemuller/ECUManager`.
Os pins do `ecu-core` nos dois apps apontam para o commit do `TableWriteWithUndo`.

## Como validar (armadilhas conhecidas)

- ecu-core: `./gradlew desktopTest --offline --no-build-cache` (sem `--no-build-cache` falha com
  "Could not set file mode 755 ... classpath-snapshot"). O alvo Android do core só compila pelo app Android.
- Desktop: `./gradlew :desktopApp:desktopTest --offline --no-build-cache`. O `SimulatorIntegrationTest` sobe
  `speeduino_tcp_simulator.py` e `ms2_tcp_simulator.py` de `~/Projects/EcuManager/simulator` (ou `ECU_SIMULATOR_DIR`).
- Android: `./gradlew :app:compileGoogleplayDebugKotlin --offline -q` (a variante precisa ser citada) e
  `:app:testGoogleplayDebugUnitTest --offline --tests '...'`.
- Para testar o Android contra commits locais do core: `git -C ecu-core fetch ~/Projects/SpeeduinoManagerDesktop/ecu-core HEAD`
  e `git -C ecu-core checkout FETCH_HEAD`, depois commitar o pin.
- `scripts/import_android_strings.py '<regex>'` agora lê todos os `strings*.xml` do Android.
