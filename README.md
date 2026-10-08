# Tern for Android

The Android app for **Tern**, a LoRa mesh protocol that treats airtime as a
shared, metered resource. It drives a Tern node over Bluetooth LE through
the [companion protocol](https://github.com/ternmesh/spec/blob/main/draft/companion.md),
in Kotlin.

What the app is, and why it is native, is in
[decisions/phone-apps.md](https://github.com/ternmesh/spec/blob/main/decisions/phone-apps.md).
So far there is its protocol, and no app around it.

```bash
./gradlew test    # the companion protocol against the specification's vectors
```

JDK 17 or later. The `protocol` module is plain Kotlin with no Android in it, so its tests run on
any JVM, and the app module (to come) depends on it.

## Where things are

| Path | |
|---|---|
| `protocol/src/main/kotlin/org/ternmesh/companion/Frame.kt` | Every frame of the protocol's version 0, as Kotlin types, and its numbers. |
| `protocol/src/main/kotlin/org/ternmesh/companion/Codec.kt` | A frame built into bytes, and read back from them. |
| `protocol/src/main/kotlin/org/ternmesh/companion/ByteStream.kt` | Frames on a byte stream (USB serial, TCP), with the node's console text between them. Bluetooth does not need it. |
| `protocol/src/test/` | The conformance section of the specification, as a client. |

`protocol/src/test/resources/vectors/companion.json` is a copy of the specification's
[`vectors/companion.json`](https://github.com/ternmesh/spec/blob/main/vectors/companion.json).
CI runs the tests against the copy, and against the specification's own as it is on `main`, which
also runs once a week: if the specification changes, that job fails or warns. Copy the new file
here in the pull request that changes the code to match. To run against another file locally,
set `TERN_COMPANION_VECTORS` to its path.

## Still to come

* The connection: one request at a time, counted news, syncing again, and the ping that keeps a
  node from taking the app for gone.
* Bluetooth LE: the service, an MTU of at least 183, and passkey pairing.
* The app itself, with its module beside `protocol`.

* [CONTRIBUTING.md](CONTRIBUTING.md) — DCO sign-off, and the specification first
* [Governance](https://github.com/ternmesh/spec/blob/main/GOVERNANCE.md)
