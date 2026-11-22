cmake_minimum_required(VERSION 3.22.1)

project(netsieve_native C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(BYEDPI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/byedpi)

if(NOT EXISTS ${BYEDPI_DIR}/main.c)
    message(FATAL_ERROR
        "byedpi не найден в ${BYEDPI_DIR}. "
        "Добавьте сабмодуль или запустите сборку через CI.")
endif()

file(GLOB BYEDPI_SOURCES ${BYEDPI_DIR}/*.c)
list(FILTER BYEDPI_SOURCES EXCLUDE REGEX ".*win_service\\.c$")

set_source_files_properties(
    ${BYEDPI_DIR}/main.c
    PROPERTIES COMPILE_DEFINITIONS "main=original_main"
)

add_library(netsieve SHARED
    netsieve_jni.c
    ${BYEDPI_SOURCES}
)

target_include_directories(netsieve PRIVATE ${BYEDPI_DIR})
target_compile_definitions(netsieve PRIVATE _DEFAULT_SOURCE _GNU_SOURCE)
target_compile_options(netsieve PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-sign-compare
)
target_link_libraries(netsieve PRIVATE log)
target_link_options(netsieve PRIVATE -Wl,-z,max-page-size=16384)
set(BYEDPI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/byedpi)

if(NOT EXISTS ${BYEDPI_DIR}/main.c)
    message(FATAL_ERROR
        "byedpi не найден в $
{BYEDPI_DIR}. "
        "Добавьте сабмодуль или запустите сборку через CI.")
endif()

file(GLOB BYEDPI_SOURCES ${BYEDPI_DIR}/*.c)
list(FILTER BYEDPI_SOURCES EXCLUDE REGEX ".*win_service\.c$")

set_source_files_properties(
    ${BYEDPI_DIR}/main.c
    PROPERTIES COMPILE_DEFINITIONS "main=original_main"
)

add_library(netsieve SHARED
    netsieve_jni.c
    ${BYEDPI_SOURCES}
)

target_include_directories(netsieve PRIVATE ${BYEDPI_DIR})

target_compile_definitions(netsieve PRIVATE _DEFAULT_SOURCE _GNU_SOURCE)

target_compile_options(netsieve PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-sign-compare
)

target_link_libraries(netsieve PRIVATE log)

# Выравнивание страниц 16 КБ (требование Google Play для Android 15+)
target_link_options(netsieve PRIVATE -Wl,-z,max-page-size=16384)
