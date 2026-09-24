# EuroMillions Predictor (Android)

A small Android app that generates EuroMillions lines from **every previous draw**
(13 Feb 2004 onwards) and lets you copy them to the clipboard.

**Download:** [`release/EuroMillionsPredictor.apk`](release/EuroMillionsPredictor.apk)
(Android 5.0+). Open it on your phone and allow "install unknown apps" when asked.

## Features

- **Predict**: generates 2–10 distinct lines (5 main numbers + 2 Lucky Stars). Each line has a
  **Copy** button, and **Copy all lines** copies every line at once, e.g.
  `3, 17, 22, 41, 48 | Lucky Stars: 5, 11`.
- **Methods**
  - *Mixed* (default): average of the three below.
  - *Hot – all-time*: weighted by how often each number has been drawn across the full history.
  - *Hot – recent form*: weighted by appearances in the last 50 draws.
  - *Overdue*: weighted by how many draws since each number last appeared.
  Weights are squared to make the favoured numbers stand out, then numbers are sampled at random
  without replacement, so each tap gives different lines.
- **Typical patterns only** (on by default): redraws lines whose ball total is outside the middle
  90% of past draws, or that are all odd / all even.
- **Statistics**: for each main number and Lucky Star: times drawn, historical chance per draw
  (vs 10.0% / 16.7% for a fair draw), recent count and draws since last seen. Lucky Stars are
  adjusted for the pool growing from 9 → 11 (May 2011) → 12 (Sep 2016).
- **History**: the latest 100 draws.
- **New-result notifications** (on by default, switch on the History tab): a background check runs
  about once an hour. It only goes online when a Tuesday/Friday draw should be out (after 21:30 UK
  time) and isn't stored yet. When the new draw arrives you get a notification with the winning
  numbers, and tapping it opens the app. The History tab also has a **Send a test notification** button.
  Android 13+ asks for notification permission the first time the app opens.
- **Update results**: runs on launch and on demand. It fetches new draws from the National
  Lottery's draw-history CSV and a public GitHub archive
  ([daowa89/lottery-archive](https://github.com/daowa89/lottery-archive)). Only draws newer than
  the ones it already has are added.

## Data

`assets/euromillions.csv` holds 1,983 draws (13 Feb 2004 to 22 Sep 2026). Draws 1–1863 come from the
lottery.merseyworld.com archive (numbered sequentially, so no gaps). Later draws come from
daowa89/lottery-archive. Every date the two sources share has the same numbers.

## A note on odds

EuroMillions draws are random and independent. Past results don't change future odds: every line
has the same 1 in 139,838,160 jackpot chance, whatever method picks it. The app is for fun.

## Building

No Gradle or Android SDK install needed, only Ubuntu/Debian packages and a JDK:

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./build.sh          # -> release/EuroMillionsPredictor.apk
test/run.sh         # unit checks for parsing, statistics and generation
```

`keystore/predictor.jks` (password `euromillions`) is committed on purpose. New builds are signed
with the same key, so they install over the old version. It's a hobby key, so don't reuse it for
anything else.
