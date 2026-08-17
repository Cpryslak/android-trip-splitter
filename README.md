# Trip Split

An offline expense splitter for a group trip. Log who paid for what and who it
covered; it works out the shortest list of payments that squares everyone up.

Built for a Galaxy Tab S10 FE: large type, and a two-pane layout in landscape
with the ledger beside the balances.

- Works with no signal at all. Nothing leaves the tablet.
- 3–12 people. Each expense can cover any subset of the group, so "dinner, but
  only four of us" is one tap.
- Optional second currency. The rate is frozen onto each expense when you enter
  it, so correcting the rate later never rewrites what you already logged.
- All arithmetic in whole cents. A 100.00 dinner for three splits 33.34 / 33.33 /
  33.33 — it never loses or invents a cent.
- Sends the settle-up to the group chat as plain text.

---

## Getting the APK without installing anything

The project ships with a GitHub Actions workflow, so GitHub builds the app for
you. This is the easiest route if you've never used Android Studio.

1. Create a repository at github.com (private is fine).
2. Upload the contents of this folder — on the repo page, **Add file → Upload
   files**, then drag everything in. Keep the folder structure intact.
3. Go to the **Actions** tab. The build starts on its own; give it 3–5 minutes.
4. Open the finished run and download **trip-split-apk** at the bottom. Unzip it
   to get `app-debug.apk`.
5. Copy that file to the tablet, tap it, and allow installing from unknown
   sources when Android asks.

If the build fails, open the run, click the red step, and copy the error out —
that text is all anyone needs to fix it.

## Or building it locally

Requires Android Studio (a large download, and you need a computer — the tablet
can't build it).

1. **File → Open** and pick this folder.
2. If it asks about the Gradle wrapper, let it create one, or run
   `gradle wrapper` in this folder first if you already have Gradle.
3. Wait for the first sync. It downloads the Android SDK pieces it needs.
4. Plug in the tablet with USB debugging on, then press Run. Or
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** and copy the file over.

Android Studio may offer to upgrade the Gradle or Kotlin versions. You can
accept, but nothing here needs it.

## Layout

```
app/src/main/java/com/tripsplit/app/
  Money.kt        parsing and formatting, integer cents only
  Settle.kt       splitting, balances, and the settle-up algorithm
  Model.kt        Trip, Person, Expense
  Store.kt        one JSON file in private storage
  Theme.kt        colours and the type scale
  Ui.kt           ledger, balances, two-pane layout
  Forms.kt        trip setup and expense entry
  MainActivity.kt
app/src/test/java/com/tripsplit/app/
  LogicTest.kt    3,000 randomised trips asserting nothing is ever lost
```

`Money`, `Settle`, and `Model` have no Android imports, which is why the test
suite runs on a plain JVM in seconds — including on GitHub before every build.

## Things it deliberately doesn't do

- No accounts, no sync. One tablet holds the ledger; everyone else looks over
  your shoulder or gets the shared summary.
- No receipt photos or barcode scanning.
- No unequal weighting. An expense splits evenly among whoever it covered, which
  covers nearly every real case and keeps entry to a few taps.
