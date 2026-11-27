#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>

/* The SDK exports these public C APIs but does not include them in its Java binding. */
JNIEXPORT jboolean JNICALL Java_org_sippy_CallEncryption_setMandatory(
    JNIEnv *env, jobject instance, jlong pointer, jboolean mandatory) {
    (void)env;
    (void)instance;
    if (!pointer) return JNI_FALSE;
    void *library = dlopen("liblinphone.so", RTLD_NOW | RTLD_NOLOAD);
    if (!library) return JNI_FALSE;
    /* Linphone's bool_t is unsigned char, including on 32-bit ARM. */
    typedef void (*setter)(void *, unsigned char);
    typedef unsigned char (*getter)(const void *);
    setter set = (setter)dlsym(library, "linphone_call_params_enable_mandatory_media_encryption");
    getter get = (getter)dlsym(library, "linphone_call_params_mandatory_media_encryption_enabled");
    int result = 0;
    if (set && get) {
        void *params = (void *)(uintptr_t)pointer;
        set(params, mandatory == JNI_TRUE);
        result = !!get(params) == (mandatory == JNI_TRUE);
    }
    dlclose(library);
    return result ? JNI_TRUE : JNI_FALSE;
}
