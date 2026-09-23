cmake_minimum_required(VERSION 3.22.1)
project(kinescope_dpi C)

# ByeDPI sources are vendored unmodified in byedpi/ (MIT, see byedpi/LICENSE). win_service.c is
# Windows-only and deliberately not vendored.
set(BYEDPI_SOURCES
    byedpi/conev.c
    byedpi/desync.c
    byedpi/extend.c
    byedpi/main.c
    byedpi/mpool.c
    byedpi/packets.c
    byedpi/proxy.c)

add_library(kinescope_dpi SHARED ${BYEDPI_SOURCES} dpi_jni.c)
target_include_directories(kinescope_dpi PRIVATE byedpi)

# ANDROID_APP routes ByeDPI's own logging to logcat. main=byedpi_main turns its entry point into a
# plain function that dpi_jni.c can call.
target_compile_definitions(kinescope_dpi PRIVATE _DEFAULT_SOURCE ANDROID_APP main=byedpi_main)
target_compile_options(kinescope_dpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter)
target_link_libraries(kinescope_dpi PRIVATE log)

# 16 KB page-size compatible ELF segments (default from NDK r28; explicit for r27 and older).
target_link_options(kinescope_dpi PRIVATE -Wl,-z,max-page-size=16384)
