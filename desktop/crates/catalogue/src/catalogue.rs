//! The signed catalogue (desktop/catalogue/q25.json) and the choice of a LineageOS build.

use crate::{Error, Result};
use ed25519_dalek::{Signature, VerifyingKey, pkcs8::DecodePublicKey};
use serde::Deserialize;
use std::collections::BTreeMap;

const BUNDLED_JSON: &[u8] = include_bytes!("../../../catalogue/q25.json");
const BUNDLED_SIG: &[u8] = include_bytes!("../../../catalogue/q25.json.sig");
const CATALOGUE_PUB: &str = include_str!("../../../catalogue/bold7-catalogue.pub");
const RELEASE_PUB: &str = include_str!("../../../../system/module/release/bold7-release.pub");

/// The LineageOS files an installation needs, all from the same build: the zip and the four
/// images written before the sideload.
const LINEAGE_IMAGES: [&str; 4] = ["boot.img", "dtbo.img", "vbmeta.img", "vendor_boot.img"];

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Catalogue {
    /// Version of this format; only 1 exists.
    pub format: u32,
    /// Grows with each published catalogue: an older one is refused (no rollback).
    pub serial: u64,
    pub device: String,
    pub lineage: Lineage,
    pub components: Vec<Component>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Lineage {
    /// The official API, the only place the builds come from.
    pub api: String,
    /// Only builds of this version (the API field `version`, e.g. "24.0").
    pub version: String,
    /// Every file URL the API gives must start with this.
    pub files_prefix: String,
    /// false: only the builds of `tested`. true: also any newer build from `min_date` on.
    pub accept_untested: bool,
    /// "YYYY-MM-DD"; older builds are never taken.
    pub min_date: String,
    /// Dates of builds known to be broken, never taken.
    pub blocked: Vec<String>,
    pub tested: Vec<TestedBuild>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TestedBuild {
    pub date: String,
    /// File name -> sha256, for the zip and the four images; the API must agree.
    pub sha256: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Component {
    /// mindthegapps, kernelsu-next, neozygisk, teesimulator-rs, playintegrityfix-inject,
    /// ta-enhanced, bold7.
    pub id: String,
    pub version: String,
    pub files: Vec<File>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct File {
    pub name: String,
    pub url: String,
    pub size: u64,
    pub sha256: String,
    /// Hex Ed25519 signature of the file by keys/bold7-release.pem (Bold7's zip only),
    /// checked with system/module/release/bold7-release.pub.
    #[serde(default)]
    pub ed25519: Option<String>,
}

/// One LineageOS build, its zip and images, chosen by [`resolve_lineage`].
#[derive(Debug, Clone, PartialEq)]
pub struct LineageBuild {
    pub date: String,
    pub version: String,
    pub tested: bool,
    pub zip: File,
    pub boot: File,
    pub dtbo: File,
    pub vbmeta: File,
    pub vendor_boot: File,
}

/// The public key that signs catalogues (its private half: keys/bold7-catalogue.pem).
pub fn catalogue_key() -> VerifyingKey {
    VerifyingKey::from_public_key_pem(CATALOGUE_PUB).expect("bundled catalogue key")
}

/// The public key that signs Bold7's releases (tools/release.sh).
pub fn bold7_release_key() -> VerifyingKey {
    VerifyingKey::from_public_key_pem(RELEASE_PUB).expect("bundled release key")
}

impl Catalogue {
    /// The catalogue shipped with this build of the app, checked like any other.
    pub fn bundled() -> Result<Catalogue> {
        Catalogue::verify(BUNDLED_JSON, BUNDLED_SIG, &catalogue_key(), 0)
    }

    /// Checks `sig` (64 raw bytes, `openssl pkeyutl -sign -rawin`) over the exact bytes of
    /// `json` before parsing anything, then the content, then that `serial` is at least
    /// `min_serial` (the serial of the newest catalogue already trusted).
    pub fn verify(json: &[u8], sig: &[u8], key: &VerifyingKey, min_serial: u64) -> Result<Catalogue> {
        let sig = Signature::try_from(sig).map_err(|_| Error::BadSignature)?;
        key.verify_strict(json, &sig).map_err(|_| Error::BadSignature)?;
        let c: Catalogue = serde_json::from_slice(json).map_err(|e| invalid(format!("catalogue: {e}")))?;
        c.check()?;
        if c.serial < min_serial {
            return Err(invalid(format!(
                "catalogue serial {} is older than {min_serial}",
                c.serial
            )));
        }
        Ok(c)
    }

    pub fn component(&self, id: &str) -> Option<&Component> {
        self.components.iter().find(|c| c.id == id)
    }

    fn check(&self) -> Result<()> {
        if self.format != 1 {
            return Err(invalid(format!("unknown catalogue format {}", self.format)));
        }
        let l = &self.lineage;
        https(&l.api)?;
        https(&l.files_prefix)?;
        if !l.files_prefix.ends_with('/') {
            return Err(invalid("files_prefix must end with /".into()));
        }
        for d in l
            .blocked
            .iter()
            .chain([&l.min_date])
            .chain(l.tested.iter().map(|t| &t.date))
        {
            date(d)?;
        }
        for t in &l.tested {
            let zips = t.sha256.keys().filter(|n| is_lineage_zip(n)).count();
            let images = LINEAGE_IMAGES.iter().filter(|n| t.sha256.contains_key(**n)).count();
            if zips != 1 || images != LINEAGE_IMAGES.len() || t.sha256.len() != 1 + LINEAGE_IMAGES.len() {
                return Err(invalid(format!(
                    "tested build {}: needs one zip and the four images",
                    t.date
                )));
            }
            t.sha256.values().try_for_each(|h| hex(h, 64))?;
        }
        let mut ids = std::collections::HashSet::new();
        for c in &self.components {
            if !ids.insert(&c.id) || c.files.is_empty() {
                return Err(invalid(format!("component {}: duplicate or without files", c.id)));
            }
            c.files.iter().try_for_each(File::check)?;
        }
        Ok(())
    }
}

impl File {
    fn check(&self) -> Result<()> {
        https(&self.url)?;
        hex(&self.sha256, 64)?;
        if let Some(s) = &self.ed25519 {
            hex(s, 128)?;
        }
        if self.name.is_empty() || self.name.contains(['/', '\\']) || self.name.starts_with('.') || self.size == 0 {
            return Err(invalid(format!("file {:?}: bad name or size", self.name)));
        }
        Ok(())
    }
}

/// The API's view of a build (fields we do not read are ignored).
#[derive(Deserialize)]
struct ApiBuild {
    date: String,
    version: String,
    files: Vec<ApiFile>,
}

#[derive(Deserialize)]
struct ApiFile {
    filename: String,
    sha256: String,
    size: u64,
    url: String,
}

/// Picks the newest build of the API answer `api_json` that the catalogue allows: right
/// version, not blocked, tested (or, if `accept_untested`, not older than `min_date`), with the
/// zip and the four images all under `files_prefix`. A tested build whose hashes differ from
/// the API's stops everything: one of the two is not what was tested.
pub fn resolve_lineage(c: &Catalogue, api_json: &[u8]) -> Result<LineageBuild> {
    let l = &c.lineage;
    let builds: Vec<ApiBuild> = serde_json::from_slice(api_json).map_err(|e| invalid(format!("LineageOS API: {e}")))?;
    let mut best: Option<LineageBuild> = None;
    for b in builds {
        if b.version != l.version || l.blocked.contains(&b.date) {
            continue;
        }
        let tested = l.tested.iter().find(|t| t.date == b.date);
        if tested.is_none() && !(l.accept_untested && b.date >= l.min_date) {
            continue;
        }
        let pick = |name: &str| -> Result<File> {
            let found: Vec<&ApiFile> = b.files.iter().filter(|f| f.filename == name).collect();
            let [f] = found.as_slice() else {
                return Err(invalid(format!("LineageOS {}: {name} missing or listed twice", b.date)));
            };
            let file = File {
                name: f.filename.clone(),
                url: f.url.clone(),
                size: f.size,
                sha256: f.sha256.to_ascii_lowercase(),
                ed25519: None,
            };
            file.check()?;
            if !file.url.starts_with(&l.files_prefix) {
                return Err(invalid(format!(
                    "LineageOS {}: {} is not under {}",
                    b.date, file.url, l.files_prefix
                )));
            }
            if let Some(t) = tested
                && t.sha256.get(name) != Some(&file.sha256)
            {
                return Err(Error::Mismatch(format!(
                    "LineageOS {}: the API's sha256 of {name} is not the tested one",
                    b.date
                )));
            }
            Ok(file)
        };
        let zips: Vec<&str> = b
            .files
            .iter()
            .map(|f| f.filename.as_str())
            .filter(|n| is_lineage_zip(n))
            .collect();
        let [zip] = zips.as_slice() else {
            return Err(invalid(format!(
                "LineageOS {}: expected one signed zip, found {}",
                b.date,
                zips.len()
            )));
        };
        let build = LineageBuild {
            zip: pick(zip)?,
            boot: pick("boot.img")?,
            dtbo: pick("dtbo.img")?,
            vbmeta: pick("vbmeta.img")?,
            vendor_boot: pick("vendor_boot.img")?,
            tested: tested.is_some(),
            date: b.date,
            version: b.version,
        };
        if best.as_ref().is_none_or(|x| build.date > x.date) {
            best = Some(build);
        }
    }
    best.ok_or_else(|| invalid(format!("no LineageOS {} build that the catalogue allows", l.version)))
}

fn is_lineage_zip(name: &str) -> bool {
    name.starts_with("lineage-") && name.ends_with("-signed.zip")
}

fn invalid(s: String) -> Error {
    Error::Invalid(s)
}

fn https(url: &str) -> Result<()> {
    if url.starts_with("https://") && url.len() > "https://".len() {
        Ok(())
    } else {
        Err(invalid(format!("not an https URL: {url}")))
    }
}

fn hex(s: &str, len: usize) -> Result<()> {
    if s.len() == len && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        Ok(())
    } else {
        Err(invalid(format!("expected {len} lowercase hex digits: {s:?}")))
    }
}

fn date(s: &str) -> Result<()> {
    let b = s.as_bytes();
    let ok = b.len() == 10
        && b.iter().enumerate().all(|(i, c)| {
            if i == 4 || i == 7 {
                *c == b'-'
            } else {
                c.is_ascii_digit()
            }
        });
    if ok {
        Ok(())
    } else {
        Err(invalid(format!("not a YYYY-MM-DD date: {s:?}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    pub(crate) fn test_key() -> SigningKey {
        SigningKey::from_bytes(&[7; 32])
    }

    fn sign(json: &[u8]) -> Vec<u8> {
        test_key().sign(json).to_bytes().to_vec()
    }

    #[test]
    fn bundled_catalogue_verifies() {
        let c = Catalogue::bundled().expect("re-sign desktop/catalogue/q25.json (desktop/README.md)");
        assert_eq!(c.device, "Q25");
        for id in [
            "mindthegapps",
            "kernelsu-next",
            "neozygisk",
            "teesimulator-rs",
            "playintegrityfix-inject",
            "ta-enhanced",
        ] {
            assert!(c.component(id).is_some(), "{id}");
        }
    }

    #[test]
    fn release_key_is_bold7s() {
        let k = bold7_release_key();
        assert_eq!(k.as_bytes()[..4], [0x6f, 0x6b, 0x18, 0x3e]);
    }

    #[test]
    fn badly_signed_catalogue_refused() {
        let json = BUNDLED_JSON;
        let key = test_key().verifying_key();
        assert!(Catalogue::verify(json, &sign(json), &key, 0).is_ok());

        // one byte changed after signing
        let mut tampered = json.to_vec();
        let i = tampered.iter().position(|&b| b == b'3').unwrap();
        tampered[i] = b'4';
        assert!(matches!(
            Catalogue::verify(&tampered, &sign(json), &key, 0),
            Err(Error::BadSignature)
        ));
        // signed by another key
        assert!(matches!(
            Catalogue::verify(json, &sign(json), &catalogue_key(), 0),
            Err(Error::BadSignature)
        ));
        // not a signature at all
        assert!(matches!(
            Catalogue::verify(json, b"", &key, 0),
            Err(Error::BadSignature)
        ));
        // the bundled signature over another catalogue
        assert!(matches!(
            Catalogue::verify(&tampered, BUNDLED_SIG, &catalogue_key(), 0),
            Err(Error::BadSignature)
        ));
    }

    #[test]
    fn older_catalogue_refused() {
        let key = test_key().verifying_key();
        assert!(matches!(
            Catalogue::verify(BUNDLED_JSON, &sign(BUNDLED_JSON), &key, 3),
            Err(Error::Invalid(_))
        ));
    }

    #[test]
    fn signed_but_unsafe_catalogue_refused() {
        let key = test_key().verifying_key();
        let base = std::str::from_utf8(BUNDLED_JSON).unwrap();
        for (from, to) in [
            ("https://github.com/JingMatrix", "http://github.com/JingMatrix"),
            ("\"name\": \"NeoZygisk-v2.4-289-release.zip\"", "\"name\": \"../evil\""),
            (
                "93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1",
                "93A1",
            ),
            ("\"format\": 1", "\"format\": 2"),
            ("\"serial\": 2,", "\"serial\": 2, \"latest\": true,"),
            ("\"dtbo.img\": ", "\"other.img\": "),
        ] {
            assert!(base.contains(from), "{from}");
            let json = base.replacen(from, to, 1);
            let r = Catalogue::verify(json.as_bytes(), &sign(json.as_bytes()), &key, 0);
            assert!(matches!(r, Err(Error::Invalid(_))), "{to}: {r:?}");
        }
    }

    // A LineageOS API answer: builds of 2026-11-15 (tested), 2026-11-22 and 2026-10-04.
    fn api(date_hash: &[(&str, &str)]) -> Vec<u8> {
        let builds: Vec<String> = date_hash
            .iter()
            .map(|(date, h)| {
                let d = date.replace('-', "");
                let names = [format!("lineage-24.0-{d}-nightly-Q25-signed.zip"), "boot.img".into(), "dtbo.img".into(),
                    "vbmeta.img".into(), "vendor_boot.img".into(), "lk.img".into()];
                let files: Vec<String> = names
                    .iter()
                    .map(|n| {
                        let sha = Catalogue::bundled().unwrap().lineage.tested[0].sha256.get(&n.replace(&d, "20261115")).cloned();
                        let sha = if *h == "tested" { sha.unwrap_or_else(|| "0".repeat(64)) } else { h.repeat(64) };
                        format!(r#"{{"filename":"{n}","sha256":"{sha}","size":1,"url":"https://mirrorbits.lineageos.org/full/Q25/{d}/{n}"}}"#)
                    })
                    .collect();
                format!(r#"{{"date":"{date}","datetime":1,"type":"nightly","version":"24.0","files":[{}]}}"#, files.join(","))
            })
            .collect();
        format!("[{}]", builds.join(",")).into_bytes()
    }

    #[test]
    fn lineage_takes_the_latest_build() {
        let c = Catalogue::bundled().unwrap();
        let b = resolve_lineage(
            &c,
            &api(&[("2026-11-22", "a"), ("2026-11-15", "tested"), ("2026-10-04", "b")]),
        )
        .unwrap();
        assert_eq!((b.date.as_str(), b.tested), ("2026-11-22", false));
        assert_eq!(b.zip.name, "lineage-24.0-20261122-nightly-Q25-signed.zip");
        let t = resolve_lineage(&c, &api(&[("2026-11-15", "tested"), ("2026-10-04", "b")])).unwrap();
        assert_eq!((t.date.as_str(), t.tested), ("2026-11-15", true));
        assert_eq!(t.boot.sha256, c.lineage.tested[0].sha256["boot.img"]);
    }

    #[test]
    fn lineage_untested_when_allowed_and_not_blocked() {
        let mut c = Catalogue::bundled().unwrap();
        c.lineage.accept_untested = true;
        let answer = api(&[("2026-11-22", "a"), ("2026-11-15", "tested"), ("2026-10-04", "b")]);
        let b = resolve_lineage(&c, &answer).unwrap();
        assert_eq!(
            (b.date.as_str(), b.tested, b.vbmeta.sha256.as_str()),
            ("2026-11-22", false, "a".repeat(64).as_str())
        );
        c.lineage.blocked.push("2026-11-22".into());
        assert_eq!(resolve_lineage(&c, &answer).unwrap().date, "2026-11-15");
        c.lineage.min_date = "2026-10-01".into();
        c.lineage.blocked.push("2026-11-15".into());
        assert_eq!(resolve_lineage(&c, &answer).unwrap().date, "2026-10-04");
    }

    #[test]
    fn lineage_api_disagreeing_with_tested_hashes_refused() {
        let c = Catalogue::bundled().unwrap();
        let r = resolve_lineage(&c, &api(&[("2026-11-15", "c")]));
        assert!(matches!(r, Err(Error::Mismatch(_))), "{r:?}");
    }

    #[test]
    fn lineage_files_outside_prefix_or_missing_refused() {
        let mut c = Catalogue::bundled().unwrap();
        c.lineage.accept_untested = true;
        let ok = String::from_utf8(api(&[("2026-11-22", "a")])).unwrap();
        let moved = ok.replacen(
            "https://mirrorbits.lineageos.org/full/Q25/20261122/boot.img",
            "https://example.org/boot.img",
            1,
        );
        assert!(resolve_lineage(&c, moved.as_bytes()).is_err());
        let missing = ok.replacen("\"filename\":\"vendor_boot.img\"", "\"filename\":\"other.img\"", 1);
        assert!(resolve_lineage(&c, missing.as_bytes()).is_err());
        assert!(resolve_lineage(&c, ok.as_bytes()).is_ok());
    }
}
