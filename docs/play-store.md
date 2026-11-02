# Chhaon on the Play Store (TWA / WebView wrapper)

Chhaon ships to Android as a **Trusted Web Activity (TWA)** built with
[Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap). The app is a thin native
shell that opens the live PWA at **https://chhaon.site/** inside full Chrome. There is no
second codebase and no React Native: redeploy the website and every installed user gets the
update instantly — no Play Store resubmission needed. We only rebuild the `.aab` to change
the icon, name, version, or Android behaviour.

This is deliberately different from the Smart Gurukul app, which is a full React Native /
Expo app built with EAS. Chhaon reuses none of that — only the idea of keeping a release
keystore safe.

## One-time local setup

- **JDK 17** (Bubblewrap requires exactly 17): `brew install openjdk@17`
- **Android SDK** with command-line tools. If the SDK was installed via Android Studio it
  may lack `cmdline-tools`; Bubblewrap also expects `sdkmanager` at `$SDK/bin`. We bridge it:
  ```sh
  SDK="$HOME/Library/Android/sdk"
  # install cmdline-tools into $SDK/cmdline-tools/latest/ (download from
  # https://developer.android.com/studio#command-tools), then:
  ln -sfn "$SDK/cmdline-tools/latest/bin" "$SDK/bin"
  ln -sfn "$SDK/cmdline-tools/latest/lib" "$SDK/lib"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses
  ```
- **Bubblewrap**: `npm install -g @bubblewrap/cli`
- **Bubblewrap config** `~/.bubblewrap/config.json` — note `jdkPath` is the JDK **bundle
  root**, NOT `.../Contents/Home` (Bubblewrap appends `/Contents/Home` itself on macOS):
  ```json
  {
    "jdkPath": "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk",
    "androidSdkPath": "/Users/<you>/Library/Android/sdk"
  }
  ```

## Signing key — DO NOT LOSE THIS

- Keystore: `~/chhaon-release-keystore/chhaon-release.keystore`, alias `chhaon`.
- Passwords: `~/chhaon-release-keystore/credentials.txt` (kept outside the repo, never committed).
- **Back both up somewhere safe (password manager + encrypted backup).** If the upload key is
  lost you can reset it in Play Console (with Play App Signing), but keep it anyway.

## Build the `.aab`

```sh
cd android-twa
export BUBBLEWRAP_KEYSTORE_PASSWORD="$(grep '^storepass' ~/chhaon-release-keystore/credentials.txt | awk '{print $2}')"
export BUBBLEWRAP_KEY_PASSWORD="$BUBBLEWRAP_KEYSTORE_PASSWORD"
bubblewrap build          # produces app-release-bundle.aab (upload this) + app-release-signed.apk
```

Bump `appVersionCode` (and usually `appVersionName`) in `android-twa/twa-manifest.json`
before each new Play upload, then `bubblewrap update && bubblewrap build`.

## Digital Asset Links (removes the browser URL bar)

The app is verified against the website via `/.well-known/assetlinks.json`, which must list
the SHA-256 fingerprint of the key that signs the **installed** app.

- Source of truth: `web/public/assetlinks.json`, served at `/.well-known/assetlinks.json`
  via a forced redirect in `web/netlify.toml` (Vite does not reliably copy the dotfolder).
- It currently contains our **upload key** fingerprint. **After the first Play upload**, if
  you use Play App Signing (recommended, the default), Google re-signs the app with its own
  key. Copy the **App signing key certificate** SHA-256 from
  *Play Console → Test and release → App integrity → App signing* and add it as a second
  entry in `assetlinks.json`, then redeploy the site. Until then the URL bar may show.

## First Play Store submission (manual, one-time)

1. Create a Google Play Console developer account ($25 one-time).
2. Create app → upload `android-twa/app-release-bundle.aab` to the Internal testing track.
3. Fill store listing, data safety (health data — be accurate), privacy policy URL
   (https://chhaon.site/privacy.html), content rating.
4. After upload, add the Play app-signing fingerprint to `assetlinks.json` (see above).
5. Promote Internal → Closed/Open → Production when ready.
