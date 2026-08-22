use sense_mic_client::protocol::DISCOVERY_PORT;
use std::net::{Ipv4Addr, UdpSocket};
use std::process::{Command, Output};

#[test]
fn no_discovered_phone_is_a_failure_for_discover_and_one_shot_serve() {
    // Binding a silent UDP fixture prevents Windows from turning an unanswered localhost
    // datagram into WSAECONNRESET. Both commands therefore exercise the clean zero-results path.
    let _silent_phone = UdpSocket::bind((Ipv4Addr::LOCALHOST, DISCOVERY_PORT)).unwrap();

    let discover = sense_mic(&["discover", "--host", "127.0.0.1", "--timeout-ms", "100"]);
    assert!(
        !discover.status.success(),
        "discover unexpectedly succeeded: {}",
        String::from_utf8_lossy(&discover.stdout)
    );
    assert!(
        String::from_utf8_lossy(&discover.stderr).contains("no Sense Mic phone replied"),
        "{}",
        String::from_utf8_lossy(&discover.stderr)
    );

    let serve = sense_mic(&[
        "serve",
        "--host",
        "127.0.0.1",
        "--timeout-ms",
        "100",
        "--code",
        "123456",
        "--output",
        "default",
        "--once",
    ]);
    assert!(
        !serve.status.success(),
        "one-shot serve unexpectedly succeeded: {}",
        String::from_utf8_lossy(&serve.stdout)
    );
    assert!(
        String::from_utf8_lossy(&serve.stderr).contains("Sense Mic discovery failed"),
        "{}",
        String::from_utf8_lossy(&serve.stderr)
    );
}

fn sense_mic(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_sense-mic"))
        .args(args)
        .output()
        .unwrap()
}
