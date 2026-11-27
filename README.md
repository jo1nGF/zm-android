# OZMA para Android (S10 / Android 12) — uso pessoal

Roda o `bricktracker` **original e sem modificação** (aarch64 glibc) dentro de um app Android.
Não é emulação: o app executa o loader glibc empacotado e fornece uma `libSDL2-2.0.so.0` própria (shim) que
manda vídeo, áudio e entrada para o Android.

    bricktracker ──(SDL API)──> shim/sdl_shim.c ──sockets + memória compartilhada──> app Kotlin
                                                                                      ├ SurfaceView (vídeo)
                                                                                      ├ AudioTrack baixa latência
                                                                                      └ controles na tela / gamepad

## Como compilar (Windows + GitHub, sem instalar nada)
1. Crie um repositório **PRIVADO** no GitHub (a licença do OZMA proíbe redistribuir; repositório público = distribuir).
2. Envie o conteúdo desta pasta para ele e coloque o `OZMA-0_9_5-ports.zip` dentro da pasta `ozma/`
   (pelo site: *Add file > Upload files*; o zip tem ~14 MB, cabe).
3. Aba **Actions > Build OZMA APK**. Ela roda sozinha a cada push (ou clique em *Run workflow*). Leva uns 5-10 min.
4. Quando ficar verde, abra a execução e baixe **ozma-android-apk** (um zip com `app-debug.apk`).
5. Passe o APK para o S10, permita "instalar apps desconhecidos" e instale.
Se ficar vermelha, abra o passo que falhou e me mande o texto do erro.

## Como compilar (Linux, opcional)
`sudo apt install gcc-aarch64-linux-gnu zip unzip`, depois `tools/build_assets.sh OZMA-0_9_5-ports.zip` e abra no Android Studio.

## Onde ficam os dados
`Android/data/com.ozma.android/files/OZMA/` (músicas, samples, `pad_map.txt`). Log do processo: `files/ozma.log`
(`adb shell run-as com.ozma.android cat files/ozma.log`).

## Pontos de atenção (nada disso foi testado em aparelho)
- **targetSdk 28 de propósito**: no Android 10+ não dá para executar binários da pasta de dados com targetSdk ≥ 29. Só sideload.
- O runtime glibc vem do Debian bookworm (2.36): o glibc < 2.33 usa a syscall `getrlimit`, que o seccomp do Android 12 bloqueia. `GLIBC_TUNABLES=glibc.pthread.rseq=0` desliga a syscall `rseq`, também bloqueada.
- Entrada: o shim manda eventos de **game controller**. Se o OZMA não reagir, exporte `OZMA_SHIM_JOYEVENTS=1`
  (em `OzmaRuntime.kt`, no bloco `env[...]`) para mandar também eventos de joystick.
- O shim implementa só o necessário: `SDL_RenderCopy` ignora flip/rotação/alpha; áudio só S16/F32, saída (sem microfone);
  MIDI USB ainda não existe.
- Em segundo plano o Android 12 pode matar o processo filho; um Foreground Service seria o próximo passo.
- Licença do OZMA: uso nos seus próprios aparelhos; não distribua o APK.
