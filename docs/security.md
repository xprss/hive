# Modello di sicurezza

## Ambito

Hive protegge i media acquisiti con la propria fotocamera. Senza root non può intercettare o nascondere i file prodotti dalla fotocamera di sistema o da altre app. Durante la sessione i file risiedono in `noBackupFilesDir/vault`, isolato dal sandbox Android. Dopo `end` vengono copiati in `Pictures/Hive` o `Movies/Hive` tramite MediaStore.

## Chiavi e accesso

Ogni sessione usa una chiave AES casuale da 256 bit. L'app cifra i media in blocchi da al massimo 1 MiB con AES-GCM, IV casuale per blocco e un blocco finale autenticato. ID del media, indice e lunghezza del blocco sono dati autenticati. La chiave della sessione è cifrata con una chiave non esportabile di Android Keystore, autorizzata per 30 secondi dopo PIN, sequenza, password o biometria forte. Avvio, apertura della galleria privata, apertura della fotocamera e chiusura richiedono BiometricPrompt. La chiave di sessione decifrata rimane in memoria finché l'app deve acquisire o pubblicare; dopo un arresto del processo serve un nuovo sblocco. `FLAG_SECURE` oscura screenshot e app recenti.

I file `.raw` esistono solo nello spazio privato durante la registrazione o finché la cifratura non termina. Se l'app viene arrestata, una successiva autenticazione tenta di cifrarli. Un video non finalizzato dalla fotocamera dopo un arresto forzato può essere incompleto o non riproducibile. I media senza file valido vengono scartati; quelli recuperabili restano privati fino alla pubblicazione.

## Termux

La socket Unix nel namespace astratto ha un nome pubblico. L'app accetta comandi solo se `SO_PEERCRED` corrisponde all'UID del package `com.termux` **e** al certificato SHA-256 autorizzato nell'interfaccia dell'app. Termux controlla a sua volta l'UID del server contro l'UID del package `io.xprss.hive` restituito dal package manager. La socket non trasporta foto, video, chiavi, anteprime o nomi dei file. `status` restituisce solo ora di avvio, conteggi, spazio e stato. Le altre azioni richiedono comunque l'autenticazione nell'app.

Chi può eseguire codice con l'UID di Termux può richiedere azioni alla socket; per iniziare o chiudere una sessione serve ancora lo sblocco sul telefono. Il pairing non distingue processi diversi all'interno di Termux.

## Pubblicazione e interruzioni

Il journal privato registra sessione, catture e avanzamento. Ogni media ha un nome MediaStore deterministico. Prima di inserirlo, l'app cerca la riga già creata, incluse le righe `IS_PENDING=1`. Riscrive una riga pending incompleta e rende visibile l'elemento solo dopo decifratura, scrittura e verifica del tag GCM. Segna l'elemento pubblicato nel journal prima di cancellare la copia cifrata. Dopo un'interruzione, `end` riprende senza creare un duplicato della riga precedente.

Durante una pubblicazione lunga, alcuni media possono diventare visibili prima degli altri: MediaStore rende visibile ciascun elemento completato singolarmente. Non è una transazione atomica dell'intera sessione. Se lo spazio si esaurisce, l'elemento pending rimane nascosto e la copia cifrata viene mantenuta per riprovare. L'app non può recuperare dati dopo disinstallazione, guasto o perdita della chiave Keystore.

Riferimenti Android: [memoria privata](https://developer.android.com/training/data-storage/app-specific), [Keystore](https://developer.android.com/privacy-and-security/keystore), [MediaStore e IS_PENDING](https://developer.android.com/training/data-storage/shared/media), [SO_PEERCRED](https://developer.android.com/reference/android/net/LocalSocket#getPeerCredentials()).
