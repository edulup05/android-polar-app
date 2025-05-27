package com.polar.androidblesdk.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.polar.androidblesdk.receiver.AlarmReceiver
import com.polar.androidblesdk.R
import com.polar.sdk.api.PolarBleApiDefaultImpl
import java.text.SimpleDateFormat
import java.util.Date

///////////////////////////////////////////////
class MainActivityWork : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    ///////////////////////////////////////////////
    //Estas son referencias a elementos de la interfaz. Se enlazan con el XML activity_main.
    private lateinit var listContainer: LinearLayout
    private lateinit var batteryContainer : LinearLayout
    private lateinit var estatusText: TextView

    //Referencias para lo relacionado con el botón de insulina
    private lateinit var spinnerTipoInsulina: Spinner
    private lateinit var editTextUnidadesInsulina: EditText
    private lateinit var btnRegistrarInsulina: Button

    //Referencias para lo relacionado con el botón de carbohidratos
    private lateinit var editTextCarbohidratos: EditText
    private lateinit var spinnerTipoAbsorcion: Spinner
    private lateinit var btnRegistrarCarbohidratos: Button

    private var dispositivosClientes = HashMap<String, String>()
    private var dispositivos = ArrayList<String>()

    private lateinit var intent : Intent
    private var wakeLock: PowerManager.WakeLock? = null

    ///////////////////////////////////////////////
    @SuppressLint("CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo())

        //Inicializa los elementos de la UI
        estatusText = findViewById(R.id.estado)
        listContainer = findViewById(R.id.listContainer)
        batteryContainer = findViewById(R.id.battery_container_list)

        //Configuración del botón de registro de insulina
        editTextUnidadesInsulina = findViewById(R.id.editTextUnidadesInsulina)
        btnRegistrarInsulina = findViewById(R.id.btnRegistrarInsulina)

        //Configuración del Spinner del tipo de insulina
        spinnerTipoInsulina = findViewById(R.id.spinnerTipoInsulina)
        val tipos = listOf("Rápida", "Lenta")

        val adapter = ArrayAdapter(this, R.layout.spinner_item, tipos)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTipoInsulina.adapter = adapter

        //Configuración del botón de registro de carbohidratos
        editTextCarbohidratos = findViewById(R.id.editTextCarbohidratos)
        btnRegistrarCarbohidratos = findViewById(R.id.btnRegistrarCarbohidratos)

        //Configuración del Spinner del tipo de carbohidratos
        spinnerTipoAbsorcion = findViewById(R.id.spinnerTipoAbsorcion)
        val tiposAbsorcion = listOf("Rápida", "Media", "Lenta")
        val adapterAbsorcion = ArrayAdapter(this, R.layout.spinner_item, tiposAbsorcion)
        adapterAbsorcion.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTipoAbsorcion.adapter = adapterAbsorcion

        estatusText.text = "Iniciando..."

        dispositivosClientes["C4A50D2E"] = "Dispositivo 1" //BA057E29 - C4A50D2E
        dispositivos.add("C4A50D2E")

        for(i in 0 until dispositivosClientes.size){
            addItemToBatteryContainer("Battería ${i+1}", "-")
        }

        // Initialize the Handler and Runnable
        Log.d(TAG, "Actividad principal. Antes de iniciar el servicio.")
        Log.d(TAG, "Actividad principal. Después de de iniciar el servicio.")

        // Register the BroadcastReceiver to receive the custom events
        val intentFilter = IntentFilter("prueba")
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter)

        /*val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        intent.putExtra("momento_ultima_descarga", "-1")
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, FLAG_MUTABLE)
        Log.d(TAG, "antes de llamar la alarma en el proceso general")
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1, pendingIntent)*/

        val millis = System.currentTimeMillis() + 10_000 // Espera de 10 segundos
        programarAlarmaDescarga(this, millis)


        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MainActivityWork::lock").apply {
                acquire()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Manifest.permission.SCHEDULE_EXACT_ALARM
                        ), PERMISSION_REQUEST_CODE
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Exception when asking for permissions")
                }
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }

        //Acción para el botón de insulina
        btnRegistrarInsulina.setOnClickListener {
            val unidades = editTextUnidadesInsulina.text.toString().trim()
            val tipo = spinnerTipoInsulina.selectedItem.toString().lowercase()

            if (unidades.isNotBlank()) {
                Log.d(TAG, "💉 Registro de insulina iniciado: tipo=$tipo, unidades=$unidades")
                registrarInsulina(tipo, unidades)
            } else {
                Log.w(TAG, "⚠️ Unidades de insulina vacías")
                showToast("Introduce las unidades de insulina")
            }
        }

        // Acción para el botón de carbohidratos
        btnRegistrarCarbohidratos.setOnClickListener {
            val gramos = editTextCarbohidratos.text.toString().trim()
            val tipo = spinnerTipoAbsorcion.selectedItem.toString().lowercase()

            if (gramos.isNotBlank()) {
                Log.d(TAG, "🍞 Registro de carbohidratos iniciado: tipo=$tipo, gramos=$gramos")
                registrarCarbohidratos(tipo, gramos)
            } else {
                Log.w(TAG, "⚠️ Gramos de carbohidratos vacíos")
                showToast("Introduce los gramos de carbohidratos")
            }
        }
    }

    ///////////////////////////////////////////////
    private fun anadir_evento_lista(texto: String) {
        val textView = TextView(this)
        val sdf = SimpleDateFormat("HH:mm:ss")
        val currentTime: String = sdf.format(Date())
        val fullText = "$currentTime $texto"
        val spannableString = SpannableString(fullText)
        val styleSpan = StyleSpan(Typeface.ITALIC)
        spannableString.setSpan(
            styleSpan,
            0,
            currentTime.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        textView.text = spannableString
        textView.setTextColor(Color.BLACK)
        val lastTextView = listContainer.getChildAt(0) as? TextView
        if (lastTextView != null) {
            Log.d(TAG, "Texto = ${texto}. Anterior = ${lastTextView.text}")
            if(texto == lastTextView.text){return}
        }
        if (lastTextView != null) {
            if(texto.contains("Descargado") && lastTextView.text.contains("Descargado ")){
                lastTextView.text = "${currentTime} ${texto}"
                return
            }
        }
        listContainer.addView(textView, 0)
    }

    ///////////////////////////////////////////////
    //Esta función se encarga de registrar un registro de insulina y enviarlo por MQTT
    private fun registrarInsulina(tipo: String, unidades: String) {
        val timestamp = System.currentTimeMillis()

        val mensaje = "Insulina $tipo: $unidades unidades"
        anadir_evento_lista(mensaje)
        showToast("Registrado: $mensaje")

        // Crear JSON con Gson
        val registro = mapOf("timestamp" to timestamp, "tipo" to tipo, "unidades" to unidades.toFloat())

        val gson = Gson()
        val json = gson.toJson(registro)

        // Lanzar BroadcastReceiver con un Intent extra para la insulina
        val intent = Intent("com.polar.INSULINA")
        intent.putExtra("insulina_json", json)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    ///////////////////////////////////////////////
    // Esta función se encarga de registrar un registro de carbohidratos y enviarlo por MQTT
    private fun registrarCarbohidratos(tipo: String, gramos: String) {
        val timestamp = System.currentTimeMillis()

        val mensaje = "Carbohidratos $tipo: $gramos g"
        anadir_evento_lista(mensaje)
        showToast("Registrado: $mensaje")

        // Crear JSON con Gson
        val registro = mapOf("timestamp" to timestamp, "tipo" to tipo, "gramos" to gramos.toFloat())

        val gson = Gson()
        val json = gson.toJson(registro)

        // Lanzar BroadcastReceiver con un Intent extra para los carbohidratos
        val intent = Intent("com.polar.CARBOHIDRATOS")
        intent.putExtra("carbohidratos_json", json)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    ///////////////////////////////////////////////
    private fun programarAlarmaDescarga(context: Context, millis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        intent.putExtra("momento_ultima_descarga", "-1")
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            millis,
            pendingIntent
        )
    }

    ///////////////////////////////////////////////
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra("status")
            val mensaje_splitted = message?.split("__")
            val dispositivo = mensaje_splitted?.get(0)
            val tipo = mensaje_splitted?.get(1)
            val valor = mensaje_splitted?.get(2)
            Log.d(TAG,"Total: ${message.toString()}. Individual = ${dispositivo} - ${tipo} - ${valor}")
            if(tipo == "battery"){
                val index = dispositivos.indexOf(dispositivo)
                if(valor != ""){
                    Log.d(TAG, "${dispositivosClientes[dispositivo]} -> Tiene un ${valor}% de batería.")
                    anadir_evento_lista("${dispositivosClientes[dispositivo]} -> Tiene un ${valor}% de batería.")
                    estatusText.text = "${dispositivosClientes[dispositivo]} -> Tiene un ${valor}% de batería."
                    changeBatteryValue(index,"${valor}%")
                }else{
                    changeBatteryValue(index,"-")
                }
            }else if(tipo == "connection"){
                anadir_evento_lista("${valor}")
                estatusText.text = "${valor}"
                //enableButton(descargar_datos)
            }else{
                anadir_evento_lista("${dispositivosClientes[dispositivo]} -> ${valor}")
                estatusText.text = "${dispositivosClientes[dispositivo]} -> ${valor}"
            }
        }
    }

    ///////////////////////////////////////////////
    fun Context.dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp.toFloat() * density).toInt()
    }

    ///////////////////////////////////////////////
    fun addItemToBatteryContainer(text1: String, text2: String) {
        // Create a new LinearLayout to hold the battery item
        val batteryItemLayout = LinearLayout(this)
        batteryItemLayout.orientation = LinearLayout.VERTICAL

        batteryItemLayout.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.toFloat()/(dispositivos.size).toFloat()
        )
        batteryItemLayout.gravity = Gravity.CENTER // Set the gravity to center
        // Create and customize the ImageView
        val imageView = ImageView(this)
        imageView.layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
        imageView.setImageResource(R.drawable.baseline_battery_alert_24) // Replace with desired image resource
        batteryItemLayout.addView(imageView)

        // Create and customize the first TextView
        val textView1 = TextView(this)
        textView1.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textView1.text = text1 // Set the desired text
        textView1.setTextColor(Color.parseColor("#0D47A1")) // Set the desired text color
        textView1.textSize = 16f // Set the desired text size
        batteryItemLayout.addView(textView1)

        // Create and customize the second TextView
        val textView2 = TextView(this)
        textView2.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textView2.text = text2 // Set the desired text
        textView2.setTextColor(Color.parseColor("#0D47A1")) // Set the desired text color
        textView2.textSize = 16f // Set the desired text size
        batteryItemLayout.addView(textView2)

        // Add the battery item to the LinearLayout container
        batteryContainer.addView(batteryItemLayout)
    }

    ///////////////////////////////////////////////
    fun changeBatteryValue(index: Int, value: String) {
        val itemLayout = batteryContainer.getChildAt(index) as? LinearLayout
        itemLayout?.let {
            val secondTextView = it.getChildAt(2) as? TextView
            secondTextView?.text = value // Replace with the desired new text
        }
    }

    ///////////////////////////////////////////////
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "No sufficient permissions")
                    showToast("No sufficient permissions")
                    return
                }
            }
            Log.d(TAG, "Needed permissions are granted")
        }
    }

    ///////////////////////////////////////////////
    public override fun onPause() {
        super.onPause()
        //api.foregroundEntered()
    }

    ///////////////////////////////////////////////
    public override fun onResume() {
        super.onResume()
        //api.foregroundEntered()
    }

    ///////////////////////////////////////////////
    public override fun onDestroy() {
        super.onDestroy()
        //api.shutDown()
    }

    ///////////////////////////////////////////////
    private fun showToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.show()
    }
}