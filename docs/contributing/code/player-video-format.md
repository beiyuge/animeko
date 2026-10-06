# 播放分辨率标识

播放器右上角的 `PlaybackVideoFormatBadge` 默认显示当前视频规格, 控制栏隐藏时保留.
普通播放页通过 `VideoScaffold.topEndOverlay` 接入, 避开顶部控制栏、右侧按钮、系统安全区域及桌面标题栏;
画中画只渲染视频. TV 通过状态覆盖层接入, 位于时钟下方、自动跳过提示上方, 随侧边栏收缩后的主区域对齐.

`PlaybackVideoFormat` 表示输入视频的尺寸和动态范围. `playbackVideoFormatFlow` 仅在当前媒体
`MediaStatus.Ready` 时观察格式, 暂停和缓冲保留格式; 切源、加载、停止、结束、错误和释放清空.
页面还按 `VideoLoadingState.Succeed` 限制显示, 解析新资源期间不会保留旧标识.

平台读取在播放器的 `mainDispatcher` 执行, 250ms 检查一次当前格式并去除重复结果. 取消订阅停止检查.

| 平台 | 尺寸依据 | HDR 依据 |
| --- | --- | --- |
| Android / TV | ExoPlayer 当前已选择的视频输入 `videoFormat.width/height` | `colorInfo.colorTransfer` 明确为 ST2084/PQ 或 HLG |
| 桌面 mpv | `video-dec-params/w` 和 `h`, 在滤镜、缩放及用户覆盖之前 | `video-dec-params/gamma` 明确为 `pq` 或 `hlg` |
| iOS AVKit | 当前 `AVPlayerItem.presentationSize`, 包含旋转和像素比例 | Unknown: 接口未可靠暴露当前自适应变体的色彩传递信息 |
| 其他后端 | Mediamp 提供的当前视频尺寸, 不可用时隐藏 | Unknown |

标识中的 HDR 描述视频内容的色彩传递, 不表示显示设备已启用 HDR 输出. 分辨率、10-bit 像素格式、
BT.2020 色域、资源标题和屏幕能力均不作为 HDR 判定依据. 未知动态范围不显示 HDR.

常见规格按短边映射为 `480p`、`1080p`、`4K`、`8K` 等, 支持竖屏和 90° 旋转.
标准长边且短边为标称高度的 65%–100% 时识别宽银幕裁边规格; 其他画幅显示实际 `宽×高`.
这些标签描述尺寸规格, 不判定逐行/隔行扫描方式.

相关测试位于 `video-player` 的 `PlaybackVideoFormatTest`、`MpvVideoDynamicRangeTest`、
`ExoPlayerVideoFormatTest` 和 `PlaybackVideoFormatBadgeTest`. UI 测试覆盖窄/宽窗口、安全边距、
顶部和右侧控件避让、控制栏隐藏、格式更新、空格式与画中画. 可通过测试 JVM 属性
`ani.ui.evidence.dir` 将渲染截图输出到仓库外, 用于人工检查.

原生接口参考: [mpv video-dec-params](https://mpv.io/manual/master/#video-dec-params),
[Media3 ColorInfo](https://developer.android.com/reference/androidx/media3/common/ColorInfo).
