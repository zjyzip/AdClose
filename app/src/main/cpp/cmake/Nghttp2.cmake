include_guard(GLOBAL)
include(FetchContent)

# Keep the dependency reproducible without maintaining a source fork in the repository.
set(ENABLE_LIB_ONLY ON CACHE BOOL "Build only the nghttp2 library" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Do not package a second nghttp2 shared library" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "Link nghttp2 statically into native_hook" FORCE)
set(ENABLE_DOC OFF CACHE BOOL "Do not build dependency documentation" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "Do not build upstream nghttp2 tests" FORCE)

if(NOT NGHTTP2_ARCHIVE OR NOT EXISTS "${NGHTTP2_ARCHIVE}")
    message(FATAL_ERROR "NGHTTP2_ARCHIVE must point to the release archive resolved by Gradle")
endif()
file(TO_CMAKE_PATH "${NGHTTP2_ARCHIVE}" NGHTTP2_ARCHIVE)
FetchContent_Declare(nghttp2
    URL "${NGHTTP2_ARCHIVE}"
    URL_HASH SHA256=6abd7ab0a7f1580d5914457cb3c85eb80455657ee5119206edbd7f848c14f0b2
)
FetchContent_MakeAvailable(nghttp2)
set_target_properties(nghttp2_static PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
)
