import { firebaseConfig } from "./firebase-config.js?v=7";

// Bump this (and the ?v= in index.html and sw.js) with each update so phones never
// mix an old app.js with a new index.html.
const VERSION = 7;

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

// ---------- Dates and money ----------

const pad = (n) => String(n).padStart(2, "0");
const toISO = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const fromISO = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
function today() { const d = new Date(); d.setHours(0, 0, 0, 0); return d; }
function daysFromToday(n) { const d = today(); d.setDate(d.getDate() + n); return toISO(d); }
const shortDate = (d) => d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });

const GBP = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });
const money = (n) => GBP.format(n);
// Accepts "45.2", "£45.20" or "45,20". Returns null when blank or not a price.
function parseMoney(s) {
  const t = (s || "").replace(/[£\s]/g, "").replace(",", ".");
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 100) / 100 : null;
}

// ---------- Storage back ends ----------
// Both back ends keep the same shape: list-level fields (date, shops, past shops,
// remembered positions) on one record, and the items separately.

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

  // Batched writes (not transactions) so everything still works with no signal in the shop.
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
    // Maps inside `patch` (like shopOrder) are merged key by key; everything else is replaced.
    setMeta: (patch) => fs.setDoc(listRef, patch, { merge: true }),
    async addMany(entries, metaPatch) {
      const batch = fs.writeBatch(db);
      const now = Date.now();
      entries.forEach((e, i) => batch.set(fs.doc(itemsRef), { got: false, createdAt: now + i, ...e }));
      if (metaPatch) batch.set(listRef, metaPatch, { merge: true });
      await batch.commit();
    },
    setGot: (id, got) => fs.updateDoc(fs.doc(itemsRef, id), { got }),
    async update(id, data, metaPatch) {
      const batch = fs.writeBatch(db);
      batch.update(fs.doc(itemsRef, id), data);
      batch.set(listRef, metaPatch, { merge: true });
      await batch.commit();
    },
    remove: (id) => fs.deleteDoc(fs.doc(itemsRef, id)),
    async finishShop(ids, metaPatch) {
      const batch = fs.writeBatch(db);
      batch.set(listRef, metaPatch, { merge: true });
      ids.forEach((id) => batch.delete(fs.doc(itemsRef, id)));
      await batch.commit();
    },
  };
}

// This-phone-only: used until Firebase is configured, so the app can be tried out.
function localStore(code) {
  const key = "list:" + code;
  let state = (() => { try { return JSON.parse(storageGet(key)) || {}; } catch { return {}; } })();
  state.items ||= [];
  state.meta ||= { lookFor: state.lookFor, history: state.history, shopOrder: state.shopOrder }; // older saves
  let listeners = null;
  const save = () => {
    storageSet(key, JSON.stringify(state));
    listeners?.onMeta({ ...state.meta });
    listeners?.onItems(state.items.map((i) => ({ ...i })));
  };
  const isMap = (v) => v && typeof v === "object" && !Array.isArray(v);
  const merge = (patch) => {
    for (const [k, v] of Object.entries(patch || {})) {
      state.meta[k] = isMap(v) && isMap(state.meta[k]) ? { ...state.meta[k], ...v } : v;
    }
  };
  return {
    subscribe(onMeta, onItems, onStatus) { listeners = { onMeta, onItems }; onStatus("Saved on this phone only"); save(); },
    setMeta(patch) { merge(patch); save(); },
    addMany(entries, metaPatch) {
      const now = Date.now();
      entries.forEach((e, i) => state.items.push({ id: crypto.randomUUID(), got: false, createdAt: now + i, ...e }));
      merge(metaPatch);
      save();
    },
    setGot(id, got) { const it = state.items.find((i) => i.id === id); if (it) it.got = got; save(); },
    update(id, data, metaPatch) {
      Object.assign(state.items.find((i) => i.id === id) || {}, data);
      merge(metaPatch);
      save();
    },
    remove(id) { state.items = state.items.filter((i) => i.id !== id); save(); },
    finishShop(ids, metaPatch) {
      state.items = state.items.filter((i) => !ids.includes(i.id));
      merge(metaPatch);
      save();
    },
  };
}

// ---------- State ----------

const code = resolveListCode();
const shareUrl = `${location.origin}${location.pathname}#list=${code}`;
const MAX_HISTORY = 60; // about a year of weekly shops, for spending totals
let store;
let items = [];
let currentLookFor = null;
let pastShops = []; // newest first: { id, at, lookFor, items: [names], shops: [names], spend: [{ shop, amount }] }
let shops = []; // shop names, e.g. ["Aldi", "Food Warehouse"]
let shopOrder = {}; // item name -> remembered position, so re-added items go back to their spot
let itemShop = {}; // item name -> the shop it was last bought at
let barcodes = {}; // barcode -> the name you gave it, so a second scan needs no lookup
let filter = storageGet("shopFilter") || ""; // shop this phone is showing ("" = all). Not shared.
const shown = () => (shops.includes(filter) ? filter : ""); // ignores a filter for a shop that's gone
let busy = false; // true while dragging, so a sync from the other phone doesn't interrupt it

// Items are sorted by `order`. It's a plain number: new items get the current time
// (so they go to the bottom), and dragging sets it halfway between the new neighbours.
const key = (name) => name.trim().toLowerCase();
const orderOf = (i) => i.order ?? i.createdAt;
const byOrder = (a, b) => orderOf(a) - orderOf(b);
const orderFor = (name) => shopOrder[key(name)] ?? Date.now();
// An item's shop, ignoring shops that have since been removed.
const shopOf = (i) => (i.shop && shops.includes(i.shop) ? i.shop : null);
const rememberedShop = (name) => (shops.includes(itemShop[key(name)]) ? itemShop[key(name)] : null);
const shopLabel = (s) => s || (shops.length ? "No shop" : "Shop");

// ---------- Use-by date ----------

function renderDate(iso) {
  if (!iso) {
    $("dateValue").textContent = "Pick a date";
    $("dateSub").textContent = "Tap a button below to set it";
    return;
  }
  const d = fromISO(iso);
  $("dateValue").textContent = shortDate(d);
  const diff = Math.round((d - today()) / 86400000);
  $("dateSub").textContent =
    diff === 0 ? "That's today" :
    diff === 1 ? "That's tomorrow" :
    diff > 1 ? `That's ${diff} days from today` :
    `That was ${-diff} day${diff === -1 ? "" : "s"} ago – set a new date for this shop`;
  $("datePicker").value = iso;
}

// ---------- Shops bar ----------

function renderShopBar() {
  const bar = $("shopBar");
  bar.replaceChildren();
  const chip = (label, value) => {
    const b = document.createElement("button");
    b.className = "shop-chip" + (shown() === value ? " active" : "");
    b.textContent = label;
    b.onclick = () => { filter = value; storageSet("shopFilter", value); pickTouched = false; renderShopBar(); renderItems(); renderAddShop(); };
    return b;
  };
  if (shops.length) {
    bar.append(chip("All", ""));
    shops.forEach((s) => bar.append(chip(s, s)));
  }
  const add = document.createElement("button");
  add.className = "shop-chip add";
  add.textContent = shops.length ? "+ Shop" : "+ Add a shop (e.g. Aldi)";
  add.onclick = addShopPrompt;
  bar.append(add);
}

function addShopPrompt() {
  const name = (prompt("Shop name, e.g. Aldi or Food Warehouse:") || "").trim();
  if (!name) return;
  if (shops.some((s) => key(s) === key(name))) return alert(`${name} is already there.`);
  store.setMeta({ shops: [...shops, name] });
}

// ---------- Items ----------

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
  // In the "All" view, show which shop each item is for.
  if (!shown() && shopOf(item)) {
    const tag = document.createElement("span");
    tag.className = "tag";
    tag.textContent = shopOf(item);
    name.append(tag);
  }
  const del = document.createElement("button");
  del.className = "del";
  del.textContent = "✕";
  del.setAttribute("aria-label", "Delete " + item.name);
  del.onclick = () => store.remove(item.id);
  const edit = document.createElement("button");
  edit.className = "del";
  edit.textContent = "✎";
  edit.setAttribute("aria-label", "Edit " + item.name);
  edit.onclick = () => openEdit(item);
  if (item.got) li.append(tick, name, del);
  else li.append(handle, tick, name, edit, del);
  return li;
}

function renderItems() {
  if (busy) return;
  const f = shown();
  const visible = items.filter((i) => !f || shopOf(i) === f).sort(byOrder);
  const todo = visible.filter((i) => !i.got);
  const got = visible.filter((i) => i.got);
  $("todoList").replaceChildren(...todo.map(itemRow));
  $("gotList").replaceChildren(...got.map(itemRow));
  $("emptyMsg").hidden = visible.length > 0;
  $("emptyMsg").textContent = f ? `Nothing on the list for ${f}.` : "Nothing on the list yet.";
  $("addInput").placeholder = f ? `Add an item for ${f}` : "Add an item, e.g. Milk";
  // Finish shop covers everything ticked, whichever shop is showing.
  const allGot = items.filter((i) => i.got).length;
  $("gotHeader").hidden = allGot === 0;
  $("gotCount").textContent = got.length === allGot ? `Got (${allGot})` : `Got (${got.length} here, ${allGot} in total)`;
}

// The shop a new item goes to unless you pick one: the shop you're viewing, else the
// shop the item came from last time, else the last shop you picked in the dropdown.
let lastPicked = null;
let pickTouched = false; // true once you've changed the dropdown for the item being typed
const defaultShop = (name) =>
  shown() || (name && rememberedShop(name)) || (shops.includes(lastPicked) ? lastPicked : null);

function renderAddShop() {
  const sel = $("addShop");
  $("addShopRow").hidden = shops.length === 0;
  const keep = pickTouched ? sel.value : null;
  sel.replaceChildren(...[["", "No shop"], ...shops.map((s) => [s, s])].map(([value, label]) => {
    const o = document.createElement("option");
    o.value = value;
    o.textContent = label;
    return o;
  }));
  sel.value = keep !== null && (keep === "" || shops.includes(keep)) ? keep : defaultShop($("addInput").value.trim()) || "";
}

function addItem(name, extra = {}, metaPatch, shop = defaultShop(name)) {
  store.addMany([{ name, order: orderFor(name), shop, ...extra }], metaPatch);
}

// ---------- Barcode scanning ----------
// Chrome on Android can read barcodes from the camera (BarcodeDetector). The product
// name comes from Open Food Facts, a free, open product database. Names you type or
// change are remembered per barcode for both phones.

let scan = null; // { stream, timer } while the camera is running
let scannedCode = null;

function scanReset(message) {
  scannedCode = null;
  $("scanMsg").textContent = message || "Point the camera at the barcode.";
  $("scanResult").hidden = true;
  $("scanAdd").hidden = true;
}

async function startCamera() {
  if (!("BarcodeDetector" in window)) {
    $("scanView").hidden = true;
    $("scanMsg").textContent = "This browser can't scan barcodes with the camera. Tap below to type the number printed under the barcode.";
    return;
  }
  try {
    const detector = new BarcodeDetector({ formats: ["ean_13", "ean_8", "upc_a", "upc_e"] });
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
    if (!$("scanDialog").open) { stream.getTracks().forEach((t) => t.stop()); return; }
    const video = $("scanVideo");
    video.srcObject = stream;
    $("scanView").hidden = false;
    await video.play();
    scan = { stream, timer: null };
    const look = async () => {
      if (!scan) return;
      try {
        const found = await detector.detect(video);
        if (found.length && scan) {
          stopCamera();
          navigator.vibrate?.(80);
          return lookupBarcode(found[0].rawValue);
        }
      } catch {} // a frame that couldn't be read; try the next one
      if (scan) scan.timer = setTimeout(look, 200);
    };
    look();
  } catch (err) {
    $("scanView").hidden = true;
    $("scanMsg").textContent = err.name === "NotAllowedError"
      ? "The camera is blocked for this app. Allow it in Chrome (tap the icon left of the address, then Permissions), or type the number instead."
      : "Couldn't start the camera. You can type the number under the barcode instead.";
  }
}

function stopCamera() {
  if (!scan) return;
  clearTimeout(scan.timer);
  scan.stream.getTracks().forEach((t) => t.stop());
  scan = null;
  $("scanVideo").srcObject = null;
  $("scanView").hidden = true;
}

function openScanner() {
  scanReset();
  $("scanDialog").showModal();
  startCamera();
}

// Builds a readable name like "Cowbelle Semi Skimmed Milk 2 l" from Open Food Facts.
async function productName(code) {
  const ctrl = new AbortController();
  const timeout = setTimeout(() => ctrl.abort(), 8000);
  try {
    const r = await fetch(`https://world.openfoodfacts.org/api/v2/product/${code}?fields=product_name,product_name_en,brands,quantity`, { signal: ctrl.signal });
    if (!r.ok) return null;
    const j = await r.json();
    const p = j.status === 1 && j.product;
    const base = (p?.product_name_en || p?.product_name || "").trim();
    if (!base) return null;
    const brand = (p.brands || "").split(",")[0].trim();
    let name = brand && !base.toLowerCase().includes(brand.toLowerCase()) ? `${brand} ${base}` : base;
    const qty = (p.quantity || "").trim();
    if (qty && !name.toLowerCase().includes(qty.toLowerCase())) name += ` ${qty}`;
    return name;
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

async function lookupBarcode(raw) {
  const code = String(raw).replace(/\D/g, "");
  if (code.length < 6 || code.length > 14) {
    scanReset("That doesn't look like a barcode number. Try again.");
    return startCamera();
  }
  scannedCode = code;
  $("scanMsg").textContent = `Looking up ${code}…`;
  const name = barcodes[code] || (await productName(code));
  if (scannedCode !== code) return; // closed or rescanned meanwhile
  $("scanMsg").textContent = name
    ? "Found it. Change the name if you like, then add it."
    : "Not found in the product database. Type what it is and the app will remember it next time.";
  $("scanName").value = name || "";
  $("scanCode").textContent = `Barcode ${code}`;
  $("scanResult").hidden = false;
  $("scanAdd").hidden = false;
  $("scanName").focus();
}

function addScanned() {
  const name = $("scanName").value.trim();
  if (!name || !scannedCode) return $("scanName").focus();
  // Use the shop picked in the dropdown, if you picked one before scanning.
  const shop = pickTouched ? $("addShop").value || null : defaultShop(name);
  addItem(name, { barcode: scannedCode }, { barcodes: { [scannedCode]: name } }, shop);
  scanReset(`Added ${name} ✓ Scan the next one, or tap Done.`);
  startCamera();
}

// Edit an item's name and shop.
let editing = null;
function openEdit(item) {
  editing = { id: item.id, shop: shopOf(item) };
  $("editName").value = item.name;
  renderEditShops();
  $("editShopRow").hidden = shops.length === 0;
  $("editDialog").showModal();
}
function renderEditShops() {
  const box = $("editShops");
  box.replaceChildren();
  for (const s of [null, ...shops]) {
    const b = document.createElement("button");
    b.className = "shop-chip" + (editing.shop === s ? " active" : "");
    b.textContent = s || "No shop";
    b.onclick = () => { editing.shop = s; renderEditShops(); };
    box.append(b);
  }
}
function saveEdit() {
  const item = items.find((i) => i.id === editing?.id);
  const name = $("editName").value.trim();
  $("editDialog").close();
  if (!item || !name) return;
  // Remember this item's place and shop under its (possibly new) name.
  store.update(item.id, { name, shop: editing.shop }, {
    shopOrder: { [key(name)]: orderOf(item) },
    itemShop: { [key(name)]: editing.shop },
  });
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
      store.update(item.id, { order }, { shopOrder: { [key(item.name)]: order } });
    }
    renderItems();
  };
  document.addEventListener("pointermove", onMove);
  document.addEventListener("pointerup", onUp);
  document.addEventListener("pointercancel", onUp);
}

// ---------- Prices (finishing a shop, or editing a past one) ----------

// groups: [{ shop, count }]. Calls onSave([{ shop, amount }]) with the prices filled in.
let priceSave = null;
function openPrices({ title, intro, button, groups, spend = [] }, onSave) {
  $("priceTitle").textContent = title;
  $("priceIntro").textContent = intro;
  $("priceSave").textContent = button;
  const box = $("priceFields");
  box.replaceChildren();
  const inputs = groups.map(({ shop, count }) => {
    const row = document.createElement("label");
    row.className = "price-row";
    const text = document.createElement("span");
    text.className = "price-shop";
    text.textContent = groups.length === 1 && !shop ? "Total spent" : shopLabel(shop);
    if (count) {
      const small = document.createElement("small");
      small.textContent = `${count} item${count === 1 ? "" : "s"}`;
      text.append(small);
    }
    const wrap = document.createElement("span");
    wrap.className = "money-input";
    const input = document.createElement("input");
    input.inputMode = "decimal";
    input.placeholder = "0.00";
    input.setAttribute("aria-label", `Amount spent at ${shopLabel(shop)}`);
    const was = spend.find((s) => s.shop === shop);
    if (was) input.value = was.amount.toFixed(2);
    wrap.append("£", input);
    row.append(text, wrap);
    box.append(row);
    return { shop, input };
  });
  priceSave = () => {
    const bad = inputs.find(({ input }) => input.value.trim() && parseMoney(input.value) === null);
    if (bad) { bad.input.focus(); return alert("That price doesn't look right – use numbers like 45.20"); }
    $("priceDialog").close();
    onSave(inputs.map(({ shop, input }) => ({ shop, amount: parseMoney(input.value) })).filter((s) => s.amount !== null));
  };
  $("priceDialog").showModal();
}

function groupsFor(list) {
  const counts = new Map();
  list.forEach((i) => counts.set(shopOf(i), (counts.get(shopOf(i)) || 0) + 1));
  // Named shops first, in the order they were added; "No shop" last.
  return [...counts].map(([shop, count]) => ({ shop, count }))
    .sort((a, b) => (a.shop === null) - (b.shop === null) || shops.indexOf(a.shop) - shops.indexOf(b.shop));
}

function finishShop() {
  const got = items.filter((i) => i.got).sort(byOrder);
  if (!got.length) return;
  const n = got.length;
  openPrices({
    title: "Finish shop",
    intro: `The ${n} ticked item${n > 1 ? "s" : ""} will be saved to Past shops and cleared from the list. Add what you spent if you want to track it (optional).`,
    button: "Finish shop",
    groups: groupsFor(got),
  }, (spend) => {
    const entry = {
      id: crypto.randomUUID(), at: Date.now(), lookFor: currentLookFor || null,
      items: got.map((i) => i.name),
      shops: [...new Set(got.map(shopOf))],
      spend,
    };
    // Remember where everything bought was, and which shop, so it goes back there next time.
    store.finishShop(got.map((i) => i.id), {
      history: [entry, ...pastShops].slice(0, MAX_HISTORY),
      shopOrder: Object.fromEntries(got.map((i) => [key(i.name), orderOf(i)])),
      itemShop: Object.fromEntries(got.map((i) => [key(i.name), shopOf(i)])),
    });
  });
}

// ---------- Past shops and spending ----------

const totalOf = (shop) => (shop.spend || []).reduce((t, s) => t + s.amount, 0);

function spendLine(spend) {
  if (!spend?.length) return "";
  if (spend.length === 1) return money(spend[0].amount);
  return spend.map((s) => `${shopLabel(s.shop)} ${money(s.amount)}`).join(" · ") + ` = ${money(spend.reduce((t, s) => t + s.amount, 0))}`;
}

function renderSpendSummary() {
  const box = $("spendSummary");
  const now = new Date();
  const month = (offset) => {
    const start = new Date(now.getFullYear(), now.getMonth() + offset, 1);
    const end = new Date(now.getFullYear(), now.getMonth() + offset + 1, 1);
    const inMonth = pastShops.filter((p) => p.at >= start && p.at < end && p.spend?.length);
    const byShop = new Map();
    inMonth.forEach((p) => p.spend.forEach((s) => byShop.set(s.shop, (byShop.get(s.shop) || 0) + s.amount)));
    return { name: start.toLocaleDateString(undefined, { month: "long" }), total: inMonth.reduce((t, p) => t + totalOf(p), 0), byShop, trips: inMonth.length };
  };
  const [thisM, lastM] = [month(0), month(-1)];
  if (!thisM.trips && !lastM.trips) { box.hidden = true; return; }
  box.hidden = false;
  box.replaceChildren();
  for (const m of [thisM, lastM]) {
    if (!m.trips) continue;
    const div = document.createElement("div");
    div.className = "spend-month";
    const head = document.createElement("div");
    head.innerHTML = `<span></span><b></b>`;
    head.firstChild.textContent = `${m.name} (${m.trips} shop${m.trips === 1 ? "" : "s"})`;
    head.lastChild.textContent = money(m.total);
    div.append(head);
    if (m.byShop.size > 1 || (m.byShop.size === 1 && !m.byShop.has(null))) {
      const small = document.createElement("small");
      small.textContent = [...m.byShop].map(([s, a]) => `${shopLabel(s)} ${money(a)}`).join(" · ");
      div.append(small);
    }
    box.append(div);
  }
}

function renderHistory() {
  renderSpendSummary();
  const box = $("historyList");
  box.replaceChildren();
  $("historyEmpty").hidden = pastShops.length > 0;
  const onList = new Set(items.filter((i) => !i.got).map((i) => key(i.name)));

  for (const shop of pastShops) {
    const card = document.createElement("details");
    card.className = "shop";
    const summary = document.createElement("summary");
    const when = shortDate(new Date(shop.at));
    summary.innerHTML = `<span class="shop-date"></span><span class="shop-count"></span>`;
    summary.firstChild.textContent = when;
    const total = totalOf(shop);
    summary.lastChild.textContent = (total ? money(total) + " · " : "") + `${shop.items.length} item${shop.items.length === 1 ? "" : "s"}`;
    card.append(summary);

    if (shop.spend?.length > 1 || shop.spend?.[0]?.shop) {
      const line = document.createElement("div");
      line.className = "shop-spend";
      line.textContent = spendLine(shop.spend);
      card.append(line);
    }

    const checks = [];
    const ul = document.createElement("div");
    ul.className = "shop-items";
    for (const name of shop.items) {
      const already = onList.has(key(name));
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
    const prices = document.createElement("button");
    prices.className = "link-btn";
    prices.textContent = "£ Prices";
    prices.onclick = () => {
      const known = (shop.shops?.length ? shop.shops : [null]).filter((s) => s === null || shops.includes(s));
      const groups = [...new Set([...known, ...(shop.spend || []).map((s) => s.shop)])].map((s) => ({ shop: s }));
      openPrices({
        title: `Prices for ${when}`, intro: "Add or change what you spent on this shop.", button: "Save",
        groups: groups.length ? groups : [{ shop: null }], spend: shop.spend || [],
      }, (spend) => store.setMeta({ history: pastShops.map((h) => (h.id === shop.id ? { ...h, spend } : h)) }));
    };
    const del = document.createElement("button");
    del.className = "link-btn danger";
    del.textContent = "Delete";
    del.onclick = () => confirm(`Delete the shop from ${when}?`) && store.setMeta({ history: pastShops.filter((h) => h.id !== shop.id) });
    const add = document.createElement("button");
    add.className = "btn";
    add.textContent = "Add to list";
    add.onclick = () => {
      const names = checks.filter((c) => c.checked).map((c) => c.value);
      if (!names.length) return;
      store.addMany(names.map((name) => ({ name, order: orderFor(name), shop: rememberedShop(name) })));
      $("history").close();
    };
    if (!checks.length) add.disabled = true;
    actions.append(toggle, prices, del, add);
    card.append(actions);
    box.append(card);
  }
  box.querySelector("details")?.setAttribute("open", "");
}

// ---------- Settings: shops ----------

function renderShopSettings() {
  const box = $("shopSettings");
  box.replaceChildren();
  $("noShops").hidden = shops.length > 0;
  for (const s of shops) {
    const row = document.createElement("div");
    row.className = "shop-setting";
    const name = document.createElement("span");
    name.textContent = s;
    const del = document.createElement("button");
    del.className = "link-btn danger";
    del.textContent = "Remove";
    del.onclick = () => {
      if (!confirm(`Remove ${s}? Items for ${s} will stay on the list without a shop.`)) return;
      store.setMeta({ shops: shops.filter((x) => x !== s) });
    };
    row.append(name, del);
    box.append(row);
  }
}

// ---------- Wiring ----------

function wireUp() {
  document.querySelectorAll(".chip[data-days]").forEach((b) => {
    b.onclick = () => store.setMeta({ lookFor: daysFromToday(Number(b.dataset.days)) });
  });
  const openPicker = () => {
    const p = $("datePicker");
    if (!p.value) p.value = daysFromToday(7);
    try { p.showPicker(); } catch { p.click(); }
  };
  $("pickBtn").onclick = openPicker;
  $("dateValue").onclick = openPicker;
  $("datePicker").onchange = (e) => e.target.value && store.setMeta({ lookFor: e.target.value });

  $("scanBtn").onclick = openScanner;
  $("scanAdd").onclick = addScanned;
  $("scanName").onkeydown = (e) => e.key === "Enter" && addScanned();
  $("scanDone").onclick = () => $("scanDialog").close();
  $("scanDialog").onclose = () => { stopCamera(); scannedCode = null; };
  $("scanManual").onclick = () => {
    const typed = prompt("Type the numbers under the barcode:");
    if (typed) { stopCamera(); lookupBarcode(typed); }
  };

  $("addForm").onsubmit = (e) => {
    e.preventDefault();
    const name = $("addInput").value.trim();
    if (name) {
      const shop = shops.length ? $("addShop").value || null : defaultShop(name);
      if (pickTouched) lastPicked = shop;
      addItem(name, {}, undefined, shop);
    }
    $("addInput").value = "";
    pickTouched = false;
    renderAddShop();
    $("addInput").focus();
  };
  // Follow the item being typed (e.g. "milk" -> Aldi) until you pick a shop yourself.
  $("addInput").oninput = () => { if (!pickTouched) $("addShop").value = defaultShop($("addInput").value.trim()) || ""; };
  $("addShop").onchange = () => { pickTouched = true; };

  $("finishShop").onclick = finishShop;
  $("priceSave").onclick = () => priceSave?.();
  $("priceCancel").onclick = () => $("priceDialog").close();
  $("priceFields").onkeydown = (e) => e.key === "Enter" && priceSave?.();

  $("editSave").onclick = saveEdit;
  $("editCancel").onclick = () => $("editDialog").close();
  $("editName").onkeydown = (e) => e.key === "Enter" && saveEdit();

  $("historyBtn").onclick = () => { renderHistory(); $("history").showModal(); };
  $("closeHistory").onclick = () => $("history").close();

  $("settingsBtn").onclick = () => { $("listCode").textContent = code; renderShopSettings(); $("settings").showModal(); };
  $("closeSettings").onclick = () => $("settings").close();
  $("addShopBtn").onclick = addShopPrompt;
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
      shops = Array.isArray(meta.shops) ? meta.shops : [];
      shopOrder = meta.shopOrder || {};
      itemShop = meta.itemShop || {};
      barcodes = meta.barcodes || {};
      renderDate(meta.lookFor);
      renderShopBar();
      renderAddShop();
      renderItems();
      if ($("history").open) renderHistory();
      if ($("settings").open) renderShopSettings();
    },
    (list) => { items = list; renderItems(); },
    (msg) => { $("status").textContent = `${msg} · v${VERSION}`; },
  );
}

start();

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
