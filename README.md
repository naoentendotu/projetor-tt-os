# Projetor TT OS

Launcher leve para o projetor **HY300** (Android 9, 1 GB de RAM). App nativo em Java puro, sem nenhuma dependência: APK de ~180 KB e ~57 MB de RAM em uso.

## O que faz

- **Início:** destaque do Jellyfin (último assistido + sugestão), *Continuar assistindo*, *Seus apps* e *Adicionados recentemente*. Cards estilo Apple TV: capa vertical parada, alarga em 16:9 no foco.
- **Apps:** grade com todos os apps; os ocultos ficam no fim.
- **Projetor:** imagem (trapézio vertical/horizontal e zoom ao vivo), áudio (saída e modo noturno), rede, liberar memória, aparência, app ao ligar, conta do Jellyfin e backup.
- **Jellyfin:** "Assistir" toca no player do próprio app Jellyfin para TV, de onde parou (comando *PlayNow* de sessão remota).
- **Controle:** navegação cíclica; OK segurado num app → mover, favoritar, ocultar, forçar parada, informações; Home duas vezes → recentes; Menu → painel rápido por cima de qualquer app.
- **RAM:** ao sair de um app para o Início ou outro app, o app anterior é encerrado.
- **Descanso:** após 10 min sem uso, escurece e mostra um relógio grande.

## Estrutura

| Arquivo | Papel |
|---|---|
| `MainActivity` | Tela inicial e abas |
| `TvActivity` / `Nav` | Teclas do controle, OK segurado e navegação em linhas |
| `Ui` | Tema, fontes, cards e animações |
| `Apps` | Lista de apps, capas, ordem, favoritos e ocultos |
| `Jellyfin` / `PlayService` | Cliente do servidor e reprodução no app Jellyfin |
| `Sys` / `AdbSelf` | Comandos de sistema pelo ADB local (keystone, forçar parada, recentes) |
| `KeyService` / `QuickPanel` | Serviço de acessibilidade: Home, Menu, painel em camada e encerramento de apps |

O firmware do HY300 encerra o app da frente sempre que outra tela abre e, com isso, apaga a tela inicial padrão. Por isso o `KeyService` roda em processo próprio (`:keys`): intercepta o Home, refaz a preferência de tela inicial e desenha o painel como camada, sem abrir tela nova.

## Compilar e instalar

```powershell
$env:JAVA_HOME = 'C:\caminho\para\jdk-17'
.\gradlew.bat assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect IP_DO_PROJETOR:5555
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell cmd package set-home-activity com.projetoros.launcher/.MainActivity
& $adb shell settings put secure enabled_accessibility_services com.projetoros.launcher/com.projetoros.launcher.KeyService
& $adb shell settings put secure accessibility_enabled 1
```

Na primeira vez que um comando de sistema roda, o Android pergunta **"Permitir a depuração USB?"**: marque *Sempre permitir* e OK (é a chave do próprio launcher).

## Observações

- Backup em `/sdcard/Download/projetor-os-backup.json`. Ele contém o token do Jellyfin: não compartilhe.
- Papel de parede: imagens da pasta `/sdcard/Pictures` do projetor.
- Fonte Inter (só os caracteres do português) embutida em `assets/`.
