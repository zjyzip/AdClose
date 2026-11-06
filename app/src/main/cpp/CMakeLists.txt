cmake_minimum_required(VERSION 3.22.1)
project(native_hook LANGUAGES C CXX)

include(cmake/Nghttp2.cmake)
find_package(shadowhook REQUIRED CONFIG)

add_library(native_hook SHARED native_hook.cpp http2_parser.cpp)
target_compile_features(native_hook PRIVATE cxx_std_17)
target_include_directories(native_hook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(native_hook PRIVATE -Wno-unused-parameter -Wno-sign-compare)
target_link_libraries(native_hook PRIVATE nghttp2_static shadowhook::shadowhook log)
