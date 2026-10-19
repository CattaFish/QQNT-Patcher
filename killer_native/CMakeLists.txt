cmake_minimum_required(VERSION 3.18)
project(zcraft C)
set(CMAKE_C_STANDARD 11)

add_library(zcraft SHARED
    mt_jni.c
    xhook.c
    xh_core.c
    xh_elf.c
    xh_log.c
    xh_util.c
    xh_version.c
)

target_include_directories(zcraft PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(zcraft log)
