# Tern for Android

The Android app for **Tern**, a LoRa mesh protocol that treats airtime as a
shared, metered resource. It drives a Tern node over Bluetooth LE through
the [companion protocol](https://github.com/ternmesh/spec/blob/main/draft/companion.md),
in Kotlin.

What the app is, and why it is native, is in
[decisions/phone-apps.md](https://github.com/ternmesh/spec/blob/main/decisions/phone-apps.md).
It finds a node, pairs with it, and keeps a link to it in the background; it shows conversations
with contacts and groups, where each message is and what it waits for, the node's battery, airtime
and the nodes it hears, and changes its region, role, power and passkey, with the power set to a number or to the most the node
works out it may send at. It updates the node's
firmware with the release ternmesh.org publishes for its board and region: over the same link for
an ESP32 board, and through its bootloader for an nRF52840 board (Heltec T114, Seeed T1000-E), as
below. It
turns the node off when the user asks, after saying that only the node's button, reset or power
turns it on again, and then stays off it, saying the node is off, until the user connects again.
It erases a node of version 13 or later when the user asks (`WIPE`), after saying that its address,
contacts, groups and messages go for good, and then forgets the node and the phone's pairing with it.

It speaks version 20 of the companion protocol, and any earlier version a node speaks. From a node
of version 14, the routing ids it is told (of neighbours, group messages' writers and group
positions) are made from a key blinded from each address, as below; from an earlier node, from the
address itself. When pairing fails it says that a node with no screen pairs only for three minutes
after it is turned on, reset or its button pressed.

A leaf of version 15 or later can be set to sleep (`SET` 8 and 9): the radio screen asks first,
saying that messages to it then wait until it wakes, offers how often it wakes and how long it
listens, and says what it does now, from `SLEEP`: asleep and which relay keeps its messages,
waiting for one, or resting relays that did not answer. A contact that said it sleeps shows how
often it wakes, and a message sent to it says it is held until it does.

A chat can be deleted from a node of version 16 or later (`FORGET`, `FORGET_GROUP`), from its ⋮
menu or by pressing and holding it in the list. The app asks first, saying that its messages go from
the node and from every phone or computer connected to it, for good, and that any not yet
delivered will not be sent, and deletes only as far as the last message it showed. Leaving a group
and removing a contact offer to delete the chat too. When any client deletes a chat, the node tells
every client (`FORGOTTEN`, `GROUP_FORGOTTEN`), at once or at its next sync, and each removes those
messages and their notifications. The contact, its session and the group are kept.

From a node of version 17 the app manages another node over the mesh (`MANAGE`), if that node has
made the app's node one of its owners. The app keeps, beside the records, the nodes it knows its node
owns: from `OWNER_CHANGED` notices, from any answer over the mesh, and, as no longer owned, from
`ERROR` 15 (a draft: a node that says at once that the asker is not an owner, where version 17 says
nothing). A contact's menu offers "Manage" only for those; for any other it offers "Check if I'm an
owner", one `HELLO` over the mesh, and says it is not yours. While a request is on its way the app says
what it waits for, from its message's `STATE`: first contact, a route, the air, delivery or the answer;
and, for a node known to sleep, from its last sync or its contact's wake, how long that can take, and
waits that much longer; once delivered to a sleeping node's keeper, it says it is held until the
node wakes, and after 20 s with no answer from one not known to sleep, that it may be asleep. Not
knowing whether a node sleeps, the app waits ten minutes. A check, and a request already on its way
when the Manage screen is left, go on until they end, whatever happens to the screen: the check's
dialog and a banner on the Chats and People tabs come from the repository's state, the service's
notification says what is waited for, and what it came to is a notification when the user is not
looking, which opens Manage or the check. Only Stop or Cancel stops them. "Manage" shows that node's own state from a
sync over the mesh, and changes its role, power, presence card and its name, receiver, sleep (how often
it wakes and how long it listens) and Bluetooth lock, who its owners are, and restarts it. Region and passkey are changed only at the node. Each request goes one at
a time, is tried again up to three times when not delivered, and waits two minutes for its answer once
delivered, longer by a sleep and a listen for a node that sleeps; `ERROR` 15 stops it, and what waited. A change made over the mesh is on trial, and the app confirms it with a `PING` at once, or 45
seconds later for a role or a power, which the node restarts to apply; the rest restart nothing. In the app's own node, a
contact can be made an owner (`OWNER`), after saying what an owner can do. When a node says its owners
changed (`OWNER_CHANGED`), the app says who, of which node, and whether at the node or over the mesh, in
the chats and in a notification, and keeps the last 20 on disk until the user dismisses them. A node whose Bluetooth is locked is shown so; the app can lock its own
node, after saying only USB or an owner can unlock it.

From a node of version 18 the app shows how each message went (`ROUTE`), in a line under it: one
received came direct or across so many hops, at what SNR; a group message at most so many, since its
writer hides how far off it is; one sent went direct or via a neighbour, named where the app can, and
once delivered, how many hops its acknowledgement came back and the round trip. Tapping a message
shows every field in words. "Trace route", in a contact's menu and beside each neighbour, traces the
way frames go to that node and back (`TRACE`, `TRACED`): who answered, or how far it got, and each
node out and back with how well it heard the one before, saying that a trace shows how frames went
just then and that relays on the way can misreport it. A node no contact names can be given a name on
the phone, from the trace or the neighbours, and is shown by it, or by its eight hex digits. A node of
version 17 is not asked: the menu says to update its firmware, and messages have no line.

A range test, from a contact's menu or the node's dashboard, sends a contact "range #1", "range #2"
and on, every 30 to 60 s, while its screen is open and kept on, and logs each with where the phone
was, whether it was delivered and, from version 18, its hops, SNR, RSSI, round trip and first
neighbour. A ping due while the node is not connected is logged as not sent, and one the node did not
answer for is found by its text once a sync shows it went. The log is shared as CSV.

It shows who is about: the presence cards the node has heard from the nodes near it, each with
the name its sender claims beside its short code, and a contact made from one only when the user
asks. The node's own card is off until the user turns it on, and the switch says what that puts
on the air: the node's address and the name the user gives it, in clear, every couple of hours.
From a node of version 20, "Ask who's about", at the top of Nearby, has the node ask the nodes in
earshot for their cards now (`LOOK`), only when the user taps it; a node asks at most once every
ten minutes, and the app says so when it is too soon (`ERROR` 16).

From a node of version 19, a node that first contact was refused to shows the name it gave itself
(`ASKED`'s `name`) beside its short code, as its claim, and saving it offers that name. A contact the
user gave no name is shown by the name it gave itself, in quotes, with its short code (`CONTACT`'s
`says`), and naming it offers that name; the name the user gives is never replaced by it.

It shares the user's position with the contacts and groups they choose, each at the precision
they choose, and shows on a map where those sharing with them are, and where the node itself is:
from its own satellite receiver, which the user can turn off to save its battery, or from a phone.
The map opens on those positions, or with none on where the phone last was. The phone gives the
node its exact location only while the node shares with someone; the node rounds it for each
destination before anything goes on the air. The map's tiles come from [OpenFreeMap](https://openfreemap.org),
drawn by [MapLibre](https://maplibre.org): the tile server sees which areas the map shows, and
nothing else of the user's.

It hands a group on with its join code, a link and a QR code that the node writes when the user
asks, after saying that anyone who sees it can read the group. It joins one from a code scanned,
pasted or opened from a `ternmesh.org/G` link, naming the group first; the node reads the code,
and the app keeps neither the link nor the secret in it.

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
./gradlew :app:recordRoborazziDebug   # the store's screenshots, drawn from the app's screens
```

JDK 17 or later; the app also needs the Android SDK (platform 35), which Android Studio installs,
or `sdk.dir` in `local.properties`. The `protocol` module is plain Kotlin with no Android in it, so
its tests run on any JVM.

## Signed firmware

From companion version 13, every image a release carries is signed with the project's **release
key**: Ed25519 over `"TERN-FIRMWARE-V1" || SHA-256(file)`, which `latest.json` gives as each image's
`signature` (the firmware's
[docs/signing.md](https://github.com/ternmesh/firmware/blob/main/docs/signing.md)). The app checks it
against its own list of release keys before it downloads an image, and again over the bytes it
downloaded, before it sends anything:

* **Signed by a release key:** offered as any update. To a node of version 13 or later the
  signature goes in `UPDATE_BEGIN`, and the node checks it too. A node that refuses it (`ERROR` 11
  to `UPDATE_BEGIN`) is said not to trust the key it was signed with; nothing was sent.
* **Signed, but by no key the app holds,** or **not signed** (releases up to 0.6.0-alpha.1 were
  published before signing): the app says the image may not be the project's, and installs it only
  if the user then asks again. A node of version 12 or earlier checks nothing itself.
* **Not signed, for a node of version 13 or later updated over the link:** not offered. The node
  takes only signed images, so the app says the release came before signing, and to flash it over
  USB at `ternmesh.org/flash`.

An nRF52840 node's bootloader checks no signature, so the app's check is the only one its package
has, and the app says that until the update finishes anyone in Bluetooth range could give the
bootloader an image of their own.

**The release keys** are in
[`protocol/src/main/kotlin/org/ternmesh/companion/ReleaseKeys.kt`](protocol/src/main/kotlin/org/ternmesh/companion/ReleaseKeys.kt),
the same list as the firmware's `ports/node/release_keys.h`: today release key 1, added 2026-11-18
(ternmesh/firmware#120). A new key is one line there, its 64 hex digits with a comment saying when it
was added; an old one stays until no release signed with it is offered. The specification's
published test key never goes there; the tests use it on their own. Checking is BouncyCastle's
Ed25519 (`bcprov-jdk18on`), which refuses an `S` not less than the group order, as the
specification requires.

## Routing ids

A node of version 14 knows every node by a routing id made from a **routing key** `B = [h]A`
blinded from its address `A`, with `h = SHA-512("tern v0 blind" || A || u32be(0)) mod L`, and not
from `A` itself (the specification's draft/routing.md). The app works out each contact's and card's
id the way the node's version does (`RoutingIds.kt`, through `Records.routingIdOf`), keeps the
blinded ones once worked out, and matches neighbours, group messages' writers and group positions
against them. An address that is not a valid key of the curve's prime order has no blinded id, and
matches nothing. The one scalar multiplication is done with `java.math.BigInteger` and the curve's
textbook formulas, checked against every id of the specification's `routing.json` and against
BouncyCastle's own keys: neither Android nor BouncyCastle offers it on a point of one's own, and
since an address and its hash are public, it need not run in constant time.

## Updating an nRF52840 node

An nRF52840 board has room for one image of its firmware, not two, so it cannot take an update
over the link as an ESP32 board does. Its bootloader takes it instead (companion protocol version
12, "By the bootloader"). The node's `INFO` says which way it is updated, and the manifest at
`ternmesh.org/firmware/latest.json` lists, for each nRF52840 board and region, a DFU package
(`tern-<board>-<region>-<version>.zip`) marked `"update": "bootloader"`. The app:

1. downloads the package, checks its size and SHA-256 against the manifest, and its signature
   against the release keys (above);
2. sends `UPDATE_BOOTLOADER`; the node answers, and restarts into its bootloader, which drops the link;
3. scans for the bootloader's service (Nordic's legacy DFU, `00001530-1212-efde-1523-785feabcd123`),
   preferring one at the node's address or the next one up, as Adafruit's bootloader advertises;
4. gives it the package, as `tools/ble_dfu.py` in the firmware repository does: the bootloader
   erases the old firmware (tens of seconds), then takes the new in 20-byte packets, about 10 ms
   apart, waiting for a receipt every 10;
5. connects to the node again, and says it is updated only if its `INFO` gives the new release.

It takes about 5 minutes, during which the node is off the air; the phone should stay near it. If
the transfer stops part of the way, the node waits in its bootloader, advertising again, and
**Try again** finishes it; nothing the node holds is lost. A node whose `INFO` says it cannot be
updated by a client (an nRF52840 board without that bootloader) is flashed over USB, at
`ternmesh.org/flash`. A node of version 11 or earlier is updated as before: over the link if it
says a board.

## Where things are

| Path | |
|---|---|
| `protocol/src/main/kotlin/org/ternmesh/companion/Frame.kt` | Every frame of the protocol's version 20, as Kotlin types, and its numbers. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Codec.kt` | A frame built into bytes, and read back from them. |
| `protocol/src/main/kotlin/org/ternmesh/companion/ByteStream.kt` | Frames on a byte stream (USB serial, TCP), with the node's console text between them. Bluetooth does not need it. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Connection.kt` | One connection, the client's half: `HELLO` and the version both speak, one request at a time, counted news, syncing again, and the `PING` that keeps a node from taking the app for gone. No I/O and no clock of its own: a link hands it frames and calls `tick()`. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Updater.kt` | One firmware image given to a node over a connection: `UPDATE_BEGIN`, the image in chunks from wherever the node says, `UPDATE_END`, and going on after the link drops. No I/O, as with the connection. `BootloaderUpdater`: `UPDATE_BOOTLOADER`, then the package to the bootloader, as many times as it takes. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Dfu.kt` | An nRF52840 node's bootloader given a DFU package (`manifest.json`, `.dat`, `.bin`) by Nordic's legacy DFU: its steps, waits, receipts and pace, behind `DfuLink`, a control point and a packet characteristic, so that its tests play the bootloader on any JVM. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Release.kt` | The firmware manifest at `ternmesh.org/firmware/latest.json`: the image for a board, a region and the way the node is updated (its `update`, absent for over the link), and whether its release is newer than a node's, by Semantic Versioning. |
| `protocol/src/main/kotlin/org/ternmesh/companion/ReleaseKeys.kt`, `Signatures.kt` | The release keys, and an image's signature checked against them: what is offered to which node. |
| `protocol/src/main/kotlin/org/ternmesh/companion/RoutingIds.kt` | Routing ids from addresses, as a node of version 14 (blinded) or earlier makes them. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Managing.kt` | Managing a node over the mesh, the client's half: a request's and an answer's content, and `Remote`, one request at a time inside `MANAGE`, its answer from `MANAGED`, tries again, `HELLO` again after a restart, stops at `ERROR` 15, waits longer for a node that sleeps, and the `PING` that confirms a setting on trial; and `ManageStep`, what a request waits for, from its `STATE`. `OwnedNodes.kt`: the nodes the app's node owns, as learned, and their file. No I/O and no clock of its own, as `Connection`. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Records.kt` | What the node has said it holds, as news leaves it, positions received, sharing, the cards of who is about, the node's own fix and how each message went (`ROUTE`) included, and the `after` the next sync asks from. |
| `protocol/src/main/kotlin/org/ternmesh/companion/RecordsFile.kt` | The records on disk, each as the frame that carried it, so the next run syncs only what is new; not positions, sharing, cards or the node's fix, which every sync sends whole. The Apple app keeps the same format. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Sharing.kt`, `JoinCode.kt` | An address's text, link and short code (draft/sharing.md); and a group's join code read, to name the group before the user joins it (draft/groups.md). |
| `protocol/src/main/kotlin/org/ternmesh/companion/Conversations.kt` | The records as conversations, and how far a `READ` may reach without marking another conversation's messages read. |
| `protocol/src/test/` | The conformance section of the specification, as a client: the codec against every vector, the connection as the client in `exchange`, `older`, `unknown_to_older` and `bootloader`, the updater as the client in `update`, every case of `signatures`, and every id and no-id of `routing.json`; and the DFU client against a bootloader played by hand. |
| `app/src/main/kotlin/org/ternmesh/app/link/` | Bluetooth LE: scanning for the node's service, and the GATT link (an MTU of at least 183, passkey pairing, one frame per write and per notification); and `BootloaderLink.kt`, finding an nRF52840 node's bootloader and the link the DFU client writes over. |
| `app/src/main/kotlin/org/ternmesh/app/node/` | The node the app drives: the link, the connection over it and the records on disk (`NodeRepository`), the foreground service that keeps it while the app is closed, message notifications, downloading firmware and checking it before it is sent (`Firmware.kt`), the phone's location given to the node while it shares (`LocationFeed.kt`), and a range test's pings and their log, with the phone's own fixes (`RangeTest.kt`, `PhoneFixes.kt`). |
| `app/src/main/kotlin/org/ternmesh/app/ui/` | The screens, in Jetpack Compose: choosing a node, then four tabs: chats (and one conversation), people (contacts, and who is near), the map (and sharing a position), and the node, a dashboard with a screen for each thing about it; and a trace and the range test (`RouteScreens.kt`, worded in `RouteWords.kt`). `Theme`, `TernIcons` and `Components` are what they share. Every word they show is in `res/values/strings.xml`. |

The vectors' `group_ids` are run against the join code reader, which works out the id of the
group a code is for, so that the app can say whether the node holds it already.

`protocol/src/test/resources/vectors/` holds copies of the specification's
[`companion.json`](https://github.com/ternmesh/spec/blob/main/vectors/companion.json),
[`sharing.json`](https://github.com/ternmesh/spec/blob/main/vectors/sharing.json),
[`groups.json`](https://github.com/ternmesh/spec/blob/main/vectors/groups.json), of which only
the join codes are run here, and [`routing.json`](https://github.com/ternmesh/spec/blob/main/vectors/routing.json),
of which only the routing ids are. CI runs the tests against the copies, and against the
specification's own as they are on `main`, which also runs once a week: if the specification
changes, that job fails or warns. Copy the new file here in the pull request that changes the code
to match. To run against other files locally, set `TERN_COMPANION_VECTORS`,
`TERN_SHARING_VECTORS`, `TERN_GROUPS_VECTORS` or `TERN_ROUTING_VECTORS` to their paths.

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

## Versions

A release is a tag `v` MAJOR.MINOR.PATCH, and its APK's version is the tag's: the Release
workflow passes the tag as `TERN_VERSION`, and `app/build.gradle.kts` makes `v1.2.3` versionName
`1.2.3` and versionCode `10203` (major × 10000 + minor × 100 + patch), so each release's code is
greater than the last. A tag that is not that shape, or has a minor or patch over 99, fails the
build. Any other build, on a laptop or on `main`, is `0.1.0`, code 1, unless given
`-PternVersion=1.2.3`.

The store listing is in `fastlane/metadata/android/en-US`. A release's notes for F-Droid go
beside it in `changelogs/` under its versionCode, `changelogs/10203.txt` for `v1.2.3`; F-Droid,
building from the tag, gives the same version with `gradleprops: [ternVersion=1.2.3]`.

Its phone screenshots, in `images/phoneScreenshots/`, are drawn from the app's own screens rather
than taken on a phone: `app/src/test/.../screenshots/StoreScreenshots.kt` gives the app a made-up
node, taps through it as a person would, and Robolectric and Roborazzi draw each screen to a PNG,
1080 x 2160. After a change to a screen they show, run `./gradlew :app:recordRoborazziDebug` and
commit the PNGs. CI runs the same test without writing anything (`:app:testDebugUnitTest`), so a
screen that no longer draws fails the build. The map is not among them: MapLibre needs OpenGL,
which Robolectric has not got.
