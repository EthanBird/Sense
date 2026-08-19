use crate::protocol::{AudioHeader, AudioKind, FEC_GROUP_SIZE, FRAME_MILLIS, SAMPLE_RATE};
use std::collections::BTreeMap;
use std::time::Instant;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PlayoutFrame {
    Packet(Vec<u8>),
    Recovered(Vec<u8>),
    Missing,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct JitterStats {
    pub received: u64,
    pub duplicate_or_late: u64,
    pub recovered: u64,
    pub lost: u64,
    pub jitter_millis: u16,
}

#[derive(Clone, Debug)]
struct FecGroup {
    size: u8,
    parity: Vec<u8>,
}

/// Adaptive packet buffer with a short encoded-packet history for XOR recovery.
pub struct JitterBuffer {
    frames: BTreeMap<u32, Vec<u8>>,
    history: BTreeMap<u32, Vec<u8>>,
    fec: BTreeMap<u32, FecGroup>,
    expected: Option<u32>,
    first_sequence: Option<u32>,
    first_timestamp: Option<u64>,
    first_arrival: Option<Instant>,
    previous_transit_samples: Option<f64>,
    jitter_samples: f64,
    target_frames: usize,
    base_frames: usize,
    primed: bool,
    stats: JitterStats,
}

impl JitterBuffer {
    pub fn new(base_latency_millis: u32) -> Self {
        let base = base_latency_millis.clamp(80, 240) as usize;
        let base_frames = base.div_ceil(FRAME_MILLIS as usize);
        Self {
            frames: BTreeMap::new(),
            history: BTreeMap::new(),
            fec: BTreeMap::new(),
            expected: None,
            first_sequence: None,
            first_timestamp: None,
            first_arrival: None,
            previous_transit_samples: None,
            jitter_samples: 0.0,
            target_frames: base_frames,
            base_frames,
            primed: false,
            stats: JitterStats::default(),
        }
    }

    pub fn insert(&mut self, header: AudioHeader, payload: Vec<u8>, arrival: Instant) {
        if header.kind == AudioKind::XorFec {
            if header.fec_group_size == 0 || header.fec_group_size > FEC_GROUP_SIZE {
                return;
            }
            // The parity header carries the group's true first sequence. If the first audio
            // datagram was lost, use it to rewind startup before playout is primed so frame zero
            // can still be reconstructed after the next packet supplies the target depth.
            if !self.primed
                && self
                    .expected
                    .is_none_or(|expected| header.frame_sequence < expected)
            {
                self.expected = Some(header.frame_sequence);
                self.first_sequence = Some(header.frame_sequence);
            }
            self.fec.entry(header.frame_sequence).or_insert(FecGroup {
                size: header.fec_group_size,
                parity: payload,
            });
            return;
        }
        if self.is_late(header.frame_sequence) || self.frames.contains_key(&header.frame_sequence) {
            self.stats.duplicate_or_late += 1;
            return;
        }
        self.observe_jitter(header.timestamp_samples, arrival);
        self.stats.received += 1;
        self.first_sequence.get_or_insert(header.frame_sequence);
        self.expected.get_or_insert(header.frame_sequence);
        self.frames.insert(header.frame_sequence, payload);
    }

    pub fn pop(&mut self) -> Option<PlayoutFrame> {
        let expected = self.expected?;
        if !self.primed {
            let depth = self
                .frames
                .keys()
                .filter(|&&sequence| sequence >= expected)
                .count();
            if depth < self.target_frames {
                return None;
            }
            self.primed = true;
        }

        let result = if let Some(packet) = self.frames.remove(&expected) {
            self.remember(expected, packet.clone());
            PlayoutFrame::Packet(packet)
        } else if let Some(packet) = self.recover(expected) {
            self.stats.recovered += 1;
            self.remember(expected, packet.clone());
            PlayoutFrame::Recovered(packet)
        } else {
            self.stats.lost += 1;
            PlayoutFrame::Missing
        };
        self.expected = Some(expected.wrapping_add(1));
        self.cleanup(expected);
        Some(result)
    }

    pub fn stats(&self) -> JitterStats {
        self.stats
    }

    pub fn buffered_frames(&self) -> usize {
        self.frames.len()
    }

    fn observe_jitter(&mut self, timestamp_samples: u64, arrival: Instant) {
        let first_arrival = *self.first_arrival.get_or_insert(arrival);
        let first_timestamp = *self.first_timestamp.get_or_insert(timestamp_samples);
        let arrival_samples =
            arrival.duration_since(first_arrival).as_secs_f64() * SAMPLE_RATE as f64;
        let source_samples = timestamp_samples.saturating_sub(first_timestamp) as f64;
        let transit = arrival_samples - source_samples;
        if let Some(previous) = self.previous_transit_samples {
            let delta = (transit - previous).abs();
            self.jitter_samples += (delta - self.jitter_samples) / 16.0;
        }
        self.previous_transit_samples = Some(transit);
        let jitter_ms = (self.jitter_samples * 1000.0 / SAMPLE_RATE as f64).round();
        self.stats.jitter_millis = jitter_ms.clamp(0.0, u16::MAX as f64) as u16;
        let adaptive_millis = 40usize.saturating_add(self.stats.jitter_millis as usize * 4);
        let adaptive_frames = adaptive_millis
            .div_ceil(FRAME_MILLIS as usize)
            .clamp(self.base_frames, 12);
        self.target_frames = adaptive_frames;
    }

    fn recover(&self, sequence: u32) -> Option<Vec<u8>> {
        let (&start, group) = self
            .fec
            .range(..=sequence)
            .next_back()
            .filter(|(start, group)| sequence.wrapping_sub(**start) < group.size as u32)?;
        if group.size == 0 || group.parity.is_empty() {
            return None;
        }
        let mut recovered = group.parity.clone();
        let mut missing = 0;
        for offset in 0..group.size as u32 {
            let current = start.wrapping_add(offset);
            if current == sequence {
                missing += 1;
                continue;
            }
            let packet = self
                .frames
                .get(&current)
                .or_else(|| self.history.get(&current))?;
            if packet.len() != recovered.len() {
                return None;
            }
            for (target, value) in recovered.iter_mut().zip(packet) {
                *target ^= *value;
            }
        }
        (missing == 1).then_some(recovered)
    }

    fn remember(&mut self, sequence: u32, packet: Vec<u8>) {
        self.history.insert(sequence, packet);
    }

    fn cleanup(&mut self, played: u32) {
        let history_floor = played.saturating_sub(32);
        self.history
            .retain(|sequence, _| *sequence >= history_floor);
        self.fec.retain(|start, group| {
            start.saturating_add(group.size as u32).saturating_add(2) >= played
        });
        self.frames.retain(|sequence, _| *sequence > played);
    }

    fn is_late(&self, sequence: u32) -> bool {
        self.expected
            .is_some_and(|expected| sequence < expected && expected - sequence < u32::MAX / 2)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(kind: AudioKind, sequence: u32, fec: u8) -> AudioHeader {
        AudioHeader {
            kind,
            session_id: 1,
            packet_counter: sequence as u64,
            frame_sequence: sequence,
            timestamp_samples: sequence as u64 * 960,
            payload_length: 3,
            fec_group_size: fec,
        }
    }

    #[test]
    fn waits_for_target_depth_then_plays_in_order() {
        let mut jitter = JitterBuffer::new(80);
        let now = Instant::now();
        for sequence in 10..14 {
            jitter.insert(
                header(AudioKind::Audio, sequence, 0),
                vec![sequence as u8; 3],
                now,
            );
        }
        assert_eq!(jitter.pop(), Some(PlayoutFrame::Packet(vec![10; 3])));
        assert_eq!(jitter.pop(), Some(PlayoutFrame::Packet(vec![11; 3])));
    }

    #[test]
    fn recovers_one_missing_packet_with_history_and_parity() {
        let mut jitter = JitterBuffer::new(80);
        let now = Instant::now();
        jitter.insert(header(AudioKind::Audio, 0, 0), vec![1, 2, 3], now);
        jitter.insert(header(AudioKind::Audio, 2, 0), vec![7, 8, 9], now);
        jitter.insert(header(AudioKind::Audio, 3, 0), vec![10, 11, 12], now);
        let parity: Vec<u8> = [1 ^ 4 ^ 7 ^ 10, 2 ^ 5 ^ 8 ^ 11, 3 ^ 6 ^ 9 ^ 12].to_vec();
        jitter.insert(header(AudioKind::XorFec, 0, 4), parity, now);
        // Four buffered audio frames are required, so add the next group's first frame.
        jitter.insert(header(AudioKind::Audio, 4, 0), vec![13, 14, 15], now);
        assert_eq!(jitter.pop(), Some(PlayoutFrame::Packet(vec![1, 2, 3])));
        assert_eq!(jitter.pop(), Some(PlayoutFrame::Recovered(vec![4, 5, 6])));
        assert_eq!(jitter.stats().recovered, 1);
    }

    #[test]
    fn fec_header_recovers_a_missing_first_packet_before_startup() {
        let mut jitter = JitterBuffer::new(80);
        let now = Instant::now();
        jitter.insert(header(AudioKind::Audio, 1, 0), vec![4, 5, 6], now);
        jitter.insert(header(AudioKind::Audio, 2, 0), vec![7, 8, 9], now);
        jitter.insert(header(AudioKind::Audio, 3, 0), vec![10, 11, 12], now);
        let parity: Vec<u8> = [1 ^ 4 ^ 7 ^ 10, 2 ^ 5 ^ 8 ^ 11, 3 ^ 6 ^ 9 ^ 12].to_vec();
        jitter.insert(header(AudioKind::XorFec, 0, 4), parity, now);
        jitter.insert(header(AudioKind::Audio, 4, 0), vec![13, 14, 15], now);

        assert_eq!(jitter.pop(), Some(PlayoutFrame::Recovered(vec![1, 2, 3])));
        assert_eq!(jitter.pop(), Some(PlayoutFrame::Packet(vec![4, 5, 6])));
    }
}
