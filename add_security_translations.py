#!/usr/bin/env python3
"""
Add Security Section Translations to all 16 supported languages.
"""

import os
import re

# Translation mappings for new security settings strings
TRANSLATIONS = {
    "de": {
        # Already exists in values/strings.xml
    },
    "en": {
        "settings_section_security": "Security",
        "app_lock_change_password_subtitle": "Current password required",
        "app_lock_biometric_unlock": "Biometric Unlock",
        "app_lock_biometric_unlock_subtitle": "Use fingerprint or face recognition",
    },
    "fr": {
        "settings_section_security": "Sécurité",
        "app_lock_change_password_subtitle": "Mot de passe actuel requis",
        "app_lock_biometric_unlock": "Déverrouillage biométrique",
        "app_lock_biometric_unlock_subtitle": "Utiliser l'empreinte digitale ou la reconnaissance faciale",
    },
    "es": {
        "settings_section_security": "Seguridad",
        "app_lock_change_password_subtitle": "Contraseña actual requerida",
        "app_lock_biometric_unlock": "Desbloqueo biométrico",
        "app_lock_biometric_unlock_subtitle": "Usar huella digital o reconocimiento facial",
    },
    "it": {
        "settings_section_security": "Sicurezza",
        "app_lock_change_password_subtitle": "Password corrente richiesta",
        "app_lock_biometric_unlock": "Sblocco biometrico",
        "app_lock_biometric_unlock_subtitle": "Usa impronta digitale o riconoscimento facciale",
    },
    "pt": {
        "settings_section_security": "Segurança",
        "app_lock_change_password_subtitle": "Senha atual necessária",
        "app_lock_biometric_unlock": "Desbloqueio biométrico",
        "app_lock_biometric_unlock_subtitle": "Usar impressão digital ou reconhecimento facial",
    },
    "nl": {
        "settings_section_security": "Beveiliging",
        "app_lock_change_password_subtitle": "Huidig wachtwoord vereist",
        "app_lock_biometric_unlock": "Biometrisch ontgrendelen",
        "app_lock_biometric_unlock_subtitle": "Gebruik vingerafdruk of gezichtsherkenning",
    },
    "pl": {
        "settings_section_security": "Bezpieczeństwo",
        "app_lock_change_password_subtitle": "Wymagane aktualne hasło",
        "app_lock_biometric_unlock": "Odblokowanie biometryczne",
        "app_lock_biometric_unlock_subtitle": "Użyj odcisku palca lub rozpoznawania twarzy",
    },
    "sv": {
        "settings_section_security": "Säkerhet",
        "app_lock_change_password_subtitle": "Nuvarande lösenord krävs",
        "app_lock_biometric_unlock": "Biometrisk upplåsning",
        "app_lock_biometric_unlock_subtitle": "Använd fingeravtryck eller ansiktsigenkänning",
    },
    "da": {
        "settings_section_security": "Sikkerhed",
        "app_lock_change_password_subtitle": "Nuværende adgangskode påkrævet",
        "app_lock_biometric_unlock": "Biometrisk oplåsning",
        "app_lock_biometric_unlock_subtitle": "Brug fingeraftryk eller ansigtsgenkendelse",
    },
    "no": {
        "settings_section_security": "Sikkerhet",
        "app_lock_change_password_subtitle": "Nåværende passord påkrevd",
        "app_lock_biometric_unlock": "Biometrisk opplåsing",
        "app_lock_biometric_unlock_subtitle": "Bruk fingeravtrykk eller ansiktsgjenkjenning",
    },
    "fi": {
        "settings_section_security": "Turvallisuus",
        "app_lock_change_password_subtitle": "Nykyinen salasana vaaditaan",
        "app_lock_biometric_unlock": "Biometrinen avaus",
        "app_lock_biometric_unlock_subtitle": "Käytä sormenjälkeä tai kasvojentunnistusta",
    },
    "cs": {
        "settings_section_security": "Zabezpečení",
        "app_lock_change_password_subtitle": "Vyžaduje se aktuální heslo",
        "app_lock_biometric_unlock": "Biometrické odemknutí",
        "app_lock_biometric_unlock_subtitle": "Použít otisk prstu nebo rozpoznávání obličeje",
    },
    "hu": {
        "settings_section_security": "Biztonság",
        "app_lock_change_password_subtitle": "Jelenlegi jelszó szükséges",
        "app_lock_biometric_unlock": "Biometrikus feloldás",
        "app_lock_biometric_unlock_subtitle": "Ujjlenyomat vagy arcfelismerés használata",
    },
    "el": {
        "settings_section_security": "Ασφάλεια",
        "app_lock_change_password_subtitle": "Απαιτείται τρέχων κωδικός πρόσβασης",
        "app_lock_biometric_unlock": "Βιομετρικό ξεκλείδωμα",
        "app_lock_biometric_unlock_subtitle": "Χρήση δακτυλικού αποτυπώματος ή αναγνώρισης προσώπου",
    },
    "ro": {
        "settings_section_security": "Securitate",
        "app_lock_change_password_subtitle": "Necesită parola curentă",
        "app_lock_biometric_unlock": "Deblocare biometrică",
        "app_lock_biometric_unlock_subtitle": "Folosește amprentă sau recunoaștere facială",
    },
    "tr": {
        "settings_section_security": "Güvenlik",
        "app_lock_change_password_subtitle": "Mevcut şifre gerekli",
        "app_lock_biometric_unlock": "Biyometrik kilit açma",
        "app_lock_biometric_unlock_subtitle": "Parmak izi veya yüz tanıma kullan",
    },
}

# Language code to folder mapping
LANG_FOLDERS = {
    "en": "values-en",
    "fr": "values-fr",
    "es": "values-es",
    "it": "values-it",
    "pt": "values-pt",
    "nl": "values-nl",
    "pl": "values-pl",
    "sv": "values-sv",
    "da": "values-da",
    "no": "values-no",
    "fi": "values-fi",
    "cs": "values-cs",
    "hu": "values-hu",
    "el": "values-el",
    "ro": "values-ro",
    "tr": "values-tr",
}

RES_DIR = "app/src/main/res"


def add_translations_to_file(lang_code: str, translations: dict):
    """Add translations to a specific language file."""
    folder = LANG_FOLDERS.get(lang_code)
    if not folder:
        print(f"ERROR Unknown language code: {lang_code}")
        return

    strings_file = os.path.join(RES_DIR, folder, "strings.xml")
    if not os.path.exists(strings_file):
        print(f"ERROR File not found: {strings_file}")
        return

    # Read existing content
    with open(strings_file, "r", encoding="utf-8") as f:
        content = f.read()

    # Check if strings already exist
    existing_keys = set(re.findall(r'<string name="([^"]+)">', content))

    # Build translation block
    new_strings = []
    for key, value in translations.items():
        if key not in existing_keys:
            new_strings.append(f'    <string name="{key}">{value}</string>')

    if not new_strings:
        print(f"OK {lang_code}: All strings already exist")
        return

    # Find insertion point (before </resources>)
    translation_block = "\n".join(new_strings) + "\n"

    # Insert before closing tag
    if "</resources>" in content:
        content = content.replace("</resources>", translation_block + "</resources>")
    else:
        print(f"ERROR {lang_code}: No </resources> tag found")
        return

    # Write updated content
    with open(strings_file, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"OK {lang_code}: Added {len(new_strings)} new strings")


def main():
    print("Adding Security Section translations to 16 languages...\n")

    for lang_code, translations in TRANSLATIONS.items():
        if lang_code == "de":
            print(f"de: Skipping (already exists in values/strings.xml)")
            continue

        add_translations_to_file(lang_code, translations)

    print("\nTranslation update complete!")


if __name__ == "__main__":
    main()
