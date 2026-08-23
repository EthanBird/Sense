#[cfg(target_os = "windows")]
use crate::audio::list_output_devices;
use anyhow::{anyhow, Context, Result};
#[cfg(target_os = "windows")]
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
#[cfg(target_os = "windows")]
use cpal::{FromSample, Sample, SampleFormat, SizedSample, Stream, StreamConfig};
#[cfg(target_os = "windows")]
use std::f32::consts::TAU;
use std::path::Path;
#[cfg(target_os = "windows")]
use std::path::PathBuf;
use std::process::{Command, Output};
#[cfg(target_os = "windows")]
use std::sync::{Arc, Mutex};
#[cfg(target_os = "windows")]
use std::thread;
#[cfg(target_os = "windows")]
use std::time::Duration;
#[cfg(target_os = "windows")]
use windows_sys::Win32::Globalization::{
    GetACP, GetOEMCP, MultiByteToWideChar, MB_ERR_INVALID_CHARS,
};
#[cfg(target_os = "windows")]
use windows_sys::Win32::System::Console::GetConsoleOutputCP;

pub const WINDOWS_PLAYBACK_NAME: &str = "Sense Mic Playback";
pub const WINDOWS_CAPTURE_NAME: &str = "Sense Mic";
pub const LINUX_SINK_NAME: &str = "sense_mic";
pub const LINUX_SOURCE_NAME: &str = "sense_mic.monitor";

#[cfg(target_os = "windows")]
const VB_CABLE_SETUP: &str = "VBCABLE_Setup_x64.exe";

#[cfg(target_os = "windows")]
#[derive(Clone, Debug, PartialEq, Eq)]
enum WindowsDriverPackage {
    VbCable(PathBuf),
    SenseMicInf(PathBuf),
}

#[derive(Clone, Debug)]
pub struct DriverStatus {
    pub platform: &'static str,
    pub installed: bool,
    pub playback_endpoint: Option<String>,
    pub capture_endpoint: Option<String>,
    pub detail: String,
}

pub fn status() -> Result<DriverStatus> {
    #[cfg(target_os = "windows")]
    {
        let outputs = list_output_devices().unwrap_or_default();
        let captures = windows_capture_endpoints();
        let (playback, capture, detail, installed) = select_windows_backend(&outputs, &captures);
        Ok(DriverStatus {
            platform: "windows",
            installed,
            playback_endpoint: playback,
            capture_endpoint: capture,
            detail,
        })
    }
    #[cfg(target_os = "linux")]
    {
        let modules = pactl(&["list", "short", "modules"])?;
        let module_text = String::from_utf8_lossy(&modules.stdout);
        let sink_loaded = module_text
            .lines()
            .any(|line| line.contains("module-null-sink") && line.contains("sink_name=sense_mic"));
        let sources = pactl(&["list", "short", "sources"])?;
        let source_ready = String::from_utf8_lossy(&sources.stdout)
            .lines()
            .any(|line| line.split_whitespace().nth(1) == Some(LINUX_SOURCE_NAME));
        let installed = sink_loaded && source_ready;
        Ok(DriverStatus {
            platform: "linux",
            installed,
            playback_endpoint: installed.then(|| LINUX_SINK_NAME.to_owned()),
            capture_endpoint: installed.then(|| LINUX_SOURCE_NAME.to_owned()),
            detail: "PipeWire/PulseAudio null-sink monitor source".to_owned(),
        })
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        Ok(DriverStatus {
            platform: std::env::consts::OS,
            installed: false,
            playback_endpoint: None,
            capture_endpoint: None,
            detail: "platform integration is not configured".to_owned(),
        })
    }
}

pub fn install(package_hint: Option<&Path>) -> Result<String> {
    #[cfg(target_os = "windows")]
    {
        if status().is_ok_and(|value| value.installed) {
            return Ok("Windows virtual microphone is already ready".to_owned());
        }
        let package = resolve_windows_package(package_hint)?;
        let (output, description) = match &package {
            WindowsDriverPackage::VbCable(setup) => (
                Command::new(setup)
                    .current_dir(setup.parent().unwrap_or_else(|| Path::new(".")))
                    .args(["-i", "-h"])
                    .output()
                    .with_context(|| format!("launch {}", setup.display()))?,
                format!("VB-CABLE from {}", setup.display()),
            ),
            WindowsDriverPackage::SenseMicInf(inf) => (
                Command::new("pnputil.exe")
                    .args(["/add-driver", &inf.to_string_lossy(), "/install"])
                    .output()
                    .context("launch pnputil.exe")?,
                format!("SenseMicVAD from {}", inf.display()),
            ),
        };
        ensure_success(output, &format!("install {description}"))?;
        for _ in 0..60 {
            if status().is_ok_and(|value| value.installed) {
                return Ok(format!("installed {description}"));
            }
            thread::sleep(Duration::from_millis(500));
        }
        anyhow::bail!(
            "the installer completed but the playback/capture endpoint pair is not ready; restart Windows to finalize the audio driver"
        )
    }
    #[cfg(target_os = "linux")]
    {
        let _ = package_hint;
        if status()?.installed {
            return Ok("Sense Mic virtual source is already loaded".to_owned());
        }
        let output = pactl(&[
            "load-module",
            "module-null-sink",
            "sink_name=sense_mic",
            "sink_properties=device.description=Sense_Mic_Playback",
            "rate=48000",
            "channels=1",
            "channel_map=mono",
        ])?;
        ensure_success(output, "load Sense Mic null sink")?;
        let _ = pactl(&[
            "update-source-proplist",
            LINUX_SOURCE_NAME,
            "device.description=Sense_Mic",
        ]);
        let _ = pactl(&["set-default-source", LINUX_SOURCE_NAME]);
        Ok("loaded sense_mic sink and selected sense_mic.monitor as default source".to_owned())
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        anyhow::bail!(
            "driver installation is not implemented for {}",
            std::env::consts::OS
        )
    }
}

pub fn uninstall() -> Result<String> {
    #[cfg(target_os = "windows")]
    {
        let script = r#"
$drivers = Get-CimInstance Win32_PnPSignedDriver |
  Where-Object { $_.DeviceName -like 'Sense Mic*' -or $_.DriverProviderName -eq 'Sense Project' } |
  Select-Object -ExpandProperty InfName -Unique
$failure = 0
foreach ($driver in $drivers) {
  pnputil.exe /delete-driver $driver /uninstall /force
  if ($LASTEXITCODE -ne 0) { $failure = $LASTEXITCODE }
}
if (-not $drivers) { Write-Output 'No Sense Mic driver package is active.' }
if ($failure -ne 0) { exit $failure }
"#;
        let output = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .output()
            .context("launch PowerShell driver removal")?;
        ensure_success(output, "remove Sense Mic Windows driver")?;
        Ok("removed active Sense Mic Windows driver packages".to_owned())
    }
    #[cfg(target_os = "linux")]
    {
        let modules = pactl(&["list", "short", "modules"])?;
        let text = String::from_utf8_lossy(&modules.stdout);
        let ids: Vec<&str> = text
            .lines()
            .filter(|line| {
                line.contains("module-null-sink") && line.contains("sink_name=sense_mic")
            })
            .filter_map(|line| line.split_whitespace().next())
            .collect();
        for id in &ids {
            ensure_success(pactl(&["unload-module", id])?, "unload Sense Mic null sink")?;
        }
        Ok(format!("unloaded {} Sense Mic module(s)", ids.len()))
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        anyhow::bail!(
            "driver removal is not implemented for {}",
            std::env::consts::OS
        )
    }
}

pub fn ensure_virtual_output() -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        if !status()?.installed {
            install(None)?;
        }
    }
    Ok(())
}

pub fn verify_audio_loopback() -> Result<String> {
    #[cfg(target_os = "windows")]
    {
        let state = status()?;
        if !state.installed {
            anyhow::bail!("Windows virtual playback/capture endpoint pair is not ready");
        }
        let playback_name = state
            .playback_endpoint
            .context("missing playback endpoint")?;
        let capture_name = state.capture_endpoint.context("missing capture endpoint")?;
        let host = cpal::default_host();
        let output = host
            .output_devices()
            .context("enumerate output endpoints")?
            .find(|device| device.to_string() == playback_name)
            .with_context(|| format!("CPAL output endpoint disappeared: {playback_name}"))?;
        let input = host
            .input_devices()
            .context("enumerate input endpoints")?
            .find(|device| device.to_string() == capture_name)
            .with_context(|| format!("CPAL input endpoint disappeared: {capture_name}"))?;
        let output_supported = output
            .default_output_config()
            .context("query playback format")?;
        let input_supported = input
            .default_input_config()
            .context("query capture format")?;
        let output_format = output_supported.sample_format();
        let input_format = input_supported.sample_format();
        let output_config = output_supported.config();
        let input_config = input_supported.config();
        let stats = Arc::new(Mutex::new(LoopbackStats::default()));
        let input_stream =
            build_loopback_input(&input, input_config, input_format, Arc::clone(&stats))?;
        let output_stream = build_loopback_output(&output, output_config, output_format)?;
        input_stream.play().context("start capture test stream")?;
        thread::sleep(Duration::from_millis(250));
        output_stream.play().context("start playback test stream")?;
        thread::sleep(Duration::from_millis(1_500));
        drop(output_stream);
        thread::sleep(Duration::from_millis(150));
        drop(input_stream);
        let stats = stats
            .lock()
            .map_err(|_| anyhow!("capture statistics lock poisoned"))?;
        if stats.samples == 0 {
            anyhow::bail!("virtual capture endpoint returned no samples");
        }
        let rms = (stats.square_sum / stats.samples as f64).sqrt();
        if stats.peak < 0.05 || rms < 0.01 {
            anyhow::bail!(
                "virtual cable captured only silence (samples={}, peak={:.4}, rms={:.4})",
                stats.samples,
                stats.peak,
                rms
            );
        }
        Ok(format!(
            "loopback passed: {playback_name} -> {capture_name}; samples={}, peak={:.4}, rms={:.4}, playback={} Hz/{} ch, capture={} Hz/{} ch",
            stats.samples,
            stats.peak,
            rms,
            output_config.sample_rate,
            output_config.channels,
            input_config.sample_rate,
            input_config.channels
        ))
    }
    #[cfg(not(target_os = "windows"))]
    {
        anyhow::bail!("audio loopback verification is currently implemented on Windows")
    }
}

#[cfg(target_os = "windows")]
#[derive(Default)]
struct LoopbackStats {
    samples: u64,
    square_sum: f64,
    peak: f32,
}

#[cfg(target_os = "windows")]
fn build_loopback_input(
    device: &cpal::Device,
    config: StreamConfig,
    format: SampleFormat,
    stats: Arc<Mutex<LoopbackStats>>,
) -> Result<Stream> {
    match format {
        SampleFormat::F32 => build_typed_loopback_input::<f32>(device, config, stats),
        SampleFormat::F64 => build_typed_loopback_input::<f64>(device, config, stats),
        SampleFormat::I16 => build_typed_loopback_input::<i16>(device, config, stats),
        SampleFormat::I32 => build_typed_loopback_input::<i32>(device, config, stats),
        SampleFormat::I64 => build_typed_loopback_input::<i64>(device, config, stats),
        SampleFormat::I8 => build_typed_loopback_input::<i8>(device, config, stats),
        SampleFormat::U16 => build_typed_loopback_input::<u16>(device, config, stats),
        SampleFormat::U32 => build_typed_loopback_input::<u32>(device, config, stats),
        SampleFormat::U64 => build_typed_loopback_input::<u64>(device, config, stats),
        SampleFormat::U8 => build_typed_loopback_input::<u8>(device, config, stats),
        other => anyhow::bail!("capture sample format {other} is not supported by the verifier"),
    }
}

#[cfg(target_os = "windows")]
fn build_typed_loopback_input<T>(
    device: &cpal::Device,
    config: StreamConfig,
    stats: Arc<Mutex<LoopbackStats>>,
) -> Result<Stream>
where
    T: SizedSample + Sample,
    f32: FromSample<T>,
{
    device
        .build_input_stream(
            config,
            move |input: &[T], _| {
                if let Ok(mut stats) = stats.try_lock() {
                    for &sample in input {
                        let value = f32::from_sample(sample);
                        stats.samples += 1;
                        stats.square_sum += f64::from(value) * f64::from(value);
                        stats.peak = stats.peak.max(value.abs());
                    }
                }
            },
            move |error| eprintln!("loopback capture error: {error}"),
            None,
        )
        .context("build capture test stream")
}

#[cfg(target_os = "windows")]
fn build_loopback_output(
    device: &cpal::Device,
    config: StreamConfig,
    format: SampleFormat,
) -> Result<Stream> {
    match format {
        SampleFormat::F32 => build_typed_loopback_output::<f32>(device, config),
        SampleFormat::F64 => build_typed_loopback_output::<f64>(device, config),
        SampleFormat::I16 => build_typed_loopback_output::<i16>(device, config),
        SampleFormat::I32 => build_typed_loopback_output::<i32>(device, config),
        SampleFormat::I64 => build_typed_loopback_output::<i64>(device, config),
        SampleFormat::I8 => build_typed_loopback_output::<i8>(device, config),
        SampleFormat::U16 => build_typed_loopback_output::<u16>(device, config),
        SampleFormat::U32 => build_typed_loopback_output::<u32>(device, config),
        SampleFormat::U64 => build_typed_loopback_output::<u64>(device, config),
        SampleFormat::U8 => build_typed_loopback_output::<u8>(device, config),
        other => anyhow::bail!("playback sample format {other} is not supported by the verifier"),
    }
}

#[cfg(target_os = "windows")]
fn build_typed_loopback_output<T>(device: &cpal::Device, config: StreamConfig) -> Result<Stream>
where
    T: SizedSample + Sample + FromSample<f32>,
{
    let channels = usize::from(config.channels.max(1));
    let phase_step = 997.0 * TAU / config.sample_rate.max(1) as f32;
    let mut phase = 0.0f32;
    device
        .build_output_stream(
            config,
            move |output: &mut [T], _| {
                for frame in output.chunks_mut(channels) {
                    let value = (phase.sin() * 0.25).clamp(-1.0, 1.0);
                    phase = (phase + phase_step) % TAU;
                    for sample in frame {
                        *sample = T::from_sample(value);
                    }
                }
            },
            move |error| eprintln!("loopback playback error: {error}"),
            None,
        )
        .context("build playback test stream")
}

#[cfg(target_os = "windows")]
fn windows_capture_endpoints() -> Vec<String> {
    let script = r#"
$endpoints = Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue |
  Where-Object { $_.FriendlyName -eq 'Sense Mic' -or $_.FriendlyName -like 'CABLE Output*' } |
  Select-Object -ExpandProperty FriendlyName -Unique
if (-not $endpoints) {
  $endpoints = Get-CimInstance Win32_PnPEntity |
    Where-Object { $_.Name -eq 'Sense Mic' -or $_.Name -like 'CABLE Output*' } |
    Select-Object -ExpandProperty Name -Unique
}
if ($endpoints) { $endpoints | ForEach-Object { Write-Output $_ } }
"#;
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output()
        .ok();
    let Some(output) = output else {
        return Vec::new();
    };
    if !output.status.success() {
        return Vec::new();
    }
    decode_command_output(&output.stdout)
        .lines()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect()
}

#[cfg(target_os = "windows")]
fn select_windows_backend(
    outputs: &[String],
    captures: &[String],
) -> (Option<String>, Option<String>, String, bool) {
    let find = |values: &[String], needle: &str| {
        values
            .iter()
            .find(|name| name.to_ascii_lowercase().contains(needle))
            .cloned()
    };
    let sense_playback = find(outputs, "sense mic playback");
    let sense_capture = find(captures, "sense mic");
    if sense_playback.is_some() && sense_capture.is_some() {
        return (
            sense_playback,
            sense_capture,
            "SenseMicVAD WaveRT virtual cable".to_owned(),
            true,
        );
    }
    let cable_playback = find(outputs, "cable input");
    let cable_capture = find(captures, "cable output");
    if cable_playback.is_some() && cable_capture.is_some() {
        return (
            cable_playback,
            cable_capture,
            "VB-CABLE by VB-Audio Software (Microsoft WHQL signed)".to_owned(),
            true,
        );
    }
    (
        sense_playback.or(cable_playback),
        sense_capture.or(cable_capture),
        "Windows virtual audio endpoint pair is incomplete".to_owned(),
        false,
    )
}

#[cfg(target_os = "windows")]
fn resolve_windows_package(package_hint: Option<&Path>) -> Result<WindowsDriverPackage> {
    if let Some(hint) = package_hint {
        return package_from_path(hint).with_context(|| {
            format!(
                "Windows virtual microphone package was not found at {}",
                hint.display()
            )
        });
    }
    let executable_root = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf));
    let source_root = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let candidates = [
        executable_root
            .as_ref()
            .map(|root| root.join("driver").join("vb-cable")),
        executable_root
            .as_ref()
            .map(|root| root.join("driver").join("windows").join("x64")),
        Some(source_root.join("driver").join("vb-cable")),
        Some(source_root.join("driver").join("windows").join("x64")),
    ];
    for candidate in candidates.into_iter().flatten() {
        if let Ok(package) = package_from_path(&candidate) {
            return Ok(package);
        }
    }
    anyhow::bail!(
        "bundled VB-CABLE package was not found beside sense-mic.exe under driver\\vb-cable"
    )
}

#[cfg(target_os = "windows")]
fn package_from_path(path: &Path) -> Result<WindowsDriverPackage> {
    if path.is_file() {
        return match path.file_name().and_then(|name| name.to_str()) {
            Some(name) if name.eq_ignore_ascii_case(VB_CABLE_SETUP) => {
                Ok(WindowsDriverPackage::VbCable(path.to_path_buf()))
            }
            Some(name) if name.eq_ignore_ascii_case("SenseMicVAD.inf") => {
                Ok(WindowsDriverPackage::SenseMicInf(path.to_path_buf()))
            }
            _ => anyhow::bail!("unsupported driver package entry {}", path.display()),
        };
    }
    let vb_cable = path.join(VB_CABLE_SETUP);
    if vb_cable.is_file() {
        return Ok(WindowsDriverPackage::VbCable(vb_cable));
    }
    let sense_inf = path.join("SenseMicVAD.inf");
    if sense_inf.is_file() {
        return Ok(WindowsDriverPackage::SenseMicInf(sense_inf));
    }
    anyhow::bail!(
        "no supported Windows driver installer below {}",
        path.display()
    )
}

#[cfg(target_os = "linux")]
fn pactl(args: &[&str]) -> Result<Output> {
    Command::new("pactl")
        .args(args)
        .output()
        .with_context(|| format!("run pactl {}", args.join(" ")))
}

fn ensure_success(output: Output, action: &str) -> Result<()> {
    if output.status.success() {
        return Ok(());
    }
    let stderr = decode_command_output(&output.stderr).trim().to_owned();
    let stdout = decode_command_output(&output.stdout).trim().to_owned();
    Err(anyhow!(
        "{action} failed: {}",
        if stderr.is_empty() { stdout } else { stderr }
    ))
}

fn decode_command_output(bytes: &[u8]) -> String {
    if let Some(value) = decode_utf16_bom(bytes) {
        return value;
    }
    let bytes = bytes.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(bytes);
    if let Ok(value) = std::str::from_utf8(bytes) {
        return value.to_owned();
    }

    #[cfg(target_os = "windows")]
    {
        // Native Windows utilities such as pnputil write redirected output in the
        // inherited console/OEM code page on some systems and the process ANSI code
        // page on others. Strict decoding lets a UTF-8 console fall through to ACP
        // instead of silently replacing invalid bytes.
        let candidates = unsafe { [GetConsoleOutputCP(), GetOEMCP(), GetACP()] };
        let mut attempted = Vec::with_capacity(candidates.len());
        for code_page in candidates {
            if code_page == 0 || attempted.contains(&code_page) {
                continue;
            }
            attempted.push(code_page);
            if let Some(value) = decode_windows_code_page_strict(bytes, code_page) {
                return value;
            }
        }
    }

    String::from_utf8_lossy(bytes).into_owned()
}

fn decode_utf16_bom(bytes: &[u8]) -> Option<String> {
    let (little_endian, payload) = if let Some(payload) = bytes.strip_prefix(&[0xff, 0xfe]) {
        (true, payload)
    } else {
        (false, bytes.strip_prefix(&[0xfe, 0xff])?)
    };
    if payload.len() % 2 != 0 {
        return None;
    }
    let units = payload
        .chunks_exact(2)
        .map(|pair| {
            if little_endian {
                u16::from_le_bytes([pair[0], pair[1]])
            } else {
                u16::from_be_bytes([pair[0], pair[1]])
            }
        })
        .collect::<Vec<_>>();
    String::from_utf16(&units).ok()
}

#[cfg(all(target_os = "windows", test))]
fn decode_windows_code_page(bytes: &[u8], code_page: u32) -> Option<String> {
    decode_windows_code_page_with_flags(bytes, code_page, 0)
}

#[cfg(target_os = "windows")]
fn decode_windows_code_page_strict(bytes: &[u8], code_page: u32) -> Option<String> {
    decode_windows_code_page_with_flags(bytes, code_page, MB_ERR_INVALID_CHARS)
}

#[cfg(target_os = "windows")]
fn decode_windows_code_page_with_flags(bytes: &[u8], code_page: u32, flags: u32) -> Option<String> {
    if bytes.is_empty() {
        return Some(String::new());
    }
    let byte_count = i32::try_from(bytes.len()).ok()?;
    let wide_count = unsafe {
        MultiByteToWideChar(
            code_page,
            flags,
            bytes.as_ptr(),
            byte_count,
            std::ptr::null_mut(),
            0,
        )
    };
    if wide_count <= 0 {
        return None;
    }

    let mut wide = vec![0u16; wide_count as usize];
    let converted = unsafe {
        MultiByteToWideChar(
            code_page,
            flags,
            bytes.as_ptr(),
            byte_count,
            wide.as_mut_ptr(),
            wide_count,
        )
    };
    if converted <= 0 {
        return None;
    }
    wide.truncate(converted as usize);
    Some(String::from_utf16_lossy(&wide))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(target_os = "windows")]
    use std::os::windows::process::ExitStatusExt;

    #[cfg(target_os = "windows")]
    #[test]
    fn decodes_cp936_driver_tool_output() {
        let cp936 = [
            0xc7, 0xfd, 0xb6, 0xaf, 0xb3, 0xcc, 0xd0, 0xf2, 0xb0, 0xb2, 0xd7, 0xb0, 0xb3, 0xc9,
            0xb9, 0xa6,
        ];
        assert_eq!(
            decode_windows_code_page(&cp936, 936).as_deref(),
            Some("驱动程序安装成功")
        );
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn driver_command_error_preserves_current_code_page_text() {
        let current_code_page = unsafe { GetACP() };
        let fixture = if current_code_page == 936 {
            vec![
                0xc7, 0xfd, 0xb6, 0xaf, 0xb3, 0xcc, 0xd0, 0xf2, 0xb0, 0xb2, 0xd7, 0xb0, 0xca, 0xa7,
                0xb0, 0xdc,
            ]
        } else {
            b"driver installation failed".to_vec()
        };
        let expected = decode_windows_code_page(&fixture, current_code_page).unwrap();
        let output = Output {
            status: std::process::ExitStatus::from_raw(1),
            stdout: Vec::new(),
            stderr: fixture,
        };

        let message = ensure_success(output, "install test driver")
            .unwrap_err()
            .to_string();
        assert!(message.contains(expected.trim()), "{message}");
    }

    #[test]
    fn keeps_utf8_command_output_unchanged() {
        assert_eq!(decode_command_output("驱动已就绪".as_bytes()), "驱动已就绪");
    }

    #[test]
    fn decodes_utf16_command_output_boms() {
        let little = [0xff, 0xfe, 0x71, 0x9a, 0xa8, 0x52];
        let big = [0xfe, 0xff, 0x9a, 0x71, 0x52, 0xa8];
        assert_eq!(decode_command_output(&little), "驱动");
        assert_eq!(decode_command_output(&big), "驱动");
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn selects_only_a_coherent_windows_endpoint_pair() {
        let outputs = vec!["Sense Mic Playback".to_owned()];
        let captures = vec!["CABLE Output (VB-Audio Virtual Cable)".to_owned()];
        let (playback, capture, detail, installed) = select_windows_backend(&outputs, &captures);
        assert!(playback.is_some());
        assert!(capture.is_some());
        assert!(detail.contains("incomplete"));
        assert!(!installed);

        let outputs = vec!["CABLE Input (VB-Audio Virtual Cable)".to_owned()];
        let (playback, capture, detail, installed) = select_windows_backend(&outputs, &captures);
        assert_eq!(
            playback.as_deref(),
            Some("CABLE Input (VB-Audio Virtual Cable)")
        );
        assert_eq!(
            capture.as_deref(),
            Some("CABLE Output (VB-Audio Virtual Cable)")
        );
        assert!(detail.contains("VB-CABLE"));
        assert!(installed);
    }
}
