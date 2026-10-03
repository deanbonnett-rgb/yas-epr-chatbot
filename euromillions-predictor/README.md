# Lottery Predictor (Android)

An Android app that generates lines for **EuroMillions**, **Lotto**, **Set For Life**,
**Thunderball** and **Powerball** from previous draws, lets you copy them to the clipboard, and
reminds you the day before each draw.

Powerball is the US game, also sold in the UK by the National Lottery since 21 July 2026. UK
players enter the same US draws, so one history covers both.

**Download:** [`release/LotteryPredictor.apk`](release/LotteryPredictor.apk) (Android 5.0+). Open it
on your phone and allow "install unknown apps" when asked. It installs over the older
EuroMillions Predictor.

## Games

| Game | Format today | History bundled | Draws |
|---|---|---|---|
| EuroMillions | 5 from 50 + 2 Lucky Stars from 12 | 1,983 draws, 13 Feb 2004 – 22 Sep 2026 | Tue & Fri |
| Lotto (UK National Lottery) | 6 from 59 (bonus ball not chosen), two rounds per draw since 10 Jun 2026 | 3,089 draws, 19 Nov 1994 – 30 Jul 2025* | Wed & Sat |
| Set For Life | 5 from 47 + Life Ball from 10 | 785 draws, 18 Mar 2019 – 21 Sep 2026 (complete) | Mon & Thu |
| Thunderball | 5 from 39 + Thunderball from 14 | 314 draws: 21 Dec 2021 – 18 Jun 2022 and 19 Sep 2025 – 23 Sep 2026** | Tue, Wed, Fri & Sat |
| Powerball (USA & UK) | 5 from 69 + Powerball from 26 | 3,859 draws, 22 Apr 1992 – 23 Sep 2026 | Mon, Wed & Sat US time (about 4am UK on Tue, Thu & Sun) |

\*Lotto draws after 30 Jul 2025 are downloaded the first time you open Lotto (see *Updates*).
Since 10 June 2026 every Lotto night has two draws (rounds). The app stores both, whether a results
file puts them on separate rows or on one row.

\*\*The full Thunderball history (since 1999) wasn't available. The app uses the two official
180-day downloads that were (the official file is missing 1 and 2 May 2026), and adds every new
draw from now on.

The rules changed over the years: Lotto went from 49 to 59 balls (Oct 2015), Powerball went through
seven formats (1992–2015), and EuroMillions Lucky Stars went from 9 to 11 to 12. The statistics
compare each number only with the draws it could have appeared in, then scale to today's format.

## Features

- **Game picker** on launch, showing each game's format, number of results and latest draw.
- **Predict**: generates 2–10 distinct lines. Each line has a **Copy** button, and **Copy all lines**
  copies every line at once, e.g. `3, 17, 22, 41, 48 | Lucky Stars: 5, 11`,
  `4, 11, 23, 35, 47, 58` or `5, 15, 26, 29, 30 | Powerball: 14`.
- **Methods**
  - *Mixed* (default): average of the three below.
  - *Hot – all-time*: weighted by how often each number has come up across the full history.
  - *Hot – recent form*: weighted by appearances in the last 50 draws.
  - *Overdue*: weighted by how many draws since each number last appeared.

  Weights are squared to make the favoured numbers stand out, then numbers are sampled at random
  without replacement, so each tap gives different lines.
- **Entering numbers in The National Lottery app**: its "Type numbers" screen has one box per
  number, so a whole copied line can't be pasted. Tap any ball to copy just that number, or tap
  **Show numbers over the Lottery app** (or **Float** on a line) to keep the lines in a small panel
  on top of other apps. Tap a number in the panel to copy it, and it's dimmed so you can see where
  you're up to. Use ‹ › for other lines, drag the title to move the panel, and close it with ✕.
  The first time, Android asks you to allow "Display over other apps".
- **Tickets and winnings log** (Tickets tab in each game):
  - **I played this line** on a generated line saves it for the next draw, and the line card then
    shows it's been played.
  - **Add a ticket** logs a Lucky Dip or your own numbers: pick the draw (recent or upcoming), how
    the numbers were chosen, tap the numbers, and set the cost (defaults: EuroMillions £2.50,
    Lotto £2, Set For Life £1.50, Thunderball £1, Powerball £4; the last cost you enter is remembered).
  - When results are in, each ticket is checked automatically. Matched numbers get a green ring and
    the tier is shown (both rounds for Lotto). Fixed prizes are filled in: Lotto from 10 Jun 2026
    £1M / £1,000 / £50 / £10 / £1 per round, Thunderball £500,000 down to £3, Set For Life £250 down
    to £5. EuroMillions and Powerball prizes change every draw, so you enter what you won with
    **Winnings**, which can also override any amount.
  - Totals per game (spent, won, up or down, tickets waiting) and across all games on the games
    screen. Result notifications also say how your tickets did.
- **Typical patterns only** (on by default): redraws lines whose ball total is outside the middle
  90% of draws under today's rules, or that are all odd / all even.
- **Statistics**: for each number, times drawn, historical chance per draw (vs. a fair draw),
  recent count and draws since last seen. Sort by number or by frequency.
- **History**: the latest 100 draws.
- **Reminders**: on the games screen, tick the games you play and pick a time (7am–10pm, default
  6pm). The day before each of their draws you get "draw tomorrow" with a suggested line, and
  tapping it opens the game. Each History tab has the same switch and a **Send a test reminder**
  button. Powerball reminders follow the UK ticket days (Mon, Wed & Sat).
- **Notifications**: a switch per game on the History tab. A background check runs about once an
  hour, only goes online when a draw should be out but isn't stored yet, and posts the winning
  numbers. Tapping it opens that game. There are no checks between 11pm and 7am, so Powerball's
  4am results arrive in the morning. There's also a **Send a test notification** button.

## Updates

Opening a game checks for new results when a draw is due, and so does **Update results**.

| Game | Sources |
|---|---|
| EuroMillions | National Lottery CSV, [daowa89/lottery-archive](https://github.com/daowa89/lottery-archive), lottery.merseyworld.com (only when draws are missing) |
| Lotto | National Lottery CSV (last 180 days), lotto.merseyworld.com full archive (only when draws are missing) |
| Set For Life | [apkelepouris/predict-for-life-data](https://github.com/apkelepouris/predict-for-life-data) (updated after every draw), National Lottery CSV |
| Thunderball | National Lottery CSV |
| Powerball | [jbaranski/jeffs-lottery-utils](https://github.com/jbaranski/jeffs-lottery-utils) (updated after every draw), National Lottery Powerball CSV |

Stored draws are never overwritten. A download that repeats a neighbouring draw under a different
date is ignored. Powerball results dated the UK morning after a draw are moved back to the US draw
date.

## Data

- EuroMillions: draws 1–1863 from the lottery.merseyworld.com archive, later draws from
  daowa89/lottery-archive. Every date the two share has the same numbers.
- Lotto: draws 1–3089 from the lotto.merseyworld.com archive.
- Set For Life: apkelepouris/predict-for-life-data, every Monday and Thursday draw since the first.
- Thunderball: official National Lottery downloads saved in
  [newportg/POC-Thunderball](https://github.com/newportg/POC-Thunderball) and
  [markheydon/national-lottery-library](https://github.com/markheydon/national-lottery-library).
- The 2022 official Lotto and EuroMillions downloads in markheydon/national-lottery-library match
  the bundled history on all 52 draws each.
- National Lottery downloads come from `api-dfe.national-lottery.co.uk/draw-game/results/<id>/download`
  (Lotto 1, Set For Life 3, Thunderball 4, EuroMillions 33), with the older
  `www.national-lottery.co.uk/results/<game>/draw-history/csv` addresses kept as a fallback.
- Powerball: 1992–2019 from [jt2002/Data-Science-for-Powerball](https://github.com/jt2002/Data-Science-for-Powerball),
  Oct 2015 onwards from jbaranski/jeffs-lottery-utils. All 421 overlapping draws match.

## A note on odds

Every draw is random and independent. Past results don't change future odds. Whatever method picks
a line, the jackpot chance stays at 1 in 139,838,160 (EuroMillions), 1 in 45,057,474 per round (Lotto) or
1 in 292,201,338 (Powerball). Set For Life's top prize is 1 in 15,339,390 and Thunderball's is
1 in 8,060,598. The app is for fun.

## Building

No Gradle or Android SDK install needed, only Ubuntu/Debian packages and a JDK:

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./build.sh          # -> release/LotteryPredictor.apk
test/run.sh         # checks parsing, statistics, generation and draw timing for all games
```

`keystore/predictor.jks` (password `euromillions`) is committed on purpose. New builds are signed
with the same key, so they install over the old version. It's a hobby key, so don't reuse it for
anything else.
