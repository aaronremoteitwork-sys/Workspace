# Workspace — locked-down home screen for the Zebra TC52

A simple home screen that shows only your apps.

## What it shows

- **Your own apps, automatically:** any installed app signed with the same key as Workspace
  (ILS, Printers, anything you build later). Install a new one and it appears; no Workspace update needed.
- **Chrome.**

## Controls

- **Serial number (top-left):** the last 4 characters of the device's serial number.
- **Settings gear (top-right):** anyone can pick a wallpaper (10 built-in designs or a photo from the
  device), a solid color or custom `#RRGGBB`, and check for updates. White is the default.
- **Admin exit (top-left):** tap the serial number in the top-left corner **3 times quickly**.
  A menu offers: open the normal home screen, or open Settings.
- The Back button does nothing on the home screen.

## Making it the home screen

On first launch, Workspace shows a **"Make Workspace the home screen"** button. Tap it and pick
Workspace. After that, the Home button always returns to Workspace.

For full lockdown (no notification shade, no Recents, other apps blocked), use a StageNow profile
with Zebra MX. Workspace alone doesn't block those.

## Updates

Same setup as ILS: every push to `main` builds and publishes `Workspace.apk` to the latest GitHub release.
Workspace checks for a new version about every 6 hours and offers to install it. Its settings are kept.

Latest APK: `https://github.com/aaronremoteitwork-sys/Workspace/releases/latest/download/Workspace.apk`

## Signing — important

Workspace **must** use the same `ils.jks` key as ILS. That shared key is how it recognizes your apps.
In the Workspace GitHub repo, add the same two secrets as ILS: `ILS_KEYSTORE_B64` and `ILS_KEY_PASSWORD`.
`app/ils.jks` is excluded by `.gitignore` and never uploaded.

## Serial number setup (Android 10 and newer)

Zebra only gives the serial number to apps that are allowed to read it. Add this step to the
Workspace StageNow profile (or make a separate barcode):

- **AccessMgr** → Service Access Action: **Allow Caller to Call Service**
- Service Identifier: `content://oem_info/oem.zebra.secure/build_serial`
- Caller Package Name: `com.ils.workspace`
- Caller Signature: `ils.crt` (the public certificate of your signing key; safe to share)

Until that step has run, the corner shows `SN ?`. On Android 8/9 Workspace asks for the
phone permission once instead.
