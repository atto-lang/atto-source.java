//! Source-local bounded state-machine checks, not runtime or backend certification.
mod carrier;
mod teardown;

fn main() {
    carrier::verify();
    teardown::verify();
}
