package com.polar.androidblesdk.service

//PROYECTO (ACTUAL)

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.polar.androidblesdk.R
import com.polar.androidblesdk.data.Acc
import com.polar.androidblesdk.data.Gyr
import com.polar.androidblesdk.data.Ppg
import com.polar.androidblesdk.receiver.AlarmReceiver
import com.polar.androidblesdk.ui.MainActivityWork
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarOfflineRecordingTrigger
import com.polar.sdk.api.model.PolarOfflineRecordingTriggerMode
import com.polar.sdk.api.model.PolarRecordingSecret
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.fusesource.mqtt.client.BlockingConnection
import org.fusesource.mqtt.client.MQTT
import org.fusesource.mqtt.client.QoS
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.concurrent.thread

///////////////////////////////////////////////
//class MyServiceWork(context : Context, params : WorkerParameters) : Worker(context, params) {
class MyServiceWork : Service() {
    val jobId : UUID = UUID.randomUUID()
    private var broadcastIntent = Intent("prueba")

    companion object {
        public const val TAG = "Service"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
        @Volatile
        var isPolarConnecting = false
    }

    private val insulinaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val insulinaJson = intent?.getStringExtra("insulina_json")
            if (insulinaJson != null) {
                procesarInsulinaJson(insulinaJson)
            }
        }
    }

    private val carbohidratosReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra("carbohidratos_json")
            if (json != null) {
                procesarCarbohidratosJson(json)
            }
        }
    }

    private val api: PolarBleApi by lazy {
        // Notice all features are enabled
        PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
    }

    private var yourSecret =  PolarRecordingSecret(
        kotlin.byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
        )
    )

    private lateinit var settingsACC : MutableMap<PolarSensorSetting.SettingType, Int>
    private lateinit var settingsGYRO : MutableMap<PolarSensorSetting.SettingType, Int>
    private lateinit var settingsPPG : MutableMap<PolarSensorSetting.SettingType, Int>
    private lateinit var triggerFeaturesMap: Map<PolarBleApi.PolarDeviceDataType, PolarSensorSetting?>
    private lateinit var fileDir : File

    private val entryCache: MutableMap<String, MutableList<PolarOfflineRecordingEntry>> = mutableMapOf()
    private lateinit var handler: Handler

    private var deviceConnected = false

    private var activateTrigger = true
    private var minimoTiempoParaProcesarGrabacion = 1000 * 60
    private var frecuenciaConexion = 10000
    private var deviceId : String= ""
    private var devices: Array<String> = arrayOf("BA057E29") //BA057E29 - C4A50D2E
    private var startConnectDate = ""
    private var startConnectDateMilis = System.currentTimeMillis()
    private var nextConnectDateMilis = System.currentTimeMillis()
    private var indexDeviceId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var mqttServerURI = "tcp://16.171.152.69:1883"
    private var mqttTopic = "polar/"
    private lateinit var connection: BlockingConnection
    private var mqttConnected = false
    var gson = Gson()

    //60 minutos, en milisegundos. Define el tiempo mínimo que debe pasar entre una descarga y la siguiente.
    private var frecuenciaDescarga = 16 * 60000 //30 - 8

    //10 minutos, en milisegundos. Define cuándo se intentará reconectar a la pulsera.
    private var intervaloMinutos =  6 //20 - 3
    private var intervalo = intervaloMinutos * 60000

    private var realizaConexion = false
    private var primeraDescarga = false
    private var momento_ultima_descarga : Long = -2

    ///////////////////////////////////////////////
    override fun onBind(p0: Intent?): IBinder? {
        Log.d(TAG, "binded")
        return null
    }

    ///////////////////////////////////////////////
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🛠️ Servicio creado (onCreate) - MyServiceWork")

        val filter = IntentFilter("com.polar.INSULINA")
        LocalBroadcastManager.getInstance(this).registerReceiver(insulinaReceiver, filter)

        val filter2 = IntentFilter("com.polar.CARBOHIDRATOS")
        LocalBroadcastManager.getInstance(this).registerReceiver(carbohidratosReceiver, filter2)
    }

    ///////////////////////////////////////////////
    public override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"🛠️ onStartCommand executed with startId: $startId - MyServiceWork")
        startConnectDateMilis = System.currentTimeMillis() //**

        if(intent?.getStringExtra("momento_ultima_descarga") != null){
            Log.d(TAG,"El parametro que se recibe en la creación del servicio es : ${intent?.getStringExtra("momento_ultima_descarga")}")
            momento_ultima_descarga = intent?.getStringExtra("momento_ultima_descarga")!!.toLong()
        }else{
            Log.d(TAG,"No se recibe el parámetro")
        }

        if(intent?.getStringExtra("param") != null){
            Log.d(TAG,"El parametro de prueba es : ${intent?.getStringExtra("param")}")
        }else{
            Log.d(TAG,"No se recibe el parámetro param")
        }

        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyServiceWork::lock").apply {
                    acquire()
                }
            }

        if (isInternetAvailable()) {
            connectMQTT()
        }

        //enviarMensaje("status","El jobId es ${jobId.toString()}")
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        fileDir  = File(documentsDir, "POLAR")
        Log.d(TAG,"El path es: "+ documentsDir.absolutePath.toString())

        if (!fileDir.exists()) {
            val success = fileDir.mkdirs()
            if (success) {
                Log.d("MainActivity", "Carpeta creada correctamente")
            } else {
                Log.d("MainActivity", "Error al crear la carpeta")
            }
        }

        indexDeviceId = 0
        deviceId = devices[indexDeviceId]
        startConnectDate = getCurrentDateTime()

        var notification = createNotification()
        startForeground(1, notification)

        Log.d(TAG, "El momento de la ultima descarga es ${momento_ultima_descarga}")
        if(momento_ultima_descarga == (-1).toLong() ){
            momento_ultima_descarga = System.currentTimeMillis()
            Log.d(TAG, "onStart - Primera - ${momento_ultima_descarga}")
            enviarMensaje("status","Entra a descargar los datos. Es la primera vez.")


            realizaConexion = true
            primeraDescarga = true
            connectToDevice()
        }else{
            if(momento_ultima_descarga + frecuenciaDescarga < startConnectDateMilis){
                Log.d(TAG, "onStart - No Primera - Descarga - ${momento_ultima_descarga}")
                enviarMensaje("status","Entra a descargar los datos. No es la primera vez.")
                realizaConexion = true
                connectToDevice()
            }else{
                Log.d(TAG, "onStart - No Primera - No Descarga - ${momento_ultima_descarga}")
                enviarMensaje("status","No ha pasado el tiempo suficiente para que se descarguen los datos.")
                terminarEjecucion()
            }
        }
        return START_STICKY
    }

    ///////////////////////////////////////////////
    public override fun onDestroy() {
        Log.d(TAG, "Se destruye el servicio")
        api.shutDown()
        isPolarConnecting = false
        stopSelf()
        super.onDestroy()
    }

    //Prueba para que no se quede ejecutanose la app cuando la borramos de las pestañas Android
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: El usuario ha quitado la app de recientes, paro el servicio.")
        cancelarAlarmaDescarga()
        isPolarConnecting = false
        api.shutDown()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun cancelarAlarmaDescarga() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    ///////////////////////////////////////////////
    private fun connectMQTT(){
        Log.d(TAG, "Se está intentando conectar a mqtt")
        try {
            val mqtt = MQTT()
            mqtt.setHost(mqttServerURI)
            mqtt.sslContext = null
            mqtt.connectAttemptsMax = 3
            connection = mqtt.blockingConnection()
            connection.connect()
            Log.d(TAG, "Se ha conectado a MQTT")
            mqttConnected = true
        } catch (e: java.lang.Exception) {
            Log.d(TAG, "Se ha producido un error al conectar MQTT -> " + e.printStackTrace())
        }
    }

    ///////////////////////////////////////////////
    fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    ///////////////////////////////////////////////
    private fun stopAcc(): Completable {
        Log.d(TAG, "Stops ACC recording")
        return api.stopOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
            .doOnComplete { Log.d(TAG, "stop offline ACC recording completed") }
    }

    ///////////////////////////////////////////////
    private fun stopPPG(): Completable {
        Log.d(TAG, "Stops PPG recording")
        return api.stopOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
            .doOnComplete { Log.d(TAG, "stop offline PPG recording completed") }
    }

    ///////////////////////////////////////////////
    private fun stopGYR(): Completable {
        //Example of starting GYRO offline recording
        Log.d(TAG, "Stops GYRO recording")
        return api.stopOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.GYRO)
            .doOnComplete { Log.d(TAG, "stop offline GYRO recording completed") }
    }

    ///////////////////////////////////////////////
    private fun gestionListaGrabaciones(size: Int?, index: Int){
        Log.d(TAG, "Inicio de la función GESTION LISTA GRABACIONES")
        Log.d(TAG, "Size = ${size}. Index = ${index}")
        if (size != null) {
            enviarMensaje("status","Descargando datos. Descargado ${index +1}/${size}.")
            if (index != size - 1) {
                Log.d(TAG, "Processing the next function")
                try{
                    processNextElement(index + 1)
                }catch(e : Exception){
                    Log.d(TAG, "Error al llamar a la función processNextElement "+e.toString())
                    api.disconnectFromDevice(deviceId)
                    isPolarConnecting = false
                }
            }
            else {
                Log.d(TAG, "Se acaban de leer las grabaciones. Se va a comprobar el estado de los sensores antes de iniciar nuevas grabaciones.");
                api.getOfflineRecordingStatus(deviceId)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ statusList ->
                        // statusList: lista de sensores que están grabando ahora mismo
                        val toStart = mutableListOf<Completable>()
                        if (statusList.none { it == PolarBleApi.PolarDeviceDataType.ACC }) {
                            Log.d(TAG, "ACC no está grabando, se inicia.");
                            toStart.add(api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.ACC, PolarSensorSetting(settingsACC.toMap()), yourSecret))
                        } else {
                            Log.d(TAG, "ACC ya estaba grabando, no se reinicia.");
                        }
                        if (statusList.none { it == PolarBleApi.PolarDeviceDataType.GYRO }) {
                            Log.d(TAG, "GYRO no está grabando, se inicia.");
                            toStart.add(api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.GYRO, PolarSensorSetting(settingsGYRO.toMap()), yourSecret))
                        } else {
                            Log.d(TAG, "GYRO ya estaba grabando, no se reinicia.");
                        }
                        if (statusList.none { it == PolarBleApi.PolarDeviceDataType.PPG }) {
                            Log.d(TAG, "PPG no está grabando, se inicia.");
                            toStart.add(api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPG, PolarSensorSetting(settingsPPG.toMap()), yourSecret))
                        } else {
                            Log.d(TAG, "PPG ya estaba grabando, no se reinicia.");
                        }

                        // Inicia solo los sensores que no estaban grabando ya
                        if (toStart.isNotEmpty()) {
                            Completable.merge(toStart)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({
                                    Log.d(TAG, "Grabaciones iniciadas correctamente, desconectando el dispositivo.")
                                    api.disconnectFromDevice(deviceId)
                                }, { error ->
                                    Log.e(TAG, "Error al iniciar las grabaciones: $error")
                                    api.disconnectFromDevice(deviceId)
                                    isPolarConnecting = false
                                })
                        } else {
                            Log.d(TAG, "Todos los sensores ya estaban grabando, solo se desconecta el dispositivo.")
                            api.disconnectFromDevice(deviceId)
                            isPolarConnecting = false
                        }
                    }, { error ->
                        Log.e(TAG, "Error al obtener el estado de las grabaciones: $error")
                        api.disconnectFromDevice(deviceId)
                        isPolarConnecting = false
                    })
            }
        }
    }

    ///////////////////////////////////////////////
    private fun enviarMensaje(tipo : String, mensaje : String){
        Log.d(TAG, "Entrado en la función ENVIARMENSAJE")
        if (mqttConnected){
            connection.publish(mqttTopic,gson.toJson("${deviceId}__${tipo}__${mensaje}").toByteArray(), QoS.AT_LEAST_ONCE, false)
        }
        broadcastIntent.putExtra("status", "${deviceId}__${tipo}__${mensaje}")
        val cont = this.applicationContext
        LocalBroadcastManager.getInstance(cont).sendBroadcast(broadcastIntent)
    }

    ///////////////////////////////////////////////
    private fun initializeMaps(){
        settingsACC= mutableMapOf()
        settingsACC[PolarSensorSetting.SettingType.SAMPLE_RATE] = 52
        settingsACC[PolarSensorSetting.SettingType.RESOLUTION] = 16
        settingsACC[PolarSensorSetting.SettingType.RANGE] = 8
        settingsACC[PolarSensorSetting.SettingType.CHANNELS] = 3
        val polarSensorSettingACC = PolarSensorSetting(settingsACC)

        settingsGYRO= mutableMapOf()
        settingsGYRO[PolarSensorSetting.SettingType.SAMPLE_RATE] = 52
        settingsGYRO[PolarSensorSetting.SettingType.RESOLUTION] = 16
        settingsGYRO[PolarSensorSetting.SettingType.RANGE] =  2000
        settingsGYRO[PolarSensorSetting.SettingType.CHANNELS] = 3
        val polarSensorSettingGYRO = PolarSensorSetting(settingsGYRO)

        settingsPPG= mutableMapOf()
        settingsPPG[PolarSensorSetting.SettingType.SAMPLE_RATE] = 55
        settingsPPG[PolarSensorSetting.SettingType.RESOLUTION] = 22
        settingsPPG[PolarSensorSetting.SettingType.CHANNELS] = 4
        val polarSensorSettingPPG = PolarSensorSetting(settingsPPG)
        triggerFeaturesMap = mapOf(
            PolarBleApi.PolarDeviceDataType.ACC to polarSensorSettingACC,
            PolarBleApi.PolarDeviceDataType.GYRO to polarSensorSettingGYRO,
            PolarBleApi.PolarDeviceDataType.PPG to polarSensorSettingPPG
        )
    }

    ///////////////////////////////////////////////
    // Función para convertir nanosegundos desde 2000 → nanosegundos desde 1970 = Útil para los timestamp dejarlos en formato Unix
    private fun nanosSince2000ToUnixNanos(nanos: Long): Long {
        val epoch2000Nanos = 946684800_000_000_000L  // Epoch del año 2000 en nanosegundos
        return epoch2000Nanos + nanos
    }

    ///////////////////////////////////////////////
    private fun processNextElement(index: Int){
        Log.d(TAG, "Entering the processNextElement function")
        var offlineEntry = entryCache[deviceId]?.get(index) ?: null
        var size = entryCache[deviceId]?.size
        var fullyExecute = true
        if (offlineEntry != null) {
            val disposable = api.getOfflineRecord(deviceId, offlineEntry, yourSecret)
                .subscribe(
                    {
                        Log.d(TAG, "Recording ($index) ${offlineEntry.path} downloaded. Size: ${offlineEntry.size}")
                        var typeSensor = ""
                        var file: File? = null
                        var sample_rate = ""

                        if((System.currentTimeMillis() - it.startTime.timeInMillis) < (minimoTiempoParaProcesarGrabacion)) {
                            Log.d(TAG, "Se ha acabado de iniciar hace nada, y no se elimina. ($index)")
                            fullyExecute = false
                            gestionListaGrabaciones(size, index)
                        }
                        if(! fullyExecute){return@subscribe}
                        Log.d(TAG, fileDir.toString())
                        var fileOutputStream : FileOutputStream
                        try {
                            when (it) {
                                ///////////////////////////////////////////////
                                // SENSOR ACC
                                is PolarOfflineRecordingData.AccOfflineRecording -> {
                                    typeSensor = "acc"
                                    sample_rate = settingsACC[PolarSensorSetting.SettingType.SAMPLE_RATE].toString()
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    if (file.exists()) {
                                        Log.d(TAG, "File removed, it exists.")
                                        file.delete()
                                    }
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")

                                    fileOutputStream = FileOutputStream(file)

                                    Log.d(TAG, "ACC Recording started at ${it.startTime}")
                                    //val headerLine = "timestamp;x;y;z\n"

                                    val headerLine = "constant measurement,${typeSensor}\n" +
                                            "datatype time,long,long,long\n" +
                                            "time,x,y,z\n"
                                    fileOutputStream.write(headerLine.toByteArray())

                                    val accList = mutableListOf<Acc>()

                                    for (sample in it.data.samples) {
                                        val line = "${sample.timeStamp},${sample.x},${sample.y},${sample.z}\n"
                                        // Crea y guarda el objeto Acc
                                        val acc = Acc(
                                            timestamp = nanosSince2000ToUnixNanos(sample.timeStamp),
                                            x = sample.x,
                                            y = sample.y,
                                            z = sample.z
                                        )
                                        accList.add(acc)

                                        fileOutputStream.write(line.toByteArray())
                                    }
                                    Log.d(TAG, "Read data ok")
                                    fileOutputStream.close()

                                    // AÑADIDO PARA ENVIAR CSV
                                    if (mqttConnected && file != null && file.exists()) {
                                        try {
                                            //CSV
                                            //val csvContent = file.readText()
                                            //connection.publish("polar/${deviceId}/${typeSensor}",csvContent.toByteArray(), QoS.AT_LEAST_ONCE, false)

                                            //JSON
                                            val jsonOutput = mapOf("readings" to accList)
                                            val jsonArray = gson.toJson(jsonOutput)
                                            connection.publish("polar/${typeSensor}/json", jsonArray.toByteArray(), QoS.AT_LEAST_ONCE, false
                                            )
                                            Log.d(TAG, "✅ Archivo CSV/JSON enviado por MQTT al topic polar/${typeSensor}")
                                        }
                                        catch (e: Exception) {
                                            Log.e(TAG, "❌ Error al enviar archivo por MQTT: ${e.message}")
                                        }
                                    }
                                }
                                ///////////////////////////////////////////////
                                // SENSOR GYR
                                is PolarOfflineRecordingData.GyroOfflineRecording -> {
                                    typeSensor = "gyr"
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    if (file.exists()) {
                                        file.delete()
                                    }
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    Log.d(TAG,"El archivo que se va a crear es: file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")

                                    fileOutputStream = FileOutputStream(file)
                                    sample_rate = settingsGYRO[PolarSensorSetting.SettingType.SAMPLE_RATE].toString()

                                    Log.d(TAG, "GYR Recording started at ${it.startTime}")

                                    val headerLine = "constant measurement,${typeSensor}\n" +
                                            "datatype time,long,long,long\n" +
                                            "time,x,y,z\n"
                                    fileOutputStream.write(headerLine.toByteArray())

                                    val gyrList = mutableListOf<Gyr>()
                                    for (sample in it.data.samples) {
                                        val unixTs = nanosSince2000ToUnixNanos(sample.timeStamp)
                                        val line = "$unixTs,${sample.x},${sample.y},${sample.z}\n"
                                        fileOutputStream.write(line.toByteArray())

                                        gyrList.add(Gyr(unixTs, sample.x, sample.y, sample.z))
                                    }
                                    fileOutputStream.close()

                                    if (mqttConnected && file.exists()) {
                                        try {
                                            //CSV
                                            //val csvContent = file.readText()
                                            //connection.publish("polar/${deviceId}/${typeSensor}", csvContent.toByteArray(), QoS.AT_LEAST_ONCE, false)

                                            //JSON
                                            val jsonOutput = mapOf("readings" to gyrList)
                                            val jsonArray = gson.toJson(jsonOutput)
                                            connection.publish("polar/${typeSensor}/json", jsonArray.toByteArray(), QoS.AT_LEAST_ONCE, false)
                                        }
                                        catch (e: Exception) {
                                            Log.e(TAG, "❌ Error al enviar archivo GYR: ${e.message}")
                                        }
                                    }
                                }
                                ///////////////////////////////////////////////
                                // SENSOR PPG
                                is PolarOfflineRecordingData.PpgOfflineRecording -> {
                                    typeSensor = "ppg"
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    if (file.exists()) {
                                        file.delete()
                                    }
                                    file = File(fileDir, "file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    Log.d(TAG,"El archivo que se va a crear es: file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                                    fileOutputStream = FileOutputStream(file)
                                    sample_rate = settingsPPG[PolarSensorSetting.SettingType.SAMPLE_RATE].toString()

                                    Log.d(TAG, "PPG Recording started at ${it.startTime}")

                                    val headerLine = "constant measurement,${typeSensor}\n" +
                                            "datatype time,long,long,long,long\n" +
                                            "time,v1,v2,v3,v4\n"
                                    fileOutputStream.write(headerLine.toByteArray())

                                    val ppgList = mutableListOf<Ppg>()
                                    for (sample in it.data.samples) {
                                        val unixTs = nanosSince2000ToUnixNanos(sample.timeStamp)
                                        val values = sample.channelSamples
                                        val line = "$unixTs,${values[0]},${values[1]},${values[2]},${values[3]}\n"
                                        fileOutputStream.write(line.toByteArray())

                                        ppgList.add(Ppg(unixTs, values[0], values[1], values[2], values[3]))
                                    }
                                    fileOutputStream.close()

                                    if (mqttConnected && file.exists()) {
                                        try {
                                            //CSV
                                            //val csvContent = file.readText()
                                            //connection.publish("polar/${deviceId}/${typeSensor}", csvContent.toByteArray(), QoS.AT_LEAST_ONCE, false)

                                            //JSON
                                            val jsonOutput = mapOf("readings" to ppgList)
                                            val jsonArray = gson.toJson(jsonOutput)
                                            connection.publish("polar/${typeSensor}/json", jsonArray.toByteArray(), QoS.AT_LEAST_ONCE, false)
                                        }
                                        catch (e: Exception) {
                                            Log.e(TAG, "❌ Error al enviar archivo PPG: ${e.message}")
                                        }
                                    }
                                }
                                else -> {
                                    Log.d(TAG, "Recording type is not yet implemented")
                                }
                            }
                        }catch (e : java.lang.Exception){
                            if (file != null) {
                                Log.d(TAG, "ERROR GRABANDO EL ARCHIVO ${file.absolutePath.toString()}: "+e.toString())
                            }else{
                                Log.d(TAG, "ERROR GRABANDO EL ARCHIVO: "+e.toString())
                            }
                        }

                        Log.d(TAG, "Sending the test .csv file")
                        Log.d(TAG, "The filename is: file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv")
                        Log.d(TAG, fileDir.toString())
                        Log.d(TAG, "File Sent")

                        api.removeOfflineRecord(deviceId, offlineEntry)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                {
                                    Log.d(TAG, "Recording file deleted ($index) ${offlineEntry.path} ${"file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv"}")
                                    gestionListaGrabaciones(size, index)
                                },
                                { error ->
                                    val errorString = "Recording file deletion failed ($index) ${offlineEntry.path} ${"file_${deviceId}_${typeSensor}_${it.startTime.timeInMillis}.csv"}: $error"
                                    Log.e(TAG, errorString)
                                    gestionListaGrabaciones(size, index)
                                }
                            )
                    },
                    { throwable: Throwable ->
                        Log.e(TAG, "ERROR when calling getOfflineRecord ($index): ${throwable}")

                        when {
                            throwable.toString().contains("PmdFrameType cannot be parsed") ||
                                    throwable.toString().contains("OfflineRecordingErrorMetaDataParseFailed") ||
                                    throwable.toString().contains("UNIDENTIFIED_DEVICE_ERROR") -> {
                                Log.e(TAG, "Archivo corrupto/no soportado/antiguo, se borra si es posible.")
                                api.removeOfflineRecord(deviceId, offlineEntry)
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(
                                        {
                                            Log.d(TAG, "Archivo problemático borrado correctamente ($index) ${offlineEntry?.path}")
                                            gestionListaGrabaciones(size, index)
                                        },
                                        { error ->
                                            Log.e(TAG, "Error borrando archivo problemático ($index): ${offlineEntry?.path} $error")
                                            gestionListaGrabaciones(size, index)
                                        }
                                    )
                            }
                            throwable.toString().contains("isconnected") -> {
                                if (size != null) {
                                    gestionListaGrabaciones(size, size-1)
                                } else {
                                    gestionListaGrabaciones(size, index)
                                }
                            }
                            else -> {
                                gestionListaGrabaciones(size, index)
                            }
                        }
                    }
                )
        }
    }

    ///////////////////////////////////////////////
    private fun autoConnectFunction() {
        if(!deviceConnected){
            Log.d(TAG,"Se está intentando conectar a "+deviceId)
            deviceId = devices[indexDeviceId]
            enviarMensaje("status","Se está intentando conectar con el dispositivo ${indexDeviceId +1}.")
            api.connectToDevice(deviceId)
            Log.d(TAG,"continua  "+deviceId)
        }
        thread{
            Thread.sleep(15000)
            Log.d(TAG,"segunda comprobación si está conectado  "+deviceId)
            if(!deviceConnected){
                Log.e(TAG, "Comprobación. Error al conectar el dispositivo ${indexDeviceId + 1}. Current deviceId = ${deviceId}")
                enviarMensaje("status","No se ha podido conectar con el dispositivo ${indexDeviceId + 1}.")
                enviarMensaje("battery","")
                isPolarConnecting = false
                terminarEjecucion()
            }else{
                Log.d(TAG, "Para " +
                        " 10 segs la ejecución")
                thread {
                    Log.d(TAG, "Entra en el thread")
                    Thread.sleep(10000)
                    //api.disconnectFromDevice(deviceId)
                    Log.d(TAG, "Entra en descargar los datos")
                    Log.d(TAG, "El dispositivo conectado es ${deviceId}")
                    initializeMaps()
                    if(activateTrigger){
                        api.getAvailableOfflineRecordingDataTypes(deviceId)
                            .subscribe(
                                {
                                    Log.d(TAG, "Hay un total de ${it.size.toString()} elementos")
                                    Log.d(TAG, "Las opciones para monitorizar son: ")
                                    for(element in it){
                                        Log.d(TAG, element.name)
                                    }
                                    api.setOfflineRecordingTrigger(deviceId, PolarOfflineRecordingTrigger(PolarOfflineRecordingTriggerMode.TRIGGER_SYSTEM_START,triggerFeaturesMap),yourSecret)
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(
                                            {
                                                Log.d(TAG, "Se ha establecido correctamente la grabación al iniciar el dispositivo")
                                            },
                                            { error: Throwable -> Log.e(TAG, "set time failed: $error") }
                                        )
                                },
                                { error: Throwable ->
                                    val recordingStatusReadError = "Recording status read failed. Reason: $error"
                                    Log.e(TAG, recordingStatusReadError)
                                }
                            )
                    }
                    // Paramos la grabación
                    api.getOfflineRecordingStatus(deviceId).observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                            {
                                Log.d(TAG, "Hay un total de: " + it.size.toString() +" grabaciones.")
                                if(it.isNotEmpty()){
                                    Log.d(TAG, "stopping recording and listing")
                                    stopAcc()
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(
                                            {
                                                Log.e(TAG, "stopAcc() ok")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.ACC, PolarSensorSetting(settingsACC.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording ACC completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline ACC failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )
                                            },
                                            { error: Throwable -> Log.e(TAG, "stopAcc() failed: ${error.message}")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.ACC, PolarSensorSetting(settingsACC.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording ACC completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline ACC failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )}
                                        )
                                    stopGYR()
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(
                                            {
                                                Log.e(TAG, "stopGYR() ok")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.GYRO, PolarSensorSetting(settingsGYRO.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording GYRO completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline GYRO failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )
                                            },
                                            { error: Throwable -> Log.e(TAG, "stopGYR() failed: ${error.message}")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.GYRO, PolarSensorSetting(settingsGYRO.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording GYRO completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline GYRO failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )
                                            }
                                        )
                                    stopPPG()
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(
                                            {
                                                Log.e(TAG, "stopPPG() ok")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPG, PolarSensorSetting(settingsPPG.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording PPG completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline PPG failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )

                                            },
                                            { error: Throwable -> Log.e(TAG, "stopPPG() failed: ${error.message}")
                                                api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPG, PolarSensorSetting(settingsPPG.toMap()), yourSecret)
                                                    .subscribe(
                                                        {
                                                            Log.d(TAG, "start offline recording PPG completed")
                                                        },
                                                        { throwable: Throwable -> Log.e(TAG, "start offline PPG failed " + throwable.toString())
                                                            if (throwable.toString().contains("CHARGER")){
                                                                api.disconnectFromDevice(deviceId)
                                                            }}
                                                    )}
                                        )

                                }else{
                                    api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.ACC, PolarSensorSetting(settingsACC.toMap()), yourSecret)
                                        .subscribe(
                                            {
                                                Log.d(TAG, "start offline recording ACC completed")
                                            },
                                            { throwable: Throwable -> Log.e(TAG, "start offline recording ACC failed " + throwable.toString())
                                                if (throwable.toString().contains("CHARGER")){
                                                    api.disconnectFromDevice(deviceId)
                                                }
                                            }
                                        )
                                    api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.GYRO, PolarSensorSetting(settingsGYRO.toMap()), yourSecret)
                                        .subscribe(
                                            {
                                                Log.d(TAG, "start offline recording GYRO completed")
                                            },
                                            { throwable: Throwable ->
                                                Log.e(TAG, "start offline recording GYRO failed " + throwable.toString())
                                                if (throwable.toString().contains("CHARGER")){
                                                    api.disconnectFromDevice(deviceId)
                                                }
                                            }
                                        )
                                    api.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPG, PolarSensorSetting(settingsPPG.toMap()), yourSecret)
                                        .subscribe(
                                            {
                                                Log.d(TAG, "start offline recording PPG completed")
                                            },
                                            { throwable: Throwable -> Log.e(TAG, "start offline recording PPG failed " + throwable.toString())
                                                if (throwable.toString().contains("CHARGER")){
                                                    api.disconnectFromDevice(deviceId)
                                                }
                                            }
                                        )
                                }
                            },
                            { error: Throwable ->
                                val recordingStatusReadError = "Recording status read failed. Reason: $error"
                                Log.e(TAG, recordingStatusReadError)
                                api.disconnectFromDevice(deviceId)
                                isPolarConnecting = false
                            }
                        )

                    // Descargamos los datos de todas las grabaciones
                    val disposable = api.listOfflineRecordings(deviceId)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnSubscribe { entryCache[deviceId] = mutableListOf() }
                        .map {
                            entryCache[deviceId]?.add(it)
                            it
                        }
                        .subscribe(
                            { polarOfflineRecordingEntry: PolarOfflineRecordingEntry ->
                                Log.d(TAG, "next: ${polarOfflineRecordingEntry.date} path: ${polarOfflineRecordingEntry.path} size: ${polarOfflineRecordingEntry.size}")
                            },
                            { error: Throwable ->
                                Log.e(TAG, "Failed to list recordings: $error")
                                Log.d(TAG, "Se desconecta el dispositivo")
                                enviarMensaje("status","Se desconecta el dispositivo porque no hay grabaciones que descargar.")
                                api.disconnectFromDevice(deviceId)
                                isPolarConnecting = false
                            },
                            {
                                Log.d(TAG, "list recordings complete")
                                if(entryCache[deviceId]?.size!=0){
                                    enviarMensaje("status","Descargando datos. Hay un total de ${entryCache[deviceId]?.size} grabaciones")
                                    try{
                                        processNextElement(0)
                                    }catch (e : java.lang.Exception){
                                        Log.d(TAG, "Excepción al llamar a processNextElement "+ e.toString())
                                    }
                                } else{
                                    Log.d(TAG, "Se desconecta el dispositivo")
                                    enviarMensaje("status","Se desconecta el dispositivo porque no hay grabaciones que descargar.")
                                    api.disconnectFromDevice(deviceId)
                                }
                            }
                        )
                }
            }
        }
    }

    ///////////////////////////////////////////////
    private fun connectToDevice(){
        Log.d(TAG, "Estoy dentro del constructor del servicio. Se inicia todo. ")
        if (isPolarConnecting) {
            Log.w(TAG, "Intento de conexión ignorado: ya hay uno en curso")
            terminarEjecucion()
            return
        }
        isPolarConnecting = true
        api.setPolarFilter(true)
        api.setAutomaticReconnection(false)
        // If there is need to log what is happening inside the SDK, it can be enabled like this:
        val enableSdkLogs = false
        if(enableSdkLogs) {
            api.setApiLogger { s: String -> Log.d(API_LOGGER_TAG, s) }
        }
        initializeMaps()
        autoConnectFunction()

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
            }
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTED: ${polarDeviceInfo.deviceId}")
                deviceId = polarDeviceInfo.deviceId
                deviceConnected = true
                enviarMensaje("status","Conectado el dispositivo")
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTING: ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: ${polarDeviceInfo.deviceId}")
                deviceConnected = false
                isPolarConnecting = false
                enviarMensaje("status","Desconectado el dispositivo")
                Log.d(TAG, "Se llama a terminarEjecución() desde deviceDisconnected()")
                terminarEjecucion()
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                Log.d(TAG, "DIS INFO uuid: $uuid value: $value")
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "BATTERY LEVEL: $level")
                enviarMensaje("battery","${level}")
            }
        })
    }

    ///////////////////////////////////////////////
    private fun obtenerHoraFormateada(milisegundos : Long) : String{
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")
        val formattedTime = dateFormat.format(Date(milisegundos))
        return formattedTime
    }

    ///////////////////////////////////////////////
    private fun terminarEjecucion(){
        indexDeviceId = 0
        deviceId = ""
        isPolarConnecting = false

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        //Inicialización del tiempo planeado para la próxima conexión
        nextConnectDateMilis = startConnectDateMilis + intervalo

        //Variable param para indicar cuándo fue la última descarga
        var param : Long = -1

        if(realizaConexion){
            if(primeraDescarga){
                param = momento_ultima_descarga
                Log.d(TAG, "Terminar - PrimeraDescarga - ${momento_ultima_descarga} - ${param.toString()}")
            }else{
                Log.d(TAG, "Cambia la hora de última descarga")
                param = momento_ultima_descarga + frecuenciaDescarga.toLong()
                Log.d(TAG, "Terminar - No primera - Descarga - ${momento_ultima_descarga} - ${param.toString()}")
            }
        }else{
            param = momento_ultima_descarga
            Log.d(TAG, "Terminar - No Descarga - ${momento_ultima_descarga} - ${param.toString()}")
        }

        Log.d(TAG, "Ultima descarga actual:  ${momento_ultima_descarga} Ultima descarga programada: ${param.toString()} Milis = ${System.currentTimeMillis()}")
        //val intent2 = Intent(this, MyServiceWork::class.java)
        val intent2 = Intent(this, AlarmReceiver::class.java)
        intent2.putExtra("momento_ultima_descarga", param.toString())
        intent2.putExtra("param", System.currentTimeMillis().toString())

        if(System.currentTimeMillis() > nextConnectDateMilis){
            nextConnectDateMilis = System.currentTimeMillis() + 10000
        }

        //Sugerencia de chatgpt --> cambiar el request code para que sea único
        //val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val requestCode = 0

        //val pendingIntent = PendingIntent.getService(this, requestCode, intent2, FLAG_UPDATE_CURRENT or FLAG_MUTABLE)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent2,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextConnectDateMilis, pendingIntent)

        Log.d(TAG, "Se programa la llamada. Tiempo actual = ${obtenerHoraFormateada(startConnectDateMilis)}. Siguiente: ${obtenerHoraFormateada(nextConnectDateMilis)}")
        if(mqttConnected) this.connection.publish(mqttTopic, gson.toJson("Ha entrado en el servicio, y se ha programado una nueva llamada").toByteArray(), QoS.AT_MOST_ONCE, false)

        if(realizaConexion){
            if(primeraDescarga){
                enviarMensaje("connection","La próxima descarga se realizará a las ${obtenerHoraFormateada(momento_ultima_descarga + frecuenciaDescarga)}.")
            }else{
                enviarMensaje("connection","La próxima descarga se realizará a las ${obtenerHoraFormateada(momento_ultima_descarga + frecuenciaDescarga*2)}.")
            }
        }

        if (wakeLock?.isHeld() == true){
            Log.d(TAG, "El wakelock está activo")
            wakeLock!!.release();
        }
        stopSelf()
    }

    ///////////////////////////////////////////////
    fun getCurrentDateTime(): String {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm:ss")
        val currentDate = calendar.time
        return dateFormat.format(currentDate)
    }

    ///////////////////////////////////////////////
    private fun procesarInsulinaJson(json: String) {
        if (mqttConnected) {
            try {
                connection.publish(
                    "polar/insulina",
                    json.toByteArray(),
                    QoS.AT_MOST_ONCE,
                    false
                )
                Log.d(TAG, "📤 Insulina publicada por MQTT: $json")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al publicar insulina: ${e.message}")
            }
        } else {
            Log.e(TAG, "❌ MQTT no está conectado. No se pudo enviar insulina.")
        }
    }

    ///////////////////////////////////////////////
    private fun procesarCarbohidratosJson(json: String) {
        if (mqttConnected) {
            try {
                connection.publish(
                    "polar/carbohidratos",
                    json.toByteArray(),
                    QoS.AT_MOST_ONCE,
                    false
                )
                Log.d(TAG, "📤 Carbohidratos publicados por MQTT: $json")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al publicar carbohidratos: ${e.message}")
            }
        } else {
            Log.e(TAG, "❌ MQTT no está conectado. No se pudo enviar carbohidratos.")
        }
    }

    ///////////////////////////////////////////////
    private fun createNotification(): Notification {
        val notificationChannelId = "Servicio de POLAR activo"

        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
            val channel = NotificationChannel(
                notificationChannelId,
                "Endless Service notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "Endless Service channel"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivityWork::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, FLAG_IMMUTABLE)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("Servicio de POLAR activo")
            .setContentText("Servicio de POLAR activo")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Ticker text")
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }
}