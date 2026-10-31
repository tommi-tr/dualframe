# Camera Dual per Android

Ogni scatto e ogni video creano **due file**: uno 9:16 (verticale) e uno 16:9 (orizzontale), ricavati dal fotogramma 4:3 della lente scelta.

## Installazione
Apri `CameraDual.apk` sul telefono (Android 10 o successivo) e consenti l'installazione. Si aggiorna sopra la versione precedente.

## Dove finiscono i file
- **DCIM/Camera Dual Verticali**: foto e video 9:16
- **DCIM/Camera Dual Orizzontali**: foto e video 16:9
- **DCIM/Camera Dual Originali**: compare solo se un video non si riesce a tagliare. Lì trovi il video originale 4:3, così la ripresa non si perde mai.

La schermata **Ultimi scatti** (miniatura in basso a sinistra) elenca le riprese con data, durata, impostazioni e nomi dei file. Le miniature dei due formati e delle foto scattate durante il video sono affiancate.

## Come si usa
- **In alto** trovi tutte le impostazioni: flash, risoluzione, fps, codec, griglia; nella seconda riga la lente (.5, 1×, …), il fuoco e il diaframma.
- **Tocca** il mirino per spostare lì il riquadro colorato e **trascina** per muoverlo. **Pizzica** su uno dei due riquadri per ridimensionarlo. Il riquadro bianco si sposta con la sua maniglia.
- **Tieni premuto** per bloccare fuoco ed esposizione su un punto.
- Durante il video lo sfondo diventa **rosso scuro**. Il tasto per le foto sta a sinistra quando tieni il telefono in verticale, e in alto quando lo tieni in orizzontale.

## Registrazione sicura
Il video viene prima salvato intero nella memoria dell'app. Poi, in sottofondo, viene tagliato nei due formati (anche ad app chiusa). Durante una nuova ripresa il taglio si mette in pausa, perché la priorità è riprendere. Se l'app o il telefono si chiudono, il taglio riparte al prossimo avvio. All'avvio l'app recupera anche i video rimasti in sospeso dalle versioni precedenti.

## Compilare
```
./gradlew assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest
```
