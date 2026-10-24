# Orbit Hop — from zero to Google Play

A one-tap arcade game. A glowing dot orbits a planet on two rings. Tap to hop between rings, dodge the red blocks, grab gold gems (+3). It speeds up as your score climbs.

**How it makes money (all automatic, no server):**

| What | When it shows |
|---|---|
| Banner ad | Bottom of the screen, always |
| Full-screen ad | When the player taps "Play again", at most once every 3 games and 60 seconds |
| Rewarded ad | Optional "Continue (watch ad)" button after dying, once per run |
| "Remove ads" purchase | Button on the main menu. Buyers get no ads and free continues |

Consent pop-up for Europe/UK (legally required for ads) is built in. Purchases are verified by Google Play itself, so there is no server to run.

You only ever edit **one file**: `gradle.properties`.

---

## What you need (one-time)

1. **Google Play Console** developer account: https://play.google.com/console ($25 one-time).
2. **AdMob** account: https://admob.google.com (free; use the same Google account).
3. **GitHub** account: https://github.com (free). GitHub builds the app for you in the cloud, so you don't need Android Studio.

---

## Step 1 — Get your AdMob IDs

1. In AdMob: **Apps → Add app → Android → "No, it's not listed on a supported app store yet"** → name it *Orbit Hop*.
2. Copy the **App ID** (looks like `ca-app-pub-1234567890123456~1234567890`).
3. **Ad units → Add ad unit** three times: **Banner**, **Interstitial**, **Rewarded**. Copy each ad unit ID (they contain a `/`).
4. **Privacy & messaging → GDPR → Create message** for this app and **Publish** it. (This is the consent pop-up the app shows in Europe. Without it, ads won't show to European users.)

## Step 2 — Put the code on GitHub

1. GitHub → **New repository** → name `orbit-hop` → **Public** → Create.
2. Click **uploading an existing file** and drag in everything from the `OrbitHop` folder.
   - ⚠️ The `.github` folder is hidden on Mac/Windows. If it doesn't upload, click **Add file → Create new file**, type the name `.github/workflows/build.yml`, and paste in the contents of that file.
3. Open `gradle.properties` on GitHub (pencil icon), paste your 4 AdMob IDs over the test IDs, then **Commit**.

## Step 3 — Add your signing key (4 secrets)

Repo → **Settings → Secrets and variables → Actions → New repository secret**. Add the four values from `SECRETS.txt`:

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Keep `upload-keystore.jks` and `SECRETS.txt` somewhere safe (e.g. Google Drive). **Never put them in the repo.**

## Step 4 — Build

Repo → **Actions** tab → **Build app for Google Play** → **Run workflow**. In about 5 minutes you get two downloads at the bottom of the run page:

- `upload-this-to-google-play-vN` → unzip → **`app-release.aab`** (this goes to Google Play)
- `phone-test-apk-vN` → `app-debug.apk` you can install on your own phone to try the game (shows test ads, safe to tap)

Every time you change something and commit, it rebuilds automatically with a higher version number.

## Step 5 — Publish on Google Play

1. Play Console → **Create app** → name *Orbit Hop*, Game, Free.
2. **Privacy policy URL:** Repo → **Settings → Pages → Branch: main, folder: /docs → Save**. Your URL becomes `https://YOUR-GITHUB-NAME.github.io/orbit-hop/`. First open `docs/index.html` on GitHub and replace `YOUR_EMAIL_HERE` with your email.
3. **Store listing:** use the text in `store-listing/listing.txt`, plus `icon-512.png` and `feature-graphic-1024x500.png`. Screenshots: install the test APK and take 2–4 phone screenshots.
4. **App content** answers: see `store-listing/listing.txt` (ads: Yes, target age 13+, data safety answers).
5. **Testing → Closed testing:** upload `app-release.aab`. New personal developer accounts must run a closed test with at least 12 testers for 14 days before Production unlocks. Friends/family can join by email list.
6. After 14 days: **Production → Create release** → upload the newest `.aab` → **Send for review**.

When the game is live: AdMob → your app → **App settings → link to Google Play listing**.

## Step 6 (optional) — "Remove ads" purchase

1. Play Console → **Settings → Payments profile** (set up a merchant account to receive money).
2. After an `.aab` is uploaded: **Monetize → Products → One-time products → Create product**. Product ID: **`remove_ads`**. Price e.g. $1.99. **Activate**.

That's it. The "Remove ads" button appears in the game automatically once the product is active. If you never create it, the button just stays hidden.

---

## "Maintenance" — the honest version

Nothing needs to run or be watched. The game is offline, there's no server, AdMob and Google Play pay you directly.

The only thing Google forces: **once a year (deadline August 31)** Play requires apps to target the newest Android version. If you skip it, the game keeps working for people who have it, but it gradually stops appearing to new users on new phones. To update, edit `app/build.gradle`, change `compileSdk 36` and `targetSdk 36` to the new number Google announces, commit, and upload the new `.aab`. About 10 minutes a year.

## Money expectations

Ad income depends on downloads, not on the code. A game nobody finds earns close to nothing; AdMob pays out once you reach $100. Getting downloads usually takes a good store listing, screenshots, and sharing it around.

## Files

```
gradle.properties           ← your IDs live here (only file you edit)
app/src/main/java/...       ← game (GameView), ads (AdsManager), purchase (BillingManager)
.github/workflows/build.yml ← cloud build
docs/index.html             ← privacy policy page
store-listing/              ← icon, feature graphic, store text
```
