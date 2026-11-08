//! Minimal JNI bridge. The UI and business logic stay in Rust; Android's
//! Activity handles the system decoder and TV remote keys.
use std::path::PathBuf;
use jni::{JavaVM,objects::{JObject,JValue}};
pub fn data_dir()->PathBuf{
    (||{
        let ctx=ndk_context::android_context();
        // android_context is valid after the Slint Android backend starts.
        let vm=unsafe{JavaVM::from_raw(ctx.vm().cast())}.ok()?;
        let mut env=vm.attach_current_thread().ok()?;
        let activity=unsafe{JObject::from_raw(ctx.context().cast())};
        let result=env.call_method(activity,"getFilesDir","()Ljava/io/File;",&[]).ok()?
            .l().ok()?;
        let filename=env.call_method(result,"getAbsolutePath","()Ljava/lang/String;",&[])
            .ok()?.l().ok()?;
        let name: String=env.get_string((&filename).into()).ok()?.into();
        Some(PathBuf::from(name).join("aurora-rust"))
    })().unwrap_or_else(||PathBuf::from("/data/local/tmp/aurora-rust"))
}
pub fn launch_player(url:&str,title:&str,live:bool)->Result<(),String>{
    let ctx=ndk_context::android_context();
    let vm=unsafe{JavaVM::from_raw(ctx.vm().cast())}.map_err(|e|e.to_string())?;
    let mut env=vm.attach_current_thread().map_err(|e|e.to_string())?;
    let activity=unsafe{JObject::from_raw(ctx.context().cast())};
    let intent=env.new_object("android/content/Intent","()V",&[])
        .map_err(|e|e.to_string())?;
    let pkg=env.new_string("tv.aurora.rust").map_err(|e|e.to_string())?;
    let name=env.new_string("tv.aurora.rust.PlayerActivity")
        .map_err(|e|e.to_string())?;
    let jpkg=JObject::from(pkg);let jname=JObject::from(name);
    env.call_method(&intent,"setClassName",
        "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
        &[JValue::Object(&jpkg),JValue::Object(&jname)])
        .map_err(|e|e.to_string())?;
    for (key,value) in [("stream_url",url),("stream_title",title)] {
        let jk=JObject::from(env.new_string(key).map_err(|e|e.to_string())?);
        let jv=JObject::from(env.new_string(value).map_err(|e|e.to_string())?);
        env.call_method(&intent,"putExtra",
            "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
            &[JValue::Object(&jk),JValue::Object(&jv)])
            .map_err(|e|e.to_string())?;
    }
    let jk=JObject::from(env.new_string("live").map_err(|e|e.to_string())?);
    env.call_method(&intent,"putExtra",
        "(Ljava/lang/String;Z)Landroid/content/Intent;",
        &[JValue::Object(&jk),JValue::Bool(if live{1}else{0})])
        .map_err(|e|e.to_string())?;
    env.call_method(activity,"startActivity","(Landroid/content/Intent;)V",
        &[JValue::Object(&intent)]).map_err(|e|e.to_string())?;
    Ok(())
}
