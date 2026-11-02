#!/system/bin/sh
# Miracast WFD IE 修复脚本
#
# 问题根因：
#   MiracastReceiver 的 WfdRootHelper.kt 把 wpa_supplicant control socket 路径
#   硬编码成 p2p0：
#       /data/vendor/wifi/wpa/sockets/p2p0        <- 不存在
#       /data/misc/wifi/sockets/p2p0              <- 不存在
#       /data/vendor/wifi/wpa/sockets/wlan0       <- 兜底命中，但这是 STA 接口
#       /data/misc/wifi/sockets/wlan0
#   写到 wlan0 后只有全局 wifi_display 配置生效，P2P 接口上没有 WFD IE，
#   导致 Provision Discovery Response 发不出去，发送端无法接入 P2P 组。
#
# 修复：
#   写到 p2p-dev-wlan0（P2P device 接口）。wpa_supplicant 会自动把
#   WFD_SUBELEM 同步到所有 p2p-wlan0-N（P2P-GO）接口，因此不需要
#   枚举动态接口名。

SOCKDIR=/data/vendor/wifi/wpa/sockets
WPA_CLI=/vendor/bin/wpa_cli
SUBELEM=000600111c440032   # PRIMARY_SINK, control port 7236, 50Mbps

log() { echo "[wfd-fix] $*"; }

wfd() {
    iface="$1"; shift
    $WPA_CLI -i "$iface" -p "$SOCKDIR" "$@" 2>/dev/null | tr -d '\r\n'
}

main() {
    [ -d "$SOCKDIR" ] || { log "socket 目录不存在: $SOCKDIR"; exit 1; }

    # 优先写 p2p-dev-wlan0（会自动同步到 GO 接口）
    primary=""
    for c in p2p-dev-wlan0 p2p-dev-p2p0 p2p0; do
        [ -S "$SOCKDIR/$c" ] && { primary="$c"; break; }
    done

    if [ -z "$primary" ]; then
        log "未找到 P2P device 接口，退回 wlan0（可能无法投屏）"
        primary=wlan0
    fi

    wfd "$primary" SET wifi_display 1        >/dev/null
    wfd "$primary" WFD_SUBELEM_SET 0 $SUBELEM >/dev/null
    val="$(wfd "$primary" WFD_SUBELEM_GET 0)"

    if [ "$val" = "$SUBELEM" ]; then
        log "$primary -> $val  OK"
    else
        log "$primary -> ${val:-<无响应>}  失败"
        exit 1
    fi

    # 顺带覆盖所有已存在的 p2p-wlan0-N（冗余保护）
    for path in "$SOCKDIR"/p2p-wlan0-*; do
        [ -e "$path" ] || continue
        n="${path##*/}"
        wfd "$n" SET wifi_display 1 >/dev/null
        wfd "$n" WFD_SUBELEM_SET 0 $SUBELEM >/dev/null
        log "$n 已同步"
    done
}

main
