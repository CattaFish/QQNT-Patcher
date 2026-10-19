#include <jni.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include "xhook.h"

static const char *apkPath__;
static const char *repPath__;

static int (*old_open)(const char *, int, mode_t);
static int openImpl(const char *pathname, int flags, mode_t mode) {
    if (strcmp(pathname, apkPath__) == 0) return old_open(repPath__, flags, mode);
    return old_open(pathname, flags, mode);
}

static int (*old_open64)(const char *, int, mode_t);
static int open64Impl(const char *pathname, int flags, mode_t mode) {
    if (strcmp(pathname, apkPath__) == 0) return old_open64(repPath__, flags, mode);
    return old_open64(pathname, flags, mode);
}

static int (*old_openat)(int, const char *, int, mode_t);
static int openatImpl(int fd, const char *pathname, int flags, mode_t mode) {
    if (strcmp(pathname, apkPath__) == 0) return old_openat(fd, repPath__, flags, mode);
    return old_openat(fd, pathname, flags, mode);
}

static int (*old_openat64)(int, const char *, int, mode_t);
static int openat64Impl(int fd, const char *pathname, int flags, mode_t mode) {
    if (strcmp(pathname, apkPath__) == 0) return old_openat64(fd, repPath__, flags, mode);
    return old_openat64(fd, pathname, flags, mode);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass systemClass = (*env)->FindClass(env, "java/lang/System");
    if (systemClass == NULL) return JNI_ERR;

    jmethodID getProperty = (*env)->GetStaticMethodID(env, systemClass, "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;");
    if (getProperty == NULL) return JNI_ERR;

    jstring key1 = (*env)->NewStringUTF(env, "mt.signature.killer.path1");
    jstring key2 = (*env)->NewStringUTF(env, "mt.signature.killer.path2");
    if (key1 == NULL || key2 == NULL) return JNI_ERR;

    jstring path1 = (jstring)(*env)->CallStaticObjectMethod(env, systemClass, getProperty, key1);
    jstring path2 = (jstring)(*env)->CallStaticObjectMethod(env, systemClass, getProperty, key2);
    if (path1 == NULL || path2 == NULL) return JNI_ERR;

    const char *c_path1 = (*env)->GetStringUTFChars(env, path1, NULL);
    const char *c_path2 = (*env)->GetStringUTFChars(env, path2, NULL);
    if (c_path1 == NULL || c_path2 == NULL) return JNI_ERR;

    apkPath__ = strdup(c_path1);
    repPath__ = strdup(c_path2);

    xhook_register(".*\\.so$", "open64",   open64Impl,   (void **)&old_open64);
    xhook_register(".*\\.so$", "open",     openImpl,     (void **)&old_open);
    xhook_register(".*\\.so$", "openat64", openat64Impl, (void **)&old_openat64);
    xhook_register(".*\\.so$", "openat",   openatImpl,   (void **)&old_openat);
    xhook_refresh(0);

    (*env)->ReleaseStringUTFChars(env, path1, c_path1);
    (*env)->ReleaseStringUTFChars(env, path2, c_path2);
    (*env)->DeleteLocalRef(env, path1);
    (*env)->DeleteLocalRef(env, path2);
    (*env)->DeleteLocalRef(env, key1);
    (*env)->DeleteLocalRef(env, key2);
    (*env)->DeleteLocalRef(env, systemClass);
    return JNI_VERSION_1_6;
}
