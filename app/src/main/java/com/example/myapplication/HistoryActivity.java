package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class HistoryActivity extends AppCompatActivity {
    private void applySystemBarsInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
    private RecyclerView rv;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        applySystemBarsInsets();

        ImageButton btnBack = findViewById(R.id.btnBack);
        rv = findViewById(R.id.rvHistory);
        tvEmpty = findViewById(R.id.tvEmpty);

        btnBack.setOnClickListener(v -> finish());

        rv.setLayoutManager(new GridLayoutManager(this, 3));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        ArrayList<ScanItem> items = ScanStorage.loadScans(this);

        tvEmpty.setVisibility(items.isEmpty() ? TextView.VISIBLE : TextView.GONE);

        HistoryAdapter adapter = new HistoryAdapter(items, item -> {
            Intent intent = new Intent(HistoryActivity.this, ResultActivity.class);
            intent.putExtra(ResultActivity.EXTRA_IMAGE_PATH, item.imagePath);
            intent.putExtra(ResultActivity.EXTRA_DISEASE, item.disease);
            intent.putExtra(ResultActivity.EXTRA_CONFIDENCE, item.confidencePercent);
            intent.putExtra(ResultActivity.EXTRA_RECOMMENDATIONS, item.recommendations);
            startActivity(intent);
        });

        rv.setAdapter(adapter);
    }
}
