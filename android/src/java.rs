use jni::objects::{
    JByteArray, JClass, JIntArray, JMethodID, JObject, JString, JValue, ReleaseMode,
};
use jni::signature::{Primitive, ReturnType};
use jni::JNIEnv;
use ruffle_core::ContextMenuItem;
use std::path::PathBuf;
use std::sync::OnceLock;

/// Handles to various items on the Java `PlayerActivity` class.
/// This is statically initialized once at startup, via the Java method `nativeInit()`.
/// This avoids needing to pay the lookup and validation penalty for every single call back into Java,
/// which can be a significant cost.
pub struct JavaInterface {
    get_surface_width: JMethodID,
    get_surface_height: JMethodID,
    show_context_menu: JMethodID,
    get_swf_bytes: JMethodID,
    get_swf_uri: JMethodID,
    get_trace_output: JMethodID,
    get_loc_in_window: JMethodID,
    get_android_data_storage_dir: JMethodID,
    get_game_swf_url: JMethodID,
    get_render_backend: JMethodID,
    /// MoleRuffle:让 Kotlin 用隐藏输入框(EditText)接管并弹出系统软键盘。
    show_soft_keyboard: JMethodID,
    hide_soft_keyboard: JMethodID,
    /// MoleRuffle:设置面板里的"日志记录"开关(默认关)。
    is_log_enabled: JMethodID,
    /// MoleRuffle:设置面板里的"日志保留天数"(默认 7)。
    get_log_retention_days: JMethodID,
    /// MoleRuffle:把游戏文本框里已有的内容同步进输入条(便于查看/删除)。
    set_ime_bar_text: JMethodID,
    /// MoleRuffle:让 Kotlin 给 SurfaceHolder 设固定尺寸(降采样渲染缓冲)。
    ///   在渲染线程"帧之间"安全点调用,避免 UI 线程直接 resize 与渲染并发 → Adreno 崩。
    set_surface_fixed_size: JMethodID,
    /// MoleRuffle:读已保存的渲染分辨率档位(25~100,默认 100)。
    get_render_scale: JMethodID,
}

static JAVA_INTERFACE: OnceLock<JavaInterface> = OnceLock::new();

impl JavaInterface {
    pub fn get_surface_width(env: &mut JNIEnv, this: &JObject) -> i32 {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_surface_width,
                ReturnType::Primitive(Primitive::Int),
                &[],
            )
        };
        result
            .expect("getSurfaceWidth() must never throw")
            .i()
            .unwrap()
    }

    pub fn get_surface_height(env: &mut JNIEnv, this: &JObject) -> i32 {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_surface_height,
                ReturnType::Primitive(Primitive::Int),
                &[],
            )
        };
        result
            .expect("getSurfaceHeight() must never throw")
            .i()
            .unwrap()
    }

    pub fn show_context_menu(env: &mut JNIEnv, this: &JObject, items: &[ContextMenuItem]) {
        let arr = env
            .new_object_array(items.len() as i32, "java/lang/String", JObject::null())
            .unwrap();
        for (i, e) in items.iter().enumerate() {
            let s = env
                .new_string(format!(
                    "{} {} {} {}",
                    e.enabled, e.separator_before, e.checked, e.caption
                ))
                .unwrap();
            env.set_object_array_element(&arr, i as i32, s).unwrap();
        }
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().show_context_menu,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Object(&arr).as_jni()],
            )
        };
        result.expect("showContextMenu() must never throw");
    }

    pub fn get_swf_bytes(env: &mut JNIEnv, this: &JObject) -> Option<Vec<u8>> {
        let result = unsafe {
            env.call_method_unchecked(this, Self::get().get_swf_bytes, ReturnType::Array, &[])
        };
        let object = result.expect("getSwfBytes() must never throw").l().unwrap();
        if object.is_null() {
            return None;
        }

        let arr = JByteArray::from(object);
        let elements = unsafe {
            env.get_array_elements(&arr, ReleaseMode::NoCopyBack)
                .unwrap()
        };
        let data =
            unsafe { std::slice::from_raw_parts(elements.as_ptr() as *mut u8, elements.len()) };
        Some(data.to_vec())
    }

    pub fn get_swf_uri(env: &mut JNIEnv, this: &JObject) -> String {
        let result = unsafe {
            env.call_method_unchecked(this, Self::get().get_swf_uri, ReturnType::Object, &[])
        };
        let object = result.expect("getSwfUri() must never throw").l().unwrap();
        if object.is_null() {
            return Default::default();
        }
        let string_object = JString::from(object);
        let java_string = unsafe { env.get_string_unchecked(&string_object) };
        let url = java_string.unwrap().to_string_lossy().to_string();
        url
    }

    pub fn get_trace_output(env: &mut JNIEnv, this: &JObject) -> Option<PathBuf> {
        let result = unsafe {
            env.call_method_unchecked(this, Self::get().get_trace_output, ReturnType::Object, &[])
        };
        let object = result
            .expect("getTraceOutput() must never throw")
            .l()
            .unwrap();
        if object.is_null() {
            return None;
        }
        let string_object = JString::from(object);
        let java_string = unsafe { env.get_string_unchecked(&string_object) };
        let url = java_string.unwrap().to_string_lossy().to_string();
        Some(url.into())
    }

    pub fn get_loc_in_window(env: &mut JNIEnv, this: &JObject) -> (i32, i32) {
        let result = unsafe {
            env.call_method_unchecked(this, Self::get().get_loc_in_window, ReturnType::Array, &[])
        };
        let object = result
            .expect("getLocInWindow() must never throw")
            .l()
            .unwrap();
        let arr = JIntArray::from(object);
        let elements = unsafe {
            env.get_array_elements(&arr, ReleaseMode::NoCopyBack)
                .unwrap()
        };
        let data = unsafe { std::slice::from_raw_parts(elements.as_ptr(), elements.len()) };
        (data[0], data[1])
    }

    pub fn get_android_data_storage_dir(env: &mut JNIEnv, this: &JObject) -> PathBuf {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_android_data_storage_dir,
                ReturnType::Object,
                &[],
            )
        };
        let object = result
            .expect("getAndroidDataStorageDir() must never throw")
            .l()
            .unwrap();
        let string_object = JString::from(object);
        let java_string = unsafe { env.get_string_unchecked(&string_object) };
        let path = java_string.unwrap().to_string_lossy().to_string();
        PathBuf::from(path)
    }

    /// MoleRuffle:读取 Kotlin 侧配置的游戏加载地址(含版本选择)。
    pub fn get_game_swf_url(env: &mut JNIEnv, this: &JObject) -> String {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_game_swf_url,
                ReturnType::Object,
                &[],
            )
        };
        let object = result
            .expect("gameSwfUrl() must never throw")
            .l()
            .unwrap();
        let string_object = JString::from(object);
        let java_string = unsafe { env.get_string_unchecked(&string_object) };
        let url = java_string.unwrap().to_string_lossy().to_string();
        if url.is_empty() {
            // 兜底:Kotlin 侧没给就用平行服主版本。
            "http://mole.61player.com/Client.swf".to_string()
        } else {
            url
        }
    }

    /// MoleRuffle:渲染后端偏好("vulkan" / "gl"),设置面板选择、重启生效。
    pub fn get_render_backend(env: &mut JNIEnv, this: &JObject) -> String {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_render_backend,
                ReturnType::Object,
                &[],
            )
        };
        let object = match result {
            Ok(v) => v.l().unwrap(),
            Err(_) => return "vulkan".to_string(),
        };
        let string_object = JString::from(object);
        let java_string = unsafe { env.get_string_unchecked(&string_object) };
        match java_string {
            Ok(s) => {
                let url = s.to_string_lossy().to_string();
                if url.is_empty() { "vulkan".to_string() } else { url }
            }
            Err(_) => "vulkan".to_string(),
        }
    }

    /// MoleRuffle:显/隐系统软键盘(交由 Kotlin 侧的隐藏输入框接管,
    ///   这样输入法走真实 InputConnection,退格删除才收得到)。
    pub fn show_soft_keyboard(env: &mut JNIEnv, this: &JObject) {
        let _ = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().show_soft_keyboard,
                ReturnType::Primitive(Primitive::Void),
                &[],
            )
        };
    }

    pub fn hide_soft_keyboard(env: &mut JNIEnv, this: &JObject) {
        let _ = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().hide_soft_keyboard,
                ReturnType::Primitive(Primitive::Void),
                &[],
            )
        };
    }

    /// MoleRuffle:读设置面板的"日志记录"开关(默认 false)。
    pub fn is_log_enabled(env: &mut JNIEnv, this: &JObject) -> bool {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().is_log_enabled,
                ReturnType::Primitive(Primitive::Boolean),
                &[],
            )
        };
        match result {
            Ok(v) => v.z().unwrap_or(false),
            Err(_) => false,
        }
    }

    /// MoleRuffle:读设置面板的"日志保留天数"(默认 7)。
    pub fn get_log_retention_days(env: &mut JNIEnv, this: &JObject) -> u32 {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_log_retention_days,
                ReturnType::Primitive(Primitive::Int),
                &[],
            )
        };
        match result {
            Ok(v) => v.i().unwrap_or(7).max(1) as u32,
            Err(_) => 7,
        }
    }

    /// MoleRuffle:把游戏文本框的现有内容写进输入条(不触发加/删注入)。
    pub fn set_ime_bar_text(env: &mut JNIEnv, this: &JObject, text: &str) {
        let Ok(jstr) = env.new_string(text) else {
            return;
        };
        let _ = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().set_ime_bar_text,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Object(&JObject::from(jstr)).as_jni()],
            )
        };
    }

    /// MoleRuffle:让 Kotlin 给 SurfaceHolder 设固定尺寸(降采样渲染缓冲)。
    ///   在渲染线程"帧之间"安全点调用,避免 UI 线程直接 resize 与渲染并发 → Adreno 崩。
    pub fn set_surface_fixed_size(env: &mut JNIEnv, this: &JObject, w: i32, h: i32) {
        let _ = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().set_surface_fixed_size,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Int(w).as_jni(), JValue::Int(h).as_jni()],
            )
        };
    }

    /// MoleRuffle:读已保存的渲染分辨率档位(25~100,默认 100)。
    pub fn get_render_scale(env: &mut JNIEnv, this: &JObject) -> i32 {
        let result = unsafe {
            env.call_method_unchecked(
                this,
                Self::get().get_render_scale,
                ReturnType::Primitive(Primitive::Int),
                &[],
            )
        };
        match result {
            Ok(v) => v.i().unwrap_or(100),
            Err(_) => 100,
        }
    }

    pub fn get() -> &'static JavaInterface {
        JAVA_INTERFACE
            .get()
            .expect("Java interface must have been created via nativeInit()")
    }

    pub fn init(env: &mut JNIEnv, class: &JClass) {
        let _ = JAVA_INTERFACE.set(JavaInterface {
            get_surface_width: env
                .get_method_id(class, "getSurfaceWidth", "()I")
                .expect("getSurfaceWidth must exist"),
            get_surface_height: env
                .get_method_id(class, "getSurfaceHeight", "()I")
                .expect("getSurfaceHeight must exist"),
            show_context_menu: env
                .get_method_id(class, "showContextMenu", "([Ljava/lang/String;)V")
                .expect("showContextMenu must exist"),
            get_swf_bytes: env
                .get_method_id(class, "getSwfBytes", "()[B")
                .expect("getSwfBytes must exist"),
            get_swf_uri: env
                .get_method_id(class, "getSwfUri", "()Ljava/lang/String;")
                .expect("getSwfUri must exist"),
            get_trace_output: env
                .get_method_id(class, "getTraceOutput", "()Ljava/lang/String;")
                .expect("getTraceOutput must exist"),
            get_loc_in_window: env
                .get_method_id(class, "getLocInWindow", "()[I")
                .expect("getLocInWindow must exist"),
            get_android_data_storage_dir: env
                .get_method_id(class, "getAndroidDataStorageDir", "()Ljava/lang/String;")
                .expect("getAndroidDataStorageDir must exist"),
            get_game_swf_url: env
                .get_method_id(class, "gameSwfUrl", "()Ljava/lang/String;")
                .expect("gameSwfUrl must exist"),
            get_render_backend: env
                .get_method_id(class, "renderBackendPref", "()Ljava/lang/String;")
                .expect("renderBackendPref must exist"),
            show_soft_keyboard: env
                .get_method_id(class, "showImeProxy", "()V")
                .expect("showImeProxy must exist"),
            hide_soft_keyboard: env
                .get_method_id(class, "hideImeProxy", "()V")
                .expect("hideImeProxy must exist"),
            is_log_enabled: env
                .get_method_id(class, "isLogEnabled", "()Z")
                .expect("isLogEnabled must exist"),
            get_log_retention_days: env
                .get_method_id(class, "getLogRetentionDays", "()I")
                .expect("getLogRetentionDays must exist"),
            set_ime_bar_text: env
                .get_method_id(class, "setImeBarText", "(Ljava/lang/String;)V")
                .expect("setImeBarText must exist"),
            set_surface_fixed_size: env
                .get_method_id(class, "setSurfaceFixedSize", "(II)V")
                .expect("setSurfaceFixedSize must exist"),
            get_render_scale: env
                .get_method_id(class, "getRenderScale", "()I")
                .expect("getRenderScale must exist"),
        });
    }
}
