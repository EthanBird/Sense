#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
fn main() {
    eprintln!("Sense Mic GUI is available on Windows; use sense-mic on this platform.");
}

#[cfg(target_os = "windows")]
mod windows_app {
    use sense_mic_client::driver;
    use slint::{CloseRequestResponse, ComponentHandle, ModelRc, SharedString, VecModel};
    use std::ffi::OsStr;
    use std::io::{BufRead, BufReader, Write};
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::process::CommandExt;
    use std::path::Path;
    use std::process::{Command, Stdio};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use windows_sys::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Threading::{
        GetExitCodeProcess, WaitForSingleObject, INFINITE,
    };
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    slint::include_modules!();

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    #[derive(Clone, Debug)]
    struct Phone {
        name: String,
        label: String,
        host: String,
    }

    #[derive(Default)]
    struct RuntimeState {
        phones: Vec<Phone>,
        child_pid: Option<u32>,
    }

    type SharedState = Arc<Mutex<RuntimeState>>;

    pub fn run() {
        let ui = SenseMicWindow::new().expect("create Sense Mic window");
        let state = Arc::new(Mutex::new(RuntimeState::default()));

        bind_scan(&ui, Arc::clone(&state));
        bind_device_selection(&ui, Arc::clone(&state));
        bind_connection(&ui, Arc::clone(&state));
        bind_driver(&ui);
        bind_copy_endpoint(&ui);
        bind_close(&ui, Arc::clone(&state));

        ui.set_app_version(env!("CARGO_PKG_VERSION").into());

        append_log(&ui, "正在检查音频组件并扫描手机…");
        refresh_driver(ui.as_weak());
        scan(ui.as_weak(), Arc::clone(&state));

        ui.run().expect("run Sense Mic window");
    }

    fn bind_scan(ui: &SenseMicWindow, state: SharedState) {
        let weak = ui.as_weak();
        ui.on_scan(move || scan(weak.clone(), Arc::clone(&state)));
    }

    fn scan(weak: slint::Weak<SenseMicWindow>, state: SharedState) {
        if let Some(ui) = weak.upgrade() {
            if ui.get_scanning() || ui.get_session_running() {
                return;
            }
            ui.set_scanning(true);
            ui.set_connection_title("正在扫描".into());
            ui.set_connection_caption("正在寻找同一局域网中的 Sense 设备…".into());
            ui.set_connection_kind("busy".into());
        }

        thread::spawn(move || {
            let result = cli_command()
                .and_then(|mut command| {
                    command.args(["discover", "--timeout-ms", "1800"]);
                    hidden(&mut command)
                        .output()
                        .map_err(|error| error.to_string())
                })
                .and_then(|output| {
                    if output.status.success() {
                        Ok(parse_discovery(&String::from_utf8_lossy(&output.stdout)))
                    } else {
                        Err(nonempty_error(&output.stdout, &output.stderr))
                    }
                });

            let _ = weak.upgrade_in_event_loop(move |ui| {
                ui.set_scanning(false);
                match result {
                    Ok(phones) if !phones.is_empty() => {
                        let labels = phones
                            .iter()
                            .map(|phone| SharedString::from(phone.label.as_str()))
                            .collect::<Vec<_>>();
                        let first = phones[0].clone();
                        if let Ok(mut runtime) = state.lock() {
                            runtime.phones = phones;
                        }
                        ui.set_devices(ModelRc::new(VecModel::from(labels)));
                        ui.set_selected_device(0);
                        ui.set_host_text(first.host.clone().into());
                        ui.set_phone_name(first.name.into());
                        ui.set_phone_address(format!("{} · 局域网连接", first.host).into());
                        ui.set_connection_title("手机已发现".into());
                        ui.set_connection_caption("输入手机显示的 6 位配对码，然后开始传输".into());
                        ui.set_connection_kind("live".into());
                    }
                    Ok(_) => {
                        if let Ok(mut runtime) = state.lock() {
                            runtime.phones.clear();
                        }
                        ui.set_devices(ModelRc::default());
                        ui.set_phone_name("暂未发现手机".into());
                        ui.set_phone_address("也可以直接填写手机 IP".into());
                        ui.set_connection_title("等待手机".into());
                        ui.set_connection_caption("确认手机端 Sense Mic 服务已经开启".into());
                        ui.set_connection_kind("idle".into());
                    }
                    Err(error) => {
                        if let Ok(mut runtime) = state.lock() {
                            runtime.phones.clear();
                        }
                        ui.set_devices(ModelRc::default());
                        ui.set_phone_name("暂未发现手机".into());
                        ui.set_phone_address("可以直接填写手机 IP 后连接".into());
                        ui.set_connection_title("等待手机".into());
                        ui.set_connection_caption("检查局域网连接后再次扫描".into());
                        ui.set_connection_kind("idle".into());
                        append_log(&ui, &format!("扫描：{error}"));
                    }
                }
            });
        });
    }

    fn bind_device_selection(ui: &SenseMicWindow, state: SharedState) {
        let weak = ui.as_weak();
        ui.on_device_selected(move |index| {
            let Some(ui) = weak.upgrade() else {
                return;
            };
            let phone = state
                .lock()
                .ok()
                .and_then(|runtime| runtime.phones.get(index.max(0) as usize).cloned());
            if let Some(phone) = phone {
                ui.set_host_text(phone.host.clone().into());
                ui.set_phone_name(phone.name.into());
                ui.set_phone_address(format!("{} · 局域网连接", phone.host).into());
            }
        });
    }

    fn bind_connection(ui: &SenseMicWindow, state: SharedState) {
        let connect_weak = ui.as_weak();
        let connect_state = Arc::clone(&state);
        ui.on_connect(move || {
            start_connection(connect_weak.clone(), Arc::clone(&connect_state));
        });

        let stop_weak = ui.as_weak();
        ui.on_stop(move || stop_connection(stop_weak.clone(), Arc::clone(&state)));
    }

    fn start_connection(weak: slint::Weak<SenseMicWindow>, state: SharedState) {
        let Some(ui) = weak.upgrade() else {
            return;
        };
        if state
            .lock()
            .is_ok_and(|runtime| runtime.child_pid.is_some())
        {
            return;
        }

        let code = ui.get_code_text().trim().to_owned();
        if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
            ui.set_connection_title("检查配对码".into());
            ui.set_connection_caption("请输入手机端显示的 6 位数字配对码".into());
            ui.set_connection_kind("error".into());
            return;
        }

        let mut host = ui.get_host_text().trim().to_owned();
        if host.is_empty() {
            let selected = ui.get_selected_device().max(0) as usize;
            host = state
                .lock()
                .ok()
                .and_then(|runtime| runtime.phones.get(selected).map(|phone| phone.host.clone()))
                .unwrap_or_default();
        }
        if host.is_empty() {
            ui.set_connection_title("填写手机 IP".into());
            ui.set_connection_caption("先扫描并选择手机，或者直接填写手机 IP".into());
            ui.set_connection_kind("error".into());
            return;
        }

        let latency = ui.get_latency_ms().clamp(80, 240).to_string();
        ui.set_host_text(host.clone().into());
        ui.set_connecting(true);
        ui.set_connection_title("正在启动".into());
        ui.set_connection_caption(format!("连接 {host} · 缓冲 {latency} ms").into());
        ui.set_connection_kind("busy".into());
        append_log(&ui, &format!("连接 {host}，缓冲 {latency} ms"));
        drop(ui);

        thread::spawn(move || {
            let result = cli_command().and_then(|mut command| {
                command
                    .args(["serve", "--host", &host, "--latency-ms", &latency])
                    .env("SENSE_MIC_CODE", code)
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped());
                hidden(&mut command);
                command.spawn().map_err(|error| error.to_string())
            });

            let mut child = match result {
                Ok(child) => child,
                Err(error) => {
                    connection_ended(&weak, &state, Some(error));
                    return;
                }
            };

            let pid = child.id();
            if let Ok(mut runtime) = state.lock() {
                runtime.child_pid = Some(pid);
            }
            let started_weak = weak.clone();
            let _ = started_weak.upgrade_in_event_loop(move |ui| {
                ui.set_connecting(true);
                ui.set_session_running(true);
                ui.set_connection_title("正在配对".into());
                ui.set_connection_caption("已启动接收服务，等待手机发送音频".into());
                ui.set_connection_kind("busy".into());
            });

            if let Some(stdout) = child.stdout.take() {
                forward_lines(stdout, weak.clone());
            }
            if let Some(stderr) = child.stderr.take() {
                forward_lines(stderr, weak.clone());
            }

            let ended = child
                .wait()
                .map_err(|error| error.to_string())
                .and_then(|status| {
                    if status.success() {
                        Ok(())
                    } else {
                        Err(format!("接收进程已结束（{status}）"))
                    }
                });
            connection_ended(&weak, &state, ended.err());
        });
    }

    fn stop_connection(weak: slint::Weak<SenseMicWindow>, state: SharedState) {
        let pid = state.lock().ok().and_then(|runtime| runtime.child_pid);
        let Some(pid) = pid else {
            return;
        };
        if let Some(ui) = weak.upgrade() {
            ui.set_connection_title("正在停止".into());
            ui.set_connection_caption("正在关闭手机音频传输…".into());
            ui.set_connection_kind("busy".into());
            append_log(&ui, "正在停止音频连接…");
        }
        thread::spawn(move || kill_process_tree(pid));
    }

    fn connection_ended(
        weak: &slint::Weak<SenseMicWindow>,
        state: &SharedState,
        error: Option<String>,
    ) {
        if let Ok(mut runtime) = state.lock() {
            runtime.child_pid = None;
        }
        let _ = weak.upgrade_in_event_loop(move |ui| {
            ui.set_connecting(false);
            ui.set_session_running(false);
            ui.set_streaming(false);
            match error {
                Some(error) => {
                    ui.set_connection_title("连接已结束".into());
                    ui.set_connection_caption("查看运行详情后可以重新连接".into());
                    ui.set_connection_kind("error".into());
                    append_log(&ui, &error);
                }
                None => {
                    ui.set_connection_title("已停止".into());
                    ui.set_connection_caption("可以随时重新开始传输".into());
                    ui.set_connection_kind("idle".into());
                }
            }
        });
    }

    fn forward_lines<R: std::io::Read + Send + 'static>(
        reader: R,
        weak: slint::Weak<SenseMicWindow>,
    ) {
        thread::spawn(move || {
            for line in BufReader::new(reader).lines().map_while(Result::ok) {
                let ui_line = line.clone();
                let _ = weak.upgrade_in_event_loop(move |ui| {
                    apply_runtime_line(&ui, &ui_line);
                    append_log(&ui, &ui_line);
                });
            }
        });
    }

    fn apply_runtime_line(ui: &SenseMicWindow, line: &str) {
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("pairing with ") {
            ui.set_connecting(true);
            ui.set_streaming(false);
            ui.set_connection_title("正在配对".into());
            ui.set_connection_caption(line.into());
            ui.set_connection_kind("busy".into());
        } else if lower.starts_with("connected to ") {
            ui.set_connecting(false);
            ui.set_streaming(true);
            ui.set_connection_title("正在传输".into());
            ui.set_connection_caption("手机声音正在送往电脑虚拟麦克风".into());
            ui.set_connection_kind("live".into());
        } else if lower.starts_with("session ended:") || lower.starts_with("discovery:") {
            ui.set_connecting(true);
            ui.set_streaming(false);
            ui.set_connection_title("正在重连".into());
            ui.set_connection_caption("连接中断，后台接收服务正在自动恢复".into());
            ui.set_connection_kind("busy".into());
        }

        if lower.starts_with("audio:") {
            if let Some(received) = metric(line, "received") {
                let recovered = metric(line, "recovered").unwrap_or("0");
                let lost = metric(line, "lost").unwrap_or("0");
                ui.set_packet_summary(
                    format!("接收 {received} · 恢复 {recovered} · 丢失 {lost}").into(),
                );
            }
            if let Some(jitter) = metric(line, "jitter") {
                ui.set_jitter_summary(jitter.into());
            }
        }
    }

    fn metric<'a>(line: &'a str, key: &str) -> Option<&'a str> {
        line.split_whitespace()
            .find_map(|field| field.strip_prefix(&format!("{key}=")))
    }

    fn bind_driver(ui: &SenseMicWindow) {
        let refresh_weak = ui.as_weak();
        ui.on_refresh_driver(move || refresh_driver(refresh_weak.clone()));

        let install_weak = ui.as_weak();
        ui.on_install_driver(move || install_driver(install_weak.clone()));
    }

    fn refresh_driver(weak: slint::Weak<SenseMicWindow>) {
        if let Some(ui) = weak.upgrade() {
            ui.set_driver_busy(true);
            ui.set_endpoint_name("正在检查音频组件…".into());
        }
        thread::spawn(move || {
            let result = driver::status().map_err(|error| format!("{error:#}"));
            let _ = weak.upgrade_in_event_loop(move |ui| {
                ui.set_driver_busy(false);
                match result {
                    Ok(status) if status.installed => {
                        ui.set_driver_ready(true);
                        ui.set_endpoint_name(
                            status
                                .capture_endpoint
                                .as_deref()
                                .unwrap_or("CABLE Output")
                                .into(),
                        );
                        ui.set_driver_detail(status.detail.into());
                    }
                    Ok(status) => {
                        ui.set_driver_ready(false);
                        ui.set_endpoint_name("等待安装".into());
                        ui.set_driver_detail(status.detail.into());
                    }
                    Err(error) => {
                        ui.set_driver_ready(false);
                        ui.set_endpoint_name("检查未完成".into());
                        ui.set_driver_detail(error.clone().into());
                        append_log(&ui, &format!("音频组件检查：{error}"));
                    }
                }
            });
        });
    }

    fn install_driver(weak: slint::Weak<SenseMicWindow>) {
        if let Some(ui) = weak.upgrade() {
            ui.set_driver_busy(true);
            ui.set_endpoint_name("等待管理员确认…".into());
            append_log(&ui, "正在安装内置的 Microsoft 签名虚拟音频组件…");
        }
        thread::spawn(move || {
            let result = run_elevated_driver_install();
            match result {
                Ok(()) => {
                    let refresh_weak = weak.clone();
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        append_log(&ui, "音频组件安装命令已完成，正在刷新设备状态…");
                        ui.set_driver_busy(false);
                        refresh_driver(refresh_weak);
                    });
                }
                Err(error) => {
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_driver_busy(false);
                        ui.set_endpoint_name("安装未完成".into());
                        append_log(&ui, &format!("音频组件安装：{error}"));
                    });
                }
            }
        });
    }

    fn bind_copy_endpoint(ui: &SenseMicWindow) {
        let weak = ui.as_weak();
        ui.on_copy_endpoint(move || {
            let Some(ui) = weak.upgrade() else {
                return;
            };
            let endpoint = ui.get_endpoint_name().to_string();
            match copy_text(&endpoint) {
                Ok(()) => append_log(&ui, &format!("已复制设备名称：{endpoint}")),
                Err(error) => append_log(&ui, &format!("复制设备名称：{error}")),
            }
        });
    }

    fn bind_close(ui: &SenseMicWindow, state: SharedState) {
        ui.window().on_close_requested(move || {
            if let Some(pid) = state.lock().ok().and_then(|runtime| runtime.child_pid) {
                kill_process_tree(pid);
            }
            CloseRequestResponse::HideWindow
        });
    }

    fn append_log(ui: &SenseMicWindow, line: &str) {
        let mut value = ui.get_log_text().to_string();
        if value.len() > 48_000 {
            value = value.split_off(value.len().saturating_sub(32_000));
        }
        if !value.is_empty() {
            value.push('\n');
        }
        value.push_str(line.trim_end());
        ui.set_log_text(value.into());
    }

    fn copy_text(text: &str) -> Result<(), String> {
        let mut command = Command::new("clip.exe");
        command.stdin(Stdio::piped());
        hidden(&mut command);
        let mut child = command.spawn().map_err(|error| error.to_string())?;
        child
            .stdin
            .take()
            .ok_or_else(|| "未获得剪贴板输入通道".to_owned())?
            .write_all(text.as_bytes())
            .map_err(|error| error.to_string())?;
        let status = child.wait().map_err(|error| error.to_string())?;
        if status.success() {
            Ok(())
        } else {
            Err(format!("剪贴板进程退出码 {status}"))
        }
    }

    fn cli_command() -> Result<Command, String> {
        let exe = std::env::current_exe().map_err(|error| error.to_string())?;
        let cli = exe
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join("sense-mic.exe");
        if !cli.is_file() {
            return Err(format!("缺少接收组件：{}", cli.display()));
        }
        Ok(Command::new(cli))
    }

    fn hidden(command: &mut Command) -> &mut Command {
        command.creation_flags(CREATE_NO_WINDOW)
    }

    fn kill_process_tree(pid: u32) {
        let mut command = Command::new("taskkill.exe");
        command.args(["/PID", &pid.to_string(), "/T", "/F"]);
        let _ = hidden(&mut command).status();
    }

    fn parse_discovery(output: &str) -> Vec<Phone> {
        output
            .lines()
            .filter_map(|line| {
                let mut fields = line.split('\t');
                let name = fields.next()?.trim();
                let mut host = None;
                for field in fields {
                    if let Some(value) = field.strip_prefix("address=") {
                        host = value.split(':').next().map(str::to_owned);
                    }
                }
                let host = host?;
                Some(Phone {
                    name: name.to_owned(),
                    label: format!("{name}  ·  {host}"),
                    host,
                })
            })
            .collect()
    }

    fn nonempty_error(stdout: &[u8], stderr: &[u8]) -> String {
        let stderr = String::from_utf8_lossy(stderr).trim().to_owned();
        if !stderr.is_empty() {
            stderr
        } else {
            let stdout = String::from_utf8_lossy(stdout).trim().to_owned();
            if stdout.is_empty() {
                "命令执行失败".to_owned()
            } else {
                stdout
            }
        }
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(std::iter::once(0)).collect()
    }

    fn run_elevated_driver_install() -> Result<(), String> {
        let cli = std::env::current_exe()
            .map_err(|error| error.to_string())?
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join("sense-mic.exe");
        let verb = wide(OsStr::new("runas"));
        let file = wide(cli.as_os_str());
        let parameters = wide(OsStr::new("driver install"));
        let mut info = SHELLEXECUTEINFOW {
            cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS,
            lpVerb: verb.as_ptr(),
            lpFile: file.as_ptr(),
            lpParameters: parameters.as_ptr(),
            nShow: SW_SHOWNORMAL,
            ..Default::default()
        };
        if unsafe { ShellExecuteExW(&mut info) } == 0 || info.hProcess.is_null() {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let wait = unsafe { WaitForSingleObject(info.hProcess, INFINITE) };
        let mut exit_code = 1u32;
        let read_exit = unsafe { GetExitCodeProcess(info.hProcess, &mut exit_code) };
        unsafe { CloseHandle(info.hProcess) };
        if wait != WAIT_OBJECT_0 || read_exit == 0 {
            return Err("读取音频组件安装结果失败".to_owned());
        }
        if exit_code != 0 {
            return Err(format!("音频组件安装进程退出码 {exit_code}"));
        }
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_cli_discovery_rows_for_gui_selection() {
            let phones = parse_discovery("Sense Phone\tid=123456\taddress=192.168.50.8:49174\r\n");
            assert_eq!(phones.len(), 1);
            assert_eq!(phones[0].host, "192.168.50.8");
            assert_eq!(phones[0].name, "Sense Phone");
        }

        #[test]
        fn skips_non_discovery_output() {
            assert!(parse_discovery("starting scan\n").is_empty());
        }

        #[test]
        fn parses_audio_metrics_for_status_cards() {
            let line = "audio: received=18 recovered=2 lost=1 jitter=23ms buffered=4";
            assert_eq!(metric(line, "received"), Some("18"));
            assert_eq!(metric(line, "jitter"), Some("23ms"));
        }
    }
}

#[cfg(target_os = "windows")]
fn main() {
    windows_app::run();
}
