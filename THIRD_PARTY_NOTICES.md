# Third-party notices

## java-airplay-lib (vendored)

The sources under `airplay/src/main/java/com/github/serezhka/jap2lib` and the
matching tests under `airplay/src/test/java/com/github/serezhka/jap2lib` are
vendored from [serezhka/java-airplay-lib](https://github.com/serezhka/java-airplay-lib),
Copyright (c) 2020 Sergei Fedorov, licensed under the MIT License
(see `airplay/LICENSE-java-airplay-lib.txt`). They implement the AirPlay
pairing, FairPlay handshake, and stream decryption.

The Kotlin network layer in `airplay/src/main/kotlin` is original code for
VibeView, informed by the protocol behavior of
[serezhka/java-airplay-server](https://github.com/serezhka/java-airplay-server)
(MIT License, same author).

## Library dependencies

- [Netty](https://netty.io) — Apache License 2.0
- [JmDNS](https://github.com/jmdns/jmdns) — Apache License 2.0
- [dd-plist](https://github.com/3breadt/dd-plist) — MIT License
- [EdDSA-Java](https://github.com/str4d/ed25519-java) — CC0 1.0
- [curve25519-java](https://github.com/signalapp/curve25519-java) — GPLv3-exempt Signal license (AGPL-free); pure-Java provider
- [SLF4J](https://www.slf4j.org) — MIT License
- AndroidX / Media3 — Apache License 2.0
