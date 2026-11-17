//! Catalogue and downloads of Bold7 Setup.
//!
//! The catalogue pins, for one device, every component of an installation with its official
//! URL, size and sha256; it is signed in Ed25519 and checked before anything is read from it.
//! LineageOS builds come from the official API that the catalogue names, and are accepted only
//! when the catalogue lists them as tested (unless it says otherwise). The downloader fetches
//! only files the catalogue resolved, resumes after a cut, and hands out a file only once its
//! size, sha256 and, when the catalogue gives one, its Ed25519 signature are right.

mod catalogue;
mod download;

pub use catalogue::{
    Catalogue, Component, File, Lineage, LineageBuild, TestedBuild, bold7_release_key, catalogue_key, resolve_lineage,
};
pub use download::{Downloader, USER_AGENT};

use std::fmt;

#[derive(Debug)]
pub enum Error {
    /// The catalogue's signature does not verify with the expected key.
    BadSignature,
    /// The catalogue, or the LineageOS API, says something the rules refuse.
    Invalid(String),
    /// A downloaded file is not the one the catalogue names; it has been deleted.
    Mismatch(String),
    Net(String),
    Io(std::io::Error),
}

pub type Result<T> = std::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::BadSignature => write!(f, "the catalogue's signature is not valid"),
            Error::Invalid(s) => write!(f, "invalid: {s}"),
            Error::Mismatch(s) => write!(f, "file refused and deleted: {s}"),
            Error::Net(s) => write!(f, "network: {s}"),
            Error::Io(e) => write!(f, "io: {e}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

impl From<ureq::Error> for Error {
    fn from(e: ureq::Error) -> Self {
        Error::Net(e.to_string())
    }
}
