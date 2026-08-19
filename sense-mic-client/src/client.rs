use crate::audio::AudioOutput;
use crate::discovery::DiscoveredDevice;
use crate::jitter::{JitterBuffer, PlayoutFrame};
use crate::protocol::{
    decrypt_audio, encode_stats, parse_audio_header, perform_handshake, read_control_frame,
    write_control_frame, ClientStats, ControlType, ReplayWindow, AUDIO_HEADER_BYTES, FRAME_MILLIS,
    FRAME_SAMPLES, GCM_TAG_BYTES, MAX_AUDIO_PAYLOAD_BYTES,
};
use anyhow::{bail, ensure, Context, Result};
use opus_rs::OpusDecoder;
use std::io::ErrorKind;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(2);
const AUDIO_IDLE_TIMEOUT: Duration = Duration::from_secs(7);

#[derive(Clone, Debug)]
pub struct ConnectOptions {
    pub pair_code: String,
    pub output_device: Option<String>,
    pub latency_millis: u32,
    pub client_name: String,
}

#[derive(Default)]
struct SharedStats {
    received: AtomicU64,
    lost: AtomicU64,
    jitter_millis: AtomicU64,
}

pub fn stream_device(
    device: &DiscoveredDevice,
    options: &ConnectOptions,
    shutdown: Arc<AtomicBool>,
) -> Result<()> {
    let phone_ip = device.source.ip();
    let udp = UdpSocket::bind((unspecified_for(phone_ip), 0)).context("bind audio UDP socket")?;
    udp.set_read_timeout(Some(Duration::from_millis(4)))?;
    let udp_port = udp.local_addr()?.port();

    let control_address = SocketAddr::new(phone_ip, device.response.control_port);
    let mut control = TcpStream::connect_timeout(&control_address, Duration::from_secs(4))
        .with_context(|| format!("connect phone control socket {control_address}"))?;
    control.set_nodelay(true)?;
    let secrets = perform_handshake(
        &mut control,
        &device.response,
        &options.pair_code,
        udp_port,
        &options.client_name,
    )?;

    let mut output = AudioOutput::open(options.output_device.as_deref())?;
    println!(
        "Connected to {} ({}) · {} kbps · output {}",
        device.response.device_name,
        phone_ip,
        secrets.welcome.bitrate / 1_000,
        output.summary(),
    );
    let stats = Arc::new(SharedStats::default());
    let heartbeat_stop = Arc::new(AtomicBool::new(false));
    let heartbeat = spawn_heartbeat(
        control,
        Arc::clone(&stats),
        Arc::clone(&shutdown),
        Arc::clone(&heartbeat_stop),
    )?;

    let result = receive_audio(
        &udp,
        phone_ip,
        &secrets.session_key,
        secrets.welcome.session_id,
        options.latency_millis,
        &mut output,
        &stats,
        &shutdown,
        &heartbeat_stop,
    );
    heartbeat_stop.store(true, Ordering::Release);
    let _ = heartbeat.join();
    result
}

#[allow(clippy::too_many_arguments)]
fn receive_audio(
    udp: &UdpSocket,
    phone_ip: IpAddr,
    session_key: &[u8; 32],
    session_id: u32,
    latency_millis: u32,
    output: &mut AudioOutput,
    shared: &SharedStats,
    shutdown: &AtomicBool,
    heartbeat_stop: &AtomicBool,
) -> Result<()> {
    let mut decoder = OpusDecoder::new(48_000, 1).map_err(anyhow::Error::msg)?;
    let mut jitter = JitterBuffer::new(latency_millis);
    let mut replay = ReplayWindow::default();
    let mut buffer = vec![0u8; AUDIO_HEADER_BYTES + MAX_AUDIO_PAYLOAD_BYTES + GCM_TAG_BYTES];
    let mut pcm = vec![0f32; FRAME_SAMPLES];
    let mut last_toc = None;
    let mut next_playout: Option<Instant> = None;
    let mut last_audio = Instant::now();
    let mut last_report = Instant::now();

    while !shutdown.load(Ordering::Acquire) && !heartbeat_stop.load(Ordering::Acquire) {
        match udp.recv_from(&mut buffer) {
            Ok((length, source)) => {
                if source.ip() != phone_ip {
                    continue;
                }
                let datagram = &buffer[..length];
                let Ok(preview) = parse_audio_header(datagram) else {
                    continue;
                };
                if preview.session_id != session_id {
                    continue;
                }
                let Ok((header, payload)) = decrypt_audio(session_key, datagram) else {
                    continue;
                };
                if !replay.accept(header.packet_counter) {
                    continue;
                }
                last_audio = Instant::now();
                jitter.insert(header, payload, last_audio);
            }
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(error) => return Err(error).context("receive encrypted audio"),
        }

        let now = Instant::now();
        if next_playout.is_none() {
            if let Some(frame) = jitter.pop() {
                // Keep two frames queued ahead of the real-time callback. Network jitter remains
                // in JitterBuffer; this short audio pre-roll absorbs scheduler/driver bursts.
                output.push_silence(FRAME_SAMPLES * 2);
                decode_and_queue(frame, &mut decoder, &mut pcm, &mut last_toc, output)?;
                next_playout = Some(now + Duration::from_millis(FRAME_MILLIS));
            }
        } else {
            while next_playout.is_some_and(|deadline| now >= deadline) {
                let frame = jitter.pop().unwrap_or(PlayoutFrame::Missing);
                decode_and_queue(frame, &mut decoder, &mut pcm, &mut last_toc, output)?;
                next_playout =
                    next_playout.map(|deadline| deadline + Duration::from_millis(FRAME_MILLIS));
            }
        }

        let current = jitter.stats();
        shared.received.store(current.received, Ordering::Relaxed);
        shared.lost.store(current.lost, Ordering::Relaxed);
        shared
            .jitter_millis
            .store(current.jitter_millis as u64, Ordering::Relaxed);

        if last_report.elapsed() >= Duration::from_secs(5) {
            println!(
                "audio: received={} recovered={} lost={} jitter={}ms buffered={} dropped_samples={}",
                current.received,
                current.recovered,
                current.lost,
                current.jitter_millis,
                jitter.buffered_frames(),
                output.dropped_samples(),
            );
            last_report = now;
        }
        ensure!(output.is_healthy(), "virtual microphone audio stream ended");
        if last_audio.elapsed() > AUDIO_IDLE_TIMEOUT {
            bail!("phone audio timed out");
        }
    }
    Ok(())
}

fn decode_and_queue(
    frame: PlayoutFrame,
    decoder: &mut OpusDecoder,
    pcm: &mut [f32],
    last_toc: &mut Option<u8>,
    output: &mut AudioOutput,
) -> Result<()> {
    pcm.fill(0.0);
    let decoded = match frame {
        PlayoutFrame::Packet(packet) | PlayoutFrame::Recovered(packet) => {
            *last_toc = packet.first().copied();
            decoder
                .decode(&packet, FRAME_SAMPLES, pcm)
                .map_err(anyhow::Error::msg)?
        }
        PlayoutFrame::Missing => {
            if let Some(toc) = *last_toc {
                decoder
                    .decode(&[toc], FRAME_SAMPLES, pcm)
                    .unwrap_or(FRAME_SAMPLES)
            } else {
                FRAME_SAMPLES
            }
        }
    };
    output.push_frame(&pcm[..decoded.min(pcm.len())]);
    if decoded < pcm.len() {
        output.push_frame(&pcm[decoded..]);
    }
    Ok(())
}

fn spawn_heartbeat(
    mut control: TcpStream,
    stats: Arc<SharedStats>,
    shutdown: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
) -> Result<thread::JoinHandle<()>> {
    control.set_read_timeout(Some(Duration::from_secs(3)))?;
    Ok(thread::Builder::new()
        .name("sense-mic-heartbeat".to_owned())
        .spawn(move || {
            while !shutdown.load(Ordering::Acquire) && !stop.load(Ordering::Acquire) {
                let started = Instant::now();
                let payload = encode_stats(ClientStats {
                    received_packets: stats.received.load(Ordering::Relaxed),
                    lost_packets: stats.lost.load(Ordering::Relaxed),
                    jitter_millis: stats
                        .jitter_millis
                        .load(Ordering::Relaxed)
                        .min(u16::MAX as u64) as u16,
                });
                let result = write_control_frame(&mut control, ControlType::Ping, &payload)
                    .and_then(|_| read_control_frame(&mut control))
                    .and_then(|(kind, _)| {
                        ensure!(
                            kind == ControlType::Pong,
                            "phone heartbeat response was {kind:?}"
                        );
                        Ok(())
                    });
                if let Err(error) = result {
                    eprintln!("control connection ended: {error:#}");
                    stop.store(true, Ordering::Release);
                    break;
                }
                let remaining = HEARTBEAT_INTERVAL.saturating_sub(started.elapsed());
                sleep_interruptibly(remaining, &shutdown, &stop);
            }
            let _ = write_control_frame(&mut control, ControlType::Stop, &[]);
        })?)
}

fn sleep_interruptibly(duration: Duration, first: &AtomicBool, second: &AtomicBool) {
    let deadline = Instant::now() + duration;
    while Instant::now() < deadline
        && !first.load(Ordering::Acquire)
        && !second.load(Ordering::Acquire)
    {
        thread::sleep(Duration::from_millis(50));
    }
}

fn unspecified_for(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V4(_) => "0.0.0.0".parse().expect("valid IPv4"),
        IpAddr::V6(_) => "::".parse().expect("valid IPv6"),
    }
}
