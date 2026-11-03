# MiracastReceiver 在 S905L3 电视上的调试进展

设备：Amlogic S905L3 / Android 9 / ARM32，ADB 192.168.2.143:5555，应用 com.weekd.miracastreceiver v1.2.9
发送端：Windows 11（SCG-R9000P，MT7921 单射频），Win+K
仓库：fork buaasun/MiracastReceiver，本地 D:\code\tv\MiracastReceiver（分支 main，HEAD 1a1ddc4）
时间线来源：session `34d3d757-...jsonl`（北京时间）

## 当前状态（11-03 06:52）
- 代码：1a1ddc4 已推送并装机（卡死检测 + 重建退避 + IDR 请求），**尚未在真实投屏中验证**。
- 阻塞：Win+K 配对阶段。电视弹「连接邀请」需在约 60 秒内点「接受」；自动点击时序已正确（PBC-REQ 后 2s 激活 WPS-PBC），但 Windows 仍在 ~62s 后 P2P-DEVICE-LOST，GO 内无客户端。
- ~~推测：MT7921 单射频连 5G 与电视 GO 同频冲突~~ **已被用户否定**（11-03 用户：以前 5G 也能投成功）。卡点仍未知。

## 时间线
### 11-01 安装与首次排查
- 21:54 安装 v1.2.9；22:24 反复弹窗；22:37 诊断：P2P 卡在 WPS 配对，「接受」后 2 分钟超时
- 23:44 怀疑 K3 ap_isolate → 23:50 **推翻**：isolate=0，互 ping 正常，路由无问题
### 11-02 00:00–02:00 根因定位
- 00:33 发现 WfdRootHelper 硬编码 `p2p0`，电视实际是 `p2p-dev-wlan0` / `p2p-wlan0-N`
- 00:36 手工把 WFD IE 写到两个接口后 Windows 成功关联
- 01:02–01:20 做过 watchdog/crond/开机钩子，被用户要求删除，只保留手动脚本
- 01:53 发现作者只在 debug 版打日志，release 抓不到
### 11-02 10:48–13:40 fork / CI / debug 包
- 73e2710、1230998、d71c5a8 修 CI；b8f92e9 修 socket 路径；93fedad 加每秒诊断
- 12:16 误判画面卡住 → 13:05 **推翻**：电视是扩展屏，没窗口所以是静态壁纸
- 41e4c89 合并上游；b2f19a5 去掉诊断脚手架；新增 MiracastTuning 三开关
### 11-02 14:54–19:36 开关对比与上游基线
- 15:03 实测 RTCP 双端口非必需
- 16:28 **关键**：上游基线（baseline-upstream）在这台电视上本来就能投屏 → 之前"修复必需"的结论全部推翻
- 19:36 连续近 3 小时画面正常，lost=5
### 11-02 20:45–22:37 其他方案
- Chromecast 不可行（CAF 是网页接收端；CastAndroidTvReceiver 需 GMS）
- AirPlay 不可行：与 Miracast 抢唯一硬解，约 200 帧后 TCP 背压卡死
### 11-02 22:45–11-03 00:08 画面冻结
- 根因链：解复用线程 dequeueInputBuffer 阻塞 100ms → 队列溢出 → onDiscontinuity → awaitingKeyframe → 丢 27–31% 帧
- 23:57 48e9961：溢出不再触发 onDiscontinuity，队列 64，输入超时 5ms，CI run 41476543849
- 00:08 宣布「验证通过」 → 00:09 **推翻**：电视 00:04:47 vs 本机 00:09:16，画面冻结
- 原因：`decoded` 统计的是喂入尝试，不是显示帧；OMX 解码器静默死锁（输出线程 futex_wait），isHealthy 仍为 true
### 11-03 00:22–02:37 卡死检测 + P2P 无法复现
- 3c4dfe4 卡死检测 + wfd_idr_request；1a1ddc4 判据改为「喂了数据却没出帧」、重建退避 1s→10s、release 走 reaper 线程
- 00:39 失误：`svc wifi disable` 切断电视唯一网络（**禁止再做**）
- 01:50–02:36 改频段/disallow_freq/p2p_flush 等运行时折腾，均无效且有风险
### 11-03 05:43–06:52 再次连不上
- 06:03 用户提示「你点同意了吗」→ 发现一直有「连接邀请」弹窗未点
- 弹窗「接受」中心 ≈ (1302,613)，`input tap 1302 613` 有效，但窗口仅 ~60 秒
- 06:40:58 PBC-REQ→tap，06:41:00 PBC active，06:42 Windows 仍放弃
- 11-01 的反复弹窗是 UserAuthorizingJoinState→GroupCreatedState 循环 13 次；11-03 不是循环，是点晚了

## 已证实
- 上游原代码可用；p2p 接口名是 `p2p-dev-wlan0`/`p2p-wlan0-N`；WFD IE 全局共享，建组不覆盖
- 电视 P2P 与 station 同一物理网卡，每次建组 MAC 随机
- 冻结根因之一：队列溢出触发 IDR 等待（已修）；之二：OMX 死锁（检测代码已写，未验证）

## 已推翻的假设
K3 ap_isolate；PNG 哈希判画面卡住；socket 硬编码是唯一根因；全关开关=上游基线；videoPes 适合推上游；解码跟不上；48e9961 验证通过；watchdog 导致 IE 丢失；p2p_flush 等是 11-03 失败根因

## 我犯过的错（避免重犯）
1. 用 `decoded` 计数宣布验证通过，没看真实画面（用 `out=` 和电视时钟截图）
2. `svc wifi disable` 断网
3. 误诊为频段/缓存问题，实际是弹窗未点
4. 对 Windows 侧未实测的单射频推测当结论

## 待办
- [ ] 2 秒自动同意 APK（debug 开关，PBC-REQ 后延迟 2s 用 root `input tap` 点「接受」）
- [ ] 验证 1a1ddc4：out= 增长、stalls=、画面时间追上 PC
- [ ] 验证单射频假设：让 PC 暂时断开 5G Wi-Fi（或连 2.4G / 有线）后再投
- [ ] review 遗留：send() 加锁、outCseq 原子化、sessionId @Volatile、IDR 请求移出 demux 线程

## 提交
73e2710 · 1230998 · b8f92e9 · d71c5a8 · 93fedad · 41e4c89 · b2f19a5 · 48e9961 · 3c4dfe4 · 1a1ddc4
