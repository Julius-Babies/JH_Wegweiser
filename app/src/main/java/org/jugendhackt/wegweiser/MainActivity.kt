package org.jugendhackt.wegweiser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.jugendhackt.wegweiser.app.checkPermission
import org.jugendhackt.wegweiser.di.appModule
import org.jugendhackt.wegweiser.sensors.shake.ShakeSensor
import org.jugendhackt.wegweiser.ui.theme.WegweiserTheme
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.compose.KoinAndroidContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext.startKoin

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModel()
    var hasLocationPermissionRequested = false

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        if (checkPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) startLocationUpdates()
        else requestPermissions()

        setContent {
            KoinAndroidContext {
                val shakeSensor = koinInject<ShakeSensor>()
                LaunchedEffect(42) {
                    var timeThreshold = 0L
                    shakeSensor.add {
//                        if (viewModel.isPlaying) return@add
                        if (System.nanoTime() - timeThreshold < 1500000000L) return@add
                        if (viewModel.nearestStops == null) return@add
                        timeThreshold = System.nanoTime()
                        viewModel.onEvent(MainEvent.TogglePlayPause)
                        Log.d("ACC", "ButtonToggle by Shaking")
                    }
                }
                WegweiserTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                AnimatedContent(
                                    targetState = viewModel.nearestStops == null
                                ) { isLoading ->
                                    if (isLoading) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(24.dp)
                                                    .fillMaxSize(),
                                                strokeWidth = 16.dp
                                            )
                                        }
                                        return@AnimatedContent
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(16.dp)
                                    ) {
                                        viewModel.nearestStops?.let {
                                            Text(
                                                text = it.name,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .basicMarquee(iterations = Int.MAX_VALUE),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.displayLarge
                                            )
                                            Spacer(Modifier.height(16.dp))
                                            Text(
                                                text = "Nächste Abfahrten",
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                            Text(
                                                text = buildString {
                                                    it.departures.forEachIndexed { i, departure ->
                                                        if (i > 0) append("\n")
                                                        append(departure.line)
                                                        append(": ")
                                                        append(departure.destination)
                                                        append(" (")
                                                        append(departure.time)
                                                        append(") auf ${departure.platformType} ${departure.platformName}")
                                                        if (departure.isCancelled) append(" Entfällt")
                                                        else if (departure.delayInMinutes > 0) append(
                                                            " +${departure.delayInMinutes}min"
                                                        )
                                                        else if (departure.delayInMinutes < 0) append(
                                                            " ${departure.delayInMinutes}min"
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            PlayPauseButton(
                                viewModel.isPlaying,
                                viewModel.canPlay
                            ) { viewModel.onEvent(MainEvent.TogglePlayPause) }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(1000).build()

        fusedLocationClient.lastLocation.addOnSuccessListener {
            it?.let { viewModel.onEvent(MainEvent.LocationUpdate(it.latitude, it.longitude)) }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    override fun onResume() {
        super.onResume()
        if (checkPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
        else requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                viewModel.onEvent(MainEvent.LocationUpdate(location.latitude, location.longitude))
            }
        }
    }

    fun requestPermissions() {
        if (hasLocationPermissionRequested) return
        hasLocationPermissionRequested = true
        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            when {
                permissions.getOrDefault(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    false
                ) -> {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return@registerForActivityResult
                    }
                    startLocationUpdates()
                }

                permissions.getOrDefault(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    false
                ) -> {
                    // Only approximate location access granted.
                }

                else -> {
                    // No location access granted.
                }
            }
        }

        // Before you perform the actual permission request, check whether your app
        // already has the permissions, and whether your app needs to show a permission
        // rationale dialog. For more details, see Request permissions:
        // https://developer.android.com/training/permissions/requesting#request-permission
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}

@Composable
fun ColumnScope.PlayPauseButton(
    isPlaying: Boolean,
    canPlay: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick, enabled = canPlay),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isPlaying,
        ) { isPlaying ->
            if (isPlaying && canPlay) {
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxSize()
                )
            } else if ((!isPlaying) && canPlay) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxSize()
                )
            } else if (!canPlay) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = "Stop is still loading",
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxSize()
                )
            }
        }
    }
}
