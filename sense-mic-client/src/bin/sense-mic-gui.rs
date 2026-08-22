#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
fn main() {
    eprintln!("Sense Mic GUI is available on Windows; use sense-mic on this platform.");
}

#[cfg(target_os = "windows")]
mod windows_app {
    extern crate native_windows_derive as nwd;
    extern crate native_windows_gui as nwg;

    use nwd::NwgUi;
    use nwg::NativeUi;
    use sense_mic_client::driver;
    use std::cell::{Cell, RefCell};
    use std::ffi::OsStr;
    use std::io::{BufRead, BufReader};
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};
    use std::sync::mpsc::{self, Receiver, Sender};
    use std::thread;
    use windows_sys::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Threading::{
        GetExitCodeProcess, WaitForSingleObject, INFINITE,
    };
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    #[derive(Clone, Debug)]
    struct Phone {
        label: String,
        host: String,
    }

    #[derive(Debug)]
    enum GuiMessage {
        Log(String),
        Discovery(Result<Vec<Phone>, String>),
        DriverStatus(Result<driver::DriverStatus, String>),
        DriverInstall(Result<(), String>),
        Started(u32),
        Ended(Result<(), String>),
    }

    #[derive(Default, NwgUi)]
    pub struct SenseMicApp {
        #[nwg_resource(family: "Microsoft YaHei UI", size: 25, weight: 600)]
        title_font: nwg::Font,
        #[nwg_control(size: (760, 650), position: (240, 80), title: "Sense Mic", flags: "WINDOW|VISIBLE")]
        #[nwg_events(OnInit: [SenseMicApp::initialize], OnWindowClose: [SenseMicApp::exit])]
        window: nwg::Window,

        #[nwg_control(text: "Sense Mic", position: (24, 18), size: (300, 38), font: Some(&data.title_font))]
        title: nwg::Label,
        #[nwg_control(text: "把手机的麦克风稳定传到电脑", position: (26, 55), size: (400, 24))]
        subtitle: nwg::Label,

        #[nwg_control(text: "虚拟麦克风", position: (24, 94), size: (120, 26))]
        driver_heading: nwg::Label,
        #[nwg_control(text: "正在检查…", position: (150, 94), size: (370, 24))]
        driver_status: nwg::Label,
        #[nwg_control(text: "刷新", position: (532, 88), size: (86, 32))]
        #[nwg_events(OnButtonClick: [SenseMicApp::refresh_driver])]
        driver_refresh: nwg::Button,
        #[nwg_control(text: "安装驱动…", position: (626, 88), size: (108, 32))]
        #[nwg_events(OnButtonClick: [SenseMicApp::install_driver])]
        driver_install: nwg::Button,

        #[nwg_control(text: "手机", position: (24, 142), size: (80, 24))]
        phone_label: nwg::Label,
        #[nwg_control(collection: Vec::<String>::new(), position: (108, 136), size: (500, 240))]
        phone_select: nwg::ComboBox<String>,
        #[nwg_control(text: "扫描", position: (620, 136), size: (114, 32))]
        #[nwg_events(OnButtonClick: [SenseMicApp::scan])]
        scan_button: nwg::Button,

        #[nwg_control(text: "手机 IP", position: (24, 187), size: (80, 24))]
        host_label: nwg::Label,
        #[nwg_control(text: "", position: (108, 181), size: (260, 30), focus: true)]
        host_input: nwg::TextInput,
        #[nwg_control(text: "配对码", position: (390, 187), size: (70, 24))]
        code_label: nwg::Label,
        #[nwg_control(text: "", position: (462, 181), size: (146, 30), limit: 6)]
        code_input: nwg::TextInput,
        #[nwg_control(text: "延迟", position: (620, 187), size: (48, 24))]
        latency_label: nwg::Label,
        #[nwg_control(collection: vec!["80 ms".to_owned(), "100 ms".to_owned(), "140 ms".to_owned(), "180 ms".to_owned(), "240 ms".to_owned()], selected_index: Some(1), position: (670, 181), size: (64, 180))]
        latency_select: nwg::ComboBox<String>,

        #[nwg_control(text: "连接", position: (108, 230), size: (156, 40))]
        #[nwg_events(OnButtonClick: [SenseMicApp::connect])]
        connect_button: nwg::Button,
        #[nwg_control(text: "停止", position: (276, 230), size: (120, 40), enabled: false)]
        #[nwg_events(OnButtonClick: [SenseMicApp::stop])]
        stop_button: nwg::Button,
        #[nwg_control(text: "等待连接", position: (416, 239), size: (318, 24))]
        connection_status: nwg::Label,

        #[nwg_control(text: "运行记录", position: (24, 294), size: (120, 26))]
        log_heading: nwg::Label,
        #[nwg_control(text: "Sense Mic 已启动。\r\n", position: (24, 322), size: (710, 260), readonly: true, flags: "VISIBLE|AUTOVSCROLL|VSCROLL")]
        log: nwg::TextBox,
        #[nwg_control(text: "提示：手机和电脑需在同一局域网；配对码只保存在当前运行内存中。", position: (24, 596), size: (710, 24))]
        hint: nwg::Label,

        #[nwg_control]
        #[nwg_events(OnNotice: [SenseMicApp::drain_messages])]
        notice: nwg::Notice,

        #[nwg_resource(title: "选择 SenseMicVAD.inf", action: nwg::FileDialogAction::Open, filters: "Sense Mic driver (SenseMicVAD.inf)|INF driver (*.inf)|All files (*.*)")]
        driver_dialog: nwg::FileDialog,

        receiver: RefCell<Option<Receiver<GuiMessage>>>,
        sender: RefCell<Option<Sender<GuiMessage>>>,
        phones: RefCell<Vec<Phone>>,
        child_pid: Cell<Option<u32>>,
        exiting: Cell<bool>,
    }

    impl SenseMicApp {
        fn initialize(&self) {
            let (sender, receiver) = mpsc::channel();
            *self.sender.borrow_mut() = Some(sender);
            *self.receiver.borrow_mut() = Some(receiver);
            self.append_log("正在检查虚拟麦克风并扫描手机…");
            self.refresh_driver();
            self.scan();
        }

        fn sender_and_notice(&self) -> Option<(Sender<GuiMessage>, nwg::NoticeSender)> {
            self.sender
                .borrow()
                .as_ref()
                .cloned()
                .map(|sender| (sender, self.notice.sender()))
        }

        fn refresh_driver(&self) {
            self.driver_refresh.set_enabled(false);
            self.driver_status.set_text("正在检查…");
            let Some((sender, notice)) = self.sender_and_notice() else {
                return;
            };
            thread::spawn(move || {
                let result = driver::status().map_err(|error| format!("{error:#}"));
                let _ = sender.send(GuiMessage::DriverStatus(result));
                notice.notice();
            });
        }

        fn scan(&self) {
            self.scan_button.set_enabled(false);
            self.connection_status.set_text("正在扫描局域网…");
            let Some((sender, notice)) = self.sender_and_notice() else {
                return;
            };
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
                let _ = sender.send(GuiMessage::Discovery(result));
                notice.notice();
            });
        }

        fn connect(&self) {
            if self.child_pid.get().is_some() {
                return;
            }
            let code = self.code_input.text().trim().to_owned();
            if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
                self.connection_status
                    .set_text("请输入手机显示的 6 位配对码");
                self.code_input.set_focus();
                return;
            }
            let mut host = self.host_input.text().trim().to_owned();
            if host.is_empty() {
                if let Some(index) = self.phone_select.selection() {
                    if let Some(phone) = self.phones.borrow().get(index) {
                        host.clone_from(&phone.host);
                        self.host_input.set_text(&host);
                    }
                }
            }
            if host.is_empty() {
                self.connection_status
                    .set_text("请先扫描并选择手机，或输入手机 IP");
                return;
            }
            let latency = self
                .latency_select
                .selection_string()
                .and_then(|value| value.split_whitespace().next()?.parse::<u32>().ok())
                .unwrap_or(100)
                .to_string();

            self.connect_button.set_enabled(false);
            self.scan_button.set_enabled(false);
            self.connection_status.set_text("正在启动连接…");
            self.append_log(&format!("连接 {host}，缓冲 {latency} ms"));
            let Some((sender, notice)) = self.sender_and_notice() else {
                return;
            };
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
                        let _ = sender.send(GuiMessage::Ended(Err(error)));
                        notice.notice();
                        return;
                    }
                };
                let pid = child.id();
                let _ = sender.send(GuiMessage::Started(pid));
                notice.notice();

                if let Some(stdout) = child.stdout.take() {
                    forward_lines(stdout, sender.clone(), notice);
                }
                if let Some(stderr) = child.stderr.take() {
                    forward_lines(stderr, sender.clone(), notice);
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
                let _ = sender.send(GuiMessage::Ended(ended));
                notice.notice();
            });
        }

        fn stop(&self) {
            if let Some(pid) = self.child_pid.get() {
                self.connection_status.set_text("正在停止…");
                self.append_log("正在停止音频连接…");
                let mut command = Command::new("taskkill.exe");
                command.args(["/PID", &pid.to_string(), "/T", "/F"]);
                let _ = hidden(&mut command).status();
            }
        }

        fn install_driver(&self) {
            if !self.driver_dialog.run(Some(&self.window)) {
                return;
            }
            let Ok(selected) = self.driver_dialog.get_selected_item() else {
                return;
            };
            let path = PathBuf::from(selected);
            if path.file_name().and_then(OsStr::to_str) != Some("SenseMicVAD.inf") {
                nwg::simple_message("Sense Mic", "请选择名为 SenseMicVAD.inf 的驱动入口文件。");
                return;
            }
            self.driver_install.set_enabled(false);
            self.driver_status.set_text("等待管理员确认…");
            self.append_log(&format!("请求安装驱动：{}", path.display()));
            let Some((sender, notice)) = self.sender_and_notice() else {
                return;
            };
            thread::spawn(move || {
                let result = run_elevated_driver_install(&path);
                let _ = sender.send(GuiMessage::DriverInstall(result));
                notice.notice();
            });
        }

        fn drain_messages(&self) {
            let mut messages = Vec::new();
            if let Some(receiver) = self.receiver.borrow().as_ref() {
                while let Ok(message) = receiver.try_recv() {
                    messages.push(message);
                }
            }
            for message in messages {
                match message {
                    GuiMessage::Log(line) => self.append_log(&line),
                    GuiMessage::Discovery(result) => {
                        self.scan_button.set_enabled(self.child_pid.get().is_none());
                        match result {
                            Ok(phones) if !phones.is_empty() => {
                                let labels =
                                    phones.iter().map(|phone| phone.label.clone()).collect();
                                self.phone_select.set_collection(labels);
                                self.phone_select.set_selection(Some(0));
                                self.host_input.set_text(&phones[0].host);
                                self.connection_status
                                    .set_text(&format!("找到 {} 台手机", phones.len()));
                                *self.phones.borrow_mut() = phones;
                            }
                            Ok(_) => self
                                .connection_status
                                .set_text("暂未发现手机，可直接输入 IP"),
                            Err(error) => {
                                self.connection_status
                                    .set_text("暂未发现手机，可直接输入 IP");
                                self.append_log(&format!("扫描：{error}"));
                            }
                        }
                    }
                    GuiMessage::DriverStatus(result) => {
                        self.driver_refresh.set_enabled(true);
                        match result {
                            Ok(status) if status.installed => {
                                self.driver_status.set_text("已就绪 · Sense Mic");
                                self.driver_install.set_enabled(false);
                            }
                            Ok(_) => {
                                self.driver_status.set_text("待安装驱动");
                                self.driver_install.set_enabled(true);
                            }
                            Err(error) => {
                                self.driver_status.set_text("检查失败");
                                self.driver_install.set_enabled(true);
                                self.append_log(&format!("驱动检查：{error}"));
                            }
                        }
                    }
                    GuiMessage::DriverInstall(result) => {
                        self.driver_install.set_enabled(true);
                        match result {
                            Ok(()) => {
                                self.append_log("驱动安装命令已完成。");
                                self.refresh_driver();
                            }
                            Err(error) => {
                                self.driver_status.set_text("驱动安装未完成");
                                self.append_log(&format!("驱动安装：{error}"));
                            }
                        }
                    }
                    GuiMessage::Started(pid) => {
                        self.child_pid.set(Some(pid));
                        self.stop_button.set_enabled(true);
                        self.connection_status.set_text("已启动，正在配对/传输");
                    }
                    GuiMessage::Ended(result) => {
                        self.child_pid.set(None);
                        self.stop_button.set_enabled(false);
                        self.connect_button.set_enabled(true);
                        self.scan_button.set_enabled(true);
                        match result {
                            Ok(()) => self.connection_status.set_text("连接已停止"),
                            Err(error) => {
                                self.connection_status.set_text("连接已结束，请查看记录");
                                self.append_log(&error);
                            }
                        }
                    }
                }
            }
        }

        fn append_log(&self, line: &str) {
            let mut value = self.log.text();
            if value.len() > 48_000 {
                value = value.split_off(value.len().saturating_sub(32_000));
            }
            value.push_str(line.trim_end());
            value.push_str("\r\n");
            self.log.set_text(&value);
            self.log.scroll_lastline();
        }

        fn exit(&self) {
            if self.exiting.replace(true) {
                return;
            }
            self.stop();
            nwg::stop_thread_dispatch();
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

    fn parse_discovery(output: &str) -> Vec<Phone> {
        output
            .lines()
            .filter_map(|line| {
                let mut fields = line.split('\t');
                let name = fields.next()?.trim();
                let mut id = "unknown";
                let mut host = None;
                for field in fields {
                    if let Some(value) = field.strip_prefix("id=") {
                        id = value;
                    } else if let Some(value) = field.strip_prefix("address=") {
                        host = value.split(':').next().map(str::to_owned);
                    }
                }
                let host = host?;
                Some(Phone {
                    label: format!("{name}  ·  {host}  ·  {id}"),
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

    fn forward_lines<R: std::io::Read + Send + 'static>(
        reader: R,
        sender: Sender<GuiMessage>,
        notice: nwg::NoticeSender,
    ) {
        thread::spawn(move || {
            for line in BufReader::new(reader).lines().map_while(Result::ok) {
                let _ = sender.send(GuiMessage::Log(line));
                notice.notice();
            }
        });
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(std::iter::once(0)).collect()
    }

    fn run_elevated_driver_install(inf: &Path) -> Result<(), String> {
        let cli = std::env::current_exe()
            .map_err(|error| error.to_string())?
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join("sense-mic.exe");
        let verb = wide(OsStr::new("runas"));
        let file = wide(cli.as_os_str());
        let parameters = wide(OsStr::new(&format!(
            "driver install --package \"{}\"",
            inf.display()
        )));
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
            return Err("读取驱动安装结果失败".to_owned());
        }
        if exit_code != 0 {
            return Err(format!("驱动安装进程退出码 {exit_code}"));
        }
        Ok(())
    }

    pub fn run() {
        nwg::init().expect("initialize Native Windows GUI");
        nwg::Font::set_global_family("Microsoft YaHei UI").expect("set GUI font");
        let _app = SenseMicApp::build_ui(Default::default()).expect("build Sense Mic UI");
        nwg::dispatch_thread_events();
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_cli_discovery_rows_for_gui_selection() {
            let phones = parse_discovery("Sense Phone\tid=123456\taddress=192.168.50.8:49174\r\n");
            assert_eq!(phones.len(), 1);
            assert_eq!(phones[0].host, "192.168.50.8");
            assert!(phones[0].label.contains("Sense Phone"));
            assert!(phones[0].label.contains("123456"));
        }

        #[test]
        fn skips_non_discovery_output() {
            assert!(parse_discovery("starting scan\n").is_empty());
        }
    }
}

#[cfg(target_os = "windows")]
fn main() {
    windows_app::run();
}
