package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.loader.content.CursorLoader;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.TensorImage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private TextView resultTextView;
    private Interpreter tflite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        resultTextView = findViewById(R.id.resultTextView);
        Button captureButton = findViewById(R.id.captureButton);
        Button uploadButton = findViewById(R.id.uploadButton);

        checkAndRequestPermissions();

        cameraExecutor = Executors.newSingleThreadExecutor();

        captureButton.setOnClickListener(v -> {
            ImageView uploadedImageView = findViewById(R.id.uploadedImageView);
            PreviewView previewView = findViewById(R.id.previewView);
            uploadedImageView.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE);

            capturePhotoAndProcess();
        });
        uploadButton.setOnClickListener(v -> openImagePicker());

        // Initialize the TensorFlow Lite interpreter
        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            Log.e("TFLite", "Error initializing TensorFlow Lite interpreter", e);
        }

    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                imageCapture = new ImageCapture.Builder().build();

                PreviewView previewView = findViewById(R.id.previewView);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture);

            } catch (Exception e) {
                Log.e("CameraX", "Failed to bind camera use cases", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhotoAndProcess() {
        File photoFile = new File(getExternalFilesDir(null), "photo.jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Log.d("Capture", "Image saved successfully: " + photoFile.getAbsolutePath());

                        processImage(photoFile.getAbsolutePath());

                        processUploadedImage(photoFile.getAbsolutePath());


                        runOnUiThread(() -> {
                            // Tìm các view
                            ImageView uploadedImageView = findViewById(R.id.uploadedImageView);
                            PreviewView previewView = findViewById(R.id.previewView);
                            Button backToCameraButton = findViewById(R.id.backToCameraButton);

                            // Ẩn PreviewView và hiển thị ImageView
                            previewView.setVisibility(View.GONE);
                            uploadedImageView.setVisibility(View.VISIBLE);

                            Uri newPhotoUri = Uri.fromFile(photoFile);
                            uploadedImageView.setImageURI(null); // Reset ImageView để đảm bảo ảnh mới được tải
                            uploadedImageView.setImageURI(newPhotoUri); // Hiển thị ảnh mới

                            // Hiển thị ảnh đã chụp
                            uploadedImageView.setImageURI(Uri.fromFile(photoFile));
                            backToCameraButton.setVisibility(View.VISIBLE);

                            backToCameraButton.setOnClickListener(v -> {
                                uploadedImageView.setVisibility(View.GONE);
                                backToCameraButton.setVisibility(View.GONE);
                                previewView.setVisibility(View.VISIBLE);
                            });

                            // Hiển thị thông báo thành công
                            Toast.makeText(MainActivity.this, "Photo captured successfully!", Toast.LENGTH_SHORT).show();
                        });
                    }


                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e("CameraX", "Photo capture failed: " + exception.getMessage(), exception);
                    }
                });
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Select Image"), PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();

            ImageView uploadedImageView = findViewById(R.id.uploadedImageView);
            PreviewView previewView = findViewById(R.id.previewView);

            try {
                uploadedImageView.setImageURI(imageUri);
                uploadedImageView.setVisibility(View.VISIBLE);
                previewView.setVisibility(View.GONE);

                String imagePath = getRealPathFromURI(imageUri);
                processUploadedImage(imagePath);

            } catch (Exception e) {
                Log.e("ImagePicker", "Failed to load image", e);
                Toast.makeText(this, "Failed to process the image.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getRealPathFromURI(Uri contentUri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        CursorLoader loader = new CursorLoader(this, contentUri, proj, null, null, null);
        Cursor cursor = loader.loadInBackground();
        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String result = cursor.getString(columnIndex);
        cursor.close();
        return result;
    }

    private void processImage(String photoPath) {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Unable to load OpenCV");
            return;
        }

        // Đọc ảnh từ đường dẫn
        Mat img = Imgcodecs.imread(photoPath);
        if (img.empty()) {
            Log.e("OpenCV", "Failed to load image");
            return;
        }

        Mat gray = new Mat();
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

        // Áp dụng Canny Edge Detection
        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 50, 150);

        // Tìm các contour
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Log.d("Contours", "Number of contours found: " + contours.size());

        int keyCount = 0;
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);

            if (rect.width > 20 && rect.width < 70 && rect.height > 20 && rect.height < 70) {
                keyCount++;

                int centerX = rect.x + rect.width / 2;
                int centerY = rect.y + rect.height / 2;

                // Vẽ vòng tròn
                Imgproc.circle(img, new org.opencv.core.Point(centerX, centerY), Math.min(rect.width, rect.height) / 4,
                        new org.opencv.core.Scalar(0, 255, 0), 2);

                Log.d("Circle", "Center: (" + centerX + ", " + centerY + ")");
            }
        }

        Log.d("KeyCount", "Số phím đếm được: " + keyCount);

        // Lưu ảnh đã xử lý
        File processedFile = new File(getExternalFilesDir(null), "processed_photo.jpg");
        boolean success = Imgcodecs.imwrite(processedFile.getAbsolutePath(), img);

        if (!success) {
            Log.e("OpenCV", "Failed to save processed image.");
            return;
        }
        int finalKeyCount = keyCount;
        runOnUiThread(() -> {
            ImageView uploadedImageView = findViewById(R.id.uploadedImageView);
            Uri processedUri = Uri.fromFile(processedFile);
            uploadedImageView.setImageURI(null);
            uploadedImageView.setImageURI(processedUri);

            TextView resultTextView = findViewById(R.id.resultTextView);
            resultTextView.setText("Số phím đếm được: " + finalKeyCount);
        });
    }

    public void processUploadedImage(String imagePath) {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Unable to load OpenCV");
            return;
        }

        // Load the image
        Mat img = Imgcodecs.imread(imagePath);
        if (img.empty()) {
            Log.e("OpenCV", "Failed to load image");
            return;
        }

        // Convert to grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

        // Enhance contrast
        Mat enhanced = new Mat();
        Imgproc.equalizeHist(gray, enhanced);

        // Apply Gaussian blur to reduce noise
        Imgproc.GaussianBlur(enhanced, enhanced, new Size(9, 9), 2);

        // Detect circles using Hough Circle Transform
        Mat circles = new Mat();
        Imgproc.HoughCircles(
                enhanced,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1,        // Inverse ratio of resolution
                20,       // Minimum distance between detected centers
                100,      // Upper threshold for the Canny edge detector
                30,       // Threshold for center detection
                10,       // Minimum radius
                100       // Maximum radius
        );

        // Filter overlapping circles
        List<Circle> filteredCircles = filterOverlappingCircles(circles);

        // Draw circles and count
        int circleCount = filteredCircles.size();
        for (int i = 0; i < filteredCircles.size(); i++) {
            Circle circle = filteredCircles.get(i);
            Point center = circle.center;
            int radius = circle.radius;

            // Draw the circle outline
            Imgproc.circle(img, center, radius, new Scalar(0, 255, 0), 2);

            // Draw the circle index
            Imgproc.putText(
                    img,
                    String.valueOf(i + 1),
                    new Point(center.x - 10, center.y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(255, 255, 255),
                    2
            );
        }

        // Save the processed image
        File processedFile = new File("processed_image.jpg");
        boolean success = Imgcodecs.imwrite(processedFile.getAbsolutePath(), img);

        if (!success) {
            Log.e("OpenCV", "Failed to save processed image.");
            return;
        }

        // Display results on the UI
        Log.d("OpenCV", "Circles counted: " + circleCount);
    }

    private List<Circle> filterOverlappingCircles(Mat circles) {
        List<Circle> circleList = new ArrayList<>();
        for (int i = 0; i < circles.cols(); i++) {
            double[] circleParams = circles.get(0, i);
            if (circleParams == null) continue;
            Point center = new Point(circleParams[0], circleParams[1]);
            int radius = (int) Math.round(circleParams[2]);
            circleList.add(new Circle(center, radius));
        }

        // Filter overlapping circles
        List<Circle> filteredCircles = new ArrayList<>();
        for (Circle circle : circleList) {
            boolean isOverlapping = false;
            for (Circle filtered : filteredCircles) {
                double distance = Math.sqrt(Math.pow(circle.center.x - filtered.center.x, 2) +
                        Math.pow(circle.center.y - filtered.center.y, 2));
                if (distance < (circle.radius + filtered.radius) * 0.8) { // Overlap threshold
                    isOverlapping = true;
                    break;
                }
            }
            if (!isOverlapping) {
                filteredCircles.add(circle);
            }
        }

        return filteredCircles;
    }

    private static class Circle {
        Point center;
        int radius;

        Circle(Point center, int radius) {
            this.center = center;
            this.radius = radius;
        }
    }













    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("shape_detector.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}