# Hive Private Camera

App Android (Android 11+) e comando `privcam` per Termux. Le foto e i video ripresi **dentro l'app Hive** restano nell'archivio privato durante una sessione. `end` richiede lo sblocco Android e li pubblica in MediaStore, quindi diventano visibili alle gallerie.

La fotocamera di sistema e le altre app non sono controllate. Il telefono deve avere PIN, password, sequenza o biometria forte configurati. È supportata una sola sessione attiva.

## Installazione

1. Costruisci l'APK con Android Studio aprendo `android/`, oppure con JDK 17 e Gradle 8.13: `cd android && gradle :app:assembleDebug`. La CI produce l'APK debug come artifact.
2. Installa l'APK sul telefono e apri Hive. Installa Termux con il package `com.termux`.
3. Nell'app premi **Autorizza Termux** e conferma il certificato mostrato dopo lo sblocco Android. Ripeti l'autorizzazione se reinstalli Termux con un'altra firma.
4. Copia `termux/privcam` nel telefono e in Termux esegui `install -m 755 privcam "$PREFIX/bin/privcam"`. Richiede Python 3 (`pkg install python`).
5. Usa `privcam` per il menu o `privcam start`, `privcam camera`, `privcam view`, `privcam status`, `privcam end`.

La CLI apre l'app per mostrare autenticazione e fotocamera. Il comando `end` attende la fine della pubblicazione; se viene interrotto, apri Hive e scegli **Riprendi pubblicazione** o riesegui `privcam end`.

## Protezione e recupero

I temporanei di cattura sono file interni dell'app. Dopo la cattura sono cifrati con AES-GCM; la chiave casuale della sessione è protetta da Android Keystore. Screenshot e anteprime delle app recenti sono oscurati. Nessun dato dell'app viene incluso nei backup.

**Non esiste un backup.** Disinstallazione, guasto del telefono o invalidazione della chiave prima della pubblicazione possono rendere i media irrecuperabili. La prima sessione richiede conferma di questo rischio. Per dettagli: [modello di sicurezza](docs/security.md). Per prove su telefono: [verifica](docs/verification.md).

## Sviluppo

`android/` contiene l'app e i test del formato cifrato; `termux/` la CLI e i suoi test; `docs/` le istruzioni di sicurezza e verifica. Esegui `python3 -m unittest discover -s termux -p 'test_*.py'` e `cd android && gradle :app:testDebugUnitTest` per i test. La build CI è in `.github/workflows/android.yml`.

## Rilascio GitHub

Il workflow [release.yml](.github/workflows/release.yml) pubblica un APK firmato e un file SHA-256 in GitHub Releases quando riceve un tag `v<versionName>`, per esempio `v0.1.0`. Il tag deve corrispondere a `versionName` in `android/app/build.gradle.kts`; la CI verifica test, firma e checksum prima della pubblicazione.

La chiave di firma e le password locali sono in `.local-secrets/`, esclusa da Git. Esegui `scripts/upload-release-secrets.sh xprss/hive` con GitHub CLI autenticata per configurare i quattro segreti Actions. Poi crea e invia il tag: `git tag v0.1.0 && git push origin v0.1.0`. Per una build release locale usa `scripts/build-release.sh` con Gradle 8.13 e Android SDK 36.

Conserva una copia sicura esterna di `.local-secrets/`: senza la stessa chiave non potrai distribuire aggiornamenti installabili sopra una release precedente. Un APK debug usa una firma diversa; prima di disinstallarlo per installare la release, chiudi e pubblica eventuali sessioni private.
