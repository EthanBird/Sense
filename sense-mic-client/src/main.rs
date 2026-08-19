use anyhow::{bail, Context, Result};
use clap::{Args, Parser, Subcommand};
use sense_mic_client::audio::list_output_devices;
use sense_mic_client::client::{stream_device, ConnectOptions};
use sense_mic_client::discovery::{discover, DiscoveredDevice};
use sense_mic_client::driver;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[derive(Parser, Debug)]
#[command(
    name = "sense-mic",
    version,
    about = "Sense phone-to-PC virtual microphone client"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Discover Sense phones on the current LAN.
    Discover(DiscoveryArgs),
    /// Connect, decode and continuously feed the virtual microphone.
    Serve(ServeArgs),
    /// List output endpoints visible to the Rust audio backend.
    Devices,
    /// Inspect network, audio endpoint and virtual driver readiness.
    Doctor,
    /// Install, inspect or remove the virtual microphone endpoint.
    Driver {
        #[command(subcommand)]
        command: DriverCommand,
    },
}

#[derive(Args, Debug, Clone)]
struct DiscoveryArgs {
    /// Phone IP or host name; otherwise broadcast on every active IPv4 network.
    #[arg(long)]
    host: Option<String>,
    #[arg(long, default_value_t = 1_500)]
    timeout_ms: u64,
}

#[derive(Args, Debug)]
struct ServeArgs {
    #[command(flatten)]
    discovery: DiscoveryArgs,
    /// Select a stable device id printed by `discover`.
    #[arg(long)]
    device_id: Option<u64>,
    /// Six-digit code shown in Sense settings. SENSE_MIC_CODE is also accepted.
    #[arg(long, env = "SENSE_MIC_CODE", hide_env_values = true)]
    code: Option<String>,
    /// Substring of the virtual playback endpoint, or `default` for testing.
    #[arg(long)]
    output: Option<String>,
    /// Base jitter buffer. 100 ms covers one 4-frame XOR recovery group.
    #[arg(long, default_value_t = 100, value_parser = clap::value_parser!(u32).range(80..=240))]
    latency_ms: u32,
    /// Exit after a broken session instead of rediscovering the phone.
    #[arg(long)]
    once: bool,
}

#[derive(Subcommand, Debug)]
enum DriverCommand {
    Status,
    Install {
        /// Path to SenseMicVAD.inf or its package directory on Windows.
        #[arg(long)]
        package: Option<PathBuf>,
    },
    Uninstall,
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Command::Discover(args) => print_discovery(&args),
        Command::Serve(args) => serve(args),
        Command::Devices => {
            for name in list_output_devices()? {
                println!("{name}");
            }
            Ok(())
        }
        Command::Doctor => doctor(),
        Command::Driver { command } => driver_command(command),
    }
}

fn print_discovery(args: &DiscoveryArgs) -> Result<()> {
    let devices = run_discovery(args)?;
    if devices.is_empty() {
        println!("No Sense Mic phone replied during the discovery window.");
    }
    for device in devices {
        println!(
            "{}\tid={}\taddress={}:{}",
            device.response.device_name,
            device.response.device_id,
            device.source.ip(),
            device.response.control_port,
        );
    }
    Ok(())
}

fn serve(args: ServeArgs) -> Result<()> {
    driver::ensure_virtual_output()?;
    let pair_code = match args.code {
        Some(code) => code,
        None => rpassword::prompt_password("Sense Mic six-digit pairing code: ")?,
    };
    validate_code(&pair_code)?;
    let shutdown = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&shutdown);
    ctrlc::set_handler(move || signal.store(true, Ordering::Release))?;
    let options = ConnectOptions {
        pair_code,
        output_device: args.output,
        latency_millis: args.latency_ms,
        client_name: computer_name(),
    };
    let mut failure_count = 0u32;
    while !shutdown.load(Ordering::Acquire) {
        match select_device(run_discovery(&args.discovery)?, args.device_id) {
            Ok(device) => {
                println!(
                    "Pairing with {} at {}...",
                    device.response.device_name,
                    device.source.ip()
                );
                match stream_device(&device, &options, Arc::clone(&shutdown)) {
                    Ok(()) if shutdown.load(Ordering::Acquire) => break,
                    Ok(()) => failure_count = 0,
                    Err(error) => {
                        failure_count = failure_count.saturating_add(1);
                        eprintln!("session ended: {error:#}");
                    }
                }
            }
            Err(error) => {
                failure_count = failure_count.saturating_add(1);
                eprintln!("discovery: {error:#}");
            }
        }
        if args.once {
            break;
        }
        let delay = 1u64 << failure_count.min(5);
        sleep_until_shutdown(Duration::from_secs(delay), &shutdown);
    }
    Ok(())
}

fn doctor() -> Result<()> {
    let status = driver::status()?;
    println!("platform: {}", status.platform);
    println!(
        "virtual endpoint: {}",
        if status.installed { "ready" } else { "missing" }
    );
    println!(
        "playback: {}",
        status.playback_endpoint.as_deref().unwrap_or("—")
    );
    println!(
        "capture: {}",
        status.capture_endpoint.as_deref().unwrap_or("—")
    );
    println!("backend: {}", status.detail);
    println!("output devices:");
    for device in list_output_devices().unwrap_or_default() {
        println!("  - {device}");
    }
    let devices = discover(Duration::from_millis(800), None).unwrap_or_default();
    println!("phones discovered: {}", devices.len());
    for device in devices {
        println!(
            "  - {} ({})",
            device.response.device_name,
            device.source.ip()
        );
    }
    Ok(())
}

fn driver_command(command: DriverCommand) -> Result<()> {
    match command {
        DriverCommand::Status => {
            let status = driver::status()?;
            println!("{status:#?}");
        }
        DriverCommand::Install { package } => {
            println!("{}", driver::install(package.as_deref())?);
        }
        DriverCommand::Uninstall => println!("{}", driver::uninstall()?),
    }
    Ok(())
}

fn run_discovery(args: &DiscoveryArgs) -> Result<Vec<DiscoveredDevice>> {
    discover(
        Duration::from_millis(args.timeout_ms.clamp(100, 15_000)),
        args.host.as_deref(),
    )
}

fn select_device(
    devices: Vec<DiscoveredDevice>,
    requested: Option<u64>,
) -> Result<DiscoveredDevice> {
    if let Some(requested) = requested {
        return devices
            .into_iter()
            .find(|device| device.response.device_id == requested)
            .with_context(|| format!("Sense Mic device id {requested} was not discovered"));
    }
    match devices.len() {
        0 => bail!("no Sense Mic phone was discovered"),
        1 => Ok(devices.into_iter().next().expect("length checked")),
        _ => bail!("multiple phones replied; select one with --device-id"),
    }
}

fn validate_code(code: &str) -> Result<()> {
    if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
        bail!("pairing code must contain exactly six digits");
    }
    Ok(())
}

fn computer_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Sense Mic Client".to_owned())
        .chars()
        .take(63)
        .collect()
}

fn sleep_until_shutdown(duration: Duration, shutdown: &AtomicBool) {
    let steps = duration.as_millis().div_ceil(100) as usize;
    for _ in 0..steps {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
}
