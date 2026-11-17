# Bold7 Setup (desktop)

The desktop app that moves a Zinwa Q25 to LineageOS, root and Bold7. Only the core library exists so far; the Tauri app comes later.

- `crates/catalogue`: the signed catalogue of an installation and its downloads.
- `catalogue/q25.json`: the catalogue itself, `q25.json.sig` its Ed25519 signature,
  `bold7-catalogue.pub` the key that checks it (built into the binary).

## Build and test

Rust 1.88 or later. On Linux, the OpenSSL headers (`libssl-dev` or `openssl-devel`).

```sh
cd desktop
cargo test                      # offline: a local HTTP server stands in for the network
cargo test -- --ignored         # also the real network: LineageOS API, GitHub digests, one download
```

## The catalogue

JSON, `deny_unknown_fields`, format 1:

- `serial`: grows with each published catalogue; a catalogue older than the one already trusted is refused.
- `lineage`: the official API URL, the version (`24.0`), the URL prefix every file must have, the
  builds tested together with the rest (date and sha256 of the zip, `boot.img`, `dtbo.img`,
  `vbmeta.img`, `vendor_boot.img`), the blocked dates, and `accept_untested` (false: only tested
  builds). The API must agree with the tested hashes.
- `components`: `id`, `version`, and for each file its official URL (https), size and sha256
  (GitHub's asset digest); Bold7's zip also carries `ed25519`, its hex signature by the release key
  (`system/module/release/bold7-release.pub`).

After any change, bump `serial`, then sign the exact bytes (OpenSSL 3 or later, key and passphrase
outside the repository):

```sh
P=$(sed -n 's/^bold7\.catalogue\.password=//p' local.properties) \
  openssl pkeyutl -sign -rawin -inkey keys/bold7-catalogue.pem -passin env:P \
  -in desktop/catalogue/q25.json -out desktop/catalogue/q25.json.sig
```

`cargo test` fails while the signature does not match, and `cargo test -- --ignored` checks every
hash against the LineageOS API, GitHub and MindTheGapps' `.sha256sum`.

## Downloads

`Downloader::fetch` only fetches a `File` from a verified catalogue (or from the LineageOS build it
resolved), over HTTPS with the system's TLS, with the same `User-Agent` for everyone
(`bold7-setup/<version>`). The cache keeps `<sha256>` and `<sha256>.part`; a cut transfer resumes
with `Range` on the next call; a file is handed out only after its size, sha256 and signature are
checked, and deleted otherwise.
