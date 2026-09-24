import { firebaseConfig } from "./firebase-config.js?v=4";

// Bump this (and the ?v= in index.html and sw.js) with each update so phones never
// mix an old app.js with a new index.html.
const VERSION = 4;

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
    add: (name, order) => fs.addDoc(itemsRef, { name, got: false, createdAt: Date.now(), order }),
    setGot: (id, got) => fs.updateDoc(fs.doc(itemsRef, id), { got }),
    // Changes an item and remembers its place in the shop under its name.
    async update(id, data, name, order) {
      const batch = fs.writeBatch(db);
      batch.update(fs.doc(itemsRef, id), data);
      batch.set(listRef, { shopOrder: { [orderKey(name)]: order } }, { merge: true });
      await batch.commit();
    },
    remove: (id) => fs.deleteDoc(fs.doc(itemsRef, id)),
    // Batched writes (not transactions) so these still work with no signal in the shop.
    async finishShop(ids, pastShops, orders) {
      const batch = fs.writeBatch(db);
      batch.set(listRef, { history: pastShops, shopOrder: orders }, { merge: true });
      ids.forEach((id) => batch.delete(fs.doc(itemsRef, id)));
      await batch.commit();
    },
    setHistory: (pastShops) => fs.setDoc(listRef, { history: pastShops }, { merge: true }),
    async addMany(entries) {
      const batch = fs.writeBatch(db);
      const now = Date.now();
      entries.forEach(({ name, order }, i) => batch.set(fs.doc(itemsRef), { name, got: false, createdAt: now + i, order }));
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
    listeners?.onMeta({ lookFor: state.lookFor, history: state.history, shopOrder: state.shopOrder });
    listeners?.onItems(state.items.slice());
  };
  return {
    subscribe(onMeta, onItems, onStatus) { listeners = { onMeta, onItems }; onStatus("Saved on this phone only"); save(); },
    setDate(iso) { state.lookFor = iso; save(); },
    add(name, order) { state.items.push({ id: crypto.randomUUID(), name, got: false, createdAt: Date.now(), order }); save(); },
    update(id, data, name, order) {
      Object.assign(state.items.find((i) => i.id === id) || {}, data);
      state.shopOrder = { ...state.shopOrder, [orderKey(name)]: order };
      save();
    },
    setGot(id, got) { const it = state.items.find((i) => i.id === id); if (it) it.got = got; save(); },
    remove(id) { state.items = state.items.filter((i) => i.id !== id); save(); },
    finishShop(ids, pastShops, orders) {
      state.items = state.items.filter((i) => !ids.includes(i.id));
      state.history = pastShops;
      state.shopOrder = { ...state.shopOrder, ...orders };
      save();
    },
    setHistory(pastShops) { state.history = pastShops; save(); },
    addMany(entries) {
      const now = Date.now();
      entries.forEach(({ name, order }, i) => state.items.push({ id: crypto.randomUUID(), name, got: false, createdAt: now + i, order }));
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
let shopOrder = {}; // remembered position for each item name, so re-added items go back to their spot
let busy = false; // true while dragging or editing, so a sync from the other phone doesn't wipe it

// Items are sorted by `order`. It's a plain number: new items get the current time
// (so they go to the bottom), and dragging sets it halfway between the new neighbours.
function orderKey(name) { return name.trim().toLowerCase(); }
const orderOf = (i) => i.order ?? i.createdAt;
const orderFor = (name) => shopOrder[orderKey(name)] ?? Date.now();

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
  li.dataset.id = item.id;
  const handle = document.createElement("span");
  handle.className = "handle";
  handle.textContent = "⠿";
  handle.setAttribute("aria-label", "Drag to reorder " + item.name);
  handle.onpointerdown = (e) => startDrag(e, li);
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
  const edit = document.createElement("button");
  edit.className = "del";
  edit.textContent = "✎";
  edit.setAttribute("aria-label", "Edit " + item.name);
  edit.onclick = () => startEdit(li, item, name);
  if (item.got) li.append(tick, name, del);
  else li.append(handle, tick, name, edit, del);
  return li;
}

function startEdit(li, item, nameEl) {
  busy = true;
  const input = document.createElement("input");
  input.className = "edit";
  input.value = item.name;
  input.setAttribute("aria-label", "Item name");
  nameEl.replaceWith(input);
  input.focus();
  input.select();
  let done = false;
  const finish = (save) => {
    if (done) return;
    done = true;
    busy = false;
    const newName = input.value.trim();
    if (save && newName && newName !== item.name) {
      item.name = newName; // show it straight away; the sync confirms it
      store.update(item.id, { name: newName }, newName, orderOf(item));
    }
    renderItems();
  };
  input.onkeydown = (e) => {
    if (e.key === "Enter") finish(true);
    if (e.key === "Escape") finish(false);
  };
  input.onblur = () => finish(true);
}

// Drag by the ⠿ handle. Uses pointer events so it works with a finger, not just a mouse.
function startDrag(e, li) {
  e.preventDefault();
  busy = true;
  const list = $("todoList");
  li.classList.add("dragging");
  let lastY = e.clientY;

  // Scroll the page when dragging near the top or bottom of the screen.
  const scroller = setInterval(() => {
    if (lastY < 90) window.scrollBy(0, -12);
    else if (lastY > window.innerHeight - 90) window.scrollBy(0, 12);
    else return;
    moveTo(lastY);
  }, 16);

  function moveTo(y) {
    const others = [...list.children].filter((r) => r !== li);
    const before = others.find((r) => { const b = r.getBoundingClientRect(); return y < b.top + b.height / 2; });
    if (before !== li.nextElementSibling) list.insertBefore(li, before || null);
  }
  const onMove = (ev) => { lastY = ev.clientY; moveTo(lastY); };
  // Listen on the whole page: moving the row in the list drops pointer capture.
  const onUp = () => {
    clearInterval(scroller);
    document.removeEventListener("pointermove", onMove);
    document.removeEventListener("pointerup", onUp);
    document.removeEventListener("pointercancel", onUp);
    li.classList.remove("dragging");
    busy = false;

    const byId = (el) => el && items.find((i) => i.id === el.dataset.id);
    const item = byId(li), prev = byId(li.previousElementSibling), next = byId(li.nextElementSibling);
    let order;
    if (prev && next) order = (orderOf(prev) + orderOf(next)) / 2;
    else if (prev) order = orderOf(prev) + 1000;
    else if (next) order = orderOf(next) - 1000;
    if (item && order !== undefined && order !== orderOf(item)) {
      item.order = order; // show it straight away; the sync confirms it
      store.update(item.id, { order }, item.name, order);
    }
    renderItems();
  };
  document.addEventListener("pointermove", onMove);
  document.addEventListener("pointerup", onUp);
  document.addEventListener("pointercancel", onUp);
}

function renderItems() {
  if (busy) return;
  const sorted = items.slice().sort((a, b) => orderOf(a) - orderOf(b));
  const todo = sorted.filter((i) => !i.got);
  const got = sorted.filter((i) => i.got);
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
      store.addMany(names.map((name) => ({ name, order: orderFor(name) })));
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
    if (name) store.add(name, orderFor(name));
    $("addInput").value = "";
    $("addInput").focus();
  };

  $("finishShop").onclick = () => {
    const got = items.filter((i) => i.got).sort((a, b) => orderOf(a) - orderOf(b));
    if (!got.length) return;
    const n = got.length;
    if (!confirm(`Finish this shop? The ${n} ticked item${n > 1 ? "s" : ""} will be saved to Past shops and cleared from the list.`)) return;
    const entry = { id: crypto.randomUUID(), at: Date.now(), lookFor: currentLookFor || null, items: got.map((i) => i.name) };
    // Remember where everything bought was, so it goes back there next time.
    const orders = Object.fromEntries(got.map((i) => [orderKey(i.name), orderOf(i)]));
    store.finishShop(got.map((i) => i.id), [entry, ...pastShops].slice(0, MAX_HISTORY), orders);
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
      shopOrder = meta.shopOrder || {};
      renderDate(meta.lookFor);
      if ($("history").open) renderHistory();
    },
    (list) => { items = list; renderItems(); },
    (msg) => { $("status").textContent = `${msg} · v${VERSION}`; },
  );
}

start();

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
