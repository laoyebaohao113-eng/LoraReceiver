package com.lora.receiver;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private ListView  lvHistory;
    private TextView  tvEmpty;
    private HistoryAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        lvHistory = findViewById(R.id.lv_history);
        tvEmpty   = findViewById(R.id.tv_empty);

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        Button btnClearAll = findViewById(R.id.btn_clear_all);
        btnClearAll.setOnClickListener(v -> {
            if (MainActivity.sHistory.isEmpty()) {
                Toast.makeText(this, "没有记录", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("清空所有接收记录？（文件不会删除）")
                .setPositiveButton("清空", (d, w) -> {
                    MainActivity.sHistory.clear();
                    mAdapter.notifyDataSetChanged();
                    updateEmpty();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        mAdapter = new HistoryAdapter(MainActivity.sHistory);
        lvHistory.setAdapter(mAdapter);
        updateEmpty();
    }

    private void updateEmpty() {
        if (MainActivity.sHistory.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvHistory.setVisibility(View.VISIBLE);
        }
    }

    //----------------------------------------------
    // 列表适配器
    //----------------------------------------------
    private class HistoryAdapter extends ArrayAdapter<FileRecord> {
        HistoryAdapter(List<FileRecord> list) {
            super(HistoryActivity.this, R.layout.item_history, list);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater()
                    .inflate(R.layout.item_history, parent, false);
            }
            FileRecord r = getItem(position);
            if (r == null) return convertView;

            TextView tvFilename    = convertView.findViewById(R.id.tv_filename);
            TextView tvFilesize    = convertView.findViewById(R.id.tv_filesize);
            TextView tvTime        = convertView.findViewById(R.id.tv_time);
            TextView tvEmailStatus = convertView.findViewById(R.id.tv_email_status);

            tvFilename.setText(r.fileName);
            tvFilesize.setText(r.getSizeStr());
            tvTime.setText(r.receiveTime);

            if (r.emailSent) {
                tvEmailStatus.setText("✔ 已发送");
                tvEmailStatus.setTextColor(0xFF2E7D32);
            } else if (r.emailError != null) {
                tvEmailStatus.setText("✘ 失败");
                tvEmailStatus.setTextColor(0xFFC62828);
            } else {
                tvEmailStatus.setText("— 未发");
                tvEmailStatus.setTextColor(0xFF757575);
            }

            return convertView;
        }
    }
}
