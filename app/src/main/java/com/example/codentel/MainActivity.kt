package com.example.codentel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.codentel.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ingenieriiajhr.jhrCameraX.BitmapResponse
import com.ingenieriiajhr.jhrCameraX.CameraJhr
import androidx.core.content.edit

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private lateinit var cameraJhr: CameraJhr
    private lateinit var vb: ActivityMainBinding
    private lateinit var textRecognizer: TextRecognizer

    // Variables para controlar la captura del número
    private var capturedNumber: String? = null
    private var isCaptured = false
    private var lastProcessTime = 0L
    private val timeWait = 300L // 1000L 1 segundo entre reconocimientos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // Inicializar cámara (objeto CameraJhr)
        cameraJhr = CameraJhr(this)

        // Inicializar reconocedor de texto de ML Kit
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Configurar scroll en el TextView
        vb.txtConsola.movementMethod = ScrollingMovementMethod()

        // Configurar botones
        setupButtons()
        showFirstTimeGuide()
    }

    private fun showFirstTimeGuide() {
        val sharedPref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isFirstTime = sharedPref.getBoolean("first_time", true)

        if (isFirstTime) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📱 Para recargar credito raspe y escanea el PIN")
                .setMessage("Enfoca el PIN de recarga (15 díg.) en el cuadro de captura, se capturará automáticamente")
                .setPositiveButton("Comenzar") { dialog, _ ->
                    dialog.dismiss()
                    sharedPref.edit { putBoolean("first_time", false) }
                }
                .show()
        }
    }

    private fun setupButtons() {
        // Botón Copiar
        vb.btnCopy.setOnClickListener {
            capturedNumber?.let { number ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Número Entel", number)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Número copiado al portapapeles", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, "No se detectó un PIN válido", Toast.LENGTH_SHORT).show()
        }

        // Botón Reiniciar (limpia la detección y reactiva el escaneo)
        vb.btnReset.setOnClickListener {
            capturedNumber = null
            isCaptured = false
            vb.txtConsola.text = "PIN: "
            Toast.makeText(this, "Escaneo reiniciado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (!cameraJhr.ifStartCamera && cameraJhr.allpermissionsGranted()) {
                startCameraJhr()
            } else if (!cameraJhr.allpermissionsGranted()) {
                cameraJhr.noPermissions()
            }
        }
    }

    private fun startCameraJhr() {
        // Configurar el listener que recibe el bitmap de cada frame
        cameraJhr.addlistenerBitmap(object : BitmapResponse {
            override fun bitmapReturn(bitmap: Bitmap?) {
                // Si ya capturamos un número, no procesamos más
                if (isCaptured) return

                bitmap?.let { bmp ->
                    val currentTime = System.currentTimeMillis()
                    // Control de tiempo para no saturar el OCR
                    if (currentTime - lastProcessTime >= timeWait) {
                        lastProcessTime = currentTime
                        preprocessImageForOCR(bmp)
                        // 1. Recortar el bitmap al área central (ROI) velocida, precision
                        val roiBitmap = cropToRoi(bmp)

                        // 2. Convertir a InputImage y procesar con ML Kit
                        val image = InputImage.fromBitmap(roiBitmap, 0)
                        textRecognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                // Extraer solo dígitos del texto completo
                                val allDigits = visionText.text.filter { it.isDigit() }

                                // Verificar si son exactamente 15 dígitos
                                if (allDigits.length == 15) {
                                    // Guardar número y actualizar UI en el hilo principal
                                    runOnUiThread {
                                        capturedNumber = allDigits
                                        isCaptured = true
                                        vb.txtConsola.text = allDigits
                                        vb.txtConsola.visibility = View.VISIBLE

                                        showModernToast("✅ PIN detectado: $allDigits")  // <--- Muestra el PIN en el Toast
                                        triggerHapticFeedback()
                                        playBeepSound()

                                        // Cambiar color del ROI a verde
                                        changeROIColor(true)

                                        // ***** NUEVO: Direccionar al marcador automáticamente *****
                                        val ussdCode = "*109*${allDigits}#"
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ussdCode"))
                                        startActivity(intent)
                                        // *******************************************************
                                    }
                                }
                                // Si no son 15 dígitos, no hacemos nada (seguimos escaneando)
                            }
                            .addOnFailureListener { e ->
                                e.printStackTrace()
                            }
                    }
                }
            }
        })

        // Inicializar la captura de bitmap y el ImageProxy (requerido por la librería)
        cameraJhr.initBitmap()
        cameraJhr.initImageProxy()

        // Iniciar la cámara con LENS_FACING_BACK = 1
        cameraJhr.start(
            selectorCamera = 1,      // 1 = cámara trasera
            aspectRatio = 0,         // 0 = RATIO_4_3
            view = vb.cameraPreview,
            cameraPreview = true,
            returImageProxy = false,
            returBitmap = true
        )
    }

    private fun preprocessImageForOCR(original: Bitmap): Bitmap {
        val config = Bitmap.Config.ARGB_8888
        val processed = original.copy(config, true)

        // Aquí puedes añadir filtros de contraste, brillo, etc.
        // Para ML Kit, a veces la imagen original funciona mejor

        return processed
    }

    private fun triggerHapticFeedback() {
        vb.btnCopy.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun playBeepSound() {
        // Implementar con SoundPool
    }

    private fun changeROIColor(isDetected: Boolean) {
        if (isDetected) "#4CAF50" else "#FFFFFF"
        // Cambiar color del overlay
    }

    private fun showModernToast(message: String) {
        com.google.android.material.snackbar.Snackbar.make(
            vb.root,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).apply {
            setBackgroundTint(resources.getColor(android.R.color.holo_green_dark))
            setTextColor(resources.getColor(android.R.color.white))
            show()
        }
    }

    /**
     * Recorta el bitmap al área central (ROI) definida como un porcentaje del ancho y alto.
     * Se toma el 60% del ancho y 20% del alto, centrado.
     */
    private fun cropToRoi(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height

        // Definir tamaño del ROI (puedes ajustar estos porcentajes)
        val roiWidth = (width * 0.7).toInt()
        val roiHeight = (height * 0.15).toInt()

        // Calcular coordenadas para centrar el recorte
        val left = (width - roiWidth) / 2
        val top = (height - roiHeight) / 2

        // Asegurar que las coordenadas sean válidas
        val safeLeft = left.coerceIn(0, width - roiWidth)
        val safeTop = top.coerceIn(0, height - roiHeight)
        val safeRoiWidth = roiWidth.coerceAtMost(width - safeLeft)
        val safeRoiHeight = roiHeight.coerceAtMost(height - safeTop)

        return Bitmap.createBitmap(original, safeLeft, safeTop, safeRoiWidth, safeRoiHeight)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraJhr.close() // Liberar recursos de la cámara
    }
}

private fun CameraJhr.close() {
    TODO("Not yet implemented")
}