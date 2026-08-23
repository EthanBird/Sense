fn main() {
    println!("cargo:rerun-if-changed=windows/sense-mic.manifest");
    println!("cargo:rerun-if-changed=../windows/native/tsf/resources/sense.ico");
    println!("cargo:rerun-if-changed=ui/sense-mic.slint");

    #[cfg(target_os = "windows")]
    {
        let config = slint_build::CompilerConfiguration::new().with_style("fluent-light".into());
        slint_build::compile_with_config("ui/sense-mic.slint", config)
            .expect("compile Sense Mic Slint UI");
        let mut resource = winres::WindowsResource::new();
        resource
            .set_manifest_file("windows/sense-mic.manifest")
            .set_icon("../windows/native/tsf/resources/sense.ico")
            .set("ProductName", "Sense Mic")
            .set("FileDescription", "Sense Mic phone microphone client")
            .set("CompanyName", "Sense Project")
            .set("LegalCopyright", "GPL-3.0-only");
        resource
            .compile()
            .expect("compile Sense Mic Windows resources");
    }
}
