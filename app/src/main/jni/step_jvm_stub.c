#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "JVMStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*JNI_CreateJavaVM_t)(JavaVM **pvm, void **penv, void *args);

JNIEXPORT jint JNICALL
Java_com_eratverbum_stepbible_JVMStub_startServer(
    JNIEnv *env, jclass clazz,
    jstring jre_dir, jstring class_path,
    jstring war_path, jint port) {

    const char *c_jre_dir = NULL;
    const char *c_class_path = NULL;
    const char *c_war_path = NULL;
    void *libjvm = NULL;
    int result = -99;

    c_jre_dir = (*env)->GetStringUTFChars(env, jre_dir, NULL);
    if (!c_jre_dir) { result = -10; goto cleanup; }
    c_class_path = (*env)->GetStringUTFChars(env, class_path, NULL);
    if (!c_class_path) { result = -11; goto cleanup; }
    c_war_path = (*env)->GetStringUTFChars(env, war_path, NULL);
    if (!c_war_path) { result = -12; goto cleanup; }

    char libjvm_path[1024];
    snprintf(libjvm_path, sizeof(libjvm_path), "%s/lib/server/libjvm.so", c_jre_dir);

    char ld_path[8192];
    char *ld_orig = getenv("LD_LIBRARY_PATH");
    snprintf(ld_path, sizeof(ld_path),
             "%s/lib:%s/lib/server:%s",
             c_jre_dir, c_jre_dir, ld_orig ? ld_orig : "");
    setenv("LD_LIBRARY_PATH", ld_path, 1);
    setenv("JAVA_HOME", c_jre_dir, 1);

    libjvm = dlopen(libjvm_path, RTLD_NOW | RTLD_GLOBAL);
    if (!libjvm) { LOGE("dlopen libjvm failed: %s", dlerror()); result = -1; goto cleanup; }

    // Preload all JRE libs
    {
        char cmd[8192];
        snprintf(cmd, sizeof(cmd), "ls %s/lib/*.so 2>/dev/null", c_jre_dir);
        FILE *fp = popen(cmd, "r");
        if (fp) {
            char libpath[1024]; int count = 0;
            while (fgets(libpath, sizeof(libpath), fp)) {
                char *nl = strchr(libpath, '\n'); if (nl) *nl = '\0';
                if (libpath[0] == '/' && !strstr(libpath, "/libjvm.so"))
                    if (dlopen(libpath, RTLD_NOW | RTLD_GLOBAL)) count++;
            }
            pclose(fp); LOGI("Preloaded %d JRE libs", count);
        }
    }

    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(libjvm, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { result = -2; goto cleanup; }

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    char opt_classpath[16384];
    snprintf(opt_classpath, sizeof(opt_classpath), "-Djava.class.path=%s", c_class_path);

    char opt_war_path[1024];
    snprintf(opt_war_path, sizeof(opt_war_path), "-Dstep.war.path=%s", c_war_path);

    char opt_port[64];
    snprintf(opt_port, sizeof(opt_port), "-Dstep.war.port=%s", port_str);

    char opt_tmpdir[1024];
    snprintf(opt_tmpdir, sizeof(opt_tmpdir), "-Djava.io.tmpdir=%s/tmp", c_jre_dir);

    JavaVMOption options[12] = {
        { opt_classpath, NULL },
        { opt_war_path, NULL },
        { opt_port, NULL },
        { "-Dstep.jetty=true", NULL },
        { "-Djava.locale.providers=COMPAT,SPI", NULL },
        { opt_tmpdir, NULL },
        { "-Duser.home=/data/data/com.eratverbum.stepbible/files", NULL },
        { "-Djava.security.manager=allow", NULL },
        { "--add-opens=java.base/java.lang=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/java.io=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/java.util=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED", NULL },
    };

    JavaVMInitArgs vm_args;
    vm_args.version = 0x00010008;
    vm_args.nOptions = 12;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_TRUE;

    JavaVM *jvm;
    JNIEnv *jni_env;
    jint ret = JNI_CreateJavaVM(&jvm, (void**)&jni_env, &vm_args);
    if (ret != JNI_OK) { LOGE("JNI_CreateJavaVM failed: %d", ret); result = ret; goto cleanup; }

    LOGI("JVM created");

    jclass launcher_cls = (*jni_env)->FindClass(jni_env,
        "com/eratverbum/stepbible/bootstrap/StepServerLauncher");
    if (!launcher_cls) { LOGE("FindClass StepServerLauncher failed"); result = -3; goto cleanup; }

    jmethodID launcher_main = (*jni_env)->GetStaticMethodID(jni_env, launcher_cls,
        "main", "([Ljava/lang/String;)V");
    if (!launcher_main) { LOGE("GetStaticMethodID launcher.main failed"); result = -4; goto cleanup; }

    jclass str_cls = (*jni_env)->FindClass(jni_env, "java/lang/String");
    jobjectArray empty_args = (*jni_env)->NewObjectArray(jni_env, 0, str_cls, NULL);

    LOGI("Calling StepServerLauncher.main...");
    (*jni_env)->CallStaticVoidMethod(jni_env, launcher_cls, launcher_main, empty_args);

    if ((*jni_env)->ExceptionCheck(jni_env)) {
        LOGE("StepServerLauncher.main threw:");
        (*jni_env)->ExceptionDescribe(jni_env);
    }
    LOGI("StepServerLauncher.main returned");
    result = 0;

cleanup:
    if (c_jre_dir)  (*env)->ReleaseStringUTFChars(env, jre_dir, c_jre_dir);
    if (c_class_path) (*env)->ReleaseStringUTFChars(env, class_path, c_class_path);
    if (c_war_path)  (*env)->ReleaseStringUTFChars(env, war_path, c_war_path);
    return result;
}
