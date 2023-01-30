#![allow(non_snake_case)]

use jni::JNIEnv;
use jni::objects::{JObject};
use jni::sys::{jint};

use jni_fn::jni_fn;

#[jni_fn("a.b.c.jni$")]
pub fn add(_env: JNIEnv, _object: JObject, a: jint, b: jint) -> jint {
    a + b
}
