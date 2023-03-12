package com.example.assistent

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.assistent.ui.theme.AssistentTheme
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    companion object {
        const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    private var permissionToRecordAccepted = false
    private val permissions = arrayOf(android.Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioSentimentAnalysisApp()
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            permissionToRecordAccepted = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED
            }
        }

        if (!permissionToRecordAccepted) {
            Toast.makeText(this, "Please grant permission to record audio", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // rest of the MainActivity code here

    @Composable
    fun Greeting(name: String) {
        Text(text = "Hello $name!")
    }

    @Preview(showBackground = true)
    @Composable
    fun DefaultPreview() {
        AssistentTheme {
            Greeting("Android")
        }
    }

    @Composable
    fun AudioSentimentAnalysisApp() {
        val context = LocalContext.current
        val mediaRecorder = remember { MediaRecorder() }
        var recordingFile by remember { mutableStateOf<File?>(null) }
        var result by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Button(
                onClick = {
                    if (recordingFile == null) {
                        startRecording(context, mediaRecorder) { file ->
                            recordingFile = file
                        }
                    } else {
                        stopRecording(mediaRecorder) {
                            recordingFile?.let { file ->
                                analyzeAudioSentiment(context, file) { sentiment ->
                                    result = sentiment
                                }
                            }
                            recordingFile = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (recordingFile == null) "Record Audio" else "Stop Recording")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = result,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun createAudioFile(): File {
        val audioFileName = "recording_${System.currentTimeMillis()}.mp3"
        val audioDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return File(audioDirectory, audioFileName)
    }

    private fun createMediaRecorder(audioFile: File): MediaRecorder {
        val mediaRecorder = MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFile.absolutePath)
        }
        return mediaRecorder
    }

    private fun startRecording(context: Context, mediaRecorder: MediaRecorder, callback: (File) -> Unit) {
        var isRecording = false
        var amplitude = 0

        GlobalScope.launch {
            val audioFile = createAudioFile() as File
            val recorder = createMediaRecorder(audioFile)
            recorder.start()
            isRecording = true
            while (isRecording) {
                amplitude = recorder.maxAmplitude
            }
            recorder.stop()
            isRecording = false
            amplitude = 0
            analyzeAudioSentiment(context, audioFile) { result ->
                // toast result
                Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
            }
        }

        val outputFile = File(context.externalCacheDir, "recording.3gp")
        mediaRecorder.reset()
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        mediaRecorder.setOutputFile(outputFile.absolutePath)

        try {
            mediaRecorder.prepare()
        } catch (e: IOException) {
            Log.e("MainActivity", "Failed to prepare media recorder", e)
            return
        }

        mediaRecorder.start()
        callback(outputFile)
    }

    private fun stopRecording(mediaRecorder: MediaRecorder, callback: () -> Unit) {
        mediaRecorder.stop()
        mediaRecorder.release()
        callback()
    }

    private fun analyzeAudioSentiment(context: Context, audioFile: File, callback: (String) -> Unit) {
        val apiBaseUrl = "https://api.whisper.ai/v1/sentiment"
        val apiKey = "your_api_key"

        val httpClient = OkHttpClient.Builder().build()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "recording.3gp",
                audioFile.asRequestBody("audio/3gp".toMediaTypeOrNull()),
            )
            .build()

        val request = Request.Builder()
            .url(apiBaseUrl)
            .post(requestBody)
            .header("Authorization", "Bearer $apiKey")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                val jsonObject = JSONObject(responseBody)
                val sentiment = jsonObject.optString("sentiment")
                callback(sentiment)
            } else {
                Log.e("MainActivity", "Failed to analyze audio sentiment: ${response.code} - ${response.message}")
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Failed to analyze audio sentiment", e)
        }
    }
}
