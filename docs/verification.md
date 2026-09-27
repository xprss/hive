# Verifica su dispositivo

La CI costruisce un APK debug. La prova funzionale richiede un telefono Android 11 (API 30) e uno con una versione recente, entrambi con blocco schermo sicuro, fotocamera e Termux. Annotare modello, API, versione Termux e versione APK prima della prova.

1. Autorizzare Termux nell'app. `privcam status` deve mostrare nessuna sessione. Tentare la socket da un'app con un UID diverso: deve essere rifiutata.
2. Eseguire `privcam start`, annullare l'autenticazione: nessuna sessione deve partire. Ripetere e autenticarsi. Verificare che un secondo `start` sia rifiutato.
3. Aprire `privcam camera`, scattare foto e registrare un video con audio. `privcam status` deve mostrare conteggi e spazio, senza nomi né anteprime. Verificare con galleria di sistema e altra app che non vedano i media. Verificare che screenshot e anteprima nelle app recenti siano oscurati.
4. Aprire `privcam view`, annullare l'autenticazione: nessuna anteprima. Ripetere, verificare foto e video nell'app.
5. Riavviare il telefono durante la sessione; riaprire Hive, autenticarsi e verificare conteggi, anteprime e assenza dalla galleria pubblica.
6. Arrestare forzatamente l'app durante una registrazione. Riaprire e autenticarsi; verificare che un file parziale recuperabile resti privato. Segnalare se non riproducibile.
7. Liberare poco spazio o riempire lo storage di test, scattare e tentare `end`. L'errore non deve cancellare i file privati; liberare spazio e riprovare.
8. Eseguire `privcam end`, annullare l'autenticazione: nessuna pubblicazione. Ripetere e interrompere l'app durante la pubblicazione di molti file. Riaprire e riprendere. Verificare un solo elemento per ogni cattura, tutti visibili e apribili in galleria.
9. Su un dispositivo di test separato, invalidare o rimuovere le credenziali che proteggono Keystore durante una sessione. L'app deve mostrare un errore di sblocco, senza pubblicare dati incompleti. Questa prova può rendere la sessione irrecuperabile.

La verifica di visibilità va fatta con un'altra app o tramite MediaStore, non soltanto con il file manager. La riga `IS_PENDING=1` può essere vista dall'app proprietaria, ma non deve apparire nelle normali query delle altre app.
