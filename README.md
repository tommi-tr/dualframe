# Camera Dual

App fotocamera che per ogni scatto e ogni video crea due file: **9:16** e **16:9**.

- `android/`: app Android (Kotlin, CameraX + Media3), quella in sviluppo attivo. Vedi `android/README.md`.
- `ios/`: prima versione per iPhone (Swift, AVFoundation), al momento in pausa. Vedi `ios/README.md`.

Ogni modifica in `android/` crea in automatico l'APK in **Actions › Android – Build APK** (artifact *CameraDual-apk*).
L'IPA per iPhone si crea a mano da **Actions › iOS – Build IPA (manuale)**.
