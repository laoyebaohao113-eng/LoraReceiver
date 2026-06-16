package com.lora.receiver;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 密码锁界面
 * 每次启动或从后台恢复时显示，输入正确密码才能进入主界面
 */
public class LockActivity extends AppCompatActivity {

    private static final String CORRECT_PASSWORD = "sos1234sos";

    private EditText etPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏上显示（允许在锁屏界面显示密码输入框）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                             WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_lock);

        etPassword = findViewById(R.id.et_password);
        Button btnConfirm = findViewById(R.id.btn_confirm);
        TextView tvTitle  = findViewById(R.id.tv_lock_title);

        tvTitle.setText("LoRa 接收器");

        btnConfirm.setOnClickListener(v -> {
            String input = etPassword.getText().toString();
            if (CORRECT_PASSWORD.equals(input)) {
                // 密码正确，跳转主界面
                startActivity(new Intent(this, MainActivity.class));
                finish();
            } else {
                Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show();
                etPassword.setText("");
                etPassword.requestFocus();
            }
        });
    }

    @Override
    public void onBackPressed() {
        // 禁止返回键退出密码界面
    }
}
