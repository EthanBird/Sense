use crate::protocol::{
    decode_discovery_response, encode_discovery_request, random_nonce, DiscoveryResponse,
    DISCOVERY_PORT,
};
use anyhow::{bail, Context, Result};
use if_addrs::IfAddr;
use std::collections::{BTreeMap, BTreeSet};
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};

const DISCOVERY_RETRY_INTERVAL: Duration = Duration::from_millis(250);
const DISCOVERY_RECEIVE_SLICE: Duration = Duration::from_millis(100);

#[derive(Clone, Debug)]
pub struct DiscoveredDevice {
    pub source: SocketAddr,
    pub response: DiscoveryResponse,
}

pub fn discover(timeout: Duration, host: Option<&str>) -> Result<Vec<DiscoveredDevice>> {
    let socket =
        UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).context("bind discovery UDP socket")?;
    socket
        .set_broadcast(true)
        .context("enable UDP broadcast for Sense Mic discovery")?;
    discover_with_socket(&socket, timeout, discovery_destinations(host)?)
}

fn discover_with_socket(
    socket: &UdpSocket,
    timeout: Duration,
    destinations: BTreeSet<SocketAddr>,
) -> Result<Vec<DiscoveredDevice>> {
    if destinations.is_empty() {
        bail!("Sense Mic discovery resolved no UDP destinations");
    }

    let nonce = random_nonce();
    let request = encode_discovery_request(&nonce);
    let deadline = Instant::now() + timeout;
    let mut next_send = Instant::now();
    let mut send_rounds = 0usize;
    let mut successful_sends = 0usize;
    let mut send_errors = BTreeMap::<SocketAddr, String>::new();
    let mut receive_error = None::<String>;
    let mut invalid_responses = 0usize;
    let mut mismatched_nonces = 0usize;
    let mut devices = BTreeMap::<u64, DiscoveredDevice>::new();
    let mut buffer = [0u8; 1024];

    while Instant::now() < deadline {
        let now = Instant::now();
        if now >= next_send {
            send_rounds += 1;
            for destination in &destinations {
                match socket.send_to(&request, destination) {
                    Ok(length) if length == request.len() => {
                        successful_sends += 1;
                        send_errors.remove(destination);
                    }
                    Ok(length) => {
                        send_errors.insert(
                            *destination,
                            format!("sent {length} of {} bytes", request.len()),
                        );
                    }
                    Err(error) => {
                        send_errors.insert(*destination, error.to_string());
                    }
                }
            }
            next_send = now + DISCOVERY_RETRY_INTERVAL;
        }

        let now = Instant::now();
        if now >= deadline {
            break;
        }
        let receive_until = deadline.min(next_send);
        let wait = receive_until
            .saturating_duration_since(now)
            .min(DISCOVERY_RECEIVE_SLICE)
            .max(Duration::from_millis(1));
        socket
            .set_read_timeout(Some(wait))
            .context("set Sense Mic discovery receive timeout")?;

        match socket.recv_from(&mut buffer) {
            Ok((length, source)) => {
                let response = match decode_discovery_response(&buffer[..length]) {
                    Ok(response) => response,
                    Err(_) => {
                        invalid_responses += 1;
                        continue;
                    }
                };
                if response.request_nonce != nonce {
                    mismatched_nonces += 1;
                    continue;
                }
                devices
                    .entry(response.device_id)
                    .or_insert(DiscoveredDevice { source, response });
            }
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(error)
                if matches!(
                    error.kind(),
                    ErrorKind::ConnectionRefused | ErrorKind::ConnectionReset
                ) =>
            {
                // Windows reports an ICMP port-unreachable response as WSAECONNRESET on an
                // unconnected UDP socket. Keep retrying other destinations and surface it if
                // the entire discovery window produces no valid response.
                receive_error = Some(error.to_string());
            }
            Err(error) => return Err(error).context("receive discovery response"),
        }
    }

    if devices.is_empty() && successful_sends == 0 {
        bail!(
            "failed to send Sense Mic discovery request in {send_rounds} round(s): {}",
            format_send_errors(&send_errors)
        );
    }
    if devices.is_empty() && (!send_errors.is_empty() || receive_error.is_some()) {
        let mut details = Vec::new();
        if !send_errors.is_empty() {
            details.push(format!(
                "destination errors: {}",
                format_send_errors(&send_errors)
            ));
        }
        if let Some(error) = receive_error {
            details.push(format!("UDP receive error: {error}"));
        }
        bail!(
            "Sense Mic discovery sent {successful_sends} request(s) in {send_rounds} round(s) but received no valid reply ({})",
            details.join("; ")
        );
    }
    if devices.is_empty() && (invalid_responses > 0 || mismatched_nonces > 0) {
        bail!(
            "Sense Mic discovery received no matching reply (invalid packets: {invalid_responses}, stale nonce packets: {mismatched_nonces})"
        );
    }
    Ok(devices.into_values().collect())
}

fn format_send_errors(errors: &BTreeMap<SocketAddr, String>) -> String {
    if errors.is_empty() {
        return "no destination accepted the datagram".to_owned();
    }
    errors
        .iter()
        .map(|(destination, error)| format!("{destination}: {error}"))
        .collect::<Vec<_>>()
        .join(", ")
}

fn discovery_destinations(host: Option<&str>) -> Result<BTreeSet<SocketAddr>> {
    if let Some(host) = host {
        return Ok((host, DISCOVERY_PORT)
            .to_socket_addrs()
            .with_context(|| format!("resolve phone host {host}"))?
            .collect());
    }
    let mut addresses = BTreeSet::new();
    addresses.insert(SocketAddr::new(
        IpAddr::V4(Ipv4Addr::BROADCAST),
        DISCOVERY_PORT,
    ));
    for interface in if_addrs::get_if_addrs().context("enumerate network interfaces")? {
        if !interface.is_oper_up() || interface.is_loopback() {
            continue;
        }
        if let IfAddr::V4(v4) = interface.addr {
            if let Some(broadcast) = v4.broadcast {
                addresses.insert(SocketAddr::new(IpAddr::V4(broadcast), DISCOVERY_PORT));
            }
        }
    }
    Ok(addresses)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{DISCOVERY_MAGIC, VERSION};
    use std::thread;

    #[test]
    fn explicit_host_resolves_to_discovery_port() {
        let addresses = discovery_destinations(Some("127.0.0.1")).unwrap();
        assert_eq!(addresses.len(), 1);
        assert_eq!(addresses.iter().next().unwrap().port(), DISCOVERY_PORT);
    }

    #[test]
    fn retries_when_the_first_discovery_request_gets_no_reply() {
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        server
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let destination = server.local_addr().unwrap();
        let fixture = thread::spawn(move || {
            let mut request = [0u8; 64];
            let (first_len, client) = server.recv_from(&mut request).unwrap();
            assert_eq!(first_len, 22);
            let first = request[..first_len].to_vec();

            let (second_len, second_client) = server.recv_from(&mut request).unwrap();
            assert_eq!(client, second_client);
            assert_eq!(&request[..second_len], first.as_slice());

            let nonce: [u8; 16] = request[6..22].try_into().unwrap();
            let response = discovery_response_fixture(nonce);
            server.send_to(&response, client).unwrap();
        });

        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let devices = discover_with_socket(
            &client,
            Duration::from_millis(900),
            [destination].into_iter().collect(),
        )
        .unwrap();

        fixture.join().unwrap();
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].response.device_name, "Retry Fixture");
    }

    #[test]
    fn reports_every_failed_destination_after_retrying() {
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let ipv6_destination = SocketAddr::new(IpAddr::V6("::1".parse().unwrap()), 49_173);
        let error = discover_with_socket(
            &client,
            Duration::from_millis(300),
            [ipv6_destination].into_iter().collect(),
        )
        .unwrap_err();
        let message = error.to_string();
        assert!(message.contains("2 round(s)"), "{message}");
        assert!(message.contains("[::1]:49173"), "{message}");
    }

    fn discovery_response_fixture(request_nonce: [u8; 16]) -> Vec<u8> {
        let public_key = [4u8; 65];
        let name = b"Retry Fixture";
        let mut response = Vec::with_capacity(51 + public_key.len() + name.len());
        response.extend_from_slice(&DISCOVERY_MAGIC);
        response.push(VERSION);
        response.push(2);
        response.extend_from_slice(&49_174u16.to_be_bytes());
        response.extend_from_slice(&7u64.to_be_bytes());
        response.extend_from_slice(&request_nonce);
        response.extend_from_slice(&[3u8; 16]);
        response.extend_from_slice(&(public_key.len() as u16).to_be_bytes());
        response.push(name.len() as u8);
        response.extend_from_slice(&public_key);
        response.extend_from_slice(name);
        response
    }
}
