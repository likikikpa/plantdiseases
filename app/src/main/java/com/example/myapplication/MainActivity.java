package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
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
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView viewFinder;
    private ImageButton btnCapture;
    private ImageButton btnGallery;
    private View cropFrame;
    private ProgressBar progressAnalyze;

    private ImageCapture imageCapture;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarsInsets();

        viewFinder = findViewById(R.id.viewFinder);
        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        cropFrame = findViewById(R.id.cropFrame);
        progressAnalyze = findViewById(R.id.progressAnalyze);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        btnCapture.setOnClickListener(v -> takePhoto());
        btnGallery.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGalleryThumb();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    private void setAnalyzing(boolean analyzing) {
        runOnUiThread(() -> {
            progressAnalyze.setVisibility(analyzing ? View.VISIBLE : View.GONE);
            btnCapture.setEnabled(!analyzing);
            btnGallery.setEnabled(!analyzing);
        });
    }

    private void updateGalleryThumb() {
        ScanItem latest = ScanStorage.getLatest(this);
        if (latest == null) {
            btnGallery.setImageResource(android.R.drawable.ic_menu_gallery);
            return;
        }
        Bitmap thumb = ScanStorage.decodeSampled(latest.imagePath, 200, 200);
        if (thumb != null) {
            btnGallery.setImageBitmap(thumb);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                Toast.makeText(this, "Не удалось запустить камеру", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        Rect viewCropRect = getCropRectInPreview();
        setAnalyzing(true);

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    Bitmap bitmap = imageProxyToBitmap(image, image.getImageInfo().getRotationDegrees());
                    if (bitmap == null) {
                        showToast("Ошибка обработки изображения");
                        return;
                    }

                    Rect bitmapCropRect = mapViewRectToBitmap(viewCropRect, bitmap);

                    if (bitmapCropRect.width() <= 0 || bitmapCropRect.height() <= 0) {
                        showToast("Слишком маленькая область кропа");
                        return;
                    }

                    Bitmap cropped = Bitmap.createBitmap(
                            bitmap,
                            bitmapCropRect.left,
                            bitmapCropRect.top,
                            bitmapCropRect.width(),
                            bitmapCropRect.height()
                    );

                    // сохраняем приватно (НЕ в галерею)
                    // и этот же файл отправляем в OpenAI
                    File file = ScanStorage.saveScanJpeg(MainActivity.this, cropped);

                    // Анализ через OpenAI
                    DiagnosisResult result;
                    try {
                        result = OpenAiPlantApi.analyzePlantImage(file);
                    } catch (Exception e) {
                        e.printStackTrace();
                        result = DiagnosisResult.fallback("Ошибка запроса к OpenAI: " + e.getMessage());
                    }

                    // Добавляем в историю (с результатом)
                    ScanStorage.addScan(MainActivity.this, new ScanItem(
                            file.getAbsolutePath(),
                            result.disease,
                            result.confidencePercent,
                            result.recommendations,
                            System.currentTimeMillis()
                    ));

                    DiagnosisResult finalResult = result;
                    runOnUiThread(() -> {
                        setAnalyzing(false);
                        updateGalleryThumb();

                        Intent intent = new Intent(MainActivity.this, ResultActivity.class);
                        intent.putExtra(ResultActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
                        intent.putExtra(ResultActivity.EXTRA_DISEASE, finalResult.disease);
                        intent.putExtra(ResultActivity.EXTRA_CONFIDENCE, finalResult.confidencePercent);
                        intent.putExtra(ResultActivity.EXTRA_RECOMMENDATIONS, finalResult.recommendations);
                        startActivity(intent);
                    });

                    bitmap.recycle();
                    cropped.recycle();

                } catch (Exception e) {
                    e.printStackTrace();
                    showToast("Ошибка: " + e.getMessage());
                    setAnalyzing(false);
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                exception.printStackTrace();
                showToast("Ошибка при съёмке");
                setAnalyzing(false);
            }
        });
    }

    private void showToast(String s) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show());
    }

    // --- рамка в координатах PreviewView ---
    private Rect getCropRectInPreview() {
        int[] vfLoc = new int[2];
        int[] cfLoc = new int[2];

        viewFinder.getLocationOnScreen(vfLoc);
        cropFrame.getLocationOnScreen(cfLoc);

        int left = cfLoc[0] - vfLoc[0];
        int top = cfLoc[1] - vfLoc[1];
        int right = left + cropFrame.getWidth();
        int bottom = top + cropFrame.getHeight();

        return new Rect(left, top, right, bottom);
    }

    // перевод прямоугольника из координат PreviewView в координаты битмапа
    private Rect mapViewRectToBitmap(Rect viewRect, Bitmap bitmap) {
        int previewWidth = viewFinder.getWidth();
        int previewHeight = viewFinder.getHeight();

        if (previewWidth == 0 || previewHeight == 0) {
            return new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        float scaleX = (float) bitmap.getWidth() / (float) previewWidth;
        float scaleY = (float) bitmap.getHeight() / (float) previewHeight;

        int left = clamp((int) (viewRect.left * scaleX), 0, bitmap.getWidth());
        int top = clamp((int) (viewRect.top * scaleY), 0, bitmap.getHeight());
        int right = clamp((int) (viewRect.right * scaleX), 0, bitmap.getWidth());
        int bottom = clamp((int) (viewRect.bottom * scaleY), 0, bitmap.getHeight());

        return new Rect(left, top, right, bottom);
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // --- ImageProxy -> Bitmap (JPEG + YUV) ---
    private Bitmap imageProxyToBitmap(ImageProxy image, int rotationDegrees) {
        Bitmap bitmap = null;

        if (image.getFormat() == ImageFormat.JPEG && image.getPlanes().length > 0) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } else if (image.getFormat() == ImageFormat.YUV_420_888 && image.getPlanes().length == 3) {
            byte[] nv21 = yuv420888ToNv21(image);
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 95, out);
            byte[] jpegBytes = out.toByteArray();
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        }

        if (bitmap == null) return null;

        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }

        return bitmap;
    }

    private byte[] yuv420888ToNv21(ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();
        ImageProxy.PlaneProxy[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        byte[] out = new byte[width * height * 3 / 2];
        int pos = 0;

        // Y
        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                out[pos++] = yBuf.get(yRowStart + col * yPixelStride);
            }
        }

        // VU (NV21)
        int uvHeight = height / 2;
        int uvWidth = width / 2;
        for (int row = 0; row < uvHeight; row++) {
            int uvRowStart = row * uvRowStride;
            for (int col = 0; col < uvWidth; col++) {
                int offset = uvRowStart + col * uvPixelStride;
                out[pos++] = vBuf.get(offset);
                out[pos++] = uBuf.get(offset);
            }
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera();
            else {
                Toast.makeText(this, "Разрешение на камеру не выдано", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
