package com.lora.receiver;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private EditText etFrom, etPassword, etTo;
    private EditText etDeviceName, etServerHost, etServerPort;
    private SwitchCompat switchUpload;
    private boolean  mPwdVisible = false;
    private Prefs    mPrefs;
    private final ExecutorService mExec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mPrefs     = new Prefs(this);
        etFrom     = findViewById(R.id.et_from_email);
        etPassword = findViewById(R.id.et_app_password);
        etTo       = findViewById(R.id.et_to_email);

        etDeviceName = findViewById(R.id.et_device_name);
        etServerHost = findViewById(R.id.et_server_host);
        etServerPort = findViewById(R.id.et_server_port);
        switchUpload = findViewById(R.id.switch_upload);

        // 加载已有设置
        etFrom.setText(mPrefs.getFrom());
        etPassword.setText(mPrefs.getPassword());
        etTo.setText(mPrefs.getTo());

        etDeviceName.setText(mPrefs.getDeviceName());
        etServerHost.setText(mPrefs.getServerHost());
        etServerPort.setText(String.valueOf(mPrefs.getServerPort()));
        switchUpload.setChecked(mPrefs.isUploadEnabled());

        // 返回按钮
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // 显示/隐藏密码
        Button btnToggle = findViewById(R.id.btn_toggle_pwd);
        btnToggle.setOnClickListener(v -> {
            mPwdVisible = !mPwdVisible;
            if (mPwdVisible) {
                etPassword.setInputType(InputType.TYPE_CLASS_TEXT |
                                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnToggle.setText("隐藏");
            } else {
                etPassword.setInputType(InputType.TYPE_CLASS_TEXT |
                                        InputType.TYPE_TEXT_VARIATION_PASSWORD);
                btnToggle.setText("显示");
            }
            // 光标移到末尾
            etPassword.setSelection(etPassword.getText().length());
        });

        // 测试邮件
        Button btnTest = findViewById(R.id.btn_test_email);
        btnTest.setOnClickListener(v -> {
            if (!validateInput()) return;
            savePrefs();
            btnTest.setEnabled(false);
            btnTest.setText("发送中...");
            Toast.makeText(this, "正在发送测试邮件...", Toast.LENGTH_SHORT).show();

            mExec.execute(() -> {
                // 创建临时测试文件
                File testFile = new File(getCacheDir(), "test.txt");
                try {
                    FileOutputStream fos = new FileOutputStream(testFile);
                    fos.write("LoRa接收器测试邮件，配置正常！".getBytes("UTF-8"));
                    fos.close();
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        btnTest.setEnabled(true);
                        btnTest.setText("发送测试邮件");
                        Toast.makeText(this, "创建测试文件失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                GmailSender sender = new GmailSender(
                    mPrefs.getFrom(), mPrefs.getPassword(), mPrefs.getTo());
                String err = sender.send(
                    "[LoRa接收器] 测试邮件",
                    "邮件配置测试成功！\n\n发件人：" + mPrefs.getFrom() +
                    "\n收件人：" + mPrefs.getTo(),
                    testFile);

                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("发送测试邮件");
                    if (err == null) {
                        Toast.makeText(this, "✔ 测试邮件发送成功！", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "❌ 发送失败：" + err, Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        // 保存
        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            if (!validateInput()) return;
            savePrefs();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private boolean validateInput() {
        String from = etFrom.getText().toString().trim();
        String pwd  = etPassword.getText().toString().trim();
        String to   = etTo.getText().toString().trim();

        // 服务器上传开关开启时，设备名和服务器地址不能为空
        if (switchUpload.isChecked()) {
            String dev  = etDeviceName.getText().toString().trim();
            String host = etServerHost.getText().toString().trim();
            if (dev.isEmpty()) {
                etDeviceName.setError("请填写设备名称");
                etDeviceName.requestFocus();
                return false;
            }
            if (host.isEmpty()) {
                etServerHost.setError("请填写服务器地址");
                etServerHost.requestFocus();
                return false;
            }
        }

        if (from.isEmpty()) {
            etFrom.setError("请填写发件人邮箱");
            etFrom.requestFocus();
            return false;
        }
        if (!from.endsWith("@gmail.com")) {
            etFrom.setError("发件人必须是Gmail地址");
            etFrom.requestFocus();
            return false;
        }
        if (pwd.isEmpty()) {
            etPassword.setError("请填写应用专用密码");
            etPassword.requestFocus();
            return false;
        }
        if (pwd.replace(" ", "").length() < 16) {
            etPassword.setError("应用专用密码应为16位");
            etPassword.requestFocus();
            return false;
        }
        if (to.isEmpty() || !to.contains("@")) {
            etTo.setError("请填写有效的收件人邮箱");
            etTo.requestFocus();
            return false;
        }
        return true;
    }

    private void savePrefs() {
        mPrefs.save(
            etFrom.getText().toString().trim(),
            etPassword.getText().toString().trim(),
            etTo.getText().toString().trim()
        );

        String deviceName = etDeviceName.getText().toString().trim();
        String serverHost = etServerHost.getText().toString().trim();
        String portStr    = etServerPort.getText().toString().trim();
        int    port        = 8765;
        try { if (!portStr.isEmpty()) port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}

        mPrefs.saveServerConfig(deviceName, serverHost, port, switchUpload.isChecked());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExec.shutdownNow();
    }
}
