#include <unistd.h>
#include <stddef.h>

#if defined(OZMA_PROBE_PTHREAD_LINK_ONLY)
#include <pthread.h>
int main(void) {
    static const char message[] = "OZMA_PTHREAD_LINK_ONLY_MAIN_OK\\n";
    (void)write(STDOUT_FILENO, message, sizeof(message) - 1);
    return 0;
}
#elif defined(OZMA_PROBE_PTHREAD)
#include <pthread.h>
static void *probe_worker(void *unused) {
    (void)unused;
    static const char message[] = "OZMA_GLIBC_PTHREAD_WORKER_OK\\n";
    (void)write(STDOUT_FILENO, message, sizeof(message) - 1);
    return NULL;
}
int main(void) {
    static const char start[] = "OZMA_GLIBC_PTHREAD_MAIN_OK\\n";
    (void)write(STDOUT_FILENO, start, sizeof(start) - 1);
    pthread_t thread;
    int rc = pthread_create(&thread, NULL, probe_worker, NULL);
    if (rc != 0) return 40 + (rc % 40);
    rc = pthread_join(thread, NULL);
    if (rc != 0) return 80 + (rc % 40);
    return 0;
}
#elif defined(OZMA_PROBE_SDL)
#include <dlfcn.h>
int main(void) {
    static const char start[] = "OZMA_GLIBC_DLOPEN_MAIN_OK\\n";
    (void)write(STDOUT_FILENO, start, sizeof(start) - 1);
    void *handle = dlopen("libSDL2-2.0.so.0", RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        const char *error = dlerror();
        if (error != NULL) (void)write(STDERR_FILENO, error, __builtin_strlen(error));
        return 90;
    }
    static const char loaded[] = "OZMA_SDL_DLOPEN_OK\\n";
    (void)write(STDOUT_FILENO, loaded, sizeof(loaded) - 1);
    dlclose(handle);
    return 0;
}
#else
int main(void) {
    static const char message[] = "OZMA_GLIBC_BASIC_OK\\n";
    ssize_t written = write(STDOUT_FILENO, message, sizeof(message) - 1);
    return written == (ssize_t)(sizeof(message) - 1) ? 0 : 10;
}
#endif
