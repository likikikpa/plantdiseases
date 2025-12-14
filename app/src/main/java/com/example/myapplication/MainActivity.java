
package com.example.myapplication;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageButton;
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

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{Manifest.permission.CAMERA};

    private PreviewView viewFinder;
    private ImageButton btnCapture;
    private View cropFrame;

    private ImageCapture imageCapture;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        btnCapture = findViewById(R.id.btnCapture);
        cropFrame = findViewById(R.id.cropFrame);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
            );
        }

        btnCapture.setOnClickListener(v -> takePhoto());
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission) != PackageManager.PERMISSION_GRANTED) {
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
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                );

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                Toast.makeText(this,
                        "Не удалось запустить камеру",
                        Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // рамка в координатах PreviewView
        Rect viewCropRect = getCropRectInPreview();

        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        Bitmap bitmap = imageProxyToBitmap(
                                image,
                                image.getImageInfo().getRotationDegrees()
                        );
                        image.close();

                        if (bitmap == null) {
                            Toast.makeText(MainActivity.this,
                                    "Ошибка обработки изображения",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        Rect bitmapCropRect = mapViewRectToBitmap(viewCropRect, bitmap);

                        if (bitmapCropRect.width() <= 0
                                || bitmapCropRect.height() <= 0) {
                            Toast.makeText(MainActivity.this,
                                    "Слишком маленькая область кропа",
                                    Toast.LENGTH_SHORT).show();
                            saveBitmapToGallery(bitmap); // хотя бы целое фото
                            return;
                        }

                        Bitmap cropped = Bitmap.createBitmap(
                                bitmap,
                                bitmapCropRect.left,
                                bitmapCropRect.top,
                                bitmapCropRect.width(),
                                bitmapCropRect.height()
                        );

                        saveBitmapToGallery(cropped);
                        bitmap.recycle();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        exception.printStackTrace();
                        Toast.makeText(MainActivity.this,
                                "Ошибка при съёмке",
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    // --- работа с рамкой и координатами ---

    // рамка в координатах PreviewView
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

        int left = (int) (viewRect.left * scaleX);
        int top = (int) (viewRect.top * scaleY);
        int right = (int) (viewRect.right * scaleX);
        int bottom = (int) (viewRect.bottom * scaleY);

        left = clamp(left, 0, bitmap.getWidth());
        right = clamp(right, 0, bitmap.getWidth());
        top = clamp(top, 0, bitmap.getHeight());
        bottom = clamp(bottom, 0, bitmap.getHeight());

        return new Rect(left, top, right, bottom);
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // --- ImageProxy -> Bitmap с учётом JPEG и YUV ---


    private Bitmap imageProxyToBitmap(ImageProxy image, int rotationDegrees) {
        Bitmap bitmap = null;

        // JPEG
        if (image.getFormat() == ImageFormat.JPEG
                && image.getPlanes().length > 0) {

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        // YUV_420_888
        else if (image.getFormat() == ImageFormat.YUV_420_888
                && image.getPlanes().length == 3) {

            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    image.getWidth(),
                    image.getHeight(),
                    null
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0, image.getWidth(), image.getHeight()),
                    100,
                    out
            );
            byte[] jpegBytes = out.toByteArray();
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        }

        if (bitmap == null) {
            return null;
        }

        // поворот
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            bitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,
                    true
            );
        }

        return bitmap;
    }

    // --- сохранение в галерею ---

    private void saveBitmapToGallery(Bitmap bitmap) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis());
        String fileName = "plant_" + timeStamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PlantDiseases");
        }

        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
        );
        if (uri == null) {
            Toast.makeText(this,
                    "Ошибка сохранения",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                out.close();
                Toast.makeText(this,
                        "Фото сохранено",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this,
                    "Ошибка сохранения",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // --- обработка разрешений ---

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {


        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Разрешение на камеру не выдано",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}