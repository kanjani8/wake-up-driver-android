package com.example.drowseydriver1

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

//아웃풋 타입: <class 'list'>
//아웃풋 내용: [tensor([[ 0.8966, -0.6026]])]

@OptIn(TransformExperimental::class)
fun executorchBinaryClassifier(
    model: Module, //  "main/assets/eye_model.pte" "main/assets/mouth_model.pte"
    label: List<CameraAnalyzer.FaceState>, // ["Open","Closed"], ["no_yawn","yawn"],
    size: Int, // 160, 128
    inputTensor: FloatArray
): CameraAnalyzer.FaceState{


    val input_tensor = Tensor.fromBlob(inputTensor, longArrayOf(1L, 3L, size.toLong(), size.toLong()));
    val input_evalue = EValue.from(input_tensor);
    val output = model.forward(input_evalue);
    val scores = output[0].toTensor().dataAsFloatArray;
//    val scores = softmax(output[0].toTensor().dataAsFloatArray);
    Log.d("eyeResult1", "Scores:  ${scores.contentToString()}") // around 60ms

    val maxIndex = scores.indices.maxByOrNull { scores[it] }?: 0
    return label[maxIndex]
}
