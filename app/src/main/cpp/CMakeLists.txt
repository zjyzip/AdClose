cmake_minimum_required(VERSION 3.22.1)

project("native_hook")

include_directories(${CMAKE_SOURCE_DIR}/include)

add_library(shadowhook SHARED IMPORTED)
set_target_properties(shadowhook PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libshadowhook.so)

add_library(
        native_hook
        SHARED
        native_hook.cpp
)

target_link_libraries(
        native_hook
        PRIVATE
        shadowhook
        log
)