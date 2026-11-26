#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

pub mod audio;
mod audio_facts;
mod confirmation;
mod core;
mod discovery;
pub mod error;
#[cfg(feature = "uniffi")]
mod ffi;
pub mod gatt;
mod lifeline;
mod link;
mod queue;
mod recovery;
mod route;
mod runtime;
pub mod scan;
pub mod selection;
pub mod settings;
#[cfg(feature = "simulator")]
pub mod simulator;
pub mod snapshot;
pub mod spp;
mod state;
mod targeting;
pub mod transport;

pub use core::{LocalClock, SessionTiming};
pub use error::{ControlError, ControlErrorCode, ControlResult};
pub use runtime::{ControlConfig, ControlObserver, ControlRuntime};
