<p align="center">
  <img src="docs/app_icon.png" alt="DroidBridge Launcher" width="128" height="128">
</p>

<h1 align="center">DroidBridge Launcher</h1>

<p align="center">
  A Minecraft: Java Edition launcher for Android devices.
</p>

<p align="center">
  <strong>NOT AN OFFICIAL MINECRAFT PRODUCT.</strong><br>
  <strong>NOT APPROVED AS AN OFFICIAL MINECRAFT PRODUCT, ENDORSED BY, SPONSORED BY, OR ASSOCIATED WITH MOJANG, MICROSOFT, XBOX, OR MINECRAFT.</strong>
</p>

---

## Horizon engine (experimental)

Vanilla **26.3** instances can run on Horizon (<https://github.com/ludovic111/horizon>, MIT),
Minecraft: Java Edition 26.3 rewritten in Rust, as well as on the Java engine. Every other
instance launches with the Java engine exactly as before.

| Engine | Versions | Mods |
| --- | --- | --- |
| **Java** | 1.0 → 26.3, plus Beta 1.7.3 through Babric and BTA | Fabric, Forge, NeoForge, Quilt, OptiFine, Cleanroom |
| **Horizon (Rust)** | 26.3 only | No Java mods |

- Pressing Play on a vanilla 26.3 instance asks which engine to use ("Remember for this
  instance"). **Instance settings → Game Engine** changes it, turns DroidBridge's controls
  for Horizon on or off, opens Horizon's own settings and shows Horizon's licenses.
- Both engines use the instance's game folder (worlds, options, packs). Horizon's one-time
  26.3 download (about 500 MB, from Mojang) lives in the app's private `files/horizon`.
- Horizon gets DroidBridge's verified Microsoft session, so there is no second sign-in, and
  DroidBridge's touch layout, virtual mouse and controller mapping drive the game.
- It runs in its own `:horizon` process and needs Android 9+, arm64-v8a, and Vulkan or OpenGL
  ES 3. Devices that don't qualify never see the choice.
- Horizon's data collection is turned off when DroidBridge hosts it (`HORIZON_TELEMETRY_URL=off`),
  so it contacts only Mojang/Microsoft services (including Mojang's sample repository on GitHub)
  and the servers the player joins.

### Building with the Horizon engine

Optional: without the engine files the app builds with the Java engine only
(`BuildConfig.HORIZON_ENGINE_BUNDLED=false`, Horizon components disabled in the manifest).

1. Build the engine from the patched `horizon-engine` tree with `--public` (Google Play builds
   must use it: the engine data then holds generated tables only):
   `powershell -ExecutionPolicy Bypass -File .\packaging\android\BUILD_ANDROID_WINDOWS.ps1`
2. In `local.properties`: `HORIZON_ANDROID_OUTPUT=C:/path/to/horizon-engine/.local/android`
   (or copy the files into `horizon-engine-dist/`, see its README).
3. Build as usual. Gradle logs `DroidBridge: Horizon engine found ... bundling it.`

### Before a Google Play release

- Raise `launcherVersionCode` in `app/build.gradle` (Play rejects a reused version code).
- Check 16 KB page support: Build → Analyze APK, open `lib/arm64-v8a/libhorizon_android.so`;
  Android Studio flags libraries that are not 16 KB aligned.
- Ship through a closed or internal testing track first, then a staged rollout.
- Data safety: Horizon adds no data collection of its own in this build.

## Important

DroidBridge Launcher is an independent Android project by **DNA Mobile Applications**.

DroidBridge Launcher is built for users who already own Minecraft: Java Edition and want to manage and launch their own local game installation on Android. The launcher does not include Minecraft game files, Minecraft assets, paid content, account tokens, or other proprietary Mojang/Microsoft files.

Minecraft, Mojang, Microsoft, Xbox, and related names, services, trademarks, and assets belong to their respective owners. Users are responsible for following the Minecraft EULA, Minecraft Usage Guidelines, Microsoft Services Agreement, and any other terms that apply to their account, game copy, device, mods, resource packs, servers, or third-party content.

Other than any limited authentication or API access granted for Microsoft account sign-in, DroidBridge Launcher is not affiliated with, sponsored by, endorsed by, partnered with, or approved as an official Minecraft product by Mojang, Microsoft, Xbox, Minecraft, Zalith Launcher, Amethyst, Fold Craft Launcher, or any other third-party launcher project.

## About

DroidBridge Launcher is a launcher-side Android compatibility project. It helps users manage Minecraft: Java Edition versions, instances, runtime components, renderers, controls, mods, resource packs, shader packs, worlds, logs, and launcher settings on Android.

The project may use, modify, adapt, study, or interoperate with open-source launcher work where the applicable license allows it. DroidBridge Launcher keeps third-party license obligations attached to the code they apply to, including attribution, notice preservation, license text availability, and source availability where required.

## Microsoft account sign-in

DroidBridge Launcher may support Microsoft account sign-in through Microsoft identity services.

Microsoft account sign-in or related Minecraft service/API access may require review, registration, allow-listing, or approval from Microsoft, Mojang, or their service teams before it is available in a production build. If that access has been granted, it applies only to the authentication or API access that was reviewed and allowed.

That limited authentication/API approval does not make DroidBridge Launcher an official Minecraft product, official launcher, Mojang partner, Microsoft partner, Xbox partner, or endorsed Minecraft service.

Users must use their own Microsoft/Minecraft account and are responsible for following the terms that apply to that account.

## Installation and game setup

DroidBridge Launcher is intended to help users install and manage their own Minecraft: Java Edition game files on their device after they have signed in or otherwise provided access allowed by the official services and terms.

Code written by DNA Mobile Applications for user-facing setup, game installation guidance, account sign-in, launcher flow, or related screens is written to respect the GNU license obligations of any GNU-licensed code it partially uses, modifies, links with, or depends on. Where a file or feature is based on GPL/LGPL-covered code, DroidBridge Launcher treats the covered portions and modifications as remaining under the applicable GNU license terms.

DroidBridge Launcher does not grant a Minecraft license, bypass Minecraft ownership checks, distribute Minecraft, or replace the need for a legitimate copy of Minecraft: Java Edition.

## Legal pages

DroidBridge Launcher provides public legal and notice pages for users and contributors:

| Page | Link |
| --- | --- |
| Privacy Policy | https://dnamobilegaming.com/privacy |
| Terms of Service | https://www.dnamobilegaming.com/terms |
| Licensing Information | https://www.dnamobilegaming.com/license |
| Open Source Notices | [OPEN_SOURCE_NOTICES.md](OPEN_SOURCE_NOTICES.md) |

The in-app legal links are intended to point users to the same public privacy, terms, and licensing pages.

## Source availability and license boundaries

DroidBridge Launcher is a mixed-origin source tree.

Files written entirely by **DNA Mobile Applications** are owned by DNA Mobile Applications unless a file header, license file, or notice says otherwise.

Files copied from, modified from, derived from, or based on third-party projects remain under their original license terms. This includes any code or modifications covered by GPL, LGPL, Apache-2.0, MIT, BSD, or other open-source licenses.

If this repository includes GPL/LGPL-covered code, the corresponding covered source code and DroidBridge Launcher modifications to that covered code must remain available under the applicable GNU license terms.

Private production configuration, Microsoft application credentials, signing material, API keys, account-service configuration, and original DNA Mobile Applications service/client code may be excluded from the public repository when they are not copied from, modified from, linked in a way that requires publication, or derived from GPL/LGPL-covered code.

Any file copied from, modified from, or derived from GPL/LGPL-covered code remains subject to the applicable GPL/LGPL terms, regardless of where it appears in the project.

## Open-source libraries and licenses

DroidBridge Launcher may use or reference open-source projects and libraries, including but not limited to:

| Project / Library | License | Link |
| --- | --- | --- |
| LWJGL / LWJGL3 | BSD-3-Clause unless otherwise stated by the upstream project | https://www.lwjgl.org |
| OpenJDK / Java runtime components | GPLv2 with the Classpath Exception for many OpenJDK components, depending on the exact runtime distribution | https://openjdk.org |
| Mesa | MIT-style licenses depending on component/file | https://www.mesa3d.org |
| GL4ES | MIT unless otherwise stated by the upstream project | https://github.com/ptitSeb/gl4es |
| AndroidX / Google Material Components / Android platform libraries | Apache-2.0 unless otherwise stated | https://developer.android.com |

See [OPEN_SOURCE_NOTICES.md](OPEN_SOURCE_NOTICES.md), the public licensing page, and any license files included with third-party components for the notices that apply to this repository.

## Privacy

DroidBridge Launcher is designed around local launcher data.

Launcher settings, logs, worlds, mods, resource packs, shader packs, instances, and related files are intended to stay on the user's device unless the user chooses to export, share, upload, back up, or send them through another app or service.

See the [DroidBridge Launcher Privacy Policy](https://dnamobilegaming.com/privacy) for the full privacy policy.

## Language and translation support

Translations are welcome.

If translation files are included in this repository, contributors should keep wording clear, accurate, and respectful of the legal notices above. Avoid implying official Mojang, Microsoft, Xbox, Minecraft, or third-party launcher approval in any language.

## Build instructions

> This section is for developers who want to build the project locally.

### Requirements

- Android Studio
- Android SDK with the project-supported minimum and target API levels
- JDK required by the current Gradle/Android plugin setup
- Git

### Build steps

```bash
git clone https://github.com/DNAMobileApplications/DroidBridgeLauncher.git
cd DroidBridgeLauncher
./gradlew assembleDebug
```

On Windows:

```bat
git clone https://github.com/DNAMobileApplications/DroidBridgeLauncher.git
cd DroidBridgeLauncher
gradlew.bat assembleDebug
```

Release builds should be signed with your own release keystore. Do not publish builds containing private credentials, signing material, API keys, local machine paths, account tokens, or debug-only configuration.

## Contributing

Contributions are welcome when they respect the project's code style, legal notices, and license requirements.

Do not submit copied code unless the license allows it and the original attribution is preserved. If a contribution is based on third-party code, make that clear in the pull request and keep the required notices with the affected files.

## Disclaimer

DroidBridge Launcher is not an official Minecraft product. Any Microsoft, Mojang, or Minecraft service/API access granted for account sign-in is limited to that reviewed authentication/API use only. It does not make DroidBridge Launcher an official launcher, official Minecraft product, endorsed service, or partner of Mojang, Microsoft, Xbox, or Minecraft.

This project does not include Minecraft, does not sell Minecraft, and does not provide any rights to Minecraft or other third-party content.

This README is not legal advice. Review the actual license files, third-party notices, Microsoft/Minecraft terms, Google Play policies, and any store-specific requirements before redistributing APKs, publishing modified builds, or using this project commercially.

## Windows ndk-build note

If Android Studio reports `APP_BUILD_SCRIPT points to an unknown file`, make sure the project is extracted to a path with no spaces, such as `C:\StudioProjects\DroidBridgeRewrite`. This package also includes a Gradle-side short-path workaround for Windows, but some Windows installations disable 8.3 short paths.

## Microsoft Login Configuration Fix

The Android Studio rewrite now preserves the original Microsoft OAuth Gradle wiring and uses the DroidBridge public OAuth client ID as a safe fallback for debug/test builds. Private values such as signing passwords and API keys are intentionally not packaged in this zip. Override the client ID, redirect URI, scope, signing keys, and API keys through your local `local.properties` or CI environment.

## Clean main rewrite pass

This package includes the DroidBridge clean main rewrite pass. See `DROIDBRIDGE_CLEAN_MAIN_REWRITE_NOTES.md` for the exact scope, compatibility choices, and Play Store notes.
