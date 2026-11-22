#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include "params.h"

#define LOG_TAG "NetSieveNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern int original_main(int argc, char **argv);
extern void netsieve_stop_server(void);
extern struct params params;

static struct params g_default_params;
static int g_params_saved = 0;

JNIEXPORT jstring JNICALL
Java_io_github_jackbrownua_netsieve_core_NativeBridge_nativeAbi(JNIEnv *env, jclass clazz) {
#if defined(__aarch64__)
    const char *abi = "arm64-v8a";
#elif defined(__arm__)
    const char *abi = "armeabi-v7a";
#elif defined(__x86_64__)
    const char *abi = "x86_64";
#elif defined(__i386__)
    const char *abi = "x86";
#else
    const char *abi = "unknown";
#endif
    return (*env)->NewStringUTF(env, abi);
}

JNIEXPORT jint JNICALL
Java_io_github_jackbrownua_netsieve_core_NativeBridge_nativeRunByeDpi(JNIEnv *env, jclass clazz,
                                                                      jobjectArray args) {
    if (!g_params_saved) {
        g_default_params = params;
        g_params_saved = 1;
    } else {
        params = g_default_params;
    }

    optind = 1;
#ifdef __ANDROID__
    optreset = 1;
#endif

    jsize count = (*env)->GetArrayLength(env, args);
    int argc = (int) count + 1;
    char **argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    if (argv == NULL) {
        LOGE("calloc failed");
        return -1;
    }

    argv[0] = strdup("byedpi");
    if (argv[0] == NULL) {
        free(argv);
        LOGE("strdup failed");
        return -1;
    }

    for (jsize i = 0; i < count; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(utf != NULL ? utf : "");
        if (utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, s, utf);
        }
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    LOGI("Starting byedpi with %d args", argc);
    int rc = original_main(argc, argv);
    LOGI("byedpi finished, rc=%d", rc);

    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);
    return rc;
}

JNIEXPORT void JNICALL
Java_io_github_jackbrownua_netsieve_core_NativeBridge_nativeStopByeDpi(JNIEnv *env, jclass clazz) {
    LOGI("Stopping byedpi server");
    netsieve_stop_server();
}
