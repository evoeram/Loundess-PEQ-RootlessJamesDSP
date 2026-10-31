// Native crash recorder (port of RootlessViPER4Android CrashGuard.cpp,
// report K11): sigaction on SIGSEGV/SIGBUS/SIGFPE/SIGILL/SIGABRT writes
// signal info, the fault context (pc/lr/sp/x0 on arm64) and the jamesdsp
// lines of /proc/self/maps next to the app's other diagnostics, then
// re-raises so the platform crash pipeline (and logcat -b crash) still
// sees the fault. The app shows the file after restart.
#include <jni.h>
#include <signal.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <ucontext.h>

static char gDir[512] = {0};

static void writeAll(int fd, const char* s)
{
    write(fd, s, strlen(s));
}

static void crashHandler(int sig, siginfo_t* si, void* ucRaw)
{
    // Build the dump once, then write it to the one-shot file the UI shows
    // after restart and append it to the durable history (the card requires
    // crash_history.txt itself to carry pc/lr/sp/x0 and the maps).
    char buf[2048];
    int len = 0;
    len += snprintf(buf + len, sizeof(buf) - len, "signal=%d code=%d fault_addr=%p\n",
                    sig, si ? si->si_code : 0, si ? si->si_addr : (void*)0);
#if defined(__aarch64__)
    ucontext_t* uc = (ucontext_t*)ucRaw;
    if (uc && len < (int)sizeof(buf) - 128)
        len += snprintf(buf + len, sizeof(buf) - len, "pc=%p lr=%p sp=%p x0=%p\n",
                        (void*)uc->uc_mcontext.pc,
                        (void*)uc->uc_mcontext.regs[30],
                        (void*)uc->uc_mcontext.sp,
                        (void*)uc->uc_mcontext.regs[0]);
#endif
    if (gDir[0])
    {
        char path[600];
        int fd = -1;
        snprintf(path, sizeof(path), "%s/native_crash.txt", gDir);
        fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0)
        {
            writeAll(fd, buf);
            int mf = open("/proc/self/maps", O_RDONLY);
            if (mf >= 0)
            {
                char rd[1024];
                static char line[2048];
                int lineLen = 0;
                ssize_t n;
                while ((n = read(mf, rd, sizeof(rd))) > 0)
                {
                    for (ssize_t i = 0; i < n; i++)
                    {
                        char ch = rd[i];
                        if (lineLen < (int)sizeof(line) - 1)
                            line[lineLen++] = ch;
                        if (ch == '\n')
                        {
                            line[lineLen] = 0;
                            if (strstr(line, "jamesdsp"))
                                writeAll(fd, line);
                            lineLen = 0;
                        }
                    }
                }
                close(mf);
            }
            close(fd);
        }
        // durable copy: append the same header lines to crash_history.txt
        snprintf(path, sizeof(path), "%s/crash_history.txt", gDir);
        fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0)
        {
            writeAll(fd, "\n===== (native) =====\n");
            writeAll(fd, buf);
            close(fd);
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_initCrashGuard(JNIEnv *env, jobject obj, jstring dir)
{
    const char* d = env->GetStringUTFChars(dir, nullptr);
    if (d)
    {
        strncpy(gDir, d, sizeof(gDir) - 1);
        env->ReleaseStringUTFChars(dir, d);
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    const int sigs[] = { SIGSEGV, SIGBUS, SIGFPE, SIGILL, SIGABRT };
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++)
        sigaction(sigs[i], &sa, nullptr);
}

// Test hook for the K11 acceptance only: dereferences null so the handler
// above must fire. Removed before release.
extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_testNativeCrash(JNIEnv *env, jobject obj)
{
    volatile int* p = (volatile int*)0;
    *p = 42;
}
