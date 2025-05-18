package com.polar.androidblesdk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.polar.androidblesdk.service.MyServiceWork

///////////////////////////////////////////////
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        public const val TAG = "Alarm"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Start the service to perform the task
        Log.d(TAG, "Recibe la orden de iniciar el servicio")
        // Get the extras bundle from the intent
        val momento_ultima_descarga = intent.getStringExtra("momento_ultima_descarga")
        if (momento_ultima_descarga != null) {
            Log.d(TAG, momento_ultima_descarga)
        } else {
            Log.d(TAG, "No se recibe nada por parámetro")
        }
        val serviceIntent = Intent(context, MyServiceWork::class.java)
        serviceIntent.putExtra("momento_ultima_descarga", momento_ultima_descarga)

        /*Log.d(TAG, "Se inicia el servicio")
        context.startService(serviceIntent)*/


        Log.d(TAG, "Se inicia el servicio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}