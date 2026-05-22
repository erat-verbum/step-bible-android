#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "JVMStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PATH_BUF 1024
#define LD_PATH_BUF 8192
#define PORT_STR_BUF 16
#define OPT_PORT_BUF 64
#define OPT_PATH_BUF 1024
#define OPT_CLASSPATH_BUF 16384

#define ERR_BAD_JRE_DIR (-10)
#define ERR_BAD_CLASS_PATH (-11)
#define ERR_BAD_WAR_PATH (-12)
#define ERR_DLOPEN (-1)
#define ERR_DLSYM (-2)
#define ERR_FIND_CLASS (-3)
#define ERR_FIND_METHOD (-4)
#define ERR_JVM_EXCEPTION (-5)
#define ERR_OOM (-6)

#define JVM_OPTIONS_COUNT 14

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
    JavaVM *jvm = NULL;
    int jvm_created = 0;
    int result = -99;

    c_jre_dir = (*env)->GetStringUTFChars(env, jre_dir, NULL);
    if (!c_jre_dir) { result = ERR_BAD_JRE_DIR; goto cleanup; }
    c_class_path = (*env)->GetStringUTFChars(env, class_path, NULL);
    if (!c_class_path) { result = ERR_BAD_CLASS_PATH; goto cleanup; }
    c_war_path = (*env)->GetStringUTFChars(env, war_path, NULL);
    if (!c_war_path) { result = ERR_BAD_WAR_PATH; goto cleanup; }

    char libjvm_path[PATH_BUF];
    snprintf(libjvm_path, sizeof(libjvm_path), "%s/lib/server/libjvm.so", c_jre_dir);

    char ld_path[LD_PATH_BUF];
    char *ld_orig = getenv("LD_LIBRARY_PATH");
    snprintf(ld_path, sizeof(ld_path),
             "%s/lib:%s/lib/server:%s",
             c_jre_dir, c_jre_dir, ld_orig ? ld_orig : "");
    setenv("LD_LIBRARY_PATH", ld_path, 1);
    setenv("JAVA_HOME", c_jre_dir, 1);

    libjvm = dlopen(libjvm_path, RTLD_NOW | RTLD_GLOBAL);
    if (!libjvm) { LOGE("dlopen libjvm failed: %s", dlerror()); result = ERR_DLOPEN; goto cleanup; }

    // Preload all JRE libs
    {
        char lib_dir[PATH_BUF];
        snprintf(lib_dir, sizeof(lib_dir), "%s/lib", c_jre_dir);
        DIR *dir = opendir(lib_dir);
        if (dir) {
            struct dirent *entry;
            int count = 0;
            while ((entry = readdir(dir)) != NULL) {
                size_t len = strlen(entry->d_name);
                if (len > 3 && strcmp(entry->d_name + len - 3, ".so") == 0
                    && strcmp(entry->d_name, "libjvm.so") != 0) {
                    char libfile[PATH_BUF];
                    snprintf(libfile, sizeof(libfile), "%s/%s", lib_dir, entry->d_name);
                    if (dlopen(libfile, RTLD_NOW | RTLD_GLOBAL)) count++;
                }
            }
            closedir(dir);
            LOGI("Preloaded %d JRE libs", count);
        }
    }

    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(libjvm, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror()); result = ERR_DLSYM; goto cleanup; }

    char port_str[PORT_STR_BUF];
    snprintf(port_str, sizeof(port_str), "%d", port);

    char opt_classpath[OPT_CLASSPATH_BUF];
    snprintf(opt_classpath, sizeof(opt_classpath), "-Djava.class.path=%s", c_class_path);

    char opt_war_path[OPT_PATH_BUF];
    snprintf(opt_war_path, sizeof(opt_war_path), "-Dstep.war.path=%s", c_war_path);

    char opt_port[OPT_PORT_BUF];
    snprintf(opt_port, sizeof(opt_port), "-Dstep.war.port=%s", port_str);

    char opt_tmpdir[OPT_PATH_BUF];
    snprintf(opt_tmpdir, sizeof(opt_tmpdir), "-Djava.io.tmpdir=%s/tmp", c_jre_dir);

    char opt_userhome[OPT_PATH_BUF];
    {
        char tmp[OPT_PATH_BUF];
        snprintf(tmp, sizeof(tmp), "%s", c_jre_dir);
        char *p = strrchr(tmp, '/');
        if (p && strcmp(p, "/jre") == 0) *p = '\0';
        snprintf(opt_userhome, sizeof(opt_userhome), "-Duser.home=%s", tmp);
    }

    JavaVMOption options[JVM_OPTIONS_COUNT] = {
        { opt_classpath, NULL },
        { opt_war_path, NULL },
        { opt_port, NULL },
        { "-Dstep.war.context=", NULL },
        { "-Dstep.jetty=true", NULL },
        { "-Djava.locale.providers=COMPAT,SPI", NULL },
        { "-Djava.awt.headless=true", NULL },
        { opt_tmpdir, NULL },
        { opt_userhome, NULL },
        { "-Djava.security.manager=allow", NULL },
        { "--add-opens=java.base/java.lang=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/java.io=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/java.util=ALL-UNNAMED", NULL },
        { "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED", NULL },
    };

    JavaVMInitArgs vm_args;
    vm_args.version = 0x000a0000;
    vm_args.nOptions = JVM_OPTIONS_COUNT;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_TRUE;

    JNIEnv *jni_env;
    jint ret = JNI_CreateJavaVM(&jvm, (void**)&jni_env, &vm_args);
    if (ret != JNI_OK) { LOGE("JNI_CreateJavaVM failed: %d", ret); result = ret; goto cleanup; }
    jvm_created = 1;

    LOGI("JVM created");

    jclass launcher_cls = (*jni_env)->FindClass(jni_env,
        "com/eratverbum/stepbible/bootstrap/StepServerLauncher");
    if (!launcher_cls) { LOGE("FindClass StepServerLauncher failed"); result = ERR_FIND_CLASS; goto cleanup; }

    jmethodID launcher_main = (*jni_env)->GetStaticMethodID(jni_env, launcher_cls,
        "main", "([Ljava/lang/String;)V");
    if (!launcher_main) { LOGE("GetStaticMethodID launcher.main failed"); result = ERR_FIND_METHOD; goto cleanup; }

    jclass str_cls = (*jni_env)->FindClass(jni_env, "java/lang/String");
    if (!str_cls) { LOGE("FindClass java/lang/String failed"); result = ERR_FIND_CLASS; goto cleanup; }
    jobjectArray empty_args = (*jni_env)->NewObjectArray(jni_env, 0, str_cls, NULL);
    if (!empty_args) { LOGE("NewObjectArray failed (OOM)"); result = ERR_OOM; goto cleanup; }

    LOGI("Calling StepServerLauncher.main...");
    (*jni_env)->CallStaticVoidMethod(jni_env, launcher_cls, launcher_main, empty_args);

    if ((*jni_env)->ExceptionCheck(jni_env)) {
        LOGE("StepServerLauncher.main threw:");
        (*jni_env)->ExceptionDescribe(jni_env);
        (*jni_env)->ExceptionClear(jni_env);
        result = ERR_JVM_EXCEPTION;
    } else {
        LOGI("StepServerLauncher.main returned");
        result = 0;
    }

cleanup:
    if (jvm_created && result != 0) {
        (*jvm)->DestroyJavaVM(jvm);
        dlclose(libjvm);
    } else if (libjvm && !jvm_created) {
        dlclose(libjvm);
    }
    if (c_jre_dir)  (*env)->ReleaseStringUTFChars(env, jre_dir, c_jre_dir);
    if (c_class_path) (*env)->ReleaseStringUTFChars(env, class_path, c_class_path);
    if (c_war_path)  (*env)->ReleaseStringUTFChars(env, war_path, c_war_path);
    return result;
}
