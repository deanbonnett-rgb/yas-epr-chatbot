# Our Shopping List

A shared shopping list for two phones. When one of you adds or ticks an item, it
shows up on the other phone straight away.

- **Use-by date at the top.** This is the date you're checking labels against in
  the shop. Tap **1 week from today**, **+5 days** or **+10 days**, or choose any
  date. It's shared, so you both see the same date.
- **Add items** by typing and tapping **Add**.
- **Tick items off** while you shop. Ticked items move to a "Got" section at the
  bottom.
- **Finish shop ✓** saves what you ticked as a past shop (with the date) and clears
  it from the list.
- **🕘 Past shops** shows your previous shops. Pick one, untick anything you don't
  need, and tap **Add to list**. Items already on the list are skipped. The last 20
  shops are kept.
- **Works with no signal.** Changes you make offline sync when you're back online.

It's a web app you install to your home screen, so it opens full-screen with its own
icon like any other app. You don't need the Play Store.

---

## Setup (one-off, about 15 minutes)

### 1. Create the free database (Firebase)

1. Go to <https://console.firebase.google.com> and sign in with a Google account.
2. Click **Create a project** and call it something like `our-shopping-list`. You
   can turn Google Analytics off.
3. In the left menu, open **Build → Firestore Database** and click **Create
   database**. Pick a location near you (e.g. `europe-west2` for London). Choose
   **Start in production mode**.
4. Open the **Rules** tab, delete what's there, paste in everything from
   [`firestore.rules`](firestore.rules), and click **Publish**.
5. Go to **Project settings** (the cog at the top left) and scroll to **Your apps**.
   Click the **`</>`** (Web) icon, give it a nickname and click **Register app**.
   Skip Firebase Hosting for now.
6. It shows a `firebaseConfig = { ... }` block. Copy it into
   [`firebase-config.js`](firebase-config.js) so the file looks like the example
   in that file.

> Leave `firebase-config.js` as `null` and the app still works, but only on one
> phone. That's handy for trying it out.

### 2. Put the app online

The app is a folder of plain files, so anywhere that hosts a website works. The
easiest options:

- **Netlify Drop (no account needed to try it):** go to
  <https://app.netlify.com/drop> and drag the whole `shopping-list` folder onto
  the page. You get a link like `https://something.netlify.app`. Make a free
  account to keep it.
- **Firebase Hosting (same project as the database):** on a computer with Node.js
  installed, run `npm install -g firebase-tools`, then `firebase login`, then
  `firebase init hosting` inside this folder (public directory: `.`), then
  `firebase deploy`.

### 3. Install it on both phones

1. On your phone, open the link in **Chrome**.
2. Tap the **⋮** menu, then **Add to Home screen** (or **Install app**).
3. Open the app, tap **⚙︎**, then **Share link**, and send the link to your
   partner.
4. Your partner opens that link in Chrome and adds it to their home screen too.
   You're now both on the same list.

(On an iPhone, use Safari: **Share → Add to Home Screen**.)

---

## Privacy

Each list has a random 10-character code, and only people with the share link
(or the code) can see it. Don't post the link publicly.

## Files

| File | What it is |
|---|---|
| `index.html` | The page and its styling |
| `app.js` | The app logic, including syncing with Firebase |
| `firebase-config.js` | Your Firebase settings (step 1) |
| `firestore.rules` | Database security rules (step 1.4) |
| `sw.js`, `manifest.webmanifest`, `icon.svg` | Let the app install to the home screen and open offline |
