#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script to add 2FA translations to all language files.
"""

import os
import sys
import xml.etree.ElementTree as ET

# Set console encoding to UTF-8 for Windows
if sys.platform == 'win32':
    import codecs
    sys.stdout = codecs.getwriter('utf-8')(sys.stdout.buffer, 'strict')
    sys.stderr = codecs.getwriter('utf-8')(sys.stderr.buffer, 'strict')

# Base directory
BASE_DIR = r"E:\Dropbox\GIT\paperless client\app\src\main\res"

# 2FA translations
TRANSLATIONS = {
    "en": {
        "two_factor_title": "Two-Factor Authentication",
        "two_factor_subtitle": "Security Code Required",
        "two_factor_description": "Enter the 6-digit code from your authenticator app",
        "two_factor_code_label": "6-digit code",
        "two_factor_code_hint": "The code will be automatically submitted",
        "two_factor_verify_button": "Verify Code"
    },
    "fr": {
        "two_factor_title": "Authentification à deux facteurs",
        "two_factor_subtitle": "Code de sécurité requis",
        "two_factor_description": "Entrez le code à 6 chiffres de votre application d'authentification",
        "two_factor_code_label": "Code à 6 chiffres",
        "two_factor_code_hint": "Le code sera soumis automatiquement",
        "two_factor_verify_button": "Vérifier le code"
    },
    "es": {
        "two_factor_title": "Autenticación de dos factores",
        "two_factor_subtitle": "Código de seguridad requerido",
        "two_factor_description": "Introduce el código de 6 dígitos de tu aplicación de autenticación",
        "two_factor_code_label": "Código de 6 dígitos",
        "two_factor_code_hint": "El código se enviará automáticamente",
        "two_factor_verify_button": "Verificar código"
    },
    "it": {
        "two_factor_title": "Autenticazione a due fattori",
        "two_factor_subtitle": "Codice di sicurezza richiesto",
        "two_factor_description": "Inserisci il codice a 6 cifre dalla tua app di autenticazione",
        "two_factor_code_label": "Codice a 6 cifre",
        "two_factor_code_hint": "Il codice verrà inviato automaticamente",
        "two_factor_verify_button": "Verifica codice"
    },
    "pt": {
        "two_factor_title": "Autenticação de dois fatores",
        "two_factor_subtitle": "Código de segurança necessário",
        "two_factor_description": "Digite o código de 6 dígitos do seu aplicativo autenticador",
        "two_factor_code_label": "Código de 6 dígitos",
        "two_factor_code_hint": "O código será enviado automaticamente",
        "two_factor_verify_button": "Verificar código"
    },
    "nl": {
        "two_factor_title": "Twee-factor authenticatie",
        "two_factor_subtitle": "Beveiligingscode vereist",
        "two_factor_description": "Voer de 6-cijferige code in van je authenticator-app",
        "two_factor_code_label": "6-cijferige code",
        "two_factor_code_hint": "De code wordt automatisch verzonden",
        "two_factor_verify_button": "Code verifiëren"
    },
    "pl": {
        "two_factor_title": "Uwierzytelnianie dwuskładnikowe",
        "two_factor_subtitle": "Wymagany kod bezpieczeństwa",
        "two_factor_description": "Wprowadź 6-cyfrowy kod z aplikacji uwierzytelniającej",
        "two_factor_code_label": "6-cyfrowy kod",
        "two_factor_code_hint": "Kod zostanie automatycznie przesłany",
        "two_factor_verify_button": "Zweryfikuj kod"
    },
    "sv": {
        "two_factor_title": "Tvåfaktorsautentisering",
        "two_factor_subtitle": "Säkerhetskod krävs",
        "two_factor_description": "Ange 6-siffrig kod från din autentiseringsapp",
        "two_factor_code_label": "6-siffrig kod",
        "two_factor_code_hint": "Koden skickas automatiskt",
        "two_factor_verify_button": "Verifiera kod"
    },
    "da": {
        "two_factor_title": "To-faktor godkendelse",
        "two_factor_subtitle": "Sikkerhedskode påkrævet",
        "two_factor_description": "Indtast 6-cifret kode fra din godkendelsesapp",
        "two_factor_code_label": "6-cifret kode",
        "two_factor_code_hint": "Koden sendes automatisk",
        "two_factor_verify_button": "Verificer kode"
    },
    "no": {
        "two_factor_title": "Tofaktor-autentisering",
        "two_factor_subtitle": "Sikkerhetskode påkrevd",
        "two_factor_description": "Skriv inn 6-sifret kode fra autentiseringsappen",
        "two_factor_code_label": "6-sifret kode",
        "two_factor_code_hint": "Koden sendes automatisk",
        "two_factor_verify_button": "Bekreft kode"
    },
    "fi": {
        "two_factor_title": "Kaksivaiheinen tunnistautuminen",
        "two_factor_subtitle": "Turvakoodi vaaditaan",
        "two_factor_description": "Syötä 6-numeroinen koodi todennussovelluksesta",
        "two_factor_code_label": "6-numeroinen koodi",
        "two_factor_code_hint": "Koodi lähetetään automaattisesti",
        "two_factor_verify_button": "Vahvista koodi"
    },
    "cs": {
        "two_factor_title": "Dvoufázové ověření",
        "two_factor_subtitle": "Vyžadován bezpečnostní kód",
        "two_factor_description": "Zadejte 6místný kód z vaší autentizační aplikace",
        "two_factor_code_label": "6místný kód",
        "two_factor_code_hint": "Kód bude automaticky odeslán",
        "two_factor_verify_button": "Ověřit kód"
    },
    "hu": {
        "two_factor_title": "Kétlépcsős hitelesítés",
        "two_factor_subtitle": "Biztonsági kód szükséges",
        "two_factor_description": "Írja be a 6 számjegyű kódot a hitelesítő alkalmazásból",
        "two_factor_code_label": "6 számjegyű kód",
        "two_factor_code_hint": "A kód automatikusan elküldésre kerül",
        "two_factor_verify_button": "Kód ellenőrzése"
    },
    "el": {
        "two_factor_title": "Έλεγχος ταυτότητας δύο παραγόντων",
        "two_factor_subtitle": "Απαιτείται κωδικός ασφαλείας",
        "two_factor_description": "Εισαγάγετε τον 6ψήφιο κωδικό από την εφαρμογή ελέγχου ταυτότητας",
        "two_factor_code_label": "6ψήφιος κωδικός",
        "two_factor_code_hint": "Ο κωδικός θα υποβληθεί αυτόματα",
        "two_factor_verify_button": "Επαλήθευση κωδικού"
    },
    "ro": {
        "two_factor_title": "Autentificare cu doi factori",
        "two_factor_subtitle": "Cod de securitate necesar",
        "two_factor_description": "Introduceți codul de 6 cifre din aplicația de autentificare",
        "two_factor_code_label": "Cod de 6 cifre",
        "two_factor_code_hint": "Codul va fi trimis automat",
        "two_factor_verify_button": "Verificați codul"
    },
    "tr": {
        "two_factor_title": "İki faktörlü kimlik doğrulama",
        "two_factor_subtitle": "Güvenlik kodu gerekli",
        "two_factor_description": "Kimlik doğrulama uygulamanızdan 6 haneli kodu girin",
        "two_factor_code_label": "6 haneli kod",
        "two_factor_code_hint": "Kod otomatik olarak gönderilecek",
        "two_factor_verify_button": "Kodu doğrula"
    }
}


def add_translations_to_file(file_path, translations):
    """Add translations to a specific strings.xml file"""
    try:
        # Parse XML
        tree = ET.parse(file_path)
        root = tree.getroot()

        # Check if translations already exist
        existing_keys = {elem.get('name') for elem in root.findall('.//string')}
        new_keys = set(translations.keys())

        if new_keys.issubset(existing_keys):
            print(f"[OK] {file_path} - Already has all translations")
            return False

        # Add missing translations
        added_count = 0
        for key, value in translations.items():
            if key not in existing_keys:
                # Create new string element
                string_elem = ET.Element('string', name=key)
                string_elem.text = value
                root.append(string_elem)
                added_count += 1

        # Write back to file with proper formatting
        ET.indent(tree, space='    ', level=0)
        tree.write(file_path, encoding='utf-8', xml_declaration=True)

        print(f"[OK] {file_path} - Added {added_count} translations")
        return True

    except Exception as e:
        print(f"[ERROR] {file_path} - Error: {e}")
        return False


def main():
    """Main function to add translations to all language files"""
    print("Adding 2FA translations to all language files...")
    print("=" * 60)

    total_updated = 0

    for lang_code, translations in TRANSLATIONS.items():
        # Determine the values folder name
        if lang_code == "en":
            folder_name = "values"  # English uses default values folder
        else:
            folder_name = f"values-{lang_code}"

        file_path = os.path.join(BASE_DIR, folder_name, "strings.xml")

        if os.path.exists(file_path):
            if add_translations_to_file(file_path, translations):
                total_updated += 1
        else:
            print(f"[ERROR] {file_path} - File not found")

    print("=" * 60)
    print(f"Summary: Updated {total_updated} language file(s)")
    print(f"Total languages: {len(TRANSLATIONS)}")


if __name__ == "__main__":
    main()
