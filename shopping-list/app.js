import { firebaseConfig } from "./firebase-config.js";

const $ = (id) => document.getElementById(id);
const FIREBASE = "https://www.gstatic.com/firebasejs/10.12.2";

// ---------- List code (which shared list this phone is looking at) ----------

const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I mix-ups
function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  return Array.from(bytes, (b) => CODE_CHARS[b % CODE_CHARS.length]).join("");
}
function cleanCode(s) {
  return (s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
}
function storageGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
function storageSet(k, v) { try { localStorage.setItem(k, v); } catch {} }

function resolveListCode() {
  // A shared link looks like .../#list=ABCDEFGHJK
  const fromLink = cleanCode(new URLSearchParams(location.hash.slice(1)).get("list"));
  const code = fromLink.length >= 10 ? fromLink : cleanCode(storageGet("listCode")) || newCode();
  storageSet("listCode", code);
  history.replaceState(null, "", location.pathname + location.search);
  return code;
}

// ---------- Dates ----------

const pad = (n) => String(n).padStart(2, "0");
const toISO = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const fromISO = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
function today() { const d = new Date(); d.setHours(0, 0, 0, 0); return d; }
function daysFromToday(n) { const d = today(); d.setDate(d.getDate() + n); return toISO(d); }

// ---------- Storage back ends ----------

// Shared, real-time: Firebase Firestore.
async function firebaseStore(code) {
  const { initializeApp } = await import(`${FIREBASE}/firebase-app.js`);
  const fs = await import(`${FIREBASE}/firebase-firestore.js`);
  const app = initializeApp(firebaseConfig);
  const db = fs.initializeFirestore(app, {
    localCache: fs.persistentLocalCache({ tabManager: fs.persistentMultipleTabManager() }),
  });
  const listRef = fs.doc(db, "lists", code);
  const itemsRef = fs.collection(listRef, "items");

  return {
    subscribe(onMeta, onItems, onStatus) {
      fs.onSnapshot(listRef, { includeMetadataChanges: true }, (snap) => {
        onMeta(snap.data() || {});
        onStatus(snap.metadata.fromCache ? "Offline – changes will sync when you're back online" : "Synced");
      }, (err) => onStatus("Sync error: " + err.message));
      fs.onSnapshot(fs.query(itemsRef, fs.orderBy("createdAt")), (snap) => {
        onItems(snap.docs.map((d) => ({ id: d.id, ...d.data() })));
      }, (err) => onStatus("Sync error: " + err.message));
    },
    setDate: (iso) => fs.setDoc(listRef, { lookFor: iso }, { merge: true }),
    add: (name) => fs.addDoc(itemsRef, { name, got: false, createdAt: Date.now() }),
    setGot: (id, got) => fs.updateDoc(fs.doc(itemsRef, id), { got }),
    remove: (id) => fs.deleteDoc(fs.doc(itemsRef, id)),
    // Batched writes (not transactions) so these still work with no signal in the shop.
    async finishShop(ids, pastShops) {
      const batch = fs.writeBatch(db);
      batch.set(listRef, { history: pastShops }, { merge: true });
      ids.forEach((id) => batch.delete(fs.doc(itemsRef, id)));
      await batch.commit();
    },
    setHistory: (pastShops) => fs.setDoc(listRef, { history: pastShops }, { merge: true }),
    async addMany(names) {
      const batch = fs.writeBatch(db);
      const now = Date.now();
      names.forEach((name, i) => batch.set(fs.doc(itemsRef), { name, got: false, createdAt: now + i }));
      await batch.commit();
    },
  };
}

// This-phone-only: used until Firebase is configured, so the app can be tried out.
function localStore(code) {
  const key = "list:" + code;
  let state = (() => { try { return JSON.parse(storageGet(key)) || {}; } catch { return {}; } })();
  state.items ||= [];
  let listeners = null;
  const save = () => {
    storageSet(key, JSON.stringify(state));
    listeners?.onMeta({ lookFor: state.lookFor, history: state.history });
    listeners?.onItems(state.items.slice());
  };
  return {
    subscribe(onMeta, onItems, onStatus) { listeners = { onMeta, onItems }; onStatus("Saved on this phone only"); save(); },
    setDate(iso) { state.lookFor = iso; save(); },
    add(name) { state.items.push({ id: crypto.randomUUID(), name, got: false, createdAt: Date.now() }); save(); },
    setGot(id, got) { const it = state.items.find((i) => i.id === id); if (it) it.got = got; save(); },
    remove(id) { state.items = state.items.filter((i) => i.id !== id); save(); },
    finishShop(ids, pastShops) { state.items = state.items.filter((i) => !ids.includes(i.id)); state.history = pastShops; save(); },
    setHistory(pastShops) { state.history = pastShops; save(); },
    addMany(names) {
      const now = Date.now();
      names.forEach((name, i) => state.items.push({ id: crypto.randomUUID(), name, got: false, createdAt: now + i }));
      save();
    },
  };
}

// ---------- UI ----------

const code = resolveListCode();
const shareUrl = `${location.origin}${location.pathname}#list=${code}`;
const MAX_HISTORY = 20;
let store;
let items = [];
let pastShops = []; // past shops, newest first: { id, at, lookFor, items: [names] }

function renderDate(iso) {
  if (!iso) {
    $("dateValue").textContent = "Pick a date";
    $("dateSub").textContent = "Tap a button below to set it";
    return;
  }
  const d = fromISO(iso);
  $("dateValue").textContent = d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
  const diff = Math.round((d - today()) / 86400000);
  $("dateSub").textContent =
    diff === 0 ? "That's today" :
    diff === 1 ? "That's tomorrow" :
    diff > 1 ? `That's ${diff} days from today` :
    `That was ${-diff} day${diff === -1 ? "" : "s"} ago – set a new date for this shop`;
  $("datePicker").value = iso;
}

function itemRow(item) {
  const li = document.createElement("li");
  li.className = item.got ? "got" : "";
  const tick = document.createElement("button");
  tick.className = "tick";
  tick.textContent = item.got ? "✓" : "";
  tick.setAttribute("aria-label", (item.got ? "Untick " : "Tick ") + item.name);
  tick.onclick = () => store.setGot(item.id, !item.got);
  const name = document.createElement("span");
  name.className = "name";
  name.textContent = item.name;
  name.onclick = tick.onclick;
  const del = document.createElement("button");
  del.className = "del";
  del.textContent = "✕";
  del.setAttribute("aria-label", "Delete " + item.name);
  del.onclick = () => store.remove(item.id);
  li.append(tick, name, del);
  return li;
}

function renderItems() {
  const todo = items.filter((i) => !i.got);
  const got = items.filter((i) => i.got);
  $("todoList").replaceChildren(...todo.map(itemRow));
  $("gotList").replaceChildren(...got.map(itemRow));
  $("emptyMsg").hidden = items.length > 0;
  $("gotHeader").hidden = got.length === 0;
  $("gotCount").textContent = `Got (${got.length})`;
}

// ---------- Past shops ----------

let currentLookFor = null;
const norm = (s) => s.trim().toLowerCase();

function renderHistory() {
  const box = $("historyList");
  box.replaceChildren();
  $("historyEmpty").hidden = pastShops.length > 0;
  const onList = new Set(items.filter((i) => !i.got).map((i) => norm(i.name)));

  for (const shop of pastShops) {
    const card = document.createElement("details");
    card.className = "shop";
    const summary = document.createElement("summary");
    const when = new Date(shop.at).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
    summary.innerHTML = `<span class="shop-date"></span><span class="shop-count"></span>`;
    summary.firstChild.textContent = when;
    summary.lastChild.textContent = `${shop.items.length} item${shop.items.length === 1 ? "" : "s"}`;
    card.append(summary);

    const checks = [];
    const ul = document.createElement("div");
    ul.className = "shop-items";
    for (const name of shop.items) {
      const already = onList.has(norm(name));
      const label = document.createElement("label");
      label.className = already ? "already" : "";
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = !already;
      cb.disabled = already;
      cb.value = name;
      const text = document.createElement("span");
      text.textContent = already ? `${name} (already on list)` : name;
      label.append(cb, text);
      ul.append(label);
      if (!already) checks.push(cb);
    }
    card.append(ul);

    const actions = document.createElement("div");
    actions.className = "shop-actions";
    const toggle = document.createElement("button");
    toggle.className = "link-btn";
    toggle.textContent = "Select none";
    toggle.onclick = () => {
      const any = checks.some((c) => c.checked);
      checks.forEach((c) => (c.checked = !any));
      toggle.textContent = any ? "Select all" : "Select none";
    };
    const del = document.createElement("button");
    del.className = "link-btn danger";
    del.textContent = "Delete";
    del.onclick = () => confirm(`Delete the shop from ${when}?`) && store.setHistory(pastShops.filter((h) => h.id !== shop.id));
    const add = document.createElement("button");
    add.className = "btn";
    add.textContent = "Add to list";
    add.onclick = () => {
      const names = checks.filter((c) => c.checked).map((c) => c.value);
      if (!names.length) return;
      store.addMany(names);
      $("history").close();
    };
    if (!checks.length) add.disabled = true;
    actions.append(toggle, del, add);
    card.append(actions);
    box.append(card);
  }
  box.querySelector("details")?.setAttribute("open", "");
}

function wireUp() {
  document.querySelectorAll(".chip[data-days]").forEach((b) => {
    b.onclick = () => store.setDate(daysFromToday(Number(b.dataset.days)));
  });
  const openPicker = () => {
    const p = $("datePicker");
    if (!p.value) p.value = daysFromToday(7);
    try { p.showPicker(); } catch { p.click(); }
  };
  $("pickBtn").onclick = openPicker;
  $("dateValue").onclick = openPicker;
  $("datePicker").onchange = (e) => e.target.value && store.setDate(e.target.value);

  $("addForm").onsubmit = (e) => {
    e.preventDefault();
    const name = $("addInput").value.trim();
    if (name) store.add(name);
    $("addInput").value = "";
    $("addInput").focus();
  };

  $("finishShop").onclick = () => {
    const got = items.filter((i) => i.got);
    if (!got.length) return;
    const n = got.length;
    if (!confirm(`Finish this shop? The ${n} ticked item${n > 1 ? "s" : ""} will be saved to Past shops and cleared from the list.`)) return;
    const entry = { id: crypto.randomUUID(), at: Date.now(), lookFor: currentLookFor || null, items: got.map((i) => i.name) };
    store.finishShop(got.map((i) => i.id), [entry, ...pastShops].slice(0, MAX_HISTORY));
  };

  $("historyBtn").onclick = () => { renderHistory(); $("history").showModal(); };
  $("closeHistory").onclick = () => $("history").close();

  $("settingsBtn").onclick = () => { $("listCode").textContent = code; $("settings").showModal(); };
  $("closeSettings").onclick = () => $("settings").close();
  $("shareBtn").onclick = async () => {
    try { await navigator.share({ title: "Our Shopping List", text: "Join our shopping list", url: shareUrl }); }
    catch { $("copyBtn").click(); }
  };
  $("copyBtn").onclick = async () => {
    try { await navigator.clipboard.writeText(shareUrl); $("copyBtn").textContent = "Copied!"; }
    catch { prompt("Copy this link:", shareUrl); }
  };
  $("joinBtn").onclick = () => {
    const c = cleanCode($("joinInput").value);
    if (c.length < 10) return alert("That code looks too short – it should be 10 letters/numbers.");
    storageSet("listCode", c);
    location.reload();
  };
}

async function start() {
  if (firebaseConfig) {
    try { store = await firebaseStore(code); }
    catch (err) { $("status").textContent = "Couldn't connect to Firebase: " + err.message; return; }
  } else {
    $("demoBanner").hidden = false;
    store = localStore(code);
  }
  wireUp();
  store.subscribe(
    (meta) => {
      currentLookFor = meta.lookFor;
      pastShops = Array.isArray(meta.history) ? meta.history : [];
      renderDate(meta.lookFor);
      if ($("history").open) renderHistory();
    },
    (list) => { items = list; renderItems(); },
    (msg) => { $("status").textContent = msg; },
  );
}

start();

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
