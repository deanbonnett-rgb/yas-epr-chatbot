// Caches the app shell so it opens instantly (and offline, e.g. in a shop
// with no signal). Firestore queues changes offline and syncs when back.
const CACHE = "shopping-v16";
const SHELL = ["./", "index.html", "app.js?v=16", "firebase-config.js?v=16", "manifest.webmanifest", "icon.svg"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" })))));
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  );
  self.clients.claim();
});

// Network first for our own files so updates show up; fall back to cache offline.
// "no-cache" makes the browser check with GitHub every time instead of reusing
// a copy that could be up to 10 minutes old.
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    fetch(new Request(e.request.url, { cache: "no-cache" }))
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});

// Calendar reminders sent by the home connector (see dashboard settings).
self.addEventListener("push", (e) => {
  let m = {};
  try { m = e.data ? e.data.json() : {}; } catch { m = { title: e.data ? e.data.text() : "" }; }
  e.waitUntil(self.registration.showNotification(m.title || "Calendar reminder", {
    body: m.body || "", tag: m.tag || undefined, renotify: !!m.tag, data: { url: m.url || "dashboard/#cal" },
  }));
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const url = new URL(e.notification.data?.url || "dashboard/#cal", self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((wins) => {
    const win = wins.find((w) => w.url.startsWith(self.registration.scope));
    if (win) return win.navigate(url).then((w) => (w || win).focus()).catch(() => win.focus());
    return self.clients.openWindow(url);
  }));
});
