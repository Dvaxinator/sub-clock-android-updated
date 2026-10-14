# Sub Clock

Substitution timer for youth soccer: two teams, players rotate in list order, a short beep and a voice call ("Sub time … Maya off … Leo off") over the speaker, and "Last play before sub" 30 seconds before.

## What's in this repo

| Path | What it is |
| --- | --- |
| `index.html`, `manifest.webmanifest`, `sw.js`, `icon-*.png` | The website / installable web app (GitHub Pages serves these). Works on iPhone and Android. |
| `android/` | The native Android app. Same screens, plus a background service so it keeps working with the screen locked. |
| `.github/workflows/android.yml` | Builds the Android app on GitHub and publishes the APK. |

`index.html` is the only copy of the screens. Each Android build copies it into the app, so changing the website also changes the app the next time it builds.

## Getting the Android app

Every push that changes `index.html` or anything in `android/` builds a new APK (about 3–5 minutes; watch it under the **Actions** tab). The newest one is always at:

```
https://github.com/<your-username>/<your-repo>/releases/latest/download/SubClock.apk
```

Open that link on an Android phone, download, tap the file, and allow "Install unknown apps" for your browser when asked. A newer build installs over the old one and keeps lineups and settings.

You can also start a build by hand: **Actions → Android APK → Run workflow**.

## First run on the speaker phone

1. Allow notifications when asked (that's the countdown shown on the lock screen).
2. Settings → **Allow background running** → Allow. Without it, Android may delay alerts after the phone has been locked a while.
3. Settings → **Hear it**. If it's silent, tap **Phone voice settings** and install English voice data.
4. Start your music, then press **Start match**. You can lock the phone or switch to the music app.

Samsung phones: also set Settings → Battery → Background usage limits so Sub Clock is not in "Sleeping apps".

## Building in Android Studio instead

Open the `android/` folder in Android Studio and press Run. Copy `index.html` into `android/app/src/main/assets/www/` first if you changed it.

The signing key (`android/app/sideload.keystore`) is shared by every build so updates install cleanly. It's only for sideloading; use a private key if you ever publish to the Play Store.
