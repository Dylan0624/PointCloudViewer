# 點雲瀏覽器 (PointCloud Viewer) Android 應用程式

這是一款功能強大的 Android 應用程式，用於視覺化從 LiDAR 感測器透過 UDP 接收的 3D 點雲數據。本應用具有即時 3D 視覺化界面和手勢控制功能，讓用戶能夠互動式查看點雲數據。

## 功能特點

- **即時點雲視覺化**：渲染從 LiDAR 感測器串流的 3D 點雲數據
- **UDP 通訊**：在 7000 端口接收數據，並進行優化的數據包處理
- **互動式查看控制**：
  - 捏合手勢縮放
  - 單指旋轉
  - 雙指平移
  - 雙擊重置視圖
- **視覺化選項**：
  - 切換座標軸顯示
  - 切換網格線顯示
  - 基於強度的著色
  - 控制點顯示比例
- **使用者界面**：
  - 抽屜式選單進行設置
  - 強度圖例顯示色彩漸變
  - 全屏沉浸式視圖

## 技術架構

### 原生組件 (C++)

- `udp_receiver_node.cpp`：核心 UDP 數據包處理模組
- 優化的 LiDAR 數據解析，使用查找表進行角度校準
- 幀檢測和重建算法
- 高速數據處理的記憶體管理

### Android 組件 (Kotlin)

- `MainActivity.kt`：主要入口點和 UI 控制器
- `PointCloudRenderer.kt`：基於 OpenGL 的 3D 點雲視覺化渲染器
- `UDPManager.kt`：管理 UDP 套接字通訊和數據處理
- `TouchController.kt`：處理視圖操作的手勢識別
- `DrawerMenuManager.kt`：管理設置抽屜 UI
- `LegendView.kt`：用於顯示強度色彩圖例的自定義視圖

## 系統需求

- Android 8.0 (API level 26) 或更高版本
- 支援 OpenGL ES 2.0 的設備
- 接收 UDP 數據的網絡連接

## 使用方法

1. **啟動應用程式**：應用程式將自動開始在 7000 端口監聽 UDP 數據包
2. **視圖控制**：
   - 旋轉：用一根手指觸摸並拖曳
   - 縮放：用兩根手指進行捏合
   - 平移：用兩根手指觸摸並拖曳
   - 重置視圖：在螢幕上任意位置雙擊
3. **設置**：點擊左上角的漢堡圖標進入選單
   - 切換坐標軸和網格線顯示
   - 調整點顯示比例以優化性能
   - 切換強度圖例顯示

## 技術細節

### UDP 協議格式

本應用程式設計用於處理具有以下格式的 LiDAR 數據包：
- 數據包大小：816 位元組
- 標頭大小：32 位元組
- 數據大小：784 位元組
- 標頭魔術碼：`0x55, 0xaa, 0x5a, 0xa5`

### 性能優化

- 多線程處理，使用隊列管理確保視覺化流暢
- 幀邊界檢測實現精確的點雲重建
- 數據包配對邏輯確保完整的掃描線
- 預先記憶體分配以減少垃圾回收
- 緩衝渲染以維持 UI 響應性能

### 視覺化技術

- 坐標系統：右手系，Y 軸向上
- 著色：基於強度的漸變，從藍色（低）到紅色（高）
- 深度測試確保正確遮擋
- 點大小調整以獲得最佳可見度

## 開發

### 專案結構

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/example/pointcloudviewer/
│   │   │   ├── MainActivity.kt
│   │   │   ├── PointCloudRenderer.kt
│   │   │   ├── UDPManager.kt
│   │   │   ├── TouchController.kt
│   │   │   ├── DrawerMenuManager.kt
│   │   │   └── LegendView.kt
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── udp_receiver.h
│   │   │   └── udp_receiver_node.cpp
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   ├── raw/
│   │   │   │   └── lookup_table.txt
│   │   │   └── values/
│   │   └── AndroidManifest.xml
└── build.gradle
```

### 構建專案

1. 克隆儲存庫
2. 在 Android Studio 中打開專案
3. 在兼容設備或模擬器上構建和運行

## 授權

[您的授權信息]

## 致謝

- 本應用程式使用優化的 UDP 數據包處理技術處理 LiDAR 數據
- 包含專門的查找表進行角度校準
- 使用 OpenGL ES 實現高效 3D 渲染

## 聯絡

[您的聯絡信息]
