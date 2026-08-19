use crate::protocol::SAMPLE_RATE;
use anyhow::{anyhow, bail, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, FromSample, Host, Sample, SampleFormat, SizedSample, Stream, StreamConfig};
use rtrb::{Consumer, Producer, RingBuffer};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

// A bounded queue keeps recovery from turning an overloaded endpoint into seconds of stale audio.
const RING_CAPACITY_SAMPLES: usize = SAMPLE_RATE as usize / 2;

pub struct AudioOutput {
    producer: Producer<f32>,
    _stream: Stream,
    healthy: Arc<AtomicBool>,
    dropped_samples: Arc<AtomicU64>,
    reset_requested: Arc<AtomicBool>,
    device_name: String,
    sample_rate: u32,
    channels: u16,
}

impl AudioOutput {
    pub fn open(device_pattern: Option<&str>) -> Result<Self> {
        let host = platform_host()?;
        let device = select_output_device(&host, device_pattern)?;
        let device_name = device.to_string();
        let supported = select_config(&device)?;
        let sample_format = supported.sample_format();
        let config = supported.config();
        let sample_rate = config.sample_rate;
        let channels = config.channels;
        let (producer, consumer) = RingBuffer::<f32>::new(RING_CAPACITY_SAMPLES);
        let healthy = Arc::new(AtomicBool::new(true));
        let dropped_samples = Arc::new(AtomicU64::new(0));
        let reset_requested = Arc::new(AtomicBool::new(false));
        let stream = build_stream(
            &device,
            config,
            sample_format,
            consumer,
            Arc::clone(&healthy),
            Arc::clone(&reset_requested),
        )?;
        stream
            .play()
            .context("start virtual microphone output stream")?;
        Ok(Self {
            producer,
            _stream: stream,
            healthy,
            dropped_samples,
            reset_requested,
            device_name,
            sample_rate,
            channels,
        })
    }

    pub fn push_frame(&mut self, samples: &[f32]) {
        if self.reset_requested.load(Ordering::Acquire) {
            self.dropped_samples
                .fetch_add(samples.len() as u64, Ordering::Relaxed);
            return;
        }
        for (index, &sample) in samples.iter().enumerate() {
            if self.producer.push(sample.clamp(-1.0, 1.0)).is_err() {
                self.dropped_samples.fetch_add(
                    samples.len().saturating_sub(index) as u64,
                    Ordering::Relaxed,
                );
                self.reset_requested.store(true, Ordering::Release);
                break;
            }
        }
    }

    pub fn push_silence(&mut self, samples: usize) {
        const SILENCE: [f32; 960] = [0.0; 960];
        let mut remaining = samples;
        while remaining > 0 {
            let chunk = remaining.min(SILENCE.len());
            self.push_frame(&SILENCE[..chunk]);
            remaining -= chunk;
        }
    }

    pub fn is_healthy(&self) -> bool {
        self.healthy.load(Ordering::Acquire)
    }

    pub fn dropped_samples(&self) -> u64 {
        self.dropped_samples.load(Ordering::Relaxed)
    }

    pub fn summary(&self) -> String {
        format!(
            "{} · {} Hz · {} channel(s)",
            self.device_name, self.sample_rate, self.channels
        )
    }
}

pub fn list_output_devices() -> Result<Vec<String>> {
    let host = platform_host()?;
    let mut devices = Vec::new();
    for device in host.output_devices().context("enumerate output devices")? {
        devices.push(device.to_string());
    }
    devices.sort();
    devices.dedup();
    Ok(devices)
}

fn platform_host() -> Result<Host> {
    #[cfg(target_os = "linux")]
    {
        if let Ok(host) = cpal::host_from_id(cpal::HostId::PulseAudio) {
            return Ok(host);
        }
    }
    Ok(cpal::default_host())
}

fn select_output_device(host: &Host, pattern: Option<&str>) -> Result<Device> {
    let devices: Vec<Device> = host
        .output_devices()
        .context("enumerate audio output devices")?
        .collect();
    if let Some(pattern) = pattern {
        if pattern.eq_ignore_ascii_case("default") {
            return host
                .default_output_device()
                .ok_or_else(|| anyhow!("the audio host has no default output device"));
        }
        let needle = pattern.to_ascii_lowercase();
        return devices
            .into_iter()
            .find(|device| device.to_string().to_ascii_lowercase().contains(&needle))
            .ok_or_else(|| anyhow!("output device matching '{pattern}' was not found"));
    }

    const PREFERRED_NAMES: &[&str] = &[
        "sense mic playback",
        "sense mic input",
        "cable input",
        "sense_mic",
    ];
    for preferred in PREFERRED_NAMES {
        if let Some(device) = devices
            .iter()
            .find(|device| device.to_string().to_ascii_lowercase().contains(preferred))
        {
            return Ok(device.clone());
        }
    }
    bail!(
        "Sense Mic virtual playback endpoint was not found; run `sense-mic driver install` or pass --output"
    )
}

fn select_config(device: &Device) -> Result<cpal::SupportedStreamConfig> {
    let mut exact = device
        .supported_output_configs()
        .context("query output formats")?
        .filter(|range| {
            range.min_sample_rate() <= SAMPLE_RATE && range.max_sample_rate() >= SAMPLE_RATE
        })
        .map(|range| range.with_sample_rate(SAMPLE_RATE))
        .collect::<Vec<_>>();
    exact.sort_by_key(|config| {
        let channel_penalty = config.channels().abs_diff(1) as u32;
        let format_penalty = match config.sample_format() {
            SampleFormat::F32 => 0,
            SampleFormat::I16 => 1,
            SampleFormat::U16 => 2,
            _ => 3,
        };
        (channel_penalty, format_penalty)
    });
    if let Some(config) = exact.into_iter().next() {
        return Ok(config);
    }
    device
        .default_output_config()
        .context("read default output format")
}

fn build_stream(
    device: &Device,
    config: StreamConfig,
    format: SampleFormat,
    consumer: Consumer<f32>,
    healthy: Arc<AtomicBool>,
    reset_requested: Arc<AtomicBool>,
) -> Result<Stream> {
    match format {
        SampleFormat::F32 => {
            build_typed_stream::<f32>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::F64 => {
            build_typed_stream::<f64>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::I16 => {
            build_typed_stream::<i16>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::I32 => {
            build_typed_stream::<i32>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::I64 => {
            build_typed_stream::<i64>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::I8 => {
            build_typed_stream::<i8>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::U16 => {
            build_typed_stream::<u16>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::U32 => {
            build_typed_stream::<u32>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::U64 => {
            build_typed_stream::<u64>(device, config, consumer, healthy, reset_requested)
        }
        SampleFormat::U8 => {
            build_typed_stream::<u8>(device, config, consumer, healthy, reset_requested)
        }
        other => bail!("output sample format {other} is not supported by this client"),
    }
}

fn build_typed_stream<T>(
    device: &Device,
    config: StreamConfig,
    consumer: Consumer<f32>,
    healthy: Arc<AtomicBool>,
    reset_requested: Arc<AtomicBool>,
) -> Result<Stream>
where
    T: SizedSample + Sample + FromSample<f32>,
{
    let channels = config.channels as usize;
    let output_rate = config.sample_rate;
    let mut puller = RealtimePuller::new(consumer, output_rate, reset_requested);
    let error_health = Arc::clone(&healthy);
    device
        .build_output_stream(
            config,
            move |output: &mut [T], _| {
                for frame in output.chunks_mut(channels) {
                    let value = T::from_sample(puller.next_sample());
                    for sample in frame {
                        *sample = value;
                    }
                }
            },
            move |error| {
                error_health.store(false, Ordering::Release);
                eprintln!("audio stream error: {error}");
            },
            None,
        )
        .context("build output stream")
}

struct RealtimePuller {
    consumer: Consumer<f32>,
    source_per_output: f64,
    phase: f64,
    current: f32,
    next: f32,
    initialized: bool,
    reset_requested: Arc<AtomicBool>,
}

impl RealtimePuller {
    fn new(consumer: Consumer<f32>, output_rate: u32, reset_requested: Arc<AtomicBool>) -> Self {
        Self {
            consumer,
            source_per_output: SAMPLE_RATE as f64 / output_rate.max(1) as f64,
            phase: 0.0,
            current: 0.0,
            next: 0.0,
            initialized: false,
            reset_requested,
        }
    }

    fn next_sample(&mut self) -> f32 {
        if self.reset_requested.swap(false, Ordering::AcqRel) {
            while self.consumer.pop().is_ok() {}
            self.phase = 0.0;
            self.current = 0.0;
            self.next = 0.0;
            self.initialized = false;
            return 0.0;
        }
        if !self.initialized {
            self.current = self.consumer.pop().unwrap_or(0.0);
            self.next = self.consumer.pop().unwrap_or(0.0);
            self.initialized = true;
        }
        let output = self.current + (self.next - self.current) * self.phase as f32;
        self.phase += self.source_per_output;
        while self.phase >= 1.0 {
            self.phase -= 1.0;
            self.current = self.next;
            self.next = self.consumer.pop().unwrap_or(0.0);
        }
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn realtime_puller_resamples_without_allocation() {
        let (mut producer, consumer) = RingBuffer::new(8);
        producer.push(0.0).unwrap();
        producer.push(1.0).unwrap();
        producer.push(0.0).unwrap();
        let mut puller = RealtimePuller::new(consumer, 96_000, Arc::new(AtomicBool::new(false)));
        assert_eq!(puller.next_sample(), 0.0);
        assert!((puller.next_sample() - 0.5).abs() < 0.001);
        assert!((puller.next_sample() - 1.0).abs() < 0.001);
    }

    #[test]
    fn realtime_puller_flushes_stale_samples_after_overflow() {
        let (mut producer, consumer) = RingBuffer::new(4);
        producer.push(0.8).unwrap();
        producer.push(0.8).unwrap();
        let reset = Arc::new(AtomicBool::new(true));
        let mut puller = RealtimePuller::new(consumer, SAMPLE_RATE, reset);
        assert_eq!(puller.next_sample(), 0.0);
        assert_eq!(puller.next_sample(), 0.0);
    }
}
