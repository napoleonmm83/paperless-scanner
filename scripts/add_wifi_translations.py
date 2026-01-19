#!/usr/bin/env python3
"""
Add WiFi-only banner translations to all 16 language files.
"""

import os
from pathlib import Path

# Translation strings
translations = {
    "de": {
        "banner": "KI-Vorschläge nur im WLAN verfügbar (Einstellung aktiv)",
        "button": "Trotzdem nutzen",
        "hint": 'KI-Analyse nutzt Bilddaten und ist nur im WLAN verfügbar, um mobile Daten zu sparen. Du kannst diese Einstellung unter \\"Einstellungen → KI-Assistent\\" ändern.'
    },
    "en": {
        "banner": "AI suggestions only available on WiFi (setting active)",
        "button": "Use anyway",
        "hint": 'AI analysis uses image data and is only available on WiFi to save mobile data. You can change this setting under \\"Settings → AI Assistant\\".'
    },
    "fr": {
        "banner": "Suggestions IA disponibles uniquement sur WiFi (paramètre actif)",
        "button": "Utiliser quand même",
        "hint": 'L\\'analyse IA utilise des données d\\'image et n\\'est disponible que sur WiFi pour économiser les données mobiles. Vous pouvez modifier ce paramètre dans \\"Paramètres → Assistant IA\\".'
    },
    "es": {
        "banner": "Sugerencias IA disponibles solo con WiFi (ajuste activo)",
        "button": "Usar de todos modos",
        "hint": 'El análisis IA utiliza datos de imagen y solo está disponible en WiFi para ahorrar datos móviles. Puedes cambiar este ajuste en \\"Configuración → Asistente IA\\".'
    },
    "it": {
        "banner": "Suggerimenti IA disponibili solo su WiFi (impostazione attiva)",
        "button": "Usa comunque",
        "hint": 'L\\'analisi IA utilizza dati immagine ed è disponibile solo su WiFi per risparmiare dati mobili. Puoi modificare questa impostazione in \\"Impostazioni → Assistente IA\\".'
    },
    "pt": {
        "banner": "Sugestões de IA disponíveis apenas no WiFi (configuração ativa)",
        "button": "Usar mesmo assim",
        "hint": 'A análise de IA usa dados de imagem e está disponível apenas no WiFi para economizar dados móveis. Você pode alterar esta configuração em \\"Configurações → Assistente de IA\\".'
    },
    "nl": {
        "banner": "AI-suggesties alleen beschikbaar op WiFi (instelling actief)",
        "button": "Toch gebruiken",
        "hint": 'AI-analyse gebruikt afbeeldingsgegevens en is alleen beschikbaar op WiFi om mobiele data te besparen. U kunt deze instelling wijzigen onder \\"Instellingen → AI-assistent\\".'
    },
    "pl": {
        "banner": "Sugestie AI dostępne tylko przez WiFi (ustawienie aktywne)",
        "button": "Użyj mimo to",
        "hint": 'Analiza AI używa danych obrazu i jest dostępna tylko przez WiFi, aby oszczędzać dane mobilne. Możesz zmienić to ustawienie w \\"Ustawienia → Asystent AI\\".'
    },
    "sv": {
        "banner": "AI-förslag endast tillgängliga via WiFi (inställning aktiv)",
        "button": "Använd ändå",
        "hint": 'AI-analys använder bilddata och är endast tillgänglig via WiFi för att spara mobildata. Du kan ändra denna inställning under \\"Inställningar → AI-assistent\\".'
    },
    "da": {
        "banner": "AI-forslag kun tilgængelige på WiFi (indstilling aktiv)",
        "button": "Brug alligevel",
        "hint": 'AI-analyse bruger billeddata og er kun tilgængelig på WiFi for at spare mobildata. Du kan ændre denne indstilling under \\"Indstillinger → AI-assistent\\".'
    },
    "no": {
        "banner": "AI-forslag kun tilgjengelig på WiFi (innstilling aktiv)",
        "button": "Bruk likevel",
        "hint": 'AI-analyse bruker bildedata og er kun tilgjengelig på WiFi for å spare mobildata. Du kan endre denne innstillingen under \\"Innstillinger → AI-assistent\\".'
    },
    "fi": {
        "banner": "AI-ehdotukset saatavilla vain WiFi:ssä (asetus aktiivinen)",
        "button": "Käytä silti",
        "hint": 'AI-analyysi käyttää kuvatietoja ja on saatavilla vain WiFi:ssä mobiilitiedon säästämiseksi. Voit muuttaa tätä asetusta kohdassa \\"Asetukset → AI-avustaja\\".'
    },
    "cs": {
        "banner": "Návrhy AI dostupné pouze na WiFi (nastavení aktivní)",
        "button": "Použít i tak",
        "hint": 'AI analýza používá obrazová data a je dostupná pouze na WiFi, aby ušetřila mobilní data. Toto nastavení můžete změnit v \\"Nastavení → AI asistent\\".'
    },
    "hu": {
        "banner": "AI javaslatok csak WiFi-n elérhetők (beállítás aktív)",
        "button": "Használat mindenképpen",
        "hint": 'Az AI elemzés képadatokat használ és csak WiFi-n érhető el a mobiladatok megtakarítása érdekében. Ezt a beállítást módosíthatja a \\"Beállítások → AI asszisztens\\" menüpontban.'
    },
    "el": {
        "banner": "Προτάσεις AI διαθέσιμες μόνο σε WiFi (ρύθμιση ενεργή)",
        "button": "Χρήση ούτως ή άλλως",
        "hint": 'Η ανάλυση AI χρησιμοποιεί δεδομένα εικόνας και είναι διαθέσιμη μόνο σε WiFi για εξοικονόμηση δεδομένων κινητής. Μπορείτε να αλλάξετε αυτήν τη ρύθμιση στο \\"Ρυθμίσεις → Βοηθός AI\\".'
    },
    "ro": {
        "banner": "Sugestii AI disponibile doar pe WiFi (setare activă)",
        "button": "Utilizează oricum",
        "hint": 'Analiza AI folosește date de imagine și este disponibilă doar pe WiFi pentru a economisi date mobile. Puteți modifica această setare în \\"Setări → Asistent AI\\".'
    },
    "tr": {
        "banner": "AI önerileri yalnızca WiFi\'de kullanılabilir (ayar etkin)",
        "button": "Yine de kullan",
        "hint": 'AI analizi görüntü verilerini kullanır ve mobil veriyi korumak için yalnızca WiFi üzerinden kullanılabilir. Bu ayarı \\"Ayarlar → AI Asistanı\\" bölümünden değiştirebilirsiniz.'
    }
}

# Language code to folder mapping
lang_folders = {
    "de": "values",
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
    "tr": "values-tr"
}

def add_translations():
    base_path = Path("E:/Dropbox/GIT/paperless client/app/src/main/res")

    for lang_code, folder_name in lang_folders.items():
        strings_file = base_path / folder_name / "strings.xml"

        if not strings_file.exists():
            print(f"⚠️  Skipping {folder_name} - file not found")
            continue

        # Read file
        with open(strings_file, 'r', encoding='utf-8') as f:
            content = f.read()

        # Check if already added
        if 'ai_wifi_only_banner' in content:
            print(f"✓ {folder_name} - already has translations")
            continue

        # Find insertion point (after premium_settings_new_tags_desc)
        marker = '<string name="premium_settings_new_tags_desc">'
        if marker not in content:
            print(f"⚠️  Skipping {folder_name} - marker not found")
            continue

        # Find end of line
        marker_pos = content.find(marker)
        line_end = content.find('</string>', marker_pos) + len('</string>')
        newline_pos = content.find('\n', line_end)

        # Build new strings
        trans = translations.get(lang_code, translations["en"])
        new_strings = f'''

    <string name="ai_wifi_only_banner">{trans["banner"]}</string>
    <string name="ai_wifi_only_override_button">{trans["button"]}</string>
    <string name="ai_wifi_required_hint">{trans["hint"]}</string>
'''

        # Insert
        new_content = content[:newline_pos] + new_strings + content[newline_pos:]

        # Write back
        with open(strings_file, 'w', encoding='utf-8') as f:
            f.write(new_content)

        print(f"✓ {folder_name} - translations added")

if __name__ == "__main__":
    print("Adding WiFi-only banner translations to all language files...")
    add_translations()
    print("\n✓ Done!")
