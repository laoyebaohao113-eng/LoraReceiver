package com.lora.receiver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences 统一管理
 */
public class Prefs {
    private static final String NAME        = "lora_prefs";
    private static final String KEY_FROM     = "gmail_from";
    private static final String KEY_PWD      = "gmail_pwd";
    private static final String KEY_TO       = "gmail_to";

    // 服务器上传相关
    private static final String KEY_DEVICE_NAME   = "device_name";
    private static final String KEY_SERVER_HOST   = "server_host";
    private static final String KEY_SERVER_PORT   = "server_port";
    private static final String KEY_UPLOAD_ENABLE = "upload_enable";

    private final SharedPreferences mPrefs;

    public Prefs(Context ctx) {
        mPrefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // -------- Gmail --------
    public String getFrom()     { return mPrefs.getString(KEY_FROM, ""); }
    public String getPassword() { return mPrefs.getString(KEY_PWD,  ""); }
    public String getTo()       { return mPrefs.getString(KEY_TO,   ""); }

    public void save(String from, String pwd, String to) {
        mPrefs.edit()
              .putString(KEY_FROM, from)
              .putString(KEY_PWD,  pwd.replace(" ", ""))
              .putString(KEY_TO,   to)
              .apply();
    }

    public boolean isConfigured() {
        return !getFrom().isEmpty() && !getPassword().isEmpty() && !getTo().isEmpty();
    }

    // -------- 服务器上传 --------
    public String  getDeviceName()    { return mPrefs.getString(KEY_DEVICE_NAME, ""); }
    public String  getServerHost()    { return mPrefs.getString(KEY_SERVER_HOST, "94.103.5.102"); }
    public int     getServerPort()    { return mPrefs.getInt(KEY_SERVER_PORT, 8765); }
    public boolean isUploadEnabled()  { return mPrefs.getBoolean(KEY_UPLOAD_ENABLE, false); }

    public void saveServerConfig(String deviceName, String host, int port, boolean enable) {
        mPrefs.edit()
              .putString(KEY_DEVICE_NAME, deviceName)
              .putString(KEY_SERVER_HOST, host)
              .putInt(KEY_SERVER_PORT, port)
              .putBoolean(KEY_UPLOAD_ENABLE, enable)
              .apply();
    }

    public boolean isServerConfigured() {
        return !getDeviceName().isEmpty() && !getServerHost().isEmpty();
    }
}
