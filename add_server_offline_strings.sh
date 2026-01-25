#!/bin/bash

# Script to add server offline strings to all language files

# Define the string block for each language
declare -A translations

translations[en]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server unreachable</string>
    <string name="server_offline_no_internet">No internet connection available</string>
    <string name="server_offline_dns">Server address could not be resolved. Please check server URL in settings.</string>
    <string name="server_offline_connection_refused">Server is unreachable. Please check if server is running.</string>
    <string name="server_offline_timeout">Server is not responding. Please try again later.</string>
    <string name="server_offline_vpn_required">VPN connection required. Please activate VPN and try again.</string>
    <string name="server_offline_unknown">Server connection failed. Please check settings.</string>
    <string name="retry">Retry</string>
    <string name="check_server_settings">Check server settings</string>
'

translations[fr]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Serveur injoignable</string>
    <string name="server_offline_no_internet">Aucune connexion Internet disponible</string>
    <string name="server_offline_dns">L'\''adresse du serveur n'\''a pas pu être résolue. Veuillez vérifier l'\''URL du serveur dans les paramètres.</string>
    <string name="server_offline_connection_refused">Le serveur est injoignable. Veuillez vérifier si le serveur fonctionne.</string>
    <string name="server_offline_timeout">Le serveur ne répond pas. Veuillez réessayer plus tard.</string>
    <string name="server_offline_vpn_required">Connexion VPN requise. Veuillez activer le VPN et réessayer.</string>
    <string name="server_offline_unknown">Échec de la connexion au serveur. Veuillez vérifier les paramètres.</string>
    <string name="retry">Réessayer</string>
    <string name="check_server_settings">Vérifier les paramètres du serveur</string>
'

translations[es]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Servidor inalcanzable</string>
    <string name="server_offline_no_internet">No hay conexión a Internet disponible</string>
    <string name="server_offline_dns">No se pudo resolver la dirección del servidor. Compruebe la URL del servidor en la configuración.</string>
    <string name="server_offline_connection_refused">El servidor no está disponible. Compruebe si el servidor está funcionando.</string>
    <string name="server_offline_timeout">El servidor no responde. Inténtelo de nuevo más tarde.</string>
    <string name="server_offline_vpn_required">Se requiere conexión VPN. Active el VPN e inténtelo de nuevo.</string>
    <string name="server_offline_unknown">Falló la conexión al servidor. Compruebe la configuración.</string>
    <string name="retry">Reintentar</string>
    <string name="check_server_settings">Comprobar configuración del servidor</string>
'

translations[it]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server non raggiungibile</string>
    <string name="server_offline_no_internet">Nessuna connessione Internet disponibile</string>
    <string name="server_offline_dns">Impossibile risolvere l'\''indirizzo del server. Controllare l'\''URL del server nelle impostazioni.</string>
    <string name="server_offline_connection_refused">Il server non è raggiungibile. Verificare se il server è in esecuzione.</string>
    <string name="server_offline_timeout">Il server non risponde. Riprovare più tardi.</string>
    <string name="server_offline_vpn_required">Connessione VPN richiesta. Attivare la VPN e riprovare.</string>
    <string name="server_offline_unknown">Connessione al server fallita. Controllare le impostazioni.</string>
    <string name="retry">Riprova</string>
    <string name="check_server_settings">Controlla impostazioni server</string>
'

translations[pt]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Servidor inacessível</string>
    <string name="server_offline_no_internet">Nenhuma conexão à Internet disponível</string>
    <string name="server_offline_dns">Endereço do servidor não pôde ser resolvido. Verifique o URL do servidor nas configurações.</string>
    <string name="server_offline_connection_refused">O servidor está inacessível. Verifique se o servidor está em execução.</string>
    <string name="server_offline_timeout">O servidor não responde. Tente novamente mais tarde.</string>
    <string name="server_offline_vpn_required">Conexão VPN necessária. Ative a VPN e tente novamente.</string>
    <string name="server_offline_unknown">Falha na conexão com o servidor. Verifique as configurações.</string>
    <string name="retry">Tentar novamente</string>
    <string name="check_server_settings">Verificar configurações do servidor</string>
'

translations[nl]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server onbereikbaar</string>
    <string name="server_offline_no_internet">Geen internetverbinding beschikbaar</string>
    <string name="server_offline_dns">Serveradres kon niet worden opgelost. Controleer de server-URL in de instellingen.</string>
    <string name="server_offline_connection_refused">Server is onbereikbaar. Controleer of de server draait.</string>
    <string name="server_offline_timeout">Server reageert niet. Probeer het later opnieuw.</string>
    <string name="server_offline_vpn_required">VPN-verbinding vereist. Activeer VPN en probeer opnieuw.</string>
    <string name="server_offline_unknown">Serververbinding mislukt. Controleer de instellingen.</string>
    <string name="retry">Opnieuw proberen</string>
    <string name="check_server_settings">Serverinstellingen controleren</string>
'

translations[pl]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Serwer niedostępny</string>
    <string name="server_offline_no_internet">Brak dostępnego połączenia internetowego</string>
    <string name="server_offline_dns">Nie można rozpoznać adresu serwera. Sprawdź adres URL serwera w ustawieniach.</string>
    <string name="server_offline_connection_refused">Serwer jest niedostępny. Sprawdź, czy serwer jest uruchomiony.</string>
    <string name="server_offline_timeout">Serwer nie odpowiada. Spróbuj ponownie później.</string>
    <string name="server_offline_vpn_required">Wymagane połączenie VPN. Aktywuj VPN i spróbuj ponownie.</string>
    <string name="server_offline_unknown">Połączenie z serwerem nie powiodło się. Sprawdź ustawienia.</string>
    <string name="retry">Spróbuj ponownie</string>
    <string name="check_server_settings">Sprawdź ustawienia serwera</string>
'

translations[sv]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server onåbar</string>
    <string name="server_offline_no_internet">Ingen internetanslutning tillgänglig</string>
    <string name="server_offline_dns">Serveradressen kunde inte lösas. Kontrollera server-URL i inställningar.</string>
    <string name="server_offline_connection_refused">Servern är onåbar. Kontrollera om servern körs.</string>
    <string name="server_offline_timeout">Servern svarar inte. Försök igen senare.</string>
    <string name="server_offline_vpn_required">VPN-anslutning krävs. Aktivera VPN och försök igen.</string>
    <string name="server_offline_unknown">Serveranslutning misslyckades. Kontrollera inställningarna.</string>
    <string name="retry">Försök igen</string>
    <string name="check_server_settings">Kontrollera serverinställningar</string>
'

translations[da]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server ikke tilgængelig</string>
    <string name="server_offline_no_internet">Ingen internetforbindelse tilgængelig</string>
    <string name="server_offline_dns">Serveradressen kunne ikke løses. Kontroller server-URL i indstillinger.</string>
    <string name="server_offline_connection_refused">Serveren er ikke tilgængelig. Kontroller om serveren kører.</string>
    <string name="server_offline_timeout">Serveren svarer ikke. Prøv igen senere.</string>
    <string name="server_offline_vpn_required">VPN-forbindelse påkrævet. Aktiver VPN og prøv igen.</string>
    <string name="server_offline_unknown">Serverforbindelse mislykkedes. Kontroller indstillingerne.</string>
    <string name="retry">Prøv igen</string>
    <string name="check_server_settings">Kontroller serverindstillinger</string>
'

translations[no]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server utilgjengelig</string>
    <string name="server_offline_no_internet">Ingen internettforbindelse tilgjengelig</string>
    <string name="server_offline_dns">Serveradressen kunne ikke løses. Kontroller server-URL i innstillinger.</string>
    <string name="server_offline_connection_refused">Serveren er utilgjengelig. Kontroller om serveren kjører.</string>
    <string name="server_offline_timeout">Serveren svarer ikke. Prøv igjen senere.</string>
    <string name="server_offline_vpn_required">VPN-forbindelse påkrevd. Aktiver VPN og prøv igjen.</string>
    <string name="server_offline_unknown">Serverforbindelse mislyktes. Kontroller innstillingene.</string>
    <string name="retry">Prøv igjen</string>
    <string name="check_server_settings">Kontroller serverinnstillinger</string>
'

translations[fi]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Palvelin ei tavoitettavissa</string>
    <string name="server_offline_no_internet">Ei internet-yhteyttä saatavilla</string>
    <string name="server_offline_dns">Palvelimen osoitetta ei voitu selvittää. Tarkista palvelimen URL asetuksista.</string>
    <string name="server_offline_connection_refused">Palvelin ei ole tavoitettavissa. Tarkista, että palvelin on käynnissä.</string>
    <string name="server_offline_timeout">Palvelin ei vastaa. Yritä myöhemmin uudelleen.</string>
    <string name="server_offline_vpn_required">VPN-yhteys vaaditaan. Aktivoi VPN ja yritä uudelleen.</string>
    <string name="server_offline_unknown">Palvelinyhteys epäonnistui. Tarkista asetukset.</string>
    <string name="retry">Yritä uudelleen</string>
    <string name="check_server_settings">Tarkista palvelinasetukset</string>
'

translations[cs]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server nedostupný</string>
    <string name="server_offline_no_internet">Není k dispozici připojení k internetu</string>
    <string name="server_offline_dns">Adresu serveru se nepodařilo přeložit. Zkontrolujte URL serveru v nastavení.</string>
    <string name="server_offline_connection_refused">Server je nedostupný. Zkontrolujte, zda server běží.</string>
    <string name="server_offline_timeout">Server neodpovídá. Zkuste to znovu později.</string>
    <string name="server_offline_vpn_required">Vyžadováno připojení VPN. Aktivujte VPN a zkuste to znovu.</string>
    <string name="server_offline_unknown">Připojení k serveru selhalo. Zkontrolujte nastavení.</string>
    <string name="retry">Zkusit znovu</string>
    <string name="check_server_settings">Zkontrolovat nastavení serveru</string>
'

translations[hu]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Szerver nem elérhető</string>
    <string name="server_offline_no_internet">Nincs elérhető internetkapcsolat</string>
    <string name="server_offline_dns">A szervercímet nem sikerült feloldani. Ellenőrizze a szerver URL-címét a beállításokban.</string>
    <string name="server_offline_connection_refused">A szerver nem elérhető. Ellenőrizze, hogy a szerver fut-e.</string>
    <string name="server_offline_timeout">A szerver nem válaszol. Próbálja újra később.</string>
    <string name="server_offline_vpn_required">VPN-kapcsolat szükséges. Aktiválja a VPN-t és próbálja újra.</string>
    <string name="server_offline_unknown">Szerverkapcsolat sikertelen. Ellenőrizze a beállításokat.</string>
    <string name="retry">Újra</string>
    <string name="check_server_settings">Szerverbeállítások ellenőrzése</string>
'

translations[el]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Ο διακομιστής δεν είναι προσβάσιμος</string>
    <string name="server_offline_no_internet">Δεν υπάρχει διαθέσιμη σύνδεση στο Διαδίκτυο</string>
    <string name="server_offline_dns">Δεν ήταν δυνατή η επίλυση της διεύθυνσης του διακομιστή. Ελέγξτε τη διεύθυνση URL του διακομιστή στις ρυθμίσεις.</string>
    <string name="server_offline_connection_refused">Ο διακομιστής δεν είναι προσβάσιμος. Ελέγξτε αν ο διακομιστής λειτουργεί.</string>
    <string name="server_offline_timeout">Ο διακομιστής δεν απαντά. Δοκιμάστε ξανά αργότερα.</string>
    <string name="server_offline_vpn_required">Απαιτείται σύνδεση VPN. Ενεργοποιήστε το VPN και δοκιμάστε ξανά.</string>
    <string name="server_offline_unknown">Η σύνδεση με τον διακομιστή απέτυχε. Ελέγξτε τις ρυθμίσεις.</string>
    <string name="retry">Δοκιμή ξανά</string>
    <string name="check_server_settings">Έλεγχος ρυθμίσεων διακομιστή</string>
'

translations[ro]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Server inaccesibil</string>
    <string name="server_offline_no_internet">Nicio conexiune la internet disponibilă</string>
    <string name="server_offline_dns">Adresa serverului nu a putut fi rezolvată. Verificați URL-ul serverului în setări.</string>
    <string name="server_offline_connection_refused">Serverul este inaccesibil. Verificați dacă serverul funcționează.</string>
    <string name="server_offline_timeout">Serverul nu răspunde. Încercați din nou mai târziu.</string>
    <string name="server_offline_vpn_required">Conexiune VPN necesară. Activați VPN și încercați din nou.</string>
    <string name="server_offline_unknown">Conexiunea la server a eșuat. Verificați setările.</string>
    <string name="retry">Reîncercați</string>
    <string name="check_server_settings">Verificați setările serverului</string>
'

translations[tr]='
    <!-- Server Offline Detection Error Messages -->
    <string name="server_offline_title">Sunucuya erişilemiyor</string>
    <string name="server_offline_no_internet">Kullanılabilir internet bağlantısı yok</string>
    <string name="server_offline_dns">Sunucu adresi çözümlenemedi. Lütfen ayarlardaki sunucu URL\'sini kontrol edin.</string>
    <string name="server_offline_connection_refused">Sunucuya erişilemiyor. Lütfen sunucunun çalıştığını kontrol edin.</string>
    <string name="server_offline_timeout">Sunucu yanıt vermiyor. Lütfen daha sonra tekrar deneyin.</string>
    <string name="server_offline_vpn_required">VPN bağlantısı gerekli. Lütfen VPN\'i etkinleştirin ve tekrar deneyin.</string>
    <string name="server_offline_unknown">Sunucu bağlantısı başarısız oldu. Lütfen ayarları kontrol edin.</string>
    <string name="retry">Tekrar dene</string>
    <string name="check_server_settings">Sunucu ayarlarını kontrol et</string>
'

# Base directory
RES_DIR="app/src/main/res"

# Languages to process
LANGUAGES=(en fr es it pt nl pl sv da no fi cs hu el ro tr)

# Function to add strings to a language file
add_strings_to_file() {
    local lang=$1
    local file="$RES_DIR/values-$lang/strings.xml"

    if [ ! -f "$file" ]; then
        echo "Warning: File not found: $file"
        return
    fi

    # Find the line with error_server_unreachable and add strings after it
    # Using a temp file to safely modify
    awk -v strings="${translations[$lang]}" '
        /error_server_unreachable/ {
            print
            getline
            print
            print strings
            next
        }
        {print}
    ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"

    echo "Added strings to $lang"
}

# Process all languages
for lang in "${LANGUAGES[@]}"; do
    add_strings_to_file "$lang"
done

echo "Done! All translations added."
