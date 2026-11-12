# M3U Creator

M3U-/M3U8-Playlist laden, Gruppen auswählen, eigene Gruppen zusammenstellen und eine gefilterte M3U-Datei speichern. Stream-Adressen und Sender-Metadaten bleiben unverändert, die `#EXTM3U`-Kopfzeile (z. B. `x-tvg-url` für EPG) bleibt erhalten.

Es gibt zwei Versionen:

| | Web-Version | Android-App |
|---|---|---|
| Läuft auf | iPhone, PC, Android (Browser) | Android, Fire TV / Fire Stick |
| Laden per Link | direkt oder über Proxy (siehe unten) | direkt, ohne Einschränkung |
| Datei öffnen | ✓ | ✓ |
| Eigene Gruppen | ✓ | ✓ |

## Web-Version

Live unter **https://postmen1971.github.io/M3U-Creator/** (GitHub Pages, Quelle: Branch `main`, Ordner `/` (root)).

### Warum „Link laden“ im Browser oft scheitert
1. **CORS:** Die meisten IPTV-Server erlauben keinen Abruf aus einer fremden Webseite.
2. **http auf https:** Die Seite läuft über `https://`, viele Playlist-Links sind `http://`. Das blockiert jeder Browser (auch Safari auf dem iPhone).

Lösung: Die Seite versucht zuerst den direkten Abruf und nimmt dann automatisch einen **Proxy**. Einzustellen unter „Proxy-Einstellungen“:
- **Eigener Proxy (empfohlen):** kostenloser Cloudflare-Worker, Anleitung unten. Deine Zugangsdaten im Link gehen nur über deinen eigenen Worker.
- **Öffentliche Proxys (Notlösung):** allorigins.win / codetabs.com. Funktioniert ohne Einrichtung, aber der komplette Link (inkl. Benutzername/Passwort) geht an fremde Dienste.

Manche Anbieter sperren Zugriffe aus Rechenzentren. Dann hilft nur: Playlist herunterladen und über „Datei vom Gerät auswählen“ öffnen – oder die Android-App verwenden.

### Eigenen Proxy einrichten (Cloudflare Worker, kostenlos, ca. 5 Minuten)
1. Konto anlegen auf https://dash.cloudflare.com → **Workers & Pages** → **Create** → **Create Worker** (Name z. B. `m3u-proxy`) → **Deploy**.
2. **Edit code** → den Inhalt von [`proxy/worker.js`](proxy/worker.js) komplett einfügen → **Deploy**.
3. Unter **Settings → Runtime/Compatibility flags** `allow_custom_ports` hinzufügen (nötig für Links mit Port, z. B. `:8080`). Bei neuen Workern ist das meist schon Standard.
4. Die Worker-Adresse (z. B. `https://m3u-proxy.deinname.workers.dev`) in der Web-Version unter „Proxy-Einstellungen“ eintragen. Sie wird im Browser gespeichert.

Alternativ per Kommandozeile: `cd proxy && npx wrangler deploy`.

Der Worker nimmt nur Anfragen von `https://postmen1971.github.io` (und lokalen Dateien) an. Andere Adressen bei Bedarf in `ALLOWED_ORIGINS` ergänzen.

## Android-App

**Download (immer die neueste Version, ohne Login):**
https://github.com/Postmen1971/M3U-Creator/releases/latest/download/M3U-Creator.apk

Auf dem Fire Stick: App **Downloader** installieren, den Link oben eingeben, installieren. Vorher in den Einstellungen „Apps aus unbekannten Quellen“ für Downloader erlauben.

Jeder Push auf `main` baut die APK neu und ersetzt den Download automatisch.

Lokal bauen: Projekt in Android Studio öffnen (JDK 17, Android SDK 35) und `Run` drücken.

Funktionen:
- Laden per Link (auch `http://`, Weiterleitungen, große Listen bis 50 MB) – ohne CORS-Probleme.
- Playlist-Datei vom Gerät öffnen.
- Gruppen suchen und auswählen.
- Eigene Gruppe: Sender suchen, auswählen und in eine neue oder vorhandene Gruppe verschieben.
- Speichern als `.m3u` über den Android-Dateidialog.

## Grenzen
- Kein Xtream-Codes-Login-Formular; den vollständigen M3U-Link (`get.php?username=…&password=…&type=m3u_plus`) verwenden.
- Keine Wiedergabe, kein EPG-Download, keine Umgehung von DRM/Zugriffsschutz.
- Playlists bis 50 MB.
