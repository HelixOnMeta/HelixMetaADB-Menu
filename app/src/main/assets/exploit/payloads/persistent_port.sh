#!/system/bin/sh
# desc: wake the display and turn Wi-Fi back on (runs first, every time)
#
# configures an Android device to permanently listen for network-based ADB connections on port 5555

runcon u:r:init:s0 setprop persist.adb.tcp.port 5555