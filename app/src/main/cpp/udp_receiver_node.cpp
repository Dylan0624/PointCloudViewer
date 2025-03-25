// #include <iostream>

// #include <jni.h>
// #include <android/log.h>
// #include <sensor_msgs/PointCloud2.h>
// #include <sensor_msgs/PointField.h>
// #include <boost/asio.hpp>
// #include <boost/bind.hpp>
// #include <vector>
// #include <cmath>
// #include <array>
// #include <iomanip>
// #include <cstdio>
// #include <thread>
// #include <mutex>
// #include <queue>
// #include <atomic>
// #include <condition_variable>

// using boost::asio::ip::udp;

// // 基本配置常量
// const std::string UDP_IP = "192.168.48.10";   //暫時變更 - 0219
// const int UDP_PORT = 7000;   //6699 暫時變更 - 0219

// // 數據包格式常量
// const int HEADER_SIZE = 32;
// const int DATA_SIZE = 784;  // 260 points * 3 bytes per point
// const int PACKET_SIZE = 816;  // HEADER_SIZE + DATA_SIZE
// const int POINTS_PER_PACKET = 260;
// const int TOTAL_POINTS_PER_LINE = 520;  // 上下半部總點數
// const uint8_t HEADER_MAGIC[4] = {0x55, 0xaa, 0x5a, 0xa5};  // 0x55, 0xaa, 0x5a, 0xa5 暫時變更 - 0219
// const int HEADER_START_OFFSET = 0;
// const int DATA_START_OFFSET = 32; 

// // 定義每幀包含的掃描線數量
// const int LINES_PER_FRAME = 1990;  // 每幀包含的LiDAR掃描線數量

// // LiDAR參數常量
// const float AZIMUTH_RESOLUTION = 0.0439;
// const float ELEVATION_START_UPPER = 12.975;
// const float ELEVATION_START_LOWER = -0.025;
// const float ELEVATION_STEP = -0.05;

// // 數據包類型常量
// const uint8_t PACKET_UPPER = 0x10;
// const uint8_t PACKET_LOWER = 0x20;
// const uint8_t ECHO_1ST = 0x01;
// const uint8_t ECHO_2ND = 0x02;

// // 回波選擇模式
// enum EchoMode {
//     ECHO_MODE_ALL,    // 顯示所有回波
//     ECHO_MODE_1ST,    // 只顯示第一回波
//     ECHO_MODE_2ND     // 只顯示第二回波
// };

// #define LOG_TAG "UDPReceiver"
// #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// extern "C" JNIEXPORT jint JNICALL
// Java_com_example_pointcloudviewer_MainActivity_testNativeCall(JNIEnv* env, jobject /* this */) {
//     LOGI("Native test call successful!");
//     return 42; // 返回一個簡單的測試值
// }

// //Encoder lookup table  暫時變更 - 0305 - No3
// const int16_t lookup_table[] = {
//     1454,1459,1463,1362,1366,1371,1375,1379,1384,1388,1393,1397,1401,1406,1410,1415,1419,1423,1428,1432,1437,1441,1445
// };

// // Debug flag
// bool DEBUG = false;     //false; true;
// #define JPDEBUG 0

// // 定義點的結構
// struct LidarPoint {
//     float x, y, z;
//     float intensity;
//     float azimuth;
//     float elevation;
//     bool valid;
//     uint8_t echo_num;  // 回波序號
// };

// // 定義一個數據包結構，用於在線程間傳遞數據
// struct UdpPacket {
//     std::vector<uint8_t> data;
//     bool is_valid;
// };

// // 數據包緩衝隊列和相關同步原語
// std::queue<UdpPacket> packet_queue;
// std::mutex queue_mutex;
// std::condition_variable queue_cv;
// std::atomic<bool> running{true};

// // 用於統計性能的變數
// std::atomic<int> packet_counter{0};
// std::atomic<int> frame_counter{0};
// ros::Time performance_start_time;

// // Debug print functions
// void printInt(const char* str, int val) {
//     if (JPDEBUG) {
//         printf("[Debug msg] %s: 0x%x\n", str, val);
//     }
// }

// void printFloat(const char* str, float val) {
//     if (JPDEBUG) {
//         printf("[Debug msg] %s: %f\n", str, val);
//     }
// }

// void print3Float(const char* str, float val1, float val2, float val3) {
//     if (JPDEBUG) {
//         printf("[Debug msg] %s: %f, %f, %f\n", str, val1, val2, val3);
//     }
// }

// // 檢查數據包頭部 - 優化版本
// inline bool check_packet_header(const std::vector<uint8_t>& data) {
//     // 簡化檢查邏輯，只驗證魔數
//     return (data.size() == PACKET_SIZE) && 
//            (data[0] == HEADER_MAGIC[0]) && 
//            (data[1] == HEADER_MAGIC[1]) && 
//            (data[2] == HEADER_MAGIC[2]) && 
//            (data[3] == HEADER_MAGIC[3]);
// }

// // 優化版本的UDP解析函數
// void parse_udp_packet(const std::vector<uint8_t>& data, std::vector<LidarPoint>& points, bool& is_upper_packet, EchoMode echo_mode) {
//     // 快速檢查數據包頭部
//     if (!check_packet_header(data)) {
//         return;
//     }

//     // 解析回波序列和數據包類型
//     uint8_t time_offset = data[DATA_START_OFFSET];
//     uint8_t return_seq = data[DATA_START_OFFSET+1];
//     uint8_t packet_type = return_seq & 0xF0;
//     uint8_t echo_num = return_seq & 0x0F;
    
//     // 根據回波模式決定是否處理這個包
//     if ((echo_mode == ECHO_MODE_1ST && echo_num != ECHO_1ST) || 
//         (echo_mode == ECHO_MODE_2ND && echo_num != ECHO_2ND)) {
//         return;
//     }

//     // 快速檢查包類型
//     if (packet_type != PACKET_UPPER && packet_type != PACKET_LOWER) {
//         return;
//     }

//     is_upper_packet = (packet_type == PACKET_UPPER);
    
//     // 解析方位角
//     int16_t azimuth_raw_in = (data[DATA_START_OFFSET+3] << 8) | data[DATA_START_OFFSET+2];
//     //float azimuth_raw = azimuth_raw_in / 100.0f;      //取消 JP-0227
//     //float azimuth = round(azimuth_raw_in* AZIMUTH_RESOLUTION * 100) / 100;      //四捨五入計算
//     float azimuth = ((lookup_table[azimuth_raw_in])/100); // 改用Lookup table (暫時變更) JP-0227

//     // 預先計算方位角的三角函數值，避免重複計算
//     float rad_azimuth = azimuth * M_PI / 180.0f;
//     float cos_azimuth = cos(rad_azimuth);
//     float sin_azimuth = sin(rad_azimuth);

//     // 預先獲取起始仰角
//     float elevation_start = is_upper_packet ? ELEVATION_START_UPPER : ELEVATION_START_LOWER;
//     printFloat("elevation_start:",elevation_start );
    
//     // 預分配需要的點數
//     int expected_points = 0;
//     switch(echo_mode) {
//         case ECHO_MODE_ALL:
//             expected_points = POINTS_PER_PACKET;
//             break;
//         case ECHO_MODE_1ST:
//         case ECHO_MODE_2ND:
//             if ((echo_mode == ECHO_MODE_1ST && echo_num == ECHO_1ST) ||
//                 (echo_mode == ECHO_MODE_2ND && echo_num == ECHO_2ND)) {
//                 expected_points = POINTS_PER_PACKET;
//             }
//             break;
//     }
    
//     if (expected_points > 0) {
//         // 確保有足夠空間存儲點
//         size_t current_size = points.size();
//         points.resize(current_size + expected_points);
//     }

//     // 處理數據點
//     int point_start = DATA_START_OFFSET + 4;  // 數據點開始位置
//     int point_idx = points.size() - expected_points;
    
//     for (int i = 0; i < POINTS_PER_PACKET; i++) {
//         // 計算數據偏移量
//         int data_offset = point_start + (i * 3);
        
//         // 快速讀取強度和半徑
//         uint8_t intensity = data[data_offset];
//         uint16_t radius = (data[data_offset+2] << 8) | data[data_offset+1];
        
//         // 跳過無效點
//         if (intensity > 255) {
//             continue;
//         }
        
//         // 計算仰角及其三角函數值
//         float elevation = elevation_start + (i * ELEVATION_STEP);
//         float rad_elevation = elevation * M_PI / 180.0f;
//         float cos_elevation = cos(rad_elevation);
//         float sin_elevation = sin(rad_elevation);
        
//         // 計算笛卡爾坐標
//         LidarPoint& point = points[point_idx++];
//         point.valid = true;
//         point.echo_num = echo_num;
//         point.azimuth = azimuth;
//         point.elevation = elevation;
//         point.intensity = static_cast<float>(intensity);
//         point.y = radius * cos_elevation * cos_azimuth;     //JP-0227 測試X-Y互換
//         point.x = radius * cos_elevation * sin_azimuth;      //JP-0227 測試X-Y互換
//         point.z = radius * sin_elevation;

//         //print3Float("XYZ:", point.x, point.y, point.z);
//     }
    
//     // 如果實際點數少於預期，則調整大小
//     if (point_idx < points.size()) {
//         points.resize(point_idx);
//     }
// }

// // 創建PointCloud2消息 - 優化版本
// sensor_msgs::PointCloud2 create_point_cloud_msg(const std::vector<LidarPoint>& points) {
//     sensor_msgs::PointCloud2 msg;
//     msg.header.stamp = ros::Time::now();
//     msg.header.frame_id = "rslidar";
//     msg.height = 1;
//     msg.width = points.size();
//     msg.is_bigendian = false;
//     msg.is_dense = false;
//     msg.point_step = 20;  // 5 個 float: x, y, z, intensity, echo_num
//     msg.row_step = msg.point_step * msg.width;
    
//     // 僅創建一次字段定義
//     static std::vector<sensor_msgs::PointField> fields;
//     if (fields.empty()) {
//         sensor_msgs::PointField field;
//         field.datatype = sensor_msgs::PointField::FLOAT32;
//         field.count = 1;
        
//         field.name = "x";
//         field.offset = 0;
//         fields.push_back(field);
        
//         field.name = "y";
//         field.offset = 4;
//         fields.push_back(field);
        
//         field.name = "z";
//         field.offset = 8;
//         fields.push_back(field);
        
//         field.name = "intensity";
//         field.offset = 12;
//         fields.push_back(field);
        
//         field.name = "echo_num";
//         field.offset = 16;
//         fields.push_back(field);
//     }
    
//     msg.fields = fields;
    
//     // 分配數據空間
//     msg.data.resize(msg.row_step);
    
//     // 直接複製內存塊而不是單點賦值
//     float* dst_ptr = reinterpret_cast<float*>(msg.data.data());
//     for (const auto& point : points) {
//         *dst_ptr++ = point.x;
//         *dst_ptr++ = point.y;
//         *dst_ptr++ = point.z;
//         *dst_ptr++ = point.intensity;
//         *dst_ptr++ = static_cast<float>(point.echo_num);
//     }
    
//     return msg;
// }

// // UDP接收線程函數
// void udp_receiver_thread() {
//     try {
//         boost::asio::io_service io_service;
//         udp::socket socket(io_service, udp::endpoint(udp::v4(), UDP_PORT));
//         socket.set_option(boost::asio::socket_base::receive_buffer_size(8388608)); // 8MB 接收緩衝區
        
//         // 預分配接收緩衝區和重用端點
//         std::vector<uint8_t> recv_buffer(PACKET_SIZE);
//         udp::endpoint remote_endpoint;
        
//         // 設置socket為非阻塞模式
//         socket.non_blocking(true);
        
//         while (running) {
//             boost::system::error_code error;
//             size_t len = socket.receive_from(boost::asio::buffer(recv_buffer), remote_endpoint, 0, error);
            
//             if (error == boost::asio::error::would_block) {
//                 // 沒有數據可讀，短暫休眠避免CPU佔用過高
//                 std::this_thread::sleep_for(std::chrono::microseconds(100));
//                 continue;
//             } else if (error) {
//                 ROS_ERROR("Socket error: %s", error.message().c_str());
//                 continue;
//             }
            
//             if (len == PACKET_SIZE) {
//                 // 創建數據包並放入隊列
//                 UdpPacket packet;
//                 packet.data = recv_buffer;  // 這裡會複製數據
//                 packet.is_valid = true;
                
//                 {
//                     std::lock_guard<std::mutex> lock(queue_mutex);
//                     packet_queue.push(std::move(packet));
//                 }
                
//                 packet_counter.fetch_add(1, std::memory_order_relaxed);
//                 queue_cv.notify_one();
//             }
//         }
//     } catch (const std::exception& e) {
//         ROS_ERROR("UDP Receiver thread exception: %s", e.what());
//         running = false;
//     }
// }

// // 主處理函數
// void udp_to_pointcloud2(ros::Publisher& pub, EchoMode echo_mode) {
//     // 用於追踪掃描線狀態
//     bool got_upper = false;
//     bool got_lower = false;
//     int line_count = 0;
    
//     // 幀緩衝區 - 預先分配空間
//     std::vector<LidarPoint> frame_points_buffer;
//     frame_points_buffer.reserve(TOTAL_POINTS_PER_LINE * LINES_PER_FRAME);
    
//     // 線掃描緩衝區
//     std::vector<LidarPoint> line_points;
//     line_points.reserve(TOTAL_POINTS_PER_LINE);
    
//     // 性能監控變數
//     ros::Time last_report_time = ros::Time::now();
    
//     while (ros::ok() && running) {
//         UdpPacket packet;
//         bool has_packet = false;
        
//         // 從隊列中獲取數據包
//         {
//             std::unique_lock<std::mutex> lock(queue_mutex);
//             if (!packet_queue.empty()) {
//                 packet = std::move(packet_queue.front());
//                 packet_queue.pop();
//                 has_packet = true;
//             } else {
//                 // 等待新數據包，最多100ms
//                 queue_cv.wait_for(lock, std::chrono::milliseconds(100));
//             }
//         }
        
//         if (!has_packet) {
//             continue;
//         }
        
//         // 解析數據包
//         bool is_upper_packet = false;
//         line_points.clear();  // 準備接收新的線掃描點
        
//         parse_udp_packet(packet.data, line_points, is_upper_packet, echo_mode);
        
//         if (line_points.empty()) {
//             continue;  // 跳過沒有有效點的數據包
//         }
        
//         // 更新掃描線狀態
//         if (is_upper_packet) {
//             got_upper = true;
//         } else {
//             got_lower = true;
//         }
        
//         // 檢查是否收到完整的一條掃描線
//         if (got_upper && got_lower) {
//             // 將掃描線點添加到幀緩衝區
//             frame_points_buffer.insert(frame_points_buffer.end(), line_points.begin(), line_points.end());
//             line_count++;
            
//             // 重置掃描線狀態
//             got_upper = false;
//             got_lower = false;
            
//             // 更新進度 (每500條線輸出一次)
//             if (line_count % 500 == 0) {
//                 ROS_DEBUG("Collected %d/%d scan lines (%.1f%%)", 
//                          line_count, LINES_PER_FRAME, 
//                          (line_count * 100.0) / LINES_PER_FRAME);
//             }
            
//             // 檢查是否完成一幀
//             if (line_count >= LINES_PER_FRAME) {
//                 ROS_DEBUG("Publishing frame with %d lines (%zu points)", 
//                           line_count, frame_points_buffer.size());
                
//                 // 創建並發布點雲消息
//                 sensor_msgs::PointCloud2 pcl_msg = create_point_cloud_msg(frame_points_buffer);
//                 pub.publish(pcl_msg);
                
//                 // 更新幀計數器
//                 frame_counter.fetch_add(1, std::memory_order_relaxed);
                
//                 // 性能報告 - 每5秒輸出一次
//                 ros::Time current_time = ros::Time::now();
//                 if ((current_time - last_report_time).toSec() >= 5.0) {
//                     double elapsed = (current_time - performance_start_time).toSec();
//                     double fps = frame_counter.load() / elapsed;
//                     int packets = packet_counter.load();
                    
//                     ROS_INFO("Performance: %.2f fps, processed %d packets, queue size: %zu", 
//                               fps, packets, packet_queue.size());
                              
//                     last_report_time = current_time;
//                 }
                
//                 // 清空緩衝區，重置計數器
//                 frame_points_buffer.clear();
//                 line_count = 0;
//             }
//         }
        
//         // 允許ROS處理回調
//         ros::spinOnce();
//     }
// }

// int main(int argc, char** argv) {
//     ros::init(argc, argv, "udp_to_pointcloud2");
//     ros::NodeHandle nh;
//     ros::NodeHandle private_nh("~");
    
//     // 從參數獲取配置
//     int echo_mode_param = 0;  // 默認顯示所有回波
//     private_nh.param("echo_mode", echo_mode_param, 0);
//     private_nh.param("debug", DEBUG, false);
    
//     EchoMode echo_mode = static_cast<EchoMode>(echo_mode_param);
    
//     // 創建發布者
//     std::string topic_name;
//     switch(echo_mode) {
//         case ECHO_MODE_1ST:
//             topic_name = "/pointcloud_udp_1st_echo";
//             break;
//         case ECHO_MODE_2ND:
//             topic_name = "/pointcloud_udp_2nd_echo";
//             break;
//         default:
//             topic_name = "/pointcloud_udp";
//             break;
//     }
    
//     ros::Publisher pub = nh.advertise<sensor_msgs::PointCloud2>(topic_name, 10);
    
//     // 顯示配置信息
//     std::string echo_mode_str;
//     switch(echo_mode) {
//         case ECHO_MODE_1ST:
//             echo_mode_str = "First Echo Only (ECHO_1ST)";
//             break;
//         case ECHO_MODE_2ND:
//             echo_mode_str = "Second Echo Only (ECHO_2ND)";
//             break;
//         default:
//             echo_mode_str = "All Echoes";
//             break;
//     }
    
//     ROS_INFO("Starting LiDAR UDP to PointCloud2 node (Optimized)...");
//     ROS_INFO("Echo Mode: %s", echo_mode_str.c_str());
//     ROS_INFO("Configuration: Collecting %d lines per frame", LINES_PER_FRAME);
//     ROS_INFO("Debug mode: %s", DEBUG ? "ON" : "OFF");
    
//     try {
//         // 初始化性能監控
//         performance_start_time = ros::Time::now();
        
//         // 啟動接收線程
//         std::thread receiver(udp_receiver_thread);
        
//         // 主線程處理數據
//         udp_to_pointcloud2(pub, echo_mode);
        
//         // 清理
//         running = false;
//         if (receiver.joinable()) {
//             receiver.join();
//         }
//     } catch (const std::exception& e) {
//         ROS_ERROR("Exception: %s", e.what());
//         return 1;
//     }
    
//     return 0;
// }

// /*
// 效能優化說明：

// 1. 多線程架構：
//    - 使用一個專門的線程接收UDP數據包，減少網絡IO等待時間
//    - 主線程專注於處理和發布點雲數據
//    - 通過隊列在線程間傳遞數據，避免阻塞和數據丟失

// 2. 內存優化：
//    - 預分配緩衝區大小，減少動態內存分配
//    - 重用對象和緩衝區，避免反覆創建和銷毀
//    - 優化點雲數據結構，使用更高效的內存佈局
//    - 使用移動語義和右值引用優化數據傳遞

// 3. 算法優化：
//    - 簡化數據包檢查邏輯，減少不必要的計算
//    - 預計算三角函數值，避免重複計算
//    - 使用內存塊複製代替單點賦值
//    - 避免過多的條件檢查和分支判斷

// 4. IO優化：
//    - 設置更大的接收緩衝區，減少包丟失
//    - 使用非阻塞套接字，提高IO效率
//    - 使用條件變量實現高效等待

// 5. 性能監控：
//    - 添加性能統計和報告功能
//    - 實時計算並顯示FPS和處理的數據包數量
//    - 監控緩沖隊列大小，及時發現性能瓶頸

// 使用說明：
// 1. 編譯程式：
//    catkin_make

// 2. 運行程式：
//    - 顯示所有回波（默認）：
//      rosrun <package_name> udp_to_pointcloud2
   
//    - 只顯示第一回波：
//      rosrun <package_name> udp_to_pointcloud2 _echo_mode:=1
   
//    - 只顯示第二回波：
//      rosrun <package_name> udp_to_pointcloud2 _echo_mode:=2
   
//    - 啟用調試輸出：
//      rosrun <package_name> udp_to_pointcloud2 _debug:=true
//           rosrun udp_receiver udp_receiver_node _debug:=false

// 3. 效能監控：
//    程式會每5秒輸出當前的效能統計，包括：
//    - 幀率（FPS）
//    - 處理的數據包數量
//    - 緩衝隊列大小
// */

