use crate::protocol::{
    decode_discovery_response, encode_discovery_request, random_nonce, DiscoveryResponse,
    DISCOVERY_PORT,
};
use anyhow::{Context, Result};
use if_addrs::IfAddr;
use std::collections::{BTreeMap, BTreeSet};
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct DiscoveredDevice {
    pub source: SocketAddr,
    pub response: DiscoveryResponse,
}

pub fn discover(timeout: Duration, host: Option<&str>) -> Result<Vec<DiscoveredDevice>> {
    let socket =
        UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).context("bind discovery UDP socket")?;
    socket.set_broadcast(true)?;
    socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    let nonce = random_nonce();
    let request = encode_discovery_request(&nonce);
    let destinations = discovery_destinations(host)?;
    for destination in destinations {
        let _ = socket.send_to(&request, destination);
    }

    let deadline = Instant::now() + timeout;
    let mut devices = BTreeMap::<u64, DiscoveredDevice>::new();
    let mut buffer = [0u8; 1024];
    while Instant::now() < deadline {
        match socket.recv_from(&mut buffer) {
            Ok((length, source)) => {
                let Ok(response) = decode_discovery_response(&buffer[..length]) else {
                    continue;
                };
                if response.request_nonce != nonce {
                    continue;
                }
                devices
                    .entry(response.device_id)
                    .or_insert(DiscoveredDevice { source, response });
            }
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(error) => return Err(error).context("receive discovery response"),
        }
    }
    Ok(devices.into_values().collect())
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

    #[test]
    fn explicit_host_resolves_to_discovery_port() {
        let addresses = discovery_destinations(Some("127.0.0.1")).unwrap();
        assert_eq!(addresses.len(), 1);
        assert_eq!(addresses.iter().next().unwrap().port(), DISCOVERY_PORT);
    }
}
