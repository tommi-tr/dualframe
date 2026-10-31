# Camera Dual per Android

Ogni scatto e ogni video creano **due file**: uno 9:16 (verticale) e uno 16:9 (orizzontale), ricavati dal fotogramma 4:3 della lente scelta.

## Installazione
Apri `CameraDual.apk` sul telefono (Android 10 o successivo) e consenti l'installazione. Si aggiorna sopra la versione precedente.

## Dove finiscono i file
- **DCIM/Camera Dual Verticali**: foto e video 9:16
- **DCIM/Camera Dual Orizzontali**: foto e video 16:9
- **DCIM/Camera Dual Originali**: compare solo se un video non si riesce a tagliare. Lì trovi il video originale 4:3, così la ripresa non si perde mai.

La schermata **Ultimi scatti** (miniatura in basso a sinistra) elenca le riprese con data, durata, impostazioni e nomi dei file. Le miniature dei due formati e delle foto scattate durante il video sono affiancate. Toccando una miniatura si apre il visore interno: mostra insieme la versione 16:9 e quella 9:16 (i video scorrono sincronizzati con un'unica barra), e ciascuna ha i suoi tasti Galleria, Condividi e Cestina. Ciò che cancelli dalla galleria sparisce anche dall'elenco.

## Come si usa
- **In alto**: nome e versione (toccali per cercare aggiornamenti su GitHub) e una barra con tutte le impostazioni: lente, risoluzione, fps, codec, fuoco, diaframma, flash e guide (griglia e/o livella). Tocca una casella per cambiarla.
- **Tocca** il mirino per spostare lì il riquadro colorato e **trascina** per muoverlo. **Pizzica** su uno dei due riquadri per ridimensionarlo. Il riquadro bianco si sposta con la sua maniglia.
- **Tieni premuto** per bloccare fuoco ed esposizione su un punto. Nel fuoco manuale lo slider va da macro (a sinistra) a infinito (a destra).
- Il tasto in basso a destra (i due riquadri sovrapposti) riporta i riquadri nella posizione predefinita.
- Durante il video lo sfondo diventa **rosso scuro**. Il tasto per le foto sta a sinistra quando tieni il telefono in verticale, e in alto quando lo tieni in orizzontale.

## Registrazione sicura
Il video viene prima salvato intero nella memoria dell'app. Poi viene tagliato nei due formati in sottofondo, con la notifica "Taglio i video…", e il taglio continua anche ad app chiusa. Durante una nuova ripresa il taglio si mette in pausa, perché la priorità è riprendere. Se l'app o il telefono si spengono, il taglio riparte al prossimo avvio.

Se un video non si riesce a tagliare dopo 3 tentativi, l'originale 4:3 finisce in **Camera Dual Originali**. Se il file è danneggiato (ad esempio perché il telefono si è spento mentre registravi), non viene cancellato: resta in `Android/data/com.dualframe.cam/files/Danneggiati`, raggiungibile dal computer.

All'avvio l'app recupera anche i video rimasti in sospeso dalle versioni precedenti.

## Aggiornamenti
Tutti gli APK sono firmati con la stessa chiave (`keystore/camera-dual.keystore`), sia quelli compilati qui sia quelli di GitHub Actions. Così ogni versione si installa sopra la precedente senza disinstallare. È una chiave per uso personale: per pubblicare sul Play Store serve una chiave di rilascio vera.

## Compilare
```
./gradlew assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest
```
