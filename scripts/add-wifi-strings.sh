#!/bin/bash

# Script to add WiFi-banner strings to all translation files

STRINGS_TO_ADD='
    <string name="ai_wifi_only_banner">TRANSLATION_BANNER</string>
    <string name="ai_wifi_only_override_button">TRANSLATION_BUTTON</string>
    <string name="ai_wifi_required_hint">TRANSLATION_HINT</string>
'

# English
sed -i '/<string name="premium_settings_new_tags_desc">AI can suggest tags that don'"'"'t exist yet<\/string>/a\
\
    <string name="ai_wifi_only_banner">AI suggestions only available on WiFi (setting active)<\/string>\
    <string name="ai_wifi_only_override_button">Use anyway<\/string>\
    <string name="ai_wifi_required_hint">AI analysis uses image data and is only available on WiFi to save mobile data. You can change this setting under "Settings → AI Assistant".<\/string>' app/src/main/res/values-en/strings.xml

# French
sed -i '/<string name="premium_settings_new_tags_desc">L'"'"'IA peut suggérer des tags qui n'"'"'existent pas encore<\/string>/a\
\
    <string name="ai_wifi_only_banner">Suggestions IA disponibles uniquement sur WiFi (paramètre actif)<\/string>\
    <string name="ai_wifi_only_override_button">Utiliser quand même<\/string>\
    <string name="ai_wifi_required_hint">L'"'"'analyse IA utilise des données d'"'"'image et n'"'"'est disponible que sur WiFi pour économiser les données mobiles. Vous pouvez modifier ce paramètre dans "Paramètres → Assistant IA".<\/string>' app/src/main/res/values-fr/strings.xml

echo "✓ WiFi strings added to all translation files"
