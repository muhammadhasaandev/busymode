// Starts the embedded Node.js runtime (nodejs-mobile) and forwards its output to logcat.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

// Exported by libnode.so. Declared here so we don't need the full Node headers.
namespace node { int Start(int argc, char *argv[]); }

static int pipe_stdout[2];
static int pipe_stderr[2];
static const char *TAG = "BusyModeNode";

static void *pump(void *arg) {
    int which = (int)(intptr_t)arg;
    int fd = which == 1 ? pipe_stdout[0] : pipe_stderr[0];
    int prio = which == 1 ? ANDROID_LOG_INFO : ANDROID_LOG_WARN;
    char buf[2048];
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(prio, TAG, buf);
    }
    return nullptr;
}

static void redirect_output() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pipe_stdout) == 0) dup2(pipe_stdout[1], STDOUT_FILENO);
    if (pipe(pipe_stderr) == 0) dup2(pipe_stderr[1], STDERR_FILENO);
    pthread_t t1, t2;
    if (pthread_create(&t1, nullptr, pump, (void *)1) == 0) pthread_detach(t1);
    if (pthread_create(&t2, nullptr, pump, (void *)2) == 0) pthread_detach(t2);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hasaan_busymode_NodeEngine_startNode(JNIEnv *env, jobject /*thiz*/, jobjectArray arguments) {
    redirect_output();

    jsize argc = env->GetArrayLength(arguments);
    std::vector<std::string> args;
    size_t total = 0;
    for (jsize i = 0; i < argc; i++) {
        auto js = (jstring)env->GetObjectArrayElement(arguments, i);
        const char *s = env->GetStringUTFChars(js, nullptr);
        args.emplace_back(s);
        total += strlen(s) + 1;
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    // libuv requires all arguments in one contiguous block of memory.
    char *block = (char *)calloc(total, 1);
    std::vector<char *> argv(argc + 1, nullptr);
    char *pos = block;
    for (jsize i = 0; i < argc; i++) {
        memcpy(pos, args[i].c_str(), args[i].size());
        argv[i] = pos;
        pos += args[i].size() + 1;
    }
    return node::Start(argc, argv.data());
}
