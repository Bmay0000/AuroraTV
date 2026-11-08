//! Thin JNI interface. Platform rendering and Media3 remain in Android.
//! Never log passwords or stream URLs; return no plaintext on failure.
use auroratv_core::{matching, streams};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::ptr;

fn text(env:&mut JNIEnv<'_>, value:JString<'_>) -> Option<String> {
    env.get_string(&value).ok().map(|s| s.into())
}
fn java_string(env:&mut JNIEnv<'_>, value:String) -> jstring {
    env.new_string(value).map(|s|s.into_raw()).unwrap_or(ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_tv_aurora_player_RustBridge_nativeReference(
    mut env:JNIEnv, _:JClass, kind:JString, id:JString, ext:JString
) -> jstring {
    let Some(kind)=text(&mut env,kind) else {return ptr::null_mut()};
    let Some(id)=text(&mut env,id) else {return ptr::null_mut()};
    let Some(ext)=text(&mut env,ext) else {return ptr::null_mut()};
    streams::reference_of(&kind,&id,&ext)
        .map(|v|java_string(&mut env,v)).unwrap_or(ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_tv_aurora_player_RustBridge_nativeResolve(
    mut env:JNIEnv, _:JClass, reference:JString,server:JString,user:JString,password:JString
) -> jstring {
    let Some(reference)=text(&mut env,reference) else{return ptr::null_mut()};
    let Some(server)=text(&mut env,server) else{return ptr::null_mut()};
    let Some(user)=text(&mut env,user) else{return ptr::null_mut()};
    let Some(password)=text(&mut env,password) else{return ptr::null_mut()};
    streams::resolve_reference(&reference,&server,&user,&password)
        .ok().map(|v|java_string(&mut env,v)).unwrap_or(ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_tv_aurora_player_RustBridge_nativeChannelScore(
    mut env:JNIEnv, _:JClass, network:JString,channel:JString,category:JString
) -> jint {
    let Some(network)=text(&mut env,network) else{return 0};
    let Some(channel)=text(&mut env,channel) else{return 0};
    let Some(category)=text(&mut env,category) else{return 0};
    matching::score_us_network(&network,&channel,&category)
}
#[no_mangle]
pub extern "system" fn Java_tv_aurora_player_RustBridge_nativeNormalizeChannel(
    mut env:JNIEnv, _:JClass, channel:JString
) -> jstring {
    let Some(channel)=text(&mut env,channel) else{return ptr::null_mut()};
    java_string(&mut env,matching::normalize_channel(&channel))
}
