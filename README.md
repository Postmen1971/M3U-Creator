# M3U Creator (Android)

Ein einfacher Android-Prototyp: M3U-/M3U8-Link laden, `group-title`-Gruppen erkennen, Gruppen auswählen und eine gefilterte M3U-Datei speichern.

## Öffnen und bauen
1. Android Studio installieren.
2. Dieses Projekt entpacken.
3. In Android Studio `Open` wählen und den Ordner `M3UCreator` öffnen.
4. Gradle-Synchronisierung abwarten.
5. Ein Android-Gerät per USB-Debugging verbinden oder einen Emulator starten.
6. `Run` drücken.

Voraussetzungen: Android Studio mit JDK 17, Android SDK Platform 35.

## Was der Prototyp macht
- HTTP- und HTTPS-Playlist-Links laden.
- M3U-Einträge über `#EXTINF` erkennen.
- Gruppen aus `group-title="..."` oder alternativ `#EXTGRP:` lesen.
- Gruppen auswählen und die zugehörigen Einträge unverändert übernehmen.
- Über den Android-Dateiauswahldialog als `.m3u` speichern.
- Die Stream-URLs und EXTINF-Metadaten der übernommenen Einträge nicht umschreiben.

## Grenzen / nächste Ausbaustufe
- Kein Login-Formular für Xtream Codes; ein vollständiger M3U-Link mit nötigen Parametern kann verwendet werden.
- Keine Senderwiedergabe, kein EPG-Download und keine Umgehung von DRM/Zugriffsschutz.
- Sehr große Playlists sind auf 50 MB begrenzt.
- Der Prototyp übernimmt die Senderblöcke und deren Metadaten. Globale Kommentare vor dem ersten Sender werden nicht in die Exportdatei übernommen; `#EXTM3U` wird neu geschrieben.
- Für produktiven Einsatz sollten zusätzliche Tests für ungewöhnliche M3U-Varianten, Zeichensätze und Server-Authentifizierung ergänzt werden.
