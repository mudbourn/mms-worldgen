use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

// Returns the crate version to RustNative.version.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
