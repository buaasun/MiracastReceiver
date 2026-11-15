# Android 9 只读窗口探针

用于区分 uiautomator 的活动窗口根节点为空，与所有交互窗口根节点均不可读。
不是 Miracast 测试通过的证据，也不改变配对状态。

- Java → DEX 只在 `.github/workflows/uia-probe.yml` 的 GitHub Actions 编译。
- 不安装 APK。临时 jar 通过 `CLASSPATH=<jar> app_process /system/bin UiWindowProbe` 运行；不接受参数。
- 需要 shell/root 已授权的 UiAutomation 访问。构造器及连接接口为 Android 9 隐藏 API，其他版本未验证。
- 使用 `connect(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)` 保留现有无障碍服务；仅修改自身临时自动化服务的窗口读取 flags，结束时断开自身连接。
- 不含输入注入、点击、配对、系统设置、网络或进程控制接口。读取前后应核对既有无障碍服务仍在。
- 最多读取64个窗口、合计512个节点、深度16，每节点最多128个子节点；只导出根包名为 `android` 的系统窗口树。其他应用仅窗口元数据及根包名，不导出应用内容。
- 使用外层有界运行时限。stdout 最后一行是 schema 1 JSON；stderr 保留平台诊断。`status=observed` 仅表示完成读取，空树仍是空树；退出3表示读取或收尾异常。
- 文本、窗口标题、设备名属于本地诊断隐私；不要提交/上传实际设备输出到公开仓库或 CI。CI 产物只有 jar、提交号和 SHA256。
- 该探针目前只诊断，不可代替调用方对当前PC、对端、本轮请求、系统邀请窗口和两次新鲜一致读取的身份核验。
