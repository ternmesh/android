# Tern for Android

The Android app for **Tern**, a LoRa mesh protocol that treats airtime as a
shared, metered resource. It drives a Tern node over Bluetooth LE through
the [companion protocol](https://github.com/ternmesh/spec/blob/main/draft/companion.md),
in Kotlin.

What the app is, and why it is native, is in
[decisions/phone-apps.md](https://github.com/ternmesh/spec/blob/main/decisions/phone-apps.md).
It finds a node, pairs with it, and keeps a link to it in the background; it shows conversations
with contacts and groups, where each message is and what it waits for, the node's battery, airtime
and the nodes it hears, and changes its region, role, power and passkey. It updates the node's
firmware over the same link, with the release ternmesh.org publishes for its board and region.

It shares the user's position with the contacts and groups they choose, each at the precision
they choose, and shows on a map where those sharing with them are. The phone gives the node its
exact location only while the node shares with someone; the node rounds it for each destination
before anything goes on the air. The map's tiles come from [OpenFreeMap](https://openfreemap.org),
drawn by [MapLibre](https://maplibre.org): the tile server sees which areas the map shows, and
nothing else of the user's.

**To try it**, install `tern.apk` from the
[latest release](https://github.com/ternmesh/android/releases/latest/download/tern.apk) on
Android 8 or later, or the one from the latest CI run on `main` (the `tern-apk` artifact). Both
are signed with the maintainers' release key, so a newer one installs over an older one, and it is
the app that `ternmesh.org` links open in. The first connection asks for the passkey your node
shows, or the one set over USB.

Every run, pull requests too, also keeps a debug build (`tern-debug-apk`). It is a separate app,
**Tern debug** (`org.ternmesh.app.debug`), signed with the debug key kept here, so it installs
beside the real one and never stands in for it.

```bash
./gradlew :protocol:test        # the companion protocol and the connection, against the specification's vectors
./gradlew :app:assembleDebug    # the app: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # onto a phone over adb
```

JDK 17 or later; the app also needs the Android SDK (platform 35), which Android Studio installs,
or `sdk.dir` in `local.properties`. The `protocol` module is plain Kotlin with no Android in it, so
its tests run on any JVM.

## Where things are

| Path | |
|---|---|
| `protocol/src/main/kotlin/org/ternmesh/companion/Frame.kt` | Every frame of the protocol's version 6, as Kotlin types, and its numbers. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Codec.kt` | A frame built into bytes, and read back from them. |
| `protocol/src/main/kotlin/org/ternmesh/companion/ByteStream.kt` | Frames on a byte stream (USB serial, TCP), with the node's console text between them. Bluetooth does not need it. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Connection.kt` | One connection, the client's half: `HELLO` and the version both speak, one request at a time, counted news, syncing again, and the `PING` that keeps a node from taking the app for gone. No I/O and no clock of its own: a link hands it frames and calls `tick()`. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Updater.kt` | One firmware image given to a node over a connection: `UPDATE_BEGIN`, the image in chunks from wherever the node says, `UPDATE_END`, and going on after the link drops. No I/O, as with the connection. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Release.kt` | The firmware manifest at `ternmesh.org/firmware/latest.json`: the image for a board and region, and whether its release is newer than a node's, by Semantic Versioning. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Records.kt` | What the node has said it holds, as news leaves it, positions received, sharing and the cards of who is about included, and the `after` the next sync asks from. |
| `protocol/src/main/kotlin/org/ternmesh/companion/RecordsFile.kt` | The records on disk, each as the frame that carried it, so the next run syncs only what is new; not positions, sharing or cards, which every sync sends whole. The Apple app keeps the same format. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Conversations.kt` | The records as conversations, and how far a `READ` may reach without marking another conversation's messages read. |
| `protocol/src/test/` | The conformance section of the specification, as a client: the codec against every vector, the connection as the client in `exchange`, `older` and `unknown_to_older`, and the updater as the client in `update`. |
| `app/src/main/kotlin/org/ternmesh/app/link/` | Bluetooth LE: scanning for the node's service, and the GATT link (an MTU of at least 183, passkey pairing, one frame per write and per notification). |
| `app/src/main/kotlin/org/ternmesh/app/node/` | The node the app drives: the link, the connection over it and the records on disk (`NodeRepository`), the foreground service that keeps it while the app is closed, message notifications, downloading firmware and checking it before it is sent (`Firmware.kt`), and the phone's location given to the node while it shares (`LocationFeed.kt`). |
| `app/src/main/kotlin/org/ternmesh/app/ui/` | The screens, in Jetpack Compose: choosing a node, chats, one conversation, contacts, the map, sharing a position, and the node. Every word they show is in `res/values/strings.xml`. |

The vectors' `group_ids` are not run here. A client never holds a group's secret, since no frame
carries one, so working out an id from it is the node's part.

`protocol/src/test/resources/vectors/companion.json` is a copy of the specification's
[`vectors/companion.json`](https://github.com/ternmesh/spec/blob/main/vectors/companion.json).
CI runs the tests against the copy, and against the specification's own as it is on `main`, which
also runs once a week: if the specification changes, that job fails or warns. Copy the new file
here in the pull request that changes the code to match. To run against another file locally,
set `TERN_COMPANION_VECTORS` to its path.

## Still to come

* What the specification does not define yet: telemetry.

* [CONTRIBUTING.md](CONTRIBUTING.md) — DCO sign-off, and the specification first
* [Governance](https://github.com/ternmesh/spec/blob/main/GOVERNANCE.md)

## The release key

The release key signs the app people install, and `ternmesh.org/.well-known/assetlinks.json` names
it, so that Android opens node links only in an app signed with it. It is never in this repository.
CI on `main`, and the release a tag `v*` makes (`.github/workflows/release.yml`), read it from four
repository secrets, in `.github/workflows/apk.yml`:

| Secret | |
|---|---|
| `TERN_RELEASE_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `TERN_RELEASE_STORE_PASSWORD` | the keystore's password |
| `TERN_RELEASE_KEY_ALIAS` | the key's alias in it |
| `TERN_RELEASE_KEY_PASSWORD` | the key's password |

To make one (once, kept somewhere safe: an app signed with a lost key can never be updated):

```bash
keytool -genkeypair -v -keystore tern-release.jks -alias tern -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tern-release.jks      # macOS: base64 -i tern-release.jks
keytool -list -v -keystore tern-release.jks -alias tern | grep SHA256   # what assetlinks.json names
```

A build signs with it locally when `TERN_RELEASE_KEYSTORE` (the file's path) and the three
passwords and alias above, without `_BASE64`, are in the environment; without them
`assembleRelease` leaves the APK unsigned.
