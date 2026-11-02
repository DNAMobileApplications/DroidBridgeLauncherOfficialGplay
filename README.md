<p align="center">
  <img src="docs/app_icon.png" alt="DroidBridge Launcher" width="128" height="128">
</p>

<h1 align="center">DroidBridge Launcher</h1>

<p align="center">
  A Minecraft: Java Edition launcher and Android compatibility project by <strong>DNA Mobile Applications</strong>.
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=ca.dnamobile.droidbridgelauncher">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
         alt="Get it on Google Play"
         height="80">
  </a>
</p>

<p align="center">
  <strong>NOT AN OFFICIAL MINECRAFT PRODUCT.</strong><br>
  <strong>NOT APPROVED AS AN OFFICIAL MINECRAFT PRODUCT, ENDORSED BY, SPONSORED BY, OR ASSOCIATED WITH MOJANG, MICROSOFT, XBOX, OR MINECRAFT.</strong>
</p>

---

## About DroidBridge Launcher

DroidBridge Launcher is an independent Android project developed by **DNA Mobile Applications** for users who legitimately own **Minecraft: Java Edition** and want to manage and run their Java Edition installation on supported Android devices.

DroidBridge provides launcher-side Android compatibility and a unified interface for game instances, Java runtimes, graphics backends, controls, mods, resource packs, shader packs, worlds, logs, recording, and related launcher features.

The official Android release is distributed by DNA Mobile Applications through the Google Play listing above.

---

## Highlights

DroidBridge includes launcher-side support for features such as:

- Multiple Minecraft: Java Edition versions and instances
- Java runtime management
- Fabric, Forge, NeoForge, Quilt, Cleanroom and other supported mod-loader workflows
- Multiple OpenGL and Vulkan renderer paths
- Mesa/Zink-based rendering and supported custom graphics-driver workflows
- Touch controls and editable touch-control profiles
- Virtual mouse support
- Physical controller mapping
- Mods, resource packs, shader packs and world management
- Game logs and in-game log access
- Screen recording
- Dual-screen features on supported devices/configurations
- Per-instance settings
- An experimental Horizon/Rust game-engine option for supported instances

Feature availability depends on the Minecraft version, device, Android version, graphics hardware, selected renderer, mods and other configuration.

---

## Game engines

DroidBridge primarily runs Minecraft: Java Edition through its Java engine.

It also includes experimental support for **Horizon**, an independent Rust-based Minecraft: Java Edition 26.3 implementation: <https://github.com/ludovic111/horizon>.

| Engine | Supported use in DroidBridge | Java mods |
| --- | --- | --- |
| **Java engine** | Minecraft 1.0 through 26.3, plus supported legacy/modded configurations such as Beta 1.7.3 through Babric/BTA | Supported where compatible |
| **Horizon (Rust, experimental)** | Vanilla 26.3 only | No Java mods |

### Horizon / Rust support

For a compatible vanilla 26.3 instance, DroidBridge can offer a choice between the normal Java engine and Horizon.

When Horizon is used:

- DroidBridge continues to provide the launcher-side instance and control experience.
- DroidBridge touch controls, virtual mouse and controller mappings are bridged into the Horizon environment.
- The Horizon game process runs separately from the normal Java game process.
- Horizon uses the compatible instance data needed for gameplay, including the instance game folder where supported.
- Horizon support requires a compatible **arm64-v8a** Android device and appropriate graphics support.
- Horizon is experimental and is not a replacement for the normal Java engine or the broader Minecraft version/mod support provided by DroidBridge.

**Horizon is a third-party open-source project and is not an official Minecraft edition or Mojang/Microsoft product.**

Every unsupported version or modded configuration continues to use DroidBridge's Java engine.

---

## Minecraft ownership is required

DroidBridge Launcher does not provide Minecraft ownership or a Minecraft license.

Users of the official DroidBridge Launcher must sign in using their own eligible Microsoft account. Access to Minecraft: Java Edition is only permitted when the account has the required ownership or entitlement.

**DroidBridge Launcher does not provide cracked accounts, offline accounts intended to bypass ownership checks, authentication bypasses, ownership-check bypasses, or any other method intended to allow Minecraft: Java Edition to be played without legitimate ownership.**

If the Microsoft account being used does not have access to Minecraft: Java Edition, the official DroidBridge Launcher will not authorize that account to play the game.

---

## Minecraft game files are not included

**DroidBridge Launcher does not include, bundle, sell, or independently distribute Minecraft game files.**

The launcher does not provide Minecraft game JAR files, Minecraft assets, paid Minecraft content, account tokens, proprietary Mojang or Microsoft files, cracked game distributions, or a licensed copy of Minecraft.

DroidBridge Launcher provides launcher and compatibility functionality that allows eligible users to access and manage files and services they are authorized to use.

Nothing in this repository or application grants ownership of Minecraft or permission to redistribute Minecraft content.

---

## Microsoft account sign-in

DroidBridge Launcher supports Microsoft account authentication through Microsoft identity and Minecraft-related services.

Microsoft or Minecraft service access may require application registration, review, approval, allow-listing, or other authorization from Microsoft, Mojang, or the applicable service operator.

DNA Mobile Applications requested Microsoft/Minecraft API access for DroidBridge Launcher while identifying the application as an Android launcher intended to run Minecraft: Java Edition in accordance with the applicable Minecraft terms.

Information relating to that application review is available here:

<https://drive.google.com/file/d/1eIt6Jgbp2x5u4mpuX7uHhsesLMEg_ReJ/view?usp=sharing>

Any authorization granted for authentication or API access applies only to the functionality that was reviewed.

**Authentication or API authorization does not make DroidBridge Launcher an official Minecraft product, official Microsoft launcher, Mojang partner, Microsoft partner, Xbox partner, or endorsed Minecraft service.**

---

## Official source repository

The official DroidBridge Launcher source repository published by DNA Mobile Applications is:

<https://github.com/DNAMobileApplications/DroidBridgeLauncherGplayGithub>

This repository contains source made available by DNA Mobile Applications together with third-party and third-party-derived components governed by their respective licenses.

Files written entirely by DNA Mobile Applications remain owned by DNA Mobile Applications unless an applicable file header, license, or notice states otherwise.

Files copied from, modified from, ported from, or derived from third-party projects remain subject to their applicable licenses.

---

## Open-source software and license boundaries

DroidBridge Launcher is a mixed-origin source project.

DroidBridge may use, modify, adapt, link with, bundle, or interoperate with open-source software where permitted by the applicable license.

Third-party software remains subject to its own copyright, attribution, license, notice, source-availability, and redistribution requirements.

Projects or components used by DroidBridge Launcher may include software from or related to:

- PojavLauncher / Boardwalk-related work
- LWJGL
- OpenJDK
- Mesa
- GL4ES
- AndroidX
- Google Material Components
- Horizon
- Other open-source libraries and components identified by their applicable notices

Code copied from, modified from, ported from, or derived from third-party software remains subject to the applicable upstream license.

Where GPL- or LGPL-covered code is used, the applicable GNU license requirements continue to apply to that covered code and its covered modifications.

Moving, renaming, reorganizing, or incorporating covered code into another part of the project does not remove applicable open-source license obligations.

For detailed attribution, license information, source availability, and redistribution requirements, see:

**[Open Source Licensing & Notices](https://drive.google.com/file/d/1s5ovK-Ufzx8sdvIpK3R8vfxzXm4hcPv6/view?usp=sharing)**

---

## Unofficial forks and modified builds

Applicable open-source licenses may permit third parties to fork, modify, compile, or redistribute covered portions of this project.

That does **not** make those projects official DroidBridge Launcher releases.

Unless DNA Mobile Applications explicitly states otherwise, forks, clones, modified APKs, redistributed builds, and third-party repositories are not affiliated with, operated by, supported by, or endorsed by the official DroidBridge Launcher project.

The existence of this source repository does not authorize another project to represent itself as:

- The official DroidBridge Launcher
- An official DNA Mobile Applications release
- The official Google Play build
- A build supported by DNA Mobile Applications
- A project affiliated with the official DroidBridge Launcher

Third-party distributors are responsible for their own modifications, signing keys, authentication configuration, security, privacy practices, legal compliance, support, and distribution methods.

DNA Mobile Applications is not responsible for unofficial forks, modified APKs, third-party builds, or software distributed outside its official channels.

---

## Privacy

DroidBridge Launcher is designed primarily around launcher data stored locally on the user's device.

Launcher settings, worlds, mods, resource packs, shader packs, instances, logs, control profiles, and related local files remain on the device unless the user chooses to export, share, upload, back up, or otherwise transfer them.

Features involving Microsoft authentication, multiplayer servers, downloads, or other online services communicate with the applicable external services when those features are used.

See the full Privacy Policy for additional information.

---

## Legal information

| Document | Link |
| --- | --- |
| Privacy Policy | [DroidBridge Launcher Privacy Policy](https://docs.google.com/document/d/1aGLPa6tECaLEFr5rZxVBWba5CRyW3XFB/edit?usp=sharing&ouid=113792205778833825558&rtpof=true&sd=true) |
| Terms of Service | [DroidBridge Launcher Terms of Service](https://docs.google.com/document/d/1fd5fChQocOZ7QEYoTo4FYzOSEe8OEWNg/edit?usp=sharing&ouid=113792205778833825558&rtpof=true&sd=true) |
| Open Source Licensing & Notices | [Open Source Licensing & Notices](https://drive.google.com/file/d/1s5ovK-Ufzx8sdvIpK3R8vfxzXm4hcPv6/view?usp=sharing) |

The Open Source Licensing & Notices document contains detailed third-party attribution and licensing information applicable to the project.

---

## Building the project

### Requirements

- Android Studio
- Android SDK required by the current project
- Compatible JDK
- Git

### Linux / macOS

```bash
git clone https://github.com/DNAMobileApplications/DroidBridgeLauncherGplayGithub.git
cd DroidBridgeLauncherGplayGithub
./gradlew assembleDebug
```

### Windows

```bat
git clone https://github.com/DNAMobileApplications/DroidBridgeLauncherGplayGithub.git
cd DroidBridgeLauncherGplayGithub
gradlew.bat assembleDebug
```

Release builds must be signed using the distributor's own signing configuration.

Do not publish builds containing production credentials, API keys, signing keys, account tokens, private service credentials, or other confidential production configuration.

Compiling the source independently does not make the resulting APK an official DroidBridge Launcher release.

### Horizon engine builds

Horizon support is optional. DroidBridge can build and operate with the normal Java engine without Horizon being bundled.

Developers who choose to build Horizon support must use a compatible Horizon Android build and follow the applicable Horizon and third-party license requirements.

The large native engine artifacts are separate from the normal Java source build and may need to be provided locally during development.

---

## Contributing

Contributions are welcome when they respect the project's code style and all applicable copyright and open-source licensing requirements.

Do not submit code copied from another project unless its license permits the proposed use.

Required copyright notices, attribution, license information, and source-availability obligations must be preserved for third-party-derived code.

See the **Open Source Licensing & Notices** document before contributing or redistributing covered third-party code.

---

## Trademarks and independence

Minecraft, Minecraft: Java Edition, Mojang, Microsoft, Xbox, and associated names, trademarks, services, artwork, assets, and other intellectual property belong to their respective owners.

DroidBridge Launcher is not affiliated with, sponsored by, endorsed by, partnered with, or operated by Mojang, Microsoft, Xbox, or Minecraft.

DroidBridge Launcher is also independent from PojavLauncher, Boardwalk, Zalith Launcher, Amethyst, Fold Craft Launcher, and other third-party launcher projects.

Use, modification, reference, or attribution of open-source software from another project does not by itself create an official affiliation with that project.

---

## Disclaimer

**DroidBridge Launcher is not an official Minecraft product.**

DroidBridge Launcher does not sell Minecraft, provide a Minecraft license, provide Minecraft game files as part of the launcher, provide Minecraft accounts, or bypass Minecraft ownership requirements.

Users must use their own eligible Microsoft account and legitimately own Minecraft: Java Edition in order to use the official DroidBridge Launcher to play the game.

Any Microsoft or Minecraft authentication/API authorization granted to DroidBridge Launcher is limited to the applicable reviewed functionality and does not make DroidBridge Launcher an official Minecraft launcher or establish sponsorship, endorsement, or partnership with Mojang, Microsoft, Xbox, or Minecraft.

Forks, clones, modified builds, and redistributed versions are separate projects and are not affiliated with the official DroidBridge Launcher or its Google Play release unless DNA Mobile Applications explicitly identifies them as official.

Third-party software, trademarks, copyrighted material, and other intellectual property remain subject to the rights and licenses of their respective owners.

For third-party licensing and attribution information, see the **[Open Source Licensing & Notices](https://drive.google.com/file/d/1s5ovK-Ufzx8sdvIpK3R8vfxzXm4hcPv6/view?usp=sharing)** document.

This README is provided for project information and is not legal advice.
