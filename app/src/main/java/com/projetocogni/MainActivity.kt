package com.projetocogni

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Atividade principal do aplicativo Cogni.
 * Implementa TextToSpeech.OnInitListener para lidar com a inicialização do motor de voz.
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    // Variável para o motor de síntese de voz (Texto para Fala)
    private lateinit var tts: TextToSpeech
    // Executor para processar tarefas da câmera em uma thread separada, evitando travar a interface
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializa o motor de TTS
        tts = TextToSpeech(this, this)
        
        // Cria um pool de threads para a execução de tarefas da câmera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Define o conteúdo da tela usando Jetpack Compose
        setContent {
            MaterialTheme {
                // Chama a tela principal passando as instâncias necessárias
                CogniScreen(tts, cameraExecutor)
            }
        }
    }

    /**
     * Chamado quando o motor de TTS termina de inicializar.
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Define o idioma para Português do Brasil
            val result = tts.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Idioma não suportado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Limpeza de recursos quando a atividade é destruída para evitar vazamento de memória.
     */
    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

/**
 * Componente que exibe a pré-visualização da câmera na interface do Compose.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onImageCaptureCreated: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Obtém o provedor da câmera de forma assíncrona
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Usa AndroidView para integrar a PreviewView do sistema clássico de views no Compose
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            
            // Adiciona um listener para quando a câmera estiver pronta
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                // Configura como a imagem será esticada na tela
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                
                // Configura o caso de uso de Pré-visualização
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Configura o caso de uso de Captura de Imagem
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // Retorna o objeto de captura para ser usado no botão da interface
                onImageCaptureCreated(imageCapture)

                // Seleciona a câmera traseira por padrão
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Desvincula usos anteriores antes de vincular novos
                    cameraProvider.unbindAll()
                    // Vincula a câmera ao ciclo de vida da Activity, fecha quando o app fecha
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Falha ao vincular câmera", e)
                }
            }, executor)
            previewView
        },
        modifier = modifier
    )
}

/**
 * Tela principal do aplicativo onde toda a lógica de UI e estados está
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CogniScreen(tts: TextToSpeech, cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // Escopo para rodar funções suspensas (IA)
    
    // Estados que controlam o que aparece na tela
    var textToShow by remember { mutableStateOf("Aponte para o papel e clique em 'Ler Agora'") }
    var isLoading by remember { mutableStateOf(false) }
    var speechRate by remember { mutableStateOf(0.8f) }
    var isCameraActive by remember { mutableStateOf(true) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // Lógica para validar se o texto atual é algo "útil" para simplificar ou ler
    val isTextValid = textToShow.isNotBlank() && 
                      textToShow != "Aponte para o papel e clique em 'Ler Agora'" && 
                      textToShow != "Aponte a câmera e clique em 'Ler Agora'" &&
                      textToShow != "Nenhum texto detectado." && 
                      !textToShow.startsWith("Erro")

    // Lançador para pedir permissão de câmera ao usuário
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Precisamos da câmera para ler os textos!", Toast.LENGTH_LONG).show()
        }
    }

    // Pede a permissão assim que a tela abre
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Cogni - O Leitor Universal") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // --- ÁREA SUPERIOR: EXIBE A CÂMERA OU O TEXTO RECONHECIDO ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black, RoundedCornerShape(12.dp))
            ) {
                if (isCameraActive) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onImageCaptureCreated = { imageCapture = it }
                    )
                } else {
                    // Exibe o texto escaneado com opção de seleção e scroll
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = textToShow,
                                fontSize = 18.sp,
                                lineHeight = 26.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Indicador de carregamento (Spinner) quando a IA ou OCR estão trabalhando
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- CONTROLES DE VELOCIDADE DA VOZ ---
            Text("Velocidade da Voz", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { speechRate = 0.5f },
                    colors = if(speechRate == 0.5f) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                ) { Text("Lento") }
                
                Button(onClick = { speechRate = 1.0f },
                    colors = if(speechRate == 1.0f) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                ) { Text("Médio") }
                
                Button(onClick = { speechRate = 1.5f },
                    colors = if(speechRate == 1.5f) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                ) { Text("Rápido") }
            }

            Spacer(modifier = Modifier.weight(1f))

            // --- BOTÕES DE AÇÃO ---
            if (isCameraActive) {
                // Botão para capturar a foto e iniciar o OCR
                Button(
                    onClick = {
                        val capture = imageCapture
                        if (capture != null) {
                            isLoading = true
                            capture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                        // Processa a imagem capturada para extrair texto
                                        processImageProxy(imageProxy, context) { result ->
                                            textToShow = result
                                            isLoading = false
                                            isCameraActive = false
                                            // Fala o texto detectado imediatamente
                                            tts.setSpeechRate(speechRate)
                                            tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        isLoading = false
                                        Log.e("Cogni", "Erro ao capturar imagem", exception)
                                    }
                                }
                            )
                        } else {
                            Toast.makeText(context, "Câmera ainda não está pronta", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Ler Agora", fontSize = 18.sp)
                }
            } else {
                // Botões que aparecem após a leitura inicial
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Volta para o modo de visualização da câmera
                    Button(
                        onClick = {
                            isCameraActive = true
                            textToShow = "Aponte a câmera e clique em 'Ler Agora'"
                            tts.stop()
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("Abrir Câmera")
                    }

                    // Envia o texto para a IA Gemini simplificar
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                textToShow = callGeminiToSimplify(textToShow)
                                isLoading = false
                                tts.speak(textToShow, TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        },
                        enabled = isTextValid,
                        modifier = Modifier.weight(1f).height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!isTextValid) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Bloqueado",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text("Simplificar")
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Repete a leitura do texto atual
                    Button(
                        onClick = {
                            tts.setSpeechRate(speechRate)
                            tts.speak(textToShow, TextToSpeech.QUEUE_FLUSH, null, null)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text("Ouvir Novamente")
                    }

                    // Interrompe a voz imediatamente
                    Button(
                        onClick = { tts.stop() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("Parar Voz")
                    }
                }
            }
        }
    }
}

/**
 * Função que utiliza o Google ML Kit para reconhecer texto em uma imagem capturada.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    context: Context,
    onResult: (String) -> Unit
) {
    @androidx.camera.core.ExperimentalGetImage
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        // Converte a imagem da câmera para o formato que o ML Kit entende
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Inicia o processamento assíncrono
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text.ifEmpty { "Nenhum texto detectado." }
                onResult(resultText)
            }
            .addOnFailureListener { e ->
                Log.e("MLKit", "Erro no reconhecimento", e)
                onResult("Erro ao ler o texto.")
            }
            .addOnCompleteListener {
                // Muito importante: fechar a imagem para liberar o buffer da câmera
                imageProxy.close()
            }
    } else {
        imageProxy.close()
        onResult("Erro ao processar imagem.")
    }
}

/**
 * Função que chama o modelo Gemini (via Firebase Vertex AI) para simplificar o texto.
 */
suspend fun callGeminiToSimplify(inputText: String): String {

    try{
        // Obtém o modelo generativo configurado no Firebase
        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3-flash-preview") // Usando a versão flash para rapidez

        val prompt = "Simplifique este texto para alguém com dificuldade de compreensão, " +
                "sua resposta será usada em um dispositivo de som para ler em voz alta portanto evite usar asterisco ou qualquer caractere especial na resposta. " +
                "Use frases curtas, destaque palavras-chave em MAIÚSCULO, explique termos difíceis e/ou traduza termos em outras linguagens para português brasileiro: $inputText"

        // Gera o conteúdo de forma assíncrona
        val response = model.generateContent(prompt)

        return if(response.text == null){
            "Erro ao simplificar, retorno nulo."
        } else{
            response.text!!;
        }

    } catch (e: Exception) {
        Log.e("Gemini", "Erro ao tentar simplificar: $e.", e)
        return "Erro ao tentar simplificar: $e."
    }
}
