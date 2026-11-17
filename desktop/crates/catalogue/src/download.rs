//! Downloads of the files a catalogue names: resumed, checked, cached.

use crate::catalogue::{Catalogue, File, LineageBuild, bold7_release_key, resolve_lineage};
use crate::{Error, Result};
use ed25519_dalek::{Signature, VerifyingKey};
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;
use ureq::Agent;
use ureq::tls::{RootCerts, TlsConfig, TlsProvider};

/// The same for every user: the app and its version, nothing about the person or the machine.
pub const USER_AGENT: &str = concat!("bold7-setup/", env!("CARGO_PKG_VERSION"));

pub struct Downloader {
    agent: Agent,
    cache: PathBuf,
    release_key: VerifyingKey,
}

impl Downloader {
    /// HTTPS only (redirects included), the system's TLS and root certificates, no cookies.
    /// `cache` holds finished files as `<sha256>` and unfinished ones as `<sha256>.part`.
    pub fn new(cache: impl Into<PathBuf>) -> Result<Downloader> {
        Downloader::with(cache.into(), true)
    }

    fn with(cache: PathBuf, https_only: bool) -> Result<Downloader> {
        fs::create_dir_all(&cache)?;
        let tls = TlsConfig::builder()
            .provider(TlsProvider::NativeTls)
            .root_certs(RootCerts::PlatformVerifier)
            .build();
        let agent = Agent::config_builder()
            .tls_config(tls)
            .https_only(https_only)
            .user_agent(USER_AGENT)
            .timeout_connect(Some(Duration::from_secs(30)))
            // A stalled transfer fails instead of hanging; the next call resumes it.
            .timeout_per_read(Some(Duration::from_secs(60)))
            .build()
            .into();
        Ok(Downloader {
            agent,
            cache,
            release_key: bold7_release_key(),
        })
    }

    /// Asks the LineageOS API that the catalogue names, and picks the build it allows.
    pub fn lineage_build(&self, c: &Catalogue) -> Result<LineageBuild> {
        let answer = self
            .agent
            .get(&c.lineage.api)
            .call()?
            .body_mut()
            .with_config()
            .limit(1 << 20)
            .read_to_vec()?;
        resolve_lineage(c, &answer)
    }

    /// The path of `file` in the cache, downloaded or resumed if needed, and checked before it
    /// is handed out: size, sha256, and Ed25519 signature when the catalogue gives one. A file
    /// that fails a check is deleted. `progress(done, total)` follows the bytes on disk.
    /// After a network error the partial file stays: calling again resumes it.
    pub fn fetch(&self, file: &File, progress: &mut dyn FnMut(u64, u64)) -> Result<PathBuf> {
        let done = self.cache.join(&file.sha256);
        if done.exists() {
            match self.check(&done, file) {
                Ok(()) => {
                    progress(file.size, file.size);
                    return Ok(done);
                }
                Err(_) => fs::remove_file(&done)?,
            }
        }
        let part = self.cache.join(format!("{}.part", file.sha256));
        let mut have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
        if have > file.size {
            fs::remove_file(&part)?;
            have = 0;
        }
        if have < file.size {
            self.transfer(file, &part, have, progress)?;
        }
        if let Err(e) = self.check(&part, file) {
            fs::remove_file(&part)?;
            return Err(e);
        }
        fs::rename(&part, &done)?;
        Ok(done)
    }

    fn transfer(&self, file: &File, part: &Path, mut have: u64, progress: &mut dyn FnMut(u64, u64)) -> Result<()> {
        let mut req = self.agent.get(&file.url);
        if have > 0 {
            req = req.header("Range", format!("bytes={have}-"));
        }
        let resp = req.call()?;
        let resumed = resp.status() == 206;
        if resumed {
            let range = resp
                .headers()
                .get("content-range")
                .and_then(|v| v.to_str().ok())
                .unwrap_or("");
            if have == 0 || !range.starts_with(&format!("bytes {have}-")) {
                fs::remove_file(part).ok();
                return Err(Error::Net(format!("{}: unexpected range {range:?}", file.name)));
            }
        } else {
            have = 0; // the server sends the whole file again
        }
        let mut out = OpenOptions::new()
            .create(true)
            .write(true)
            .append(resumed)
            .truncate(!resumed)
            .open(part)?;
        let mut body = resp.into_body().into_reader();
        let mut buf = vec![0; 1 << 16];
        progress(have, file.size);
        loop {
            let n = match body.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) => {
                    out.flush()?;
                    return Err(Error::Net(format!("{}: {e}", file.name)));
                }
            };
            have += n as u64;
            if have > file.size {
                drop(out);
                fs::remove_file(part)?;
                return Err(Error::Mismatch(format!(
                    "{}: larger than {} bytes",
                    file.name, file.size
                )));
            }
            out.write_all(&buf[..n])?;
            progress(have, file.size);
        }
        out.sync_all()?;
        Ok(())
    }

    fn check(&self, path: &Path, file: &File) -> Result<()> {
        let size = fs::metadata(path)?.len();
        if size != file.size {
            return Err(Error::Mismatch(format!(
                "{}: {size} bytes, expected {}",
                file.name, file.size
            )));
        }
        let sha = sha256_file(path)?;
        if sha != file.sha256 {
            return Err(Error::Mismatch(format!(
                "{}: sha256 {sha}, expected {}",
                file.name, file.sha256
            )));
        }
        if let Some(sig) = &file.ed25519 {
            // Simplification: pure Ed25519 needs the whole message; only Bold7's zip (tens of MB) is signed.
            let sig = Signature::try_from(&unhex(sig)[..]).map_err(|_| Error::BadSignature)?;
            self.release_key
                .verify_strict(&fs::read(path)?, &sig)
                .map_err(|_| Error::Mismatch(format!("{}: Ed25519 signature not valid", file.name)))?;
        }
        Ok(())
    }
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut f = fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0; 1 << 20];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len() / 2)
        .filter_map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use std::io::BufRead;
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};

    /// One request as the test server saw it.
    #[derive(Debug, Clone)]
    struct Seen {
        path: String,
        range: Option<String>,
        agent: Option<String>,
    }

    /// A local HTTP server for one file at `/f`: honours `Range`, and, if `cut` is set, sends
    /// the first answer's headers for the whole file but closes after `cut` body bytes.
    fn serve(body: Vec<u8>, cut: Option<usize>, ranges: bool) -> (String, Arc<Mutex<Vec<Seen>>>) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/f", l.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        std::thread::spawn(move || {
            let mut cut = cut;
            for s in l.incoming() {
                let mut s = s.unwrap();
                let mut r = std::io::BufReader::new(s.try_clone().unwrap());
                let mut line = String::new();
                r.read_line(&mut line).unwrap();
                let mut seen = Seen {
                    path: line.split(' ').nth(1).unwrap_or("").into(),
                    range: None,
                    agent: None,
                };
                loop {
                    let mut h = String::new();
                    r.read_line(&mut h).unwrap();
                    let h = h.trim_end();
                    if h.is_empty() {
                        break;
                    }
                    let (k, v) = h.split_once(": ").unwrap();
                    match k.to_ascii_lowercase().as_str() {
                        "range" => seen.range = Some(v.into()),
                        "user-agent" => seen.agent = Some(v.into()),
                        _ => {}
                    }
                }
                let start: usize = seen
                    .range
                    .as_deref()
                    .filter(|_| ranges)
                    .and_then(|v| v.strip_prefix("bytes=")?.strip_suffix('-')?.parse().ok())
                    .unwrap_or(0);
                log.lock().unwrap().push(seen);
                let rest = &body[start..];
                let head = if start > 0 {
                    format!(
                        "HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\nContent-Range: bytes {start}-{}/{}\r\nConnection: close\r\n\r\n",
                        rest.len(),
                        body.len() - 1,
                        body.len()
                    )
                } else {
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        rest.len()
                    )
                };
                s.write_all(head.as_bytes()).unwrap();
                let n = cut.take().unwrap_or(rest.len());
                s.write_all(&rest[..n]).ok();
            }
        });
        (url, seen)
    }

    fn body() -> Vec<u8> {
        (0..300_000u32).map(|i| (i * 7 % 251) as u8).collect()
    }

    fn sha(b: &[u8]) -> String {
        Sha256::digest(b).iter().map(|b| format!("{b:02x}")).collect()
    }

    fn file(url: &str, b: &[u8]) -> File {
        File {
            name: "f.zip".into(),
            url: url.into(),
            size: b.len() as u64,
            sha256: sha(b),
            ed25519: None,
        }
    }

    fn downloader() -> (Downloader, PathBuf) {
        let dir = std::env::temp_dir().join(format!(
            "bold7-d2-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        fs::remove_dir_all(&dir).ok();
        (Downloader::with(dir.clone(), false).unwrap(), dir)
    }

    fn entries(dir: &Path) -> Vec<String> {
        let mut v: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        v.sort();
        v
    }

    #[test]
    fn downloads_checks_and_caches() {
        let b = body();
        let (url, seen) = serve(b.clone(), None, true);
        let (d, dir) = downloader();
        let f = file(&url, &b);
        let mut last = (0, 0);
        let p = d.fetch(&f, &mut |a, t| last = (a, t)).unwrap();
        assert_eq!(fs::read(&p).unwrap(), b);
        assert_eq!(last, (b.len() as u64, b.len() as u64));
        // second time: from the cache, rechecked, no request
        d.fetch(&f, &mut |_, _| {}).unwrap();
        let seen = seen.lock().unwrap().clone();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].path, "/f");
        assert_eq!(seen[0].agent.as_deref(), Some(USER_AGENT));
        assert_eq!(entries(&dir), std::slice::from_ref(&f.sha256));
    }

    #[test]
    fn wrong_hash_refused_and_deleted() {
        let b = body();
        let (url, _) = serve(b.clone(), None, true);
        let (d, dir) = downloader();
        let mut f = file(&url, &b);
        f.sha256 = sha(b"something else");
        assert!(matches!(d.fetch(&f, &mut |_, _| {}), Err(Error::Mismatch(_))));
        assert!(entries(&dir).is_empty());
    }

    #[test]
    fn wrong_size_refused_and_deleted() {
        let b = body();
        let (url, _) = serve(b.clone(), None, true);
        let (d, dir) = downloader();
        let mut f = file(&url, &b);
        f.size -= 1;
        assert!(matches!(d.fetch(&f, &mut |_, _| {}), Err(Error::Mismatch(_))));
        assert!(entries(&dir).is_empty());
    }

    #[test]
    fn resumes_after_a_cut() {
        let b = body();
        let (url, seen) = serve(b.clone(), Some(100_000), true);
        let (d, dir) = downloader();
        let f = file(&url, &b);
        assert!(matches!(d.fetch(&f, &mut |_, _| {}), Err(Error::Net(_))));
        assert_eq!(entries(&dir), [format!("{}.part", f.sha256)]);
        let have = fs::metadata(dir.join(format!("{}.part", f.sha256))).unwrap().len();
        assert!(have > 0);
        let mut first = None;
        let p = d
            .fetch(&f, &mut |a, _| {
                first.get_or_insert(a);
            })
            .unwrap();
        assert_eq!(fs::read(p).unwrap(), b);
        assert_eq!(first, Some(have), "progress starts where the cut left it");
        let seen = seen.lock().unwrap().clone();
        assert_eq!(seen.len(), 2);
        assert_eq!(seen[1].range.as_deref(), Some(format!("bytes={have}-").as_str()));
    }

    #[test]
    fn restarts_when_server_ignores_range() {
        let b = body();
        let (url, _) = serve(b.clone(), Some(100_000), false);
        let (d, _) = downloader();
        let f = file(&url, &b);
        assert!(d.fetch(&f, &mut |_, _| {}).is_err());
        assert_eq!(fs::read(d.fetch(&f, &mut |_, _| {}).unwrap()).unwrap(), b);
    }

    #[test]
    fn corrupted_cache_is_downloaded_again() {
        let b = body();
        let (url, seen) = serve(b.clone(), None, true);
        let (d, dir) = downloader();
        let f = file(&url, &b);
        fs::write(dir.join(&f.sha256), vec![0; b.len()]).unwrap();
        assert_eq!(fs::read(d.fetch(&f, &mut |_, _| {}).unwrap()).unwrap(), b);
        assert_eq!(seen.lock().unwrap().len(), 1);
    }

    #[test]
    fn ed25519_signature_checked() {
        let b = body();
        let (url, _) = serve(b.clone(), None, true);
        let (mut d, dir) = downloader();
        let key = SigningKey::from_bytes(&[9; 32]);
        d.release_key = key.verifying_key();
        let hex = |s: Signature| s.to_bytes().iter().map(|b| format!("{b:02x}")).collect::<String>();
        let mut f = file(&url, &b);
        f.ed25519 = Some(hex(key.sign(b"another zip")));
        assert!(matches!(d.fetch(&f, &mut |_, _| {}), Err(Error::Mismatch(_))));
        assert!(entries(&dir).is_empty());
        f.ed25519 = Some(hex(key.sign(&b)));
        assert!(d.fetch(&f, &mut |_, _| {}).is_ok());
    }

    #[test]
    fn https_only_outside_tests() {
        let b = body();
        let (url, seen) = serve(b.clone(), None, true);
        let dir = std::env::temp_dir().join(format!("bold7-d2-https-{}", std::process::id()));
        let d = Downloader::new(&dir).unwrap();
        assert!(d.fetch(&file(&url, &b), &mut |_, _| {}).is_err());
        assert!(seen.lock().unwrap().is_empty());
        fs::remove_dir_all(dir).ok();
    }

    /// Real network, off by default: `cargo test -- --ignored`. Checks the bundled catalogue
    /// against the LineageOS API, GitHub's asset digests and MindTheGapps' .sha256sum, and
    /// downloads one small module for real.
    #[test]
    #[ignore]
    fn real_network() {
        let c = Catalogue::bundled().unwrap();
        let dir = std::env::temp_dir().join(format!("bold7-d2-net-{}", std::process::id()));
        let d = Downloader::new(&dir).unwrap();
        let b = d
            .lineage_build(&c)
            .expect("the tested LineageOS build is still in the API");
        println!("LineageOS {} {} tested={}", b.version, b.date, b.tested);

        for comp in &c.components {
            for f in &comp.files {
                // https://github.com/<owner>/<repo>/releases/download/<tag>/<name>
                let p: Vec<&str> = f.url.trim_start_matches("https://github.com/").split('/').collect();
                assert_eq!((p.len(), p[2], p[3]), (6, "releases", "download"), "{}", f.url);
                let api = format!("https://api.github.com/repos/{}/{}/releases/tags/{}", p[0], p[1], p[4]);
                let rel: serde_json::Value = serde_json::from_slice(
                    &d.agent
                        .get(&api)
                        .call()
                        .unwrap()
                        .body_mut()
                        .with_config()
                        .limit(4 << 20)
                        .read_to_vec()
                        .unwrap(),
                )
                .unwrap();
                let asset = rel["assets"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|a| a["name"] == f.name)
                    .expect(&f.name);
                assert_eq!(asset["digest"], format!("sha256:{}", f.sha256), "{}", f.name);
                assert_eq!(asset["size"], f.size, "{}", f.name);
                println!("{} {}: GitHub digest ok", comp.id, f.name);
            }
        }

        let g = c.component("mindthegapps").unwrap();
        let sum = g.files.iter().find(|f| f.name.ends_with(".sha256sum")).unwrap();
        let text = fs::read_to_string(d.fetch(sum, &mut |_, _| {}).unwrap()).unwrap();
        let zip = g.files.iter().find(|f| f.name.ends_with(".zip")).unwrap();
        assert_eq!(text.split_whitespace().next(), Some(zip.sha256.as_str()));

        let pif = &c.component("playintegrityfix-inject").unwrap().files[0];
        d.fetch(pif, &mut |_, _| {}).unwrap();
        fs::remove_dir_all(dir).ok();
    }
}
