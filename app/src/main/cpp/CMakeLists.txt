cmake_minimum_required(VERSION 3.22.1)
project("platform_fix")

add_library(platform_fix SHARED platform_fix.c)
target_link_libraries(platform_fix dl)
