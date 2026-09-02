// Minimal app-shell cache: keeps the queue-status page's static assets
// available if the network drops mid-wait, so the last-known position
// stays visible instead of a blank error page. API calls are always
// network-first (never cached) since queue position must stay live.
const CACHE_NAME = 'sarthi-shell-v1';
const SHELL_ASSETS = ['/', '/manifest.json'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  // Never cache API calls — queue position, results, and grievance data
  // must always be fetched live.
  if (request.url.includes('/api/')) return;

  event.respondWith(
    caches.match(request).then((cached) => cached || fetch(request))
  );
});
