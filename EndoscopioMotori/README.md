# Endoscopio Motori — app Android per sonda USB-C UVC

App gratuita, senza pubblicità né account. Kotlin + libreria open source
[UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (Apache 2.0, su Maven Central).

> **Attenzione:** il codice è stato scritto senza poterlo compilare né provare con una sonda reale
> (ambiente senza Android SDK né rete). Alla prima compilazione può servire qualche ritocco:
> vedi "Se la compilazione dà errore".

## Compilare l'APK — strada A: Android Studio (gratis)

1. Scarica e installa **Android Studio** (developer.android.com/studio). Serve un PC con almeno 8 GB di RAM.
2. Apri Android Studio → **Open** → scegli la cartella `EndoscopioMotori`.
3. Se chiede di usare/scaricare Gradle o di aggiornare il plugin Android: accetta
   (se propone "Update", puoi anche rifiutare). Al primo sync scarica le librerie (serve internet).
4. Attendi la fine del **Sync**. Se manca l'SDK, accetta i download proposti (Android SDK 35).
5. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. A fine build, clicca **locate** nella notifica: il file è
   `app/build/outputs/apk/debug/app-debug.apk`.

## Compilare l'APK — strada B: senza installare nulla (GitHub, gratis)

Utile se il PC è vecchio.
1. Crea un account gratuito su github.com, crea un repository nuovo (privato va bene).
2. Carica tutto il contenuto della cartella `EndoscopioMotori` (compresa `.github`) nel ramo `main`.
3. Scheda **Actions → Build APK → Run workflow**. Dopo qualche minuto, in fondo alla pagina
   del run scarichi l'artefatto `endoscopio-motori-apk` (zip con `app-debug.apk`).

## Installare sul telefono

1. Copia `app-debug.apk` sul telefono (cavo, Drive, email…).
2. Aprilo dal telefono; Android chiede di consentire "Installa app sconosciute"
   per l'app da cui l'hai aperto (File, Chrome…): consenti.
3. Installa. Al primo avvio concedi **Fotocamera** (necessario ad Android per le camere USB)
   e **Microfono** (solo per l'audio dei video; se neghi, i video sono senza audio).
4. Collega la sonda con adattatore OTG/USB-C: compare la richiesta permesso USB.
   Spunta "usa sempre" per l'avvio automatico al collegamento.

In alternativa via cavo: attiva Opzioni sviluppatore → Debug USB e `adb install app-debug.apk`.

## Uso

- **FOTO** (o tasto sonda) salva un JPG; **REC/STOP** registra MP4 con timer.
- **↻** ruota 0/90/180/270, **⇋** specchio: salvati come impostazione.
- Pizzico = zoom, doppio tocco = azzera. Campo in alto = nota/motore, aggiunta al nome file
  (`ISP_20261106_064100_Motore_A.jpg`).
- **GALLERIA**: tocca un file → Apri / Condividi / Elimina.
- File in `Immagini/Ispezioni Motori` e `Video/Ispezioni Motori`.
- Sotto compare "Tasto sonda: …" per capire cosa arriva premendo il tasto fisico.

## Se la compilazione dà errore

- *"Class is not abstract and does not implement…"* su `StateCallback` in `MainActivity.kt`:
  la versione della libreria ha metodi in più/meno; aggiungi/togli gli `override` indicati
  dal messaggio. Se persiste, in `app/build.gradle.kts` prova `1.0.10`.
- Errori su `ImageCapture`/`VideoCapture` in `CameraGlue.java`: lì sono concentrate tutte le
  chiamate foto/video; i nomi esatti si vedono con Ctrl+clic su `CameraHelper`.

## Cosa NON è stato possibile fare (e perché)

1. **Compilare e provare l'app**: nessun Android SDK né rete nell'ambiente di lavoro, e nessuna sonda.
2. **Tasto fisico della sonda: non so se funziona con la tua.** Il codice ascolta sia l'evento
   UVC "button" sia i tasti tastiera (Camera, Enter, Spazio, Centro). Ma molte sonde economiche
   non inviano nulla al telefono. Si scopre solo provando: premi il tasto e guarda la riga
   "Tasto sonda…" / il messaggio "Tasto sonda premuto". Se non succede nulla, resta solo il pulsante a schermo.
3. **Rotella luce**: non gestita, come richiesto. Lo **slider "Luminosità"** compare solo se la sonda
   espone il controllo UVC *Brightness*, che è la luminosità dell'**immagine**, non il LED: lo standard UVC
   non prevede il controllo del LED. Probabilmente non comparirà.
4. **Rotazione/specchio nei video**: nelle foto sono scritti nei metadati (EXIF, senza perdita) e
   le gallerie le mostrano corrette; nei **video** la libreria non li applica, quindi il file MP4
   resta nell'orientamento originale (l'anteprima invece sì).
5. **Zoom digitale**: vale per l'anteprima; foto e video salvati sono a inquadratura intera.
6. **Massima risoluzione**: l'app sceglie la più grande dichiarata dalla sonda e riapre la camera
   se serve; se la sonda ne dichiara una che poi non regge, cala a quella predefinita.
7. **File dopo disinstallazione**: reinstallando l'app (dopo averla disinstallata) Android non
   permette più di eliminarli dalla galleria interna; restano visibili nella galleria del telefono.
   Con l'aggiornamento sopra la versione esistente non c'è problema.
8. **Uscire dall'app durante la registrazione** può far perdere il video in corso.
9. **Android < 10**: la galleria interna e il salvataggio usano i permessi di archiviazione
   classici; non verificato su telefoni così vecchi.
