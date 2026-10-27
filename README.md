# Desupon

An Android VPN client that keeps you online when networks get hostile. It picks a working server from all of your subscriptions on its own, notices mobile whitelists, and stays out of the way.

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)

> [!NOTE]
> 🤖 **All code in this repository was written by a neural network.** The Kotlin app, the Go bridge, the build scripts and the tests were produced by AI coding agents (large language models) under the direction of a human maintainer.

## ✨ Features

### 🌱 One tap to connect

Press **Connect** and Desupon probes the servers of all your subscriptions at once, then picks the fastest one that actually works. You don't need to switch subscriptions by hand. If the server drops, it quietly moves you to another one.

### 📵 Mobile whitelists

Desupon detects when a mobile carrier switches to whitelist-only access and chooses servers that still get through on that network.

### ⛓️ Priorities and sets

- **Priorities**: drag subscriptions and configs into one order, e.g. "try this one first, then that one". Desupon stays on the first one while it works.
- **Sets** choose which subscriptions and configs take part in automatic selection. The country is chosen separately, at any time.

### 🗺️ Countries and servers

Tap the bottom bar to open the list of countries with their ping. The ping is "warm": it is the latency you feel on an established connection, not the time of a full handshake.

### 🔀 Split tunneling

- Send all apps through the VPN, only the selected ones, or all except the selected ones.
- Russian banking, marketplace and government-services apps, plus every `ru.*` / `su.*` package, bypass the VPN by default.
- Rules by domain, IP and rule sets: the bundled ones, or any category from the geoip / geosite databases below.
- **IPv4 only** removes IPv6 from the tunnel: tunneled apps get no IPv6 at all, and DNS returns no IPv6 addresses.

### 🌐 geoip / geosite databases

- Desupon downloads v2ray-format `geoip.dat` and `geosite.dat` and turns their categories (e.g. `ru-blocked`, `category-ads-all`) into rule sets you can pick in split-tunnel rules, with search.
- By default it uses [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat), checks every 5 days and downloads only over Wi-Fi. The URLs, the interval and the Wi-Fi limit are all configurable, and there is an **Update now** button.
- Downloads are verified against the published checksum and replace the old files only when they are valid. Categories already in use are refreshed in place, without reconnecting.

### 🕵️ Private by default

These are always on, with no switch:

- No local listening ports, so apps that scan the device for proxies find nothing.
- No custom links or URL schemes that another app could use to detect the client.
- Neutral names in the system UI.
- The generated config with keys is never written to disk, and Android backup is disabled.

### 🧓 Simple mode

For older people and anyone who doesn't want to deal with settings:

- one screen, a big button and an arrow showing where to tap;
- if connecting fails, Desupon retries on its own;
- there is no way to stumble into the advanced settings by accident.

### 🔔 Clear notification

The notification shows the country, ping, speed and session traffic. On the lock screen it only shows a neutral "Connected".

### 🔋 Runs in the background

Desupon asks for the battery-optimization exemption it needs. On Honor and Huawei phones it points you to the autostart settings. With Android's **Always-on VPN** enabled, it starts by itself after a reboot.

When the phone wakes up after a long sleep, Desupon drops connections that went stale while it slept (for example Hysteria2 sessions the server already forgot) and reconnects at once, instead of waiting for them to time out.

### 📥 Subscriptions and protocols

- Add a subscription by link, from the clipboard, by scanning a QR code with the camera, from a photo of a QR code, or by sharing text to Desupon from another app. None of this needs Google services.
- Subscription URL mirrors and automatic updates.
- Protocols: VLESS (REALITY, XHTTP), VMess, Trojan, Shadowsocks, Hysteria2 (salamander and gecko obfuscation), TUIC, Juicity, AnyTLS, WireGuard / AmneziaWG, SOCKS / HTTP.
- Subscription formats: share-link lists (plain or base64), sing-box JSON, Xray JSON, Clash / mihomo YAML, SIP008, and WireGuard / AmneziaWG `.conf` files.

### 🔑 Configs

- Single server configs live on their own **Configs** tab, separate from subscriptions. Add them by link, from the clipboard, by QR code from the camera or a photo, or by sharing text to Desupon.
- Enter a config by hand with a per-protocol form (VLESS with REALITY / XHTTP, VMess, Trojan, Shadowsocks, Hysteria2, TUIC), or as a share link or sing-box JSON in a text editor with live checking.
- Edit saved configs the same way. Fields the form doesn't show are kept as they are.

### 🎨 Appearance

Dark, light and AMOLED themes, plus Material You colors on Android 12+. There is also an optional **desu mode** with playful captions from a mischievous gardener doll, desu 🌿

> [!NOTE]
> The interface is in Russian.

## 🚀 Quickstart

### Install

1. Download the APK for your device from [Releases](https://github.com/nerodono/desupon/releases):
   - `arm64-v8a` for most phones from recent years;
   - `armeabi-v7a` for older 32-bit devices.
2. Install it and open Desupon.
3. Add a subscription on the **Subscriptions** tab (paste the link, scan its QR code, or share it to Desupon), or a single server on the **Configs** tab.
4. Press **Connect** and allow the VPN connection when Android asks.

💡 To have the VPN come up after every reboot, turn on **Always-on VPN** for Desupon in Android's VPN settings.

### Build from source

You need x86_64 Linux with [Nix](https://nixos.org/) and flakes enabled. The dev shell provides Go, gomobile (SagerNet fork), JDK 17, the Android SDK and the NDK.

```sh
git clone https://github.com/nerodono/desupon.git
cd desupon
nix develop

# Fetch the pinned upstream sources, apply native/patches, run the Go tests and build the core AAR
./native/build-core.sh

# Build the app: one APK per ABI in app/build/outputs/apk/
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # unsigned; sign it with apksigner and your own key

# Unit tests
./gradlew test
```

## 🧩 Under the hood

- Core: [sing-box-extended](https://github.com/shtorm-7/sing-box-extended), built with gomobile together with a small Go bridge (`native/bridge`).
- Local patches on top of upstream live in `native/patches`, for example a fix for truncated large packets with gecko obfuscation in sing-quic.
- App: Kotlin, Jetpack Compose, Room, Koin.

## 🙏 Acknowledgements

- [sing-box](https://github.com/SagerNet/sing-box), [sing-quic](https://github.com/SagerNet/sing-quic) and [gomobile](https://github.com/SagerNet/gomobile) by SagerNet
- [sing-box-extended](https://github.com/shtorm-7/sing-box-extended)
- [sing-juicity](https://github.com/exclavenetwork/sing-juicity)
- Rule sets from [sing-geosite](https://github.com/SagerNet/sing-geosite) and [sing-geoip](https://github.com/SagerNet/sing-geoip); see `data/src/main/assets/ruleset/NOTICE`
- Default geoip / geosite databases from [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat)
- The default per-app bypass list is adapted from [turn-proxy-android](https://github.com/hackdiaz-dev/turn-proxy-android)
- Parts of the Android platform code are derived from shadowsocks-android and SagerNet; see the file headers

## 📄 License

[GNU General Public License v3.0](LICENSE).
