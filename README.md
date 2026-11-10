# TailConnect

TailConnect is an enhanced fork of the **KDE Connect Android app** with embedded **Tailscale (`tsnet`)** userspace tunnel support.

It enables secure, seamless device pairing and synchronization over a Tailnet (Tailscale / Headscale VPN) without requiring a system-wide VPN or root permissions, while retaining all standard KDE Connect local-network features.

## (Some) Features
- **Shared clipboard**: copy and paste between your phone and your computer (or any other device).
- **Notification sync**: Read and reply to your Android notifications from the desktop.
- **Share files and URLs** instantly from one device to another.
- **Multimedia remote control**: Use your phone as a remote for Linux media players.
- **Virtual touchpad**: Use your phone screen as your computer's touchpad and keyboard.

All this without wires, over the already existing Wi-Fi network, and using TLS encryption.

## About this app

This is a native Android port of the KDE Connect Qt app. You will find a more complete readme about KDE Connect [here](https://invent.kde.org/network/kdeconnect-kde/).

## How to install this app

You can install this app from the [Play Store](https://play.google.com/store/apps/details?id=org.kde.kdeconnect_tp) as well as [F-Droid](https://f-droid.org/repository/browse/?fdid=org.kde.kdeconnect_tp). Note you will also need to install the [desktop app](https://invent.kde.org/network/kdeconnect-kde) for it to work.

## Contributing

A lot of useful information, including how to get started working on KDE Connect and how to connect with the current developers, is on our [KDE Community Wiki page](https://community.kde.org/KDEConnect)

For bug reporting, please use [KDE's Bugzilla](https://bugs.kde.org). Please do not use the issue tracker in GitLab since we want to keep everything in one place.

To contribute patches, use [KDE Connect's Gitlab](https://invent.kde.org/network/kdeconnect-android/).
On Gitlab (as well as on our [old Phabricator](https://phabricator.kde.org/tag/kde_connect/)) you can find a task list with stuff to do and links to other relevant resources.
It is a good idea to also subscribe to the [KDE Connect mailing list](https://mail.kde.org/mailman/listinfo/kdeconnect).

Please know that all translations for all KDE apps are handled by the [localization team](https://l10n.kde.org/). If you would like to submit a translation, that should be done by working with the proper team for that language.

## License and Acknowledgments

- **TailConnect**: Licensed under [GNU GPL v2](https://www.gnu.org/licenses/gpl-2.0.html) and [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).
- **KDE Connect**: Original Android application copyright (c) Albert Vaca Cintora and KDE Connect contributors, licensed under GPL-2.0 / GPL-3.0.
- **Tailscale (`tsnet`)**: Embedded Tailscale client library copyright (c) 2020 Tailscale Inc. & contributors, licensed under the BSD 3-Clause License (see `LICENSES/BSD-3-Clause.txt`).

### Trademark Disclaimer
TailConnect is an independent community open-source project. It is not affiliated with, endorsed by, or sponsored by KDE e.V. or Tailscale Inc. "KDE" and "KDE Connect" are trademarks of KDE e.V. "Tailscale" is a registered trademark of Tailscale Inc.
