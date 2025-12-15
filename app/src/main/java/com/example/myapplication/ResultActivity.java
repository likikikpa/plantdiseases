package com.example.myapplication;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatActivity;

public class ResultActivity extends AppCompatActivity {
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
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_DISEASE = "extra_disease";
    public static final String EXTRA_CONFIDENCE = "extra_confidence";
    public static final String EXTRA_RECOMMENDATIONS = "extra_recommendations";

    private Bitmap loadedBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        applySystemBarsInsets();

        ImageButton btnClose = findViewById(R.id.btnClose);
        ImageView iv = findViewById(R.id.ivResult);

        TextView tvDisease = findViewById(R.id.tvDisease);
        TextView tvConf = findViewById(R.id.tvConfidence);
        TextView tvRecs = findViewById(R.id.tvRecs);

        btnClose.setOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        String disease = getIntent().getStringExtra(EXTRA_DISEASE);
        int conf = getIntent().getIntExtra(EXTRA_CONFIDENCE, 0);
        String recs = getIntent().getStringExtra(EXTRA_RECOMMENDATIONS);

        tvDisease.setText("Болезнь: " + (disease != null ? disease : "—"));
        tvConf.setText("Уверенность: " + conf + "%");
        tvRecs.setText("Рекомендации: " + (recs != null ? recs : "—"));

        if (path != null) {
            loadedBitmap = ScanStorage.decodeSampled(path, 1400, 1400);
            if (loadedBitmap != null) iv.setImageBitmap(loadedBitmap);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadedBitmap != null) {
            loadedBitmap.recycle();
            loadedBitmap = null;
        }
    }
}
