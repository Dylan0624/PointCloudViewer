cmake_minimum_required(VERSION 3.18.1)
project(pointcloud_processor)

add_library(udp_receiver_node SHARED
        udp_receiver_node.cpp)

# 連接 log 庫，這是 Android NDK 內建的
target_link_libraries(udp_receiver_node
        android
        log)
