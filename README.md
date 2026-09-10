# Trip Split

An offline expense splitter for a group trip. Log who paid for what and who it
covered; it works out the shortest list of payments that squares everyone up.

Built for a Galaxy Tab S10 FE: large type, and a two-pane layout in landscape
with the ledger beside the balances. Follows the tablet's light or dark setting.

- **Keeps every trip.** An *All trips* tab lists them newest first with their
  dates, totals and how many payments are still outstanding. Starting a new one
  never disturbs an old one, so you can reopen last year's to check who paid for
  the boat. New trips carry over the same people and currencies, since the group
  usually is the same.
- Works with no signal at all. Nothing leaves the tablet unless you send it.
- 3–12 people, each with their own colour so the ledger reads at a glance. Each
  expense can cover any subset of the group, so "dinner, but only four of us" is
  one tap.
- **Uneven splits.** Split equally, by shares ("she had two, I had one"), or by
  typing exact amounts per person. Whole cents throughout: a 100.00 dinner for
  three is 33.34 / 33.33 / 33.33, never a lost or invented cent.
- **Categories.** Tag an expense Food, Lodging, Transport and so on, and the
  balances page shows where the money went, per trip and per person.
- **Dates.** The ledger is grouped by day with each day's spending. An expense
  entered the next morning can be moved back to the night it happened.
- Optional second currency. The rate is frozen onto each expense when you enter
  it. Correcting the trip's rate later never rewrites what you already logged,
  and neither does editing an old entry — moving one to today's rate is a
  deliberate tap.
- Records repayments. When someone actually hands over the money, **Mark paid**
  on that settle-up line logs it, prefilled with the exact figure. Partial
  repayments work too. Repayments sit in the same ledger as expenses but are
  never split, and never count as trip spending.
- **Settle-up is pairwise, grouped by who owes.** Each line counts only the
  expenses those two people actually shared, plus repayments between the two of
  them, so nobody is ever told to pay someone they never transacted with. Tap
  *Why this much* on any line to see the expenses behind the figure.
- **A page per person.** Tap a name for their whole position: how the net figure
  is built, who it's with, their share by category, everything they paid for,
  everything they owe a share of, and every repayment.
- Deleting an expense or repayment offers **Undo** for a few seconds. Deleting
  a trip asks first, and offers Undo too.
- Sends the settle-up to the group chat as plain text, or the whole ledger as a
  **spreadsheet** (CSV, one column per person) for anyone who wants to check.
- **Backups** live under *All trips*. Three ways:
  - *Automatic.* Pick a folder once — a Google Drive folder is ideal — and a
    copy of every trip is written there a moment after each change. The Drive
    app then syncs it off the tablet by itself. This uses Android's own folder
    picker, so it needs no Google sign-in and works just as well with OneDrive,
    an SD card or a USB stick.
  - *Send a backup* attaches every trip as a .json and sends it wherever you
    like — email, Drive, the group chat. *Save to a file* writes it to a place
    you pick.
  - *Restore* reads one back and merges: trips in the file are added or
    overwritten, and any trip not in the file is left alone, so restoring an
    old backup can't cost you a newer trip.

---

## Getting the APK without installing anything

The project ships with a GitHub Actions workflow, so GitHub builds the app for
you. This is the easiest route if you've never used Android Studio.

1. Create a repository at github.com (private is fine).
2. Upload the contents of this folder — on the repo page, **Add file → Upload
   files**, then drag everything in. Keep the folder structure intact.
3. **Set up signing once** (see below). Skipping this works, but every later
   update will refuse to install over the previous one.
4. Go to the **Actions** tab. The build starts on its own; give it 3–5 minutes.
5. Open the finished run and download **trip-split-apk** at the bottom. Unzip it
   to get `app-debug.apk`.
6. Copy that file to the tablet, tap it, and allow installing from unknown
   sources when Android asks.

If the build fails, open the run, click the red step, and copy the error out —
that text is all anyone needs to fix it.

### Signing

Android only installs a new version of an app over an old one if both were
signed with the same key. Without a key of its own, each GitHub build invents a
fresh one, so the second APK you download won't install until you uninstall the
first — and uninstalling deletes every trip on the tablet.

Fix it once:

1. On the **Actions** tab, open **Create signing key** and press **Run
   workflow**. It takes under a minute.
2. Open the finished run and download the **trip-split-signing-key** artifact.
   Unzip it and read `README.txt`; it contains a password.
3. In the repository, go to **Settings → Secrets and variables → Actions** and
   add three secrets exactly as `README.txt` says: `TRIPSPLIT_KEYSTORE_BASE64`,
   `TRIPSPLIT_KEYSTORE_PASSWORD` and `TRIPSPLIT_KEY_ALIAS`.
4. Keep the downloaded folder somewhere safe (a password manager, a USB stick),
   then delete that workflow run from the Actions tab so the key isn't sitting
   in the run history.

From then on every APK the build produces installs straight over the last one,
and the build log says "Signing with the shared key." If it says "No signing
key" instead, a secret is missing or misnamed.

If you build locally in Android Studio instead, the ordinary debug key is used
unless you export the same variables (`TRIPSPLIT_KEYSTORE_PATH`,
`TRIPSPLIT_KEYSTORE_PASSWORD`, `TRIPSPLIT_KEY_ALIAS`) before building.

## Linking the backup to Google Drive

Under **All trips → Keeping a copy → Automatic**, tap **Choose a folder**.
Android's folder picker opens; choose **Google Drive** from the menu on the left
(the Drive app has to be installed and signed in), pick or create a folder, and
tap **Use this folder**. From then on `trip-split-backup.json` in that folder is
rewritten a couple of seconds after every change, and the status line shows when
it last succeeded. The Drive app syncs it up whenever the tablet has signal.

To bring everything onto a new tablet: install the app, go to **Restore**, and
pick that file from Drive.

## Tests

`gradle test` runs the unit tests: money parsing and formatting, equal and
weighted splits, balances, pairwise settle-up, repayments, the trip library, the
file format including old versions, and the CSV export. The GitHub build runs
them before producing an APK.
