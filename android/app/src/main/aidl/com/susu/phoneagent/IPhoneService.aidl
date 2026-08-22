package com.susu.phoneagent;
interface IPhoneService {
    String getForegroundApp();
    String pressHome();
    String launchApp(String packageName);
    String getDeviceStatus();
}
