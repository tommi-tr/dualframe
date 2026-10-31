# DualFrame per iPhone

Ogni scatto e ogni video creano **due file**: uno 9:16 e uno 16:9, ritagliati in tempo reale dal fotogramma 4:3 della fotocamera **ultra grandangolare** (0,5×).

## Requisiti
- Un Mac con Xcode 15 o successivo
- Un iPhone con iOS 16+ e fotocamera ultra grandangolare (iPhone 11 o successivo). Sugli iPhone senza ultra grandangolo l'app usa la fotocamera principale.
- Il Simulatore non ha la fotocamera: serve un iPhone vero.

## Installare sul tuo iPhone
1. Apri `DualFrame.xcodeproj` in Xcode.
2. Seleziona il target **DualFrame** › *Signing & Capabilities* › **Team** e scegli il tuo Apple ID (va bene anche un account gratuito).
3. Se Xcode segnala che il Bundle Identifier è già in uso, cambia `com.dualframe.cam` con uno tuo, ad esempio `com.tuonome.dualframe`.
4. Collega l'iPhone, selezionalo in alto e premi ▶︎ (Run).
5. Sull'iPhone vai in Impostazioni › Generali › VPN e gestione dispositivi e autorizza il tuo sviluppatore (serve solo la prima volta).

Le foto e i video vengono salvati nell'app **Foto**.

## Come funziona
- Il riquadro bianco è il formato di come tieni il telefono, quello giallo è l'altro formato.
- **Tocca** un punto per spostarci subito il riquadro giallo (e mettere a fuoco). **Trascina** per spostarlo e **pizzica** per ridimensionarlo, anche durante la ripresa.
- I chip in alto (risoluzione, fps, codec) si toccano per cambiare impostazione.
- Durante il video c'è il tasto foto bianco accanto allo stop.

## Note tecniche
- AVFoundation: `AVCaptureVideoDataOutput` + due `AVAssetWriter` in parallelo. Ogni fotogramma viene ritagliato con Core Image, quindi non c'è un'elaborazione dopo la ripresa.
- L'app sceglie da sola il formato 4:3 del sensore più adatto a fps e risoluzione. Se una combinazione non è supportata ripiega e te lo segnala.
- L'interfaccia è bloccata in verticale. L'orientamento del telefono si legge dall'accelerometro, quindi funziona anche con il blocco rotazione attivo.
