# Apple CarPlay (personal build)

Wired Apple CarPlay next to Android Auto, for this car only. The CarPlay stack is
[DiPlay](https://github.com/shihabal3amri/DiPlay) 0.2.22 (commit `615b32ce`, GPL-3.0, itself based on
[xcertplay](https://github.com/shilapi/xcertplay)); its UI adapts DiAuto (AGPL-3.0). Licences and
notices are in [`licenses/`](licenses/). CarPlay is Apple's; this is not an Apple-certified product.

| Module | From DiPlay | What it holds |
| --- | --- | --- |
| `:carplay-engine` (`carplay/engine`) | `:shared` | iAP2, iPhone USB/NCM link, local VPN tunnel, AirPlay video/audio/touch, microphone |
| `:carplay-ui` (`carplay/ui`) | `:common` | The CarPlay screen (`CarPlayHostActivity`) and DiPlay's own screens, which this build never shows |

## How it works in the car

- **Plug in an iPhone** → `UsbAttachedActivity` (the app's only USB entry point) hands it to
  `CarPlayLauncher`, which opens the CarPlay screen over USB. An Android phone still goes to
  Android Auto. An iPhone left plugged in across a reboot is picked up by the service's USB scan.
- **The home** has a CarPlay card beside the Android Auto one. "Connect over USB" opens CarPlay for a
  plugged-in iPhone and otherwise only says "No device found" (the Android Auto card does the same
  for Android phones). While CarPlay runs the card reads "Connected" and "Back to CarPlay".
- **Home button**: a round button in the top corner away from the driver, over CarPlay as over
  Android Auto, goes back to the home with CarPlay still running. Back and CarPlay's car icon do too.
- **Wired only.** The unit's Bluetooth belongs to its own module (gocsdk), so wireless CarPlay
  cannot work. `CarPlayLauncher.applyCarDefaultsOnce` switches DiPlay to USB, copies the
  right-hand-drive setting, and sets the car's name and make where DiPlay would say "DiPlay"/"BYD".
- **First connection** asks for: USB access (choose "always"), the local VPN (it only carries the
  USB link to the iPhone), microphone, and "Trust" on the iPhone.
- **Steering wheel**: next / previous / play-pause reach CarPlay through the unit firmware's media
  keys (DiPlay holds audio focus). `CarPlayTopwayKeys` adds answer, hang-up and Voice (Siri) from
  TWUtil 0x201, and brings CarPlay back after the unit's phone screen jumps over it.
- **Settings**: Head Unit Settings has an "Android Auto | Apple CarPlay" switch at the top. The
  CarPlay tab (`CarPlaySettingsSection`, through `CarPlaySettings`) holds the options that apply to
  this unit: driving side, day/night, icon size, resolution, frame rate, system bars, corners, video
  decoding, navigation volume, music buffer, audio focus, call audio, and a diagnostic report
  (saved to `Downloads/OpenHeadunit`). The home menu's "Apple CarPlay" opens that tab.

## No DiPlay on screen

DiPlay's own screens and name never show:

- `OpenHeadunitBridge` hooks, set by `CarPlaySession.install`: every place DiPlay would open its
  own page goes to this app's home or settings (`hostScreenIntent`); its three-finger menu is off
  (`quickMenuEnabled`); the connecting screen gets the home's look and the Home button
  (`paintWaitingScreen`, `decorateScreen`, see `CarPlayScreenDecor`); the iPhone is told the car's
  name (`carName`, `carplay_car_name`).
- `app/src/main/res/values*/carplay_strings.xml` re-word every DiPlay string that names DiPlay, in
  each of DiPlay's languages. They are generated:
  `node carplay/tools/carplay-strings.js carplay/ui/src/main/res app/src/main/res`.
  The app also overrides DiPlay's notification icon (`ic_diplay_notification`).
- The app manifest hides DiPlay's settings activity (no icon, not exported) and removes its
  BYD-only parts, its USB-dialog accessibility service and its self-update file provider.

## Accessory identity (never in git)

The iPhone only starts CarPlay after the accessory proves its identity with a private key and
certificate. This project uses the identity DiPlay ships inside its own public release APK. The
files are **not** in the repo (`.gitignore` blocks `offline-mfi/`, `*.pk8`, `*.p7b`); a build adds
them from a folder outside it. The release APK on GitHub is built with them, as DiPlay's own
release is, so anyone can extract them from it.

To build with CarPlay yourself:

1. Download `DiPlay-0.2.22.apk` from DiPlay's
   [releases](https://github.com/shihabal3amri/DiPlay/releases) and check it: its sha256 starts
   `7d04db26` and ends `2e173139` (`certutil -hashfile DiPlay-0.2.22.apk SHA256` on Windows,
   `sha256sum DiPlay-0.2.22.apk` elsewhere).
2. An APK is a zip. Extract the two files under `assets/offline-mfi/`:

   ```sh
   unzip DiPlay-0.2.22.apk "assets/offline-mfi/*" -d diplay
   ```

3. Put them in a folder **outside** the repo, keeping the `offline-mfi` folder name:

   ```
   carplay-identity/
     offline-mfi/
       identity.pk8
       certificate.p7b
   ```

4. Point the build at that folder in `local.properties` (git-ignored), or set the environment
   variable `CARPLAY_IDENTITY_DIR`:

   ```properties
   carplay.identity.dir=C\:/path/to/carplay-identity
   ```

5. Build as below. The files end up in the APK under `assets/offline-mfi/`; check with
   `unzip -l <apk> | grep offline-mfi`.

Without them the APK still builds and Android Auto works; CarPlay then reports that it could not
start. Never commit the two files: `git status` must not list them.

## Building

Build from the clone outside OneDrive (native libraries break inside OneDrive), with
`local.properties` copied in. `minSdk` is 24 for the whole app because DiPlay and Compose need it.

## Changes made to the DiPlay sources

Kept as close to upstream as possible so a newer DiPlay can be copied over:

- `engine/build.gradle`, `ui/build.gradle.kts`: rewritten for AGP 8.13, compileSdk 36, NDK 29, no
  version catalog; `androidx.core` 1.17 (1.19 needs compileSdk 37 / AGP 9.1).
- `ui/.../WiredPermissions.kt`, `WirelessPermissions.kt`: SDK 37 constants replaced by literals.
- `ui/.../DiPlayActivity.kt`: two explicit `arrayOf<Drawable>` for Kotlin 2.4.
- `ui/.../CarPlayHostActivity.kt`:
  - `showDiPlayHome` and the start-up failure go to `OpenHeadunitBridge.hostScreen` first;
  - `openSettingsMenu` stops at `quickMenuEnabled`;
  - the connecting screen hides "Back to DiPlay" and the gesture hint, and calls
    `paintWaitingScreen` and `decorateScreen`;
  - the accessory and AirPlay names are `OpenHeadunitBridge.carName`;
  - two lines in `CarPlayBackgroundSession` tell `OpenHeadunitBridge` when a session starts and ends.
- `ui/.../DiPlaySessionService.kt`: the notification is titled with the car's name.
- `ui/.../DiagnosticExportStore.kt`: reports go to `Downloads/OpenHeadunit`.
- `ui/.../OpenHeadunitBridge.kt`, `ui/.../CarPlaySettings.kt`: new, the app's window into CarPlay.
- `ui/consumer-rules.pro`: new, keeps DiPlay's classes whole under R8 (DiPlay ships unshrunk).
- Tests were not copied.

To update: copy DiPlay's `shared/src/main` and `common/src/main` over `engine/src/main` and
`ui/src/main`, re-apply the items above (`git diff` on this folder shows them), then regenerate
`carplay_strings.xml`.
