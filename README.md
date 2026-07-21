# Notification Bandit

<p align="center">
  <img src="docs/notification-bandit-preview.png" alt="Notification Bandit running inside an expanded Android notification" width="720">
</p>

**Notification Bandit** is a miniature one-arm bandit that lives inside a persistent Android notification. Expand the notification, select a bet, and pull the red lever without opening a full-screen game.

It is a small demonstration of how far Android custom notification layouts and foreground services can be pushed while remaining usable as an actual mini-game.

## Features

- Three animated slot-machine reels
- Reels stop separately from left to right
- Side-mounted red lever used to start each spin
- Selectable bets of **1, 2, 5, or 10 credits**
- Persistent credit balance and selected bet
- Win, loss, payout, and updated balance shown inside the notification
- Compact and expanded notification layouts
- Foreground service keeps the machine available in the notification shade
- Restores after a device reboot or app update
- No adverts, purchases, accounts, or real-money gambling

## Payout table

| Result | Payout |
|---|---:|
| Three 7s | 50 × bet |
| Three BAR symbols | 25 × bet |
| Three bells | 15 × bet |
| Three lemons | 10 × bet |
| Three cherries | 5 × bet |
| Any matching pair | 2 × bet |

The selected bet is deducted before the reels spin. Any payout is then added to the remaining credit balance.

## Project compatibility

- Java source
- No lambda expressions
- No AndroidX dependency
- Minimum SDK 21
- Target SDK 33
- Compile SDK 34
- Designed for AIDE/ZeroAicy AIDE
- Standard Gradle Android application structure

## Build with AIDE

1. Download or clone the repository.
2. Open the repository root as an Android project in AIDE.
3. Use **Build → Clean Project** after the first import.
4. Build and install the debug APK.
5. Open the app once and allow notifications when Android requests permission.
6. Expand **Notification Bandit** in the notification shade and pull the lever.

AIDE signs debug builds using its normal debug signing configuration. When replacing an older locally signed build, Android may require that version to be uninstalled first because the signing certificate can differ.

## Source layout

```text
NotificationBandit/
├── app/
│   ├── src/main/
│   │   ├── java/com/hyperion/notificationbandit/
│   │   │   ├── BootReceiver.java
│   │   │   ├── MainActivity.java
│   │   │   └── SlotMachineService.java
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── docs/
│   └── notification-bandit-preview.jpg
├── build.gradle
├── gradle.properties
├── settings.gradle
└── README.md
```

## How it works

`SlotMachineService` runs as a foreground service and posts custom `RemoteViews` notification layouts. Each spin chooses the final symbols first, displays a sequence of reel-strip frames, and stops the reels in stages. The final notification is redrawn after the animation ends so slower SystemUI implementations do not leave a spinning frame over the third reel.

`BootReceiver` restarts the notification after boot or package replacement, while `SharedPreferences` stores credits, the selected bet, reel positions, and the latest result.

## Android behaviour

Android can remove or suppress the notification after a force stop, notification permission removal, or aggressive battery optimisation. Reopen the app to start it again.

## Disclaimer

Notification Bandit is an entertainment-only demonstration. Credits have no monetary value and cannot be purchased, exchanged, or withdrawn.
