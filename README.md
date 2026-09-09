# Cloudflare DDNS Updater

A tiny Android app that updates a Cloudflare DNS A record to match your phone's current public IP — a one-tap replacement for a dynamic DNS client like No-IP or DuckDNS, but pointed at a domain you already control on Cloudflare.

No servers, no accounts, no ads. Everything runs on your phone and talks directly to the Cloudflare API. Your API token is stored on-device using Android's `EncryptedSharedPreferences` and never leaves your phone except in the Cloudflare API calls it's meant for.

## What you need first

1. A domain whose DNS is hosted on Cloudflare (nameservers pointed at Cloudflare).
2. Your **Zone ID** — on the Cloudflare dashboard, open your domain, and it's shown on the right side of the Overview page.
3. An **API Token** (not your Global API Key) scoped to `Zone > DNS > Edit` for that zone:
   - Cloudflare dashboard → profile icon → **My Profile** → **API Tokens** → **Create Token**
   - Use the "Edit zone DNS" template, restrict it to your zone, and create it.
4. The hostname you want to keep updated, e.g. `home.yourdomain.com`. It doesn't need to exist yet — the app creates it on first run.

## Using the app

1. Install the APK (you'll need to allow "install unknown apps" for whatever app opens it — this isn't distributed through the Play Store).
2. Open the app and tap the gear icon.
3. Enter your hostname, Zone ID, and API token, then Save.
4. Tap **Update DNS Now**.

Optional: tap **Add Home Screen Shortcut** to pin an icon that runs the update directly, or long-press the app icon in your launcher for the same quick action.

### If you also VPN into your own network

If you connect to a VPN back into the same network this hostname points at, your phone's "public IP" as seen by the app becomes your router's own WAN IP while the VPN is up — not your phone's real address. Writing that would corrupt the record. The app already detects an active VPN connection and skips the update automatically in that case. As a second layer of protection, you can also enter your router's public IP in Settings under "Router/home IP to avoid" — if the fetched IP ever matches it, the update is skipped regardless of whether the VPN was detected.

## Building it yourself

Requires a JDK (17+) and the Android SDK. From the project root:

```
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## How it works

On tap, the app:
1. Fetches your phone's public IP from `api.ipify.org`.
2. Looks up the existing A record for your hostname via the Cloudflare API.
3. Creates it if it doesn't exist yet, or updates it if the IP changed.

The record is created as DNS-only (not proxied through Cloudflare's CDN), since the point is usually to reach services running on your own network directly.
