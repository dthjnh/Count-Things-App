package com.example.myapplication;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import android.content.Context;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TFLiteModelHelper {
    private final Interpreter interpreter;

    public TFLiteModelHelper(Context context, String modelPath) throws IOException {
        interpreter = new Interpreter(loadModelFile(context, modelPath));
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(context.getAssets().openFd(modelPath).getFileDescriptor());
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = context.getAssets().openFd(modelPath).getStartOffset();
        long declaredLength = context.getAssets().openFd(modelPath).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public Map<String, Float> detectObjects(TensorImage image) {
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, 4}, DataType.FLOAT32);
        interpreter.run(image.getBuffer(), outputBuffer.getBuffer());
        return new TensorLabel(getLabels(), outputBuffer).getMapWithFloatValue();
    }

    private List<String> getLabels() {
        // Return list of labels (e.g., ["star", "heart", "diamond", "circle"])
        return Arrays.asList("star", "heart", "diamond", "circle");
    }
}