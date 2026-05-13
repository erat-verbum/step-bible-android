cmake_minimum_required(VERSION 3.22)
project("step_jvm_stub")

add_library(step_jvm_stub SHARED step_jvm_stub.c)
target_link_libraries(step_jvm_stub log dl)
target_include_directories(step_jvm_stub PRIVATE ${ANDROID_NDK}/sysroot/usr/include)
