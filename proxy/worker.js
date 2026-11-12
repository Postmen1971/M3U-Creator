// M3U Creator – eigener CORS-Proxy für Cloudflare Workers (kostenlos)
//
// Aufruf: https://<dein-worker>.workers.dev/?url=<M3U-Link, URL-kodiert>
//
// Nur Seiten aus ALLOWED_ORIGINS dürfen den Proxy im Browser nutzen,
// damit dein Worker nicht von Fremden als offener Proxy missbraucht wird.

const ALLOWED_ORIGINS = [
  'https://postmen1971.github.io',
  'http://localhost',
  'null', // lokal geöffnete HTML-Datei (file://)
];

const MAX_BYTES = 50 * 1024 * 1024; // 50 MB

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Max-Age': '86400',
    'Vary': 'Origin',
  };
}

function originAllowed(origin) {
  if (!origin) return false;
  return ALLOWED_ORIGINS.some(o => origin === o || origin.startsWith(o + ':'));
}

export default {
  async fetch(request) {
    const origin = request.headers.get('Origin') || '';
    if (!originAllowed(origin)) {
      return new Response('Origin nicht erlaubt', { status: 403 });
    }
    const cors = corsHeaders(origin);
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors });
    if (request.method !== 'GET') return new Response('Nur GET', { status: 405, headers: cors });

    const target = new URL(request.url).searchParams.get('url');
    let url;
    try { url = new URL(target); } catch { return new Response('Parameter ?url= fehlt oder ist ungültig', { status: 400, headers: cors }); }
    if (!/^https?:$/.test(url.protocol)) return new Response('Nur http/https', { status: 400, headers: cors });

    let upstream;
    try {
      upstream = await fetch(url.toString(), {
        headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20', 'Accept': '*/*' },
        redirect: 'follow',
      });
    } catch (e) {
      return new Response('Server nicht erreichbar: ' + e.message, { status: 502, headers: cors });
    }

    const len = Number(upstream.headers.get('Content-Length') || 0);
    if (len > MAX_BYTES) return new Response('Playlist größer als 50 MB', { status: 413, headers: cors });

    return new Response(upstream.body, {
      status: upstream.status,
      headers: { ...cors, 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' },
    });
  },
};
