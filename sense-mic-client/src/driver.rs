#[cfg(target_os = "windows")]
use crate::audio::list_output_devices;
use anyhow::{anyhow, Context, Result};
use std::path::Path;
#[cfg(target_os = "windows")]
use std::path::PathBuf;
use std::process::{Command, Output};
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
        let playback = outputs.into_iter().find(|name| {
            let name = name.to_ascii_lowercase();
            name.contains("sense mic playback") || name.contains("cable input")
        });
        let capture = windows_capture_endpoint();
        Ok(DriverStatus {
            platform: "windows",
            installed: playback.is_some() && capture.is_some(),
            playback_endpoint: playback,
            capture_endpoint: capture,
            detail: "WaveRT render-to-capture virtual cable".to_owned(),
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
        let inf = package_hint
            .map(Path::to_path_buf)
            .unwrap_or_else(default_windows_inf_path);
        let inf = if inf.is_dir() {
            inf.join("SenseMicVAD.inf")
        } else {
            inf
        };
        if !inf.is_file() {
            anyhow::bail!("driver package was not found at {}", inf.display());
        }
        let output = Command::new("pnputil.exe")
            .args(["/add-driver", &inf.to_string_lossy(), "/install"])
            .output()
            .context("launch pnputil.exe")?;
        ensure_success(output, "install Sense Mic Windows driver")?;
        for _ in 0..20 {
            if status().is_ok_and(|value| value.installed) {
                return Ok(format!("installed Windows driver from {}", inf.display()));
            }
            thread::sleep(Duration::from_millis(250));
        }
        anyhow::bail!("pnputil completed but the Sense Mic endpoints did not become ready")
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

#[cfg(target_os = "windows")]
fn windows_capture_endpoint() -> Option<String> {
    let script = r#"
$endpoint = Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue |
  Where-Object { $_.FriendlyName -eq 'Sense Mic' -or $_.FriendlyName -like 'CABLE Output*' } |
  Select-Object -First 1 -ExpandProperty FriendlyName
if (-not $endpoint) {
  $endpoint = Get-CimInstance Win32_PnPEntity |
    Where-Object { $_.Name -eq 'Sense Mic' -or $_.Name -like 'CABLE Output*' } |
    Select-Object -First 1 -ExpandProperty Name
}
if ($endpoint) { Write-Output $endpoint }
"#;
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = decode_command_output(&output.stdout).trim().to_owned();
    (!value.is_empty()).then_some(value)
}

#[cfg(target_os = "windows")]
fn default_windows_inf_path() -> PathBuf {
    let beside_executable = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .map(|path| {
            path.join("driver")
                .join("windows")
                .join("x64")
                .join("SenseMicVAD.inf")
        });
    if let Some(path) = beside_executable.filter(|path| path.is_file()) {
        return path;
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("driver")
        .join("windows")
        .join("x64")
        .join("SenseMicVAD.inf")
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
}
