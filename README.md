# Ryx Isla

Ryx Isla is an Android notification bridge based on [Hyper Bridge](https://github.com/D4vidDf/HyperBridge). It brings a customizable HyperIsland-style notification interface to supported Xiaomi, Redmi, and POCO devices.

## Version

**Ryx Isla 1.0.0** · package `com.dav.isla` · Android 15 or newer.

## Features

- Bridge selected app notifications into a floating island, with per-app controls.
- Customize themes, colors, shapes, actions, and notification layouts.
- Display media artwork, calls, downloads, navigation, and supported widgets.
- Use local notification processing and Android's notification access controls.

Ryx Isla is an independent derivative. The upstream Hyper Bridge code is licensed under Apache 2.0; its license is included in [`LICENSE`](LICENSE), and notices are listed in [`NOTICE.md`](NOTICE.md).

## Build

Open this repository in Android Studio or run:

```powershell
.\gradlew.bat assembleDebug
```

The project uses Gradle 9.5.0, JDK 21, and Android SDK 36.

## Upstream and attribution

- Upstream project: [D4vidDf/HyperBridge](https://github.com/D4vidDf/HyperBridge)
- Upstream revision used as the rewrite base: `e19ede13b87150028d89d31106ed898660e4c145`
- License: Apache License 2.0

Ryx Isla changes the app identity, package ID, branding, and release configuration. See the upstream repository for the original project and its full history.