# Arquitetura (planejada)

## Modulos

- `shared` (KMP)
  - `protocol` (parse, comandos, CRC)
  - `connection` (TCP, interface de transporte)
  - `model` (estruturas de dados e validacoes)
- `desktopApp` (Compose Desktop)
  - UI, estado, navegacao simples

## Reaproveitamento do Android

O objetivo e mover codigo de protocolo e transporte para `shared` sem alterar contratos publicos, permitindo que Android e Desktop consumam o mesmo core.

## Fluxo de dados

1. UI solicita conexao.
2. `EcuClient` inicia o transporte TCP.
3. `EcuProtocol` processa comandos e respostas.
4. `EcuLiveData` abastece a UI.

## Dependencias

- Coroutines para IO.
- Logger multiplataforma (a definir).

## Diretorios esperados

```
SpeeduinoManagerDesktop/
  build.gradle.kts
  settings.gradle.kts
  shared/
  desktopApp/
  docs/
```
