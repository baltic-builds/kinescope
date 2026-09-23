/*
 * JNI glue between Kinescope and the vendored ByeDPI engine (byedpi/, MIT).
 *
 * ByeDPI is a command-line program whose main() runs a local SOCKS5 proxy until it is shut down.
 * This file exposes it to Kotlin as two calls:
 *
 *   nativeRun(args)  blocks on the calling thread until the proxy exits, returns its exit code.
 *   nativeStop()     asks a running proxy to leave its event loop.
 *
 * The engine keeps its configuration in process-wide globals. Kinescope therefore hosts it in a
 * dedicated Android process (see DpiEngineService) that is discarded after every run; the reset
 * below only protects against a stale state if a process is ever reused.
 */
#include <getopt.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "params.h"

extern struct params params;
extern int server_fd;
extern int byedpi_main(int argc, char **argv); /* main() of byedpi/main.c, renamed by -Dmain=byedpi_main */

static struct params g_default_params;
static volatile int g_running = 0;

/* Runs when the library is loaded, before any engine code: params still holds its initializer. */
__attribute__((constructor))
static void kinescope_dpi_snapshot_defaults(void)
{
    g_default_params = params;
}

JNIEXPORT jint JNICALL
Java_com_kinescope_app_DpiNative_nativeRun(JNIEnv *env, jobject thiz, jobjectArray args)
{
    if (g_running) {
        return -2;
    }
    jsize count = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc((size_t)count + 2, sizeof(char *));
    if (!argv) {
        return -3;
    }
    int argc = 0;
    argv[argc++] = strdup("ciadpi");
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (!item) {
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (utf) {
            argv[argc++] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, item, utf);
        }
        (*env)->DeleteLocalRef(env, item);
    }
    argv[argc] = NULL;

    params = g_default_params;
#ifdef __GLIBC__
    optind = 0; /* glibc only fully re-initialises getopt() for 0; only host-side tests reuse a process */
#else
    optind = 1;
#endif
    g_running = 1;
    int status = byedpi_main(argc, argv);
    g_running = 0;

    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);
    return status;
}

JNIEXPORT jint JNICALL
Java_com_kinescope_app_DpiNative_nativeStop(JNIEnv *env, jobject thiz)
{
    if (!g_running) {
        return -1;
    }
    int listening = 0;
    socklen_t length = sizeof(listening);
    if (getsockopt(server_fd, SOL_SOCKET, SO_ACCEPTCONN, &listening, &length) != 0 || !listening) {
        return -1; /* not listening yet, or already closed: nothing to shut down */
    }
    return shutdown(server_fd, SHUT_RDWR) == 0 ? 0 : -1;
}
