#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "JVMStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*JNI_CreateJavaVM_t)(JavaVM **pvm, void **penv, void *args);

JNIEXPORT jint JNICALL
Java_com_eratverbum_stepbible_JVMStub_startServer(
    JNIEnv *env, jclass clazz,
    jstring jre_dir, jstring class_path,
    jstring war_path, jint port,
    jstring native_lib_dir) {

    const char *c_jre_dir = (*env)->GetStringUTFChars(env, jre_dir, NULL);
    const char *c_class_path = (*env)->GetStringUTFChars(env, class_path, NULL);
    const char *c_war_path = (*env)->GetStringUTFChars(env, war_path, NULL);
    const char *c_native_dir = (*env)->GetStringUTFChars(env, native_lib_dir, NULL);

    char libjvm_path[1024];
    snprintf(libjvm_path, sizeof(libjvm_path), "%s/lib/server/libjvm.so", c_jre_dir);

    char ld_path[8192];
    char *ld_orig = getenv("LD_LIBRARY_PATH");
    snprintf(ld_path, sizeof(ld_path),
             "%s/lib:%s/lib/server:%s/lib/jli:%s",
             c_jre_dir, c_jre_dir, c_jre_dir, ld_orig ? ld_orig : "");
    setenv("LD_LIBRARY_PATH", ld_path, 1);

    // libandroid-shmem.so must be loaded with RTLD_GLOBAL before libjvm.so
    char shm_path[1024];
    snprintf(shm_path, sizeof(shm_path), "%s/libandroid-shmem.so", c_native_dir);
    void *libshmem = dlopen(shm_path, RTLD_NOW | RTLD_GLOBAL);
    if (!libshmem) {
        // Fallback to JRE lib dir
        snprintf(shm_path, sizeof(shm_path), "%s/lib/libandroid-shmem.so", c_jre_dir);
        libshmem = dlopen(shm_path, RTLD_NOW | RTLD_GLOBAL);
    }
    LOGI("libandroid-shmem loaded: %s (%s)", libshmem ? "YES" : "NO", shm_path);

    void *libjvm = dlopen(libjvm_path, RTLD_NOW | RTLD_GLOBAL);
    if (!libjvm) {
        LOGE("dlopen libjvm failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, jre_dir, c_jre_dir);
        (*env)->ReleaseStringUTFChars(env, class_path, c_class_path);
        (*env)->ReleaseStringUTFChars(env, war_path, c_war_path);
        (*env)->ReleaseStringUTFChars(env, native_lib_dir, c_native_dir);
        return -1;
    }

    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(libjvm, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) {
        LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, native_lib_dir, c_native_dir);
        return -2;
    }

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    // Build JVM options
    char opt_bootcp[16384];
    snprintf(opt_bootcp, sizeof(opt_bootcp), "-Xbootclasspath/a:%s", c_class_path);

    char opt_classpath[16384];
    snprintf(opt_classpath, sizeof(opt_classpath), "-Djava.class.path=%s", c_class_path);

    char opt_war_path[1024];
    snprintf(opt_war_path, sizeof(opt_war_path), "-Dstep.war.path=%s", c_war_path);

    char opt_port[64];
    snprintf(opt_port, sizeof(opt_port), "-Dstep.war.port=%s", port_str);

    char opt_tmpdir[1024];
    snprintf(opt_tmpdir, sizeof(opt_tmpdir), "-Djava.io.tmpdir=%s/tmp", c_jre_dir);

    JavaVMOption options[11] = {
        { opt_bootcp, NULL },
        { opt_classpath, NULL },
        { opt_war_path, NULL },
        { opt_port, NULL },
        { "-Dstep.war.context=", NULL },
        { "-Djava.locale.providers=COMPAT,SPI", NULL },
        { "-Dstep.jetty=true", NULL },
        { "-Dtomcat.util.http.parser.HttpParser.requestTargetAllow=|", NULL },
        { opt_tmpdir, NULL },
        { "-Djava.security.manager=allow", NULL },
        { "-Dsun.util.logging.disableCallerCheck=true", NULL },
    };

    JavaVMInitArgs vm_args;
    vm_args.version = 0x00010008;
    vm_args.nOptions = 11;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_TRUE;

    JavaVM *jvm;
    JNIEnv *jni_env;
    jint ret = JNI_CreateJavaVM(&jvm, (void**)&jni_env, &vm_args);
    if (ret != JNI_OK) {
        LOGE("JNI_CreateJavaVM failed: %d", ret);
        return ret;
    }

    LOGI("JVM created, classpath length=%d", (int)strlen(c_class_path));

    jclass main_cls = (*jni_env)->FindClass(jni_env, "com/tyndalehouse/step/server/STEPTomcatServer");
    if (!main_cls) {
        LOGE("FindClass STEPTomcatServer failed");
        if ((*jni_env)->ExceptionCheck(jni_env)) {
            jthrowable exc = (*jni_env)->ExceptionOccurred(jni_env);
            (*jni_env)->ExceptionClear(jni_env);
            jclass exc_cls = (*jni_env)->GetObjectClass(jni_env, exc);
            jmethodID getMsg = (*jni_env)->GetMethodID(jni_env, exc_cls, "getMessage",
                "()Ljava/lang/String;");
            if (getMsg) {
                jstring msg = (jstring)(*jni_env)->CallObjectMethod(jni_env, exc, getMsg);
                const char *c_msg = (*jni_env)->GetStringUTFChars(jni_env, msg, NULL);
                LOGE("Exception: %s", c_msg);
                (*jni_env)->ReleaseStringUTFChars(jni_env, msg, c_msg);
            }
            jmethodID toString = (*jni_env)->GetMethodID(jni_env, exc_cls, "toString",
                "()Ljava/lang/String;");
            if (toString) {
                jstring str = (jstring)(*jni_env)->CallObjectMethod(jni_env, exc, toString);
                const char *c_str = (*jni_env)->GetStringUTFChars(jni_env, str, NULL);
                LOGE("ToString: %s", c_str);
                (*jni_env)->ReleaseStringUTFChars(jni_env, str, c_str);
            }
        }
        return -3;
    }

    jmethodID main_id = (*jni_env)->GetStaticMethodID(jni_env, main_cls, "main", "([Ljava/lang/String;)V");
    if (!main_id) {
        LOGE("GetStaticMethodID main failed");
        if ((*jni_env)->ExceptionCheck(jni_env)) (*jni_env)->ExceptionDescribe(jni_env);
        return -4;
    }

    jclass str_cls = (*jni_env)->FindClass(jni_env, "java/lang/String");
    jobjectArray args = (*jni_env)->NewObjectArray(jni_env, 1, str_cls, NULL);
    jstring bg_arg = (*jni_env)->NewStringUTF(jni_env, "backgroundLaunch");
    (*jni_env)->SetObjectArrayElement(jni_env, args, 0, bg_arg);

    LOGI("Calling STEPTomcatServer.main...");
    (*jni_env)->CallStaticVoidMethod(jni_env, main_cls, main_id, args);

    if ((*jni_env)->ExceptionCheck(jni_env)) {
        LOGE("Exception in STEPTomcatServer.main");
        (*jni_env)->ExceptionDescribe(jni_env);
        (*jni_env)->ExceptionClear(jni_env);
    }

    (*env)->ReleaseStringUTFChars(env, jre_dir, c_jre_dir);
    (*env)->ReleaseStringUTFChars(env, class_path, c_class_path);
    (*env)->ReleaseStringUTFChars(env, war_path, c_war_path);
    (*env)->ReleaseStringUTFChars(env, native_lib_dir, c_native_dir);
    return 0;
}
