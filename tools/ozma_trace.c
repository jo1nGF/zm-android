/*
 * ozma-trace: runs a command under ptrace purely as an "external SIGSYS handler".
 *
 * Android's app seccomp filter answers syscalls bionic never uses (set_robust_list, rseq, clone3, ...) with SIGSYS,
 * which kills a glibc program during its own startup - before any code of ours could install a handler.
 * Here the tracer receives that SIGSYS (a signal-delivery-stop), suppresses it and makes the syscall return -ENOSYS,
 * which glibc handles. No per-syscall stops are used, so the overhead is zero for allowed syscalls.
 *
 * Build for Android with the NDK (bionic):  aarch64-linux-android26-clang -O2 -o ozma-trace ozma_trace.c
 * Usage: ozma-trace <program> [args...]      exit status: child's exit code, or 128+signal if it was killed
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <signal.h>
#include <unistd.h>
#include <elf.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/uio.h>

#if defined(__aarch64__)
struct regs_t { uint64_t regs[31], sp, pc, pstate; };
#define SYSNR(r)      ((long)(r).regs[8])
#define SET_RET(r, v) ((r).regs[0] = (uint64_t)(long)(v))
#elif defined(__x86_64__)
#include <sys/user.h>
typedef struct user_regs_struct regs_t_;
struct regs_t { regs_t_ r; };
#define SYSNR(r)      ((long)(r).r.orig_rax)
#define SET_RET(r, v) ((r).r.rax = (unsigned long long)(long)(v))
#else
#error "unsupported architecture"
#endif

#ifndef PTRACE_O_TRACEFORK
#define PTRACE_O_TRACEFORK 0x2
#define PTRACE_O_TRACEVFORK 0x4
#define PTRACE_O_TRACECLONE 0x8
#define PTRACE_O_TRACEEXEC 0x10
#define PTRACE_O_EXITKILL 0x100000
#endif
#ifndef PTRACE_EVENT_FORK
#define PTRACE_EVENT_FORK 1
#define PTRACE_EVENT_VFORK 2
#define PTRACE_EVENT_CLONE 3
#define PTRACE_EVENT_EXEC 4
#endif
#define SECCOMP_SI_CODE 1 /* SYS_SECCOMP */

static volatile pid_t g_child;
static void forward(int sig) { if (g_child > 0) kill(g_child, sig); }

static const char *name_of(long nr) {
#if defined(__aarch64__)
    switch (nr) { case 99: return "set_robust_list"; case 100: return "get_robust_list"; case 163: return "getrlimit"; case 164: return "setrlimit";
                  case 291: return "statx"; case 293: return "rseq"; case 435: return "clone3"; case 436: return "close_range";
                  case 437: return "openat2"; case 439: return "faccessat2"; default: return ""; }
#else
    (void)nr; return "";
#endif
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s program [args...]\n", argv[0]); return 2; }
    pid_t child = fork();
    if (child < 0) { fprintf(stderr, "[trace] fork failed: %s\n", strerror(errno)); return 125; }
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) { fprintf(stderr, "[trace] PTRACE_TRACEME failed: %s\n", strerror(errno)); _exit(124); }
        raise(SIGSTOP);
        execv(argv[1], argv + 1);
        fprintf(stderr, "[trace] exec %s failed: %s\n", argv[1], strerror(errno)); _exit(127);
    }
    g_child = child;
    signal(SIGTERM, forward); signal(SIGINT, forward); signal(SIGHUP, forward);

    int status;
    if (waitpid(child, &status, __WALL) < 0) { fprintf(stderr, "[trace] waitpid: %s\n", strerror(errno)); return 125; }
    if (WIFEXITED(status)) return WEXITSTATUS(status);          /* TRACEME failed in the child */
    if (ptrace(PTRACE_SETOPTIONS, child, 0, (void *)(long)(PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXEC | PTRACE_O_EXITKILL)) != 0)
        fprintf(stderr, "[trace] PTRACE_SETOPTIONS failed: %s (threads will not be covered)\n", strerror(errno));
    ptrace(PTRACE_CONT, child, 0, 0);

    int logged = 0, main_status = 0, have_main = 0;
    for (;;) {
        pid_t t = waitpid(-1, &status, __WALL);
        if (t < 0) { if (errno == EINTR) continue; break; }
        if (WIFEXITED(status) || WIFSIGNALED(status)) { if (t == child) { main_status = status; have_main = 1; } continue; }
        if (!WIFSTOPPED(status)) continue;
        int sig = WSTOPSIG(status), event = status >> 16;
        if (sig == SIGTRAP && event) { ptrace(PTRACE_CONT, t, 0, 0); continue; }   /* clone/fork/exec events: new tracees are auto-attached */
        if (sig == SIGSTOP) { ptrace(PTRACE_CONT, t, 0, 0); continue; }            /* initial stop of a new tracee */
        if (sig == SIGSYS) {
            siginfo_t si; memset(&si, 0, sizeof si);
            if (ptrace(PTRACE_GETSIGINFO, t, 0, &si) == 0 && si.si_code == SECCOMP_SI_CODE) {
                struct regs_t r; struct iovec iov = { &r, sizeof r };
                if (ptrace(PTRACE_GETREGSET, t, (void *)(long)NT_PRSTATUS, &iov) == 0) {
                    long nr = SYSNR(r);
                    if (logged++ < 200) fprintf(stderr, "[trace] seccomp blocked syscall %ld %s (tid %d) -> ENOSYS\n", nr, name_of(nr), (int)t);
                    SET_RET(r, -ENOSYS);
                    ptrace(PTRACE_SETREGSET, t, (void *)(long)NT_PRSTATUS, &iov);
                }
                ptrace(PTRACE_CONT, t, 0, 0);                                      /* suppress the SIGSYS */
                continue;
            }
        }
        ptrace(PTRACE_CONT, t, 0, (void *)(long)sig);                              /* every other signal goes to the program */
    }
    if (!have_main) return 0;
    if (WIFEXITED(main_status)) return WEXITSTATUS(main_status);
    return 128 + WTERMSIG(main_status);
}
