一个简单的程序，使得你可以在局域网上便捷的控制路由器上openclash的开启状态。同时利用iperf3实现路由器到设备的测速。
初次启动程序会自动在"/storage/emulated/0/Android/data/com.lw5.opc/files/"目录下创建config.json配置文件，需按如下格式填入参数
{
  "host": "192.168.1.1",
  "username": "root",
  "password": "password"
}后重启应用，方可正常使用。
