use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use anyhow::{anyhow, bail, ensure, Context, Result};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::ecdh::diffie_hellman;
use p256::pkcs8::{DecodePublicKey, EncodePublicKey};
use p256::{PublicKey, SecretKey};
use pbkdf2::pbkdf2_hmac;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::Sha256;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;
use subtle::ConstantTimeEq;
use zeroize::Zeroize;

pub const VERSION: u8 = 1;
pub const DISCOVERY_PORT: u16 = 49_173;
pub const CONTROL_PORT: u16 = 49_174;
pub const SAMPLE_RATE: u32 = 48_000;
pub const FRAME_SAMPLES: usize = 960;
pub const FRAME_MILLIS: u64 = 20;
pub const CHANNELS: u8 = 1;
pub const CODEC_OPUS: u8 = 1;
pub const FEC_GROUP_SIZE: u8 = 4;
pub const AUDIO_HEADER_BYTES: usize = 36;
pub const GCM_TAG_BYTES: usize = 16;
pub const MAX_CONTROL_PAYLOAD_BYTES: usize = 4_096;
pub const MAX_AUDIO_PAYLOAD_BYTES: usize = 1_275;
pub const DISCOVERY_MAGIC: [u8; 4] = *b"SMIC";
pub const AUDIO_MAGIC: [u8; 4] = *b"SMUA";
const TRANSCRIPT_LABEL: &[u8] = b"sense-mic-handshake-v1";
const PBKDF2_ITERATIONS: u32 = 80_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ControlType {
    Hello = 1,
    Welcome = 2,
    Error = 3,
    Ping = 4,
    Pong = 5,
    Stop = 6,
}

impl TryFrom<u8> for ControlType {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::Hello),
            2 => Ok(Self::Welcome),
            3 => Ok(Self::Error),
            4 => Ok(Self::Ping),
            5 => Ok(Self::Pong),
            6 => Ok(Self::Stop),
            _ => bail!("unknown control frame type {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum AudioKind {
    Audio = 1,
    XorFec = 2,
}

impl TryFrom<u8> for AudioKind {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::Audio),
            2 => Ok(Self::XorFec),
            _ => bail!("unknown audio packet kind {value}"),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiscoveryResponse {
    pub control_port: u16,
    pub device_id: u64,
    pub request_nonce: [u8; 16],
    pub server_nonce: [u8; 16],
    pub server_public_key: Vec<u8>,
    pub device_name: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Welcome {
    pub session_id: u32,
    pub sample_rate: u32,
    pub frame_samples: u16,
    pub channels: u8,
    pub codec: u8,
    pub bitrate: u32,
    pub server_proof: [u8; 32],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ClientStats {
    pub received_packets: u64,
    pub lost_packets: u64,
    pub jitter_millis: u16,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AudioHeader {
    pub kind: AudioKind,
    pub session_id: u32,
    pub packet_counter: u64,
    pub frame_sequence: u32,
    pub timestamp_samples: u64,
    pub payload_length: u16,
    pub fec_group_size: u8,
}

pub struct SessionSecrets {
    pub session_key: [u8; 32],
    pub welcome: Welcome,
}

impl Drop for SessionSecrets {
    fn drop(&mut self) {
        self.session_key.zeroize();
    }
}

pub fn random_nonce() -> [u8; 16] {
    let mut value = [0u8; 16];
    OsRng.fill_bytes(&mut value);
    value
}

pub fn encode_discovery_request(nonce: &[u8; 16]) -> [u8; 22] {
    let mut out = [0u8; 22];
    out[..4].copy_from_slice(&DISCOVERY_MAGIC);
    out[4] = VERSION;
    out[5] = 1;
    out[6..].copy_from_slice(nonce);
    out
}

pub fn decode_discovery_response(bytes: &[u8]) -> Result<DiscoveryResponse> {
    ensure!(bytes.len() >= 53, "discovery response is truncated");
    ensure!(bytes[..4] == DISCOVERY_MAGIC, "discovery magic mismatch");
    ensure!(
        bytes[4] == VERSION && bytes[5] == 2,
        "discovery version/type mismatch"
    );
    let control_port = u16::from_be_bytes(bytes[6..8].try_into()?);
    let device_id = u64::from_be_bytes(bytes[8..16].try_into()?);
    let request_nonce = bytes[16..32].try_into()?;
    let server_nonce = bytes[32..48].try_into()?;
    let public_len = u16::from_be_bytes(bytes[48..50].try_into()?) as usize;
    let name_len = bytes[50] as usize;
    ensure!(
        (1..=512).contains(&public_len),
        "invalid server public key length"
    );
    ensure!((1..=63).contains(&name_len), "invalid device name length");
    ensure!(
        bytes.len() == 51 + public_len + name_len,
        "discovery response length mismatch"
    );
    let server_public_key = bytes[51..51 + public_len].to_vec();
    let device_name = std::str::from_utf8(&bytes[51 + public_len..])?.to_owned();
    Ok(DiscoveryResponse {
        control_port,
        device_id,
        request_nonce,
        server_nonce,
        server_public_key,
        device_name,
    })
}

pub fn write_control_frame(
    stream: &mut TcpStream,
    kind: ControlType,
    payload: &[u8],
) -> Result<()> {
    ensure!(
        payload.len() <= MAX_CONTROL_PAYLOAD_BYTES,
        "control payload is too large"
    );
    let mut header = [0u8; 12];
    header[..4].copy_from_slice(&DISCOVERY_MAGIC);
    header[4] = VERSION;
    header[5] = kind as u8;
    header[8..12].copy_from_slice(&(payload.len() as u32).to_be_bytes());
    stream.write_all(&header)?;
    stream.write_all(payload)?;
    stream.flush()?;
    Ok(())
}

pub fn read_control_frame(stream: &mut TcpStream) -> Result<(ControlType, Vec<u8>)> {
    let mut header = [0u8; 12];
    stream.read_exact(&mut header)?;
    ensure!(header[..4] == DISCOVERY_MAGIC, "control magic mismatch");
    ensure!(header[4] == VERSION, "control protocol version mismatch");
    ensure!(
        header[6] == 0 && header[7] == 0,
        "control flags are unsupported"
    );
    let kind = ControlType::try_from(header[5])?;
    let length = u32::from_be_bytes(header[8..12].try_into()?) as usize;
    ensure!(
        length <= MAX_CONTROL_PAYLOAD_BYTES,
        "control payload exceeds limit"
    );
    let mut payload = vec![0u8; length];
    stream.read_exact(&mut payload)?;
    Ok((kind, payload))
}

pub fn encode_stats(stats: ClientStats) -> [u8; 18] {
    let mut out = [0u8; 18];
    out[..8].copy_from_slice(&stats.received_packets.to_be_bytes());
    out[8..16].copy_from_slice(&stats.lost_packets.to_be_bytes());
    out[16..18].copy_from_slice(&stats.jitter_millis.to_be_bytes());
    out
}

pub fn parse_audio_header(datagram: &[u8]) -> Result<AudioHeader> {
    ensure!(
        datagram.len() >= AUDIO_HEADER_BYTES + GCM_TAG_BYTES,
        "audio datagram is truncated"
    );
    ensure!(datagram[..4] == AUDIO_MAGIC, "audio magic mismatch");
    ensure!(datagram[4] == VERSION, "audio protocol version mismatch");
    let kind = AudioKind::try_from(datagram[5])?;
    ensure!(
        u16::from_be_bytes(datagram[6..8].try_into()?) as usize == AUDIO_HEADER_BYTES,
        "audio header length mismatch"
    );
    let payload_length = u16::from_be_bytes(datagram[32..34].try_into()?);
    ensure!(
        payload_length as usize <= MAX_AUDIO_PAYLOAD_BYTES,
        "audio payload exceeds limit"
    );
    ensure!(datagram[35] == 0, "audio flags are unsupported");
    ensure!(
        datagram.len() == AUDIO_HEADER_BYTES + payload_length as usize + GCM_TAG_BYTES,
        "audio datagram length mismatch"
    );
    Ok(AudioHeader {
        kind,
        session_id: u32::from_be_bytes(datagram[8..12].try_into()?),
        packet_counter: u64::from_be_bytes(datagram[12..20].try_into()?),
        frame_sequence: u32::from_be_bytes(datagram[20..24].try_into()?),
        timestamp_samples: u64::from_be_bytes(datagram[24..32].try_into()?),
        payload_length,
        fec_group_size: datagram[34],
    })
}

pub fn decrypt_audio(key: &[u8; 32], datagram: &[u8]) -> Result<(AudioHeader, Vec<u8>)> {
    let header = parse_audio_header(datagram)?;
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| anyhow!("invalid session key"))?;
    let mut nonce_bytes = [0u8; 12];
    nonce_bytes[..4].copy_from_slice(&header.session_id.to_be_bytes());
    nonce_bytes[4..].copy_from_slice(&header.packet_counter.to_be_bytes());
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload {
                msg: &datagram[AUDIO_HEADER_BYTES..],
                aad: &datagram[..AUDIO_HEADER_BYTES],
            },
        )
        .map_err(|_| anyhow!("audio authentication failed"))?;
    ensure!(
        plaintext.len() == header.payload_length as usize,
        "audio plaintext length mismatch"
    );
    Ok((header, plaintext))
}

pub fn perform_handshake(
    stream: &mut TcpStream,
    discovery: &DiscoveryResponse,
    pair_code: &str,
    udp_port: u16,
    client_name: &str,
) -> Result<SessionSecrets> {
    ensure!(
        pair_code.len() == 6 && pair_code.bytes().all(|v| v.is_ascii_digit()),
        "pair code must contain six digits"
    );
    let client_name_bytes = client_name.as_bytes();
    ensure!(
        !client_name_bytes.is_empty() && client_name_bytes.len() <= 63,
        "client name length is invalid"
    );

    let secret = SecretKey::random(&mut OsRng);
    let public = secret.public_key();
    let public_der = public.to_public_key_der()?.as_bytes().to_vec();
    let client_nonce = random_nonce();
    let mut pair_key = derive_pair_key(pair_code, &discovery.server_nonce, &client_nonce);
    let mut transcript = build_transcript(
        &discovery.server_public_key,
        &public_der,
        &discovery.server_nonce,
        &client_nonce,
    )?;
    let proof = hmac(
        &pair_key,
        &[b"client".as_slice(), transcript.as_slice()].concat(),
    );

    let mut hello = Vec::with_capacity(21 + public_der.len() + client_name_bytes.len() + 32);
    hello.extend_from_slice(&udp_port.to_be_bytes());
    hello.extend_from_slice(&client_nonce);
    push_u16_len(&mut hello, public_der.len())?;
    hello.push(client_name_bytes.len() as u8);
    hello.extend_from_slice(&public_der);
    hello.extend_from_slice(client_name_bytes);
    hello.extend_from_slice(&proof);
    write_control_frame(stream, ControlType::Hello, &hello)?;

    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    let (kind, payload) = read_control_frame(stream).context("read WELCOME")?;
    if kind == ControlType::Error {
        let message = decode_server_error(&payload);
        pair_key.zeroize();
        transcript.zeroize();
        bail!("phone rejected pairing: {message}");
    }
    ensure!(
        kind == ControlType::Welcome,
        "expected WELCOME, received {kind:?}"
    );
    let welcome = decode_welcome(&payload)?;
    ensure!(
        welcome.sample_rate == SAMPLE_RATE,
        "phone selected unsupported sample rate"
    );
    ensure!(
        welcome.frame_samples as usize == FRAME_SAMPLES,
        "phone selected unsupported frame size"
    );
    ensure!(
        welcome.channels == CHANNELS && welcome.codec == CODEC_OPUS,
        "phone selected unsupported codec layout"
    );
    let expected_server_proof = hmac(
        &pair_key,
        &[b"server".as_slice(), transcript.as_slice()].concat(),
    );
    ensure!(
        bool::from(expected_server_proof.ct_eq(&welcome.server_proof)),
        "server proof verification failed"
    );

    let server_public = PublicKey::from_public_key_der(&discovery.server_public_key)
        .context("decode phone P-256 key")?;
    let shared = diffie_hellman(secret.to_nonzero_scalar(), server_public.as_affine());
    let hkdf = Hkdf::<Sha256>::new(Some(&pair_key), shared.raw_secret_bytes().as_slice());
    let mut session_key = [0u8; 32];
    hkdf.expand(&transcript, &mut session_key)
        .map_err(|_| anyhow!("HKDF output length is invalid"))?;
    pair_key.zeroize();
    transcript.zeroize();
    Ok(SessionSecrets {
        session_key,
        welcome,
    })
}

fn derive_pair_key(code: &str, server_nonce: &[u8; 16], client_nonce: &[u8; 16]) -> [u8; 32] {
    let mut salt = [0u8; 32];
    salt[..16].copy_from_slice(server_nonce);
    salt[16..].copy_from_slice(client_nonce);
    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(code.as_bytes(), &salt, PBKDF2_ITERATIONS, &mut key);
    salt.zeroize();
    key
}

fn build_transcript(
    server_public_key: &[u8],
    client_public_key: &[u8],
    server_nonce: &[u8; 16],
    client_nonce: &[u8; 16],
) -> Result<Vec<u8>> {
    let mut output = Vec::with_capacity(
        TRANSCRIPT_LABEL.len() + server_public_key.len() + client_public_key.len() + 36,
    );
    output.extend_from_slice(TRANSCRIPT_LABEL);
    push_u16_len(&mut output, server_public_key.len())?;
    output.extend_from_slice(server_public_key);
    push_u16_len(&mut output, client_public_key.len())?;
    output.extend_from_slice(client_public_key);
    output.extend_from_slice(server_nonce);
    output.extend_from_slice(client_nonce);
    Ok(output)
}

fn hmac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC accepts any key size");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

fn push_u16_len(output: &mut Vec<u8>, length: usize) -> Result<()> {
    ensure!(length <= u16::MAX as usize, "field is too large");
    output.extend_from_slice(&(length as u16).to_be_bytes());
    Ok(())
}

fn decode_welcome(payload: &[u8]) -> Result<Welcome> {
    ensure!(payload.len() == 48, "WELCOME length mismatch");
    Ok(Welcome {
        session_id: u32::from_be_bytes(payload[0..4].try_into()?),
        sample_rate: u32::from_be_bytes(payload[4..8].try_into()?),
        frame_samples: u16::from_be_bytes(payload[8..10].try_into()?),
        channels: payload[10],
        codec: payload[11],
        bitrate: u32::from_be_bytes(payload[12..16].try_into()?),
        server_proof: payload[16..48].try_into()?,
    })
}

fn decode_server_error(payload: &[u8]) -> String {
    if payload.len() < 3 {
        return "malformed ERROR frame".to_owned();
    }
    let code = u16::from_be_bytes([payload[0], payload[1]]);
    let text_len = payload[2] as usize;
    let text = payload
        .get(3..3 + text_len)
        .and_then(|v| std::str::from_utf8(v).ok())
        .unwrap_or("unknown error");
    format!("{code}: {text}")
}

/// Rejects duplicate authenticated UDP packets while allowing bounded reordering.
#[derive(Debug, Default)]
pub struct ReplayWindow {
    highest: Option<u64>,
    bitmap: u128,
}

impl ReplayWindow {
    pub fn accept(&mut self, counter: u64) -> bool {
        match self.highest {
            None => {
                self.highest = Some(counter);
                self.bitmap = 1;
                true
            }
            Some(highest) if counter > highest => {
                let shift = counter - highest;
                self.bitmap = if shift >= 128 {
                    1
                } else {
                    (self.bitmap << shift) | 1
                };
                self.highest = Some(counter);
                true
            }
            Some(highest) => {
                let age = highest - counter;
                if age >= 128 {
                    return false;
                }
                let mask = 1u128 << age;
                if self.bitmap & mask != 0 {
                    false
                } else {
                    self.bitmap |= mask;
                    true
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discovery_response_round_trip_fixture_matches_android_layout() {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"SMIC");
        bytes.extend_from_slice(&[1, 2]);
        bytes.extend_from_slice(&49_174u16.to_be_bytes());
        bytes.extend_from_slice(&7u64.to_be_bytes());
        bytes.extend_from_slice(&[1; 16]);
        bytes.extend_from_slice(&[2; 16]);
        bytes.extend_from_slice(&3u16.to_be_bytes());
        bytes.push(5);
        bytes.extend_from_slice(&[4, 5, 6]);
        bytes.extend_from_slice(b"Phone");
        let parsed = decode_discovery_response(&bytes).unwrap();
        assert_eq!(parsed.control_port, 49_174);
        assert_eq!(parsed.device_id, 7);
        assert_eq!(parsed.device_name, "Phone");
        assert_eq!(parsed.server_public_key, vec![4, 5, 6]);
    }

    #[test]
    fn replay_window_accepts_reordering_once() {
        let mut replay = ReplayWindow::default();
        assert!(replay.accept(10));
        assert!(replay.accept(12));
        assert!(replay.accept(11));
        assert!(!replay.accept(11));
        assert!(replay.accept(200));
        assert!(!replay.accept(12));
    }

    #[test]
    fn stats_layout_is_big_endian() {
        let bytes = encode_stats(ClientStats {
            received_packets: 1,
            lost_packets: 2,
            jitter_millis: 3,
        });
        assert_eq!(&bytes[7..9], &[1, 0]);
        assert_eq!(&bytes[15..], &[2, 0, 3]);
    }

    #[test]
    fn decrypts_the_android_v1_audio_fixture() {
        // SenseMicProtocolTest emits and asserts the same deterministic AES-GCM datagram.
        const ANDROID_AUDIO_VECTOR: &str = concat!(
            "534d55410101002412345678000000000000002a000000110000000000003fc0007f0000",
            "bf297999cfc8f05c62c054c1db314b2b5405769235716b9396aa787b2628c7a67f5a7e",
            "b7e9612147c4a3ce8d13ef56947ecf48a16ce4135052cc7bdc982001c63917150724bfe9",
            "90d1d63e2da1e9416a4489e2b60e1b729ba2526eb7d4ab426e67652a42be1c126a1ed22",
            "692fae059c53517ca3974d3887ec9dfd784fabe0ce23c9d695861711d76e33b70a3947635",
        );
        let packet = decode_hex(ANDROID_AUDIO_VECTOR);
        let key = std::array::from_fn(|index| (index * 3) as u8);
        let (header, payload) = decrypt_audio(&key, &packet).unwrap();

        assert_eq!(header.kind, AudioKind::Audio);
        assert_eq!(header.session_id, 0x1234_5678);
        assert_eq!(header.packet_counter, 42);
        assert_eq!(header.frame_sequence, 17);
        assert_eq!(header.timestamp_samples, 16_320);
        assert_eq!(
            payload,
            (0u8..127).map(|value| value ^ 0x5a).collect::<Vec<u8>>()
        );
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0);
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let text = std::str::from_utf8(pair).unwrap();
                u8::from_str_radix(text, 16).unwrap()
            })
            .collect()
    }
}
