/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.passive.bittium

import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarbase.util.Strings
import org.radarcns.bittium.faros.*
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.regex.Pattern

class FarosDeviceManager internal constructor(service: FarosService, private val farosFactory: FarosSdkFactory, private val handler: SafeHandler) : AbstractDeviceManager<FarosService, FarosDeviceStatus>(service), FarosDeviceListener, FarosSdkListener {

    private val accelerationTopic: DataCache<ObservationKey, BittiumFarosAcceleration> = createCache("android_bittium_faros_acceleration", BittiumFarosAcceleration::class.java)
    private val ecgTopic: DataCache<ObservationKey, BittiumFarosEcg> = createCache("android_bittium_faros_ecg", BittiumFarosEcg::class.java)
    private val ibiTopic: DataCache<ObservationKey, BittiumFarosInterBeatInterval> = createCache("android_bittium_faros_inter_beat_interval", BittiumFarosInterBeatInterval::class.java)
    private val temperatureTopic: DataCache<ObservationKey, BittiumFarosTemperature> = createCache("android_bittium_faros_temperature", BittiumFarosTemperature::class.java)
    private val batteryTopic: DataCache<ObservationKey, BittiumFarosBatteryLevel> = createCache("android_bittium_faros_battery_level", BittiumFarosBatteryLevel::class.java)

    private lateinit var acceptableIds: Array<Pattern>
    private lateinit var apiManager: FarosSdkManager
    private lateinit var settings: FarosSettings

    private var faros: FarosDevice? = null

    override fun start(acceptableIds: Set<String>) {
        logger.info("Faros searching for device.")

        handler.start()
        handler.execute {
            this.acceptableIds = Strings.containsPatterns(acceptableIds)

            apiManager = farosFactory.createSdkManager(service)
            try {
                apiManager.startScanning(this, handler.handler)
                updateStatus(DeviceStatusListener.Status.READY)
            } catch (ex: IllegalStateException) {
                logger.error("Failed to start scanning", ex)
                close()
            }
        }
    }

    override fun onStatusUpdate(status: Int) {
        val radarStatus = when(status) {
            FarosDeviceListener.IDLE -> {
                handler.execute {
                    logger.debug("Faros status is IDLE. Request to start/restart measurements.")
                    applySettings(this.settings)
                    faros?.run {
                        requestBatteryLevel()
                        startMeasurements()
                    }
                }
                DeviceStatusListener.Status.CONNECTING
            }
            FarosDeviceListener.CONNECTING -> DeviceStatusListener.Status.CONNECTING
            FarosDeviceListener.DISCONNECTED, FarosDeviceListener.DISCONNECTING -> DeviceStatusListener.Status.DISCONNECTED
            FarosDeviceListener.MEASURING -> DeviceStatusListener.Status.CONNECTED
            else -> {
                logger.warn("Faros status {} is unknown", status)
                return
            }
        }
        logger.debug("Faros status {} and radarStatus {}", status, radarStatus)

        updateStatus(radarStatus)
    }

    override fun onDeviceScanned(device: FarosDevice) {
        handler.executeReentrant {
            logger.info("Found Faros device {}", device.name)
            if (faros != null) {
                logger.info("Faros device {} already set", device.name)
                return@executeReentrant
            }

            val attributes: Map<String, String> = mapOf(
                    Pair("type", when(device.type) {
                        FarosDevice.FAROS_90  -> "FAROS_90"
                        FarosDevice.FAROS_180 -> "FAROS_180"
                        FarosDevice.FAROS_360 -> "FAROS_360"
                        else -> "unknown"
                    }))

            if ((acceptableIds.isEmpty() || Strings.findAny(acceptableIds, device.name))
                    && register(device.name, device.name, attributes)) {
                logger.info("Stopping scanning")
                apiManager.stopScanning()

                logger.info("Connecting to device {}", device.name)
                device.connect(this, handler.handler)
                this.faros = device
                updateStatus(DeviceStatusListener.Status.CONNECTING)
            }
        }
    }

    override fun didReceiveAcceleration(timestamp: Double, x: Float, y: Float, z: Float) {
        state.setAcceleration(x, y, z)
        send(accelerationTopic, BittiumFarosAcceleration(timestamp, currentTime, x, y, z))
    }

    override fun didReceiveTemperature(timestamp: Double, temperature: Float) {
        state.temperature = temperature
        send(temperatureTopic, BittiumFarosTemperature(timestamp, currentTime, temperature))
    }

    override fun didReceiveInterBeatInterval(timestamp: Double, interBeatInterval: Float) {
        state.heartRate = 60 / interBeatInterval
        send(ibiTopic, BittiumFarosInterBeatInterval(timestamp, currentTime, interBeatInterval))
    }

    override fun didReceiveEcg(timestamp: Double, channels: FloatArray) {
        val channelOne = channels[0]
        val channelTwo = if (channels.size > 1) channels[1] else null
        val channelThree = if (channels.size > 2) channels[2] else null

        send(ecgTopic, BittiumFarosEcg(timestamp, currentTime, channelOne, channelTwo, channelThree))
    }

    override fun didReceiveBatteryStatus(timestamp: Double, status: Int) {
        // only send approximate battery levels if the battery level interval is disabled.
        val level = when(status) {
            FarosDeviceListener.BATTERY_STATUS_CRITICAL -> 0.05f
            FarosDeviceListener.BATTERY_STATUS_LOW      -> 0.175f
            FarosDeviceListener.BATTERY_STATUS_MEDIUM   -> 0.5f
            FarosDeviceListener.BATTERY_STATUS_FULL     -> 0.875f
            else -> {
                logger.warn("Unknown battery status {} passed", status)
                return
            }
        }
        state.batteryLevel = level
        send(batteryTopic, BittiumFarosBatteryLevel(timestamp, currentTime, level, false))
    }

    override fun didReceiveBatteryLevel(timestamp: Double, level: Float) {
        state.batteryLevel = level
        send(batteryTopic, BittiumFarosBatteryLevel(timestamp, currentTime, level, true))
    }

    internal fun applySettings(settings: FarosSettings) {
        handler.executeReentrant {
            this.settings = settings
            faros?.run {
                if (isMeasuring) {
                    logger.info("Device is measuring. Stopping device before applying settings.")
                    stopMeasurements()
                    // will apply in onStatusUpdate(), when the device becomes idle.
                } else {
                    logger.info("Applying device settings {}", settings)
                    apply(settings)
                }
            }
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        logger.info("Faros BT Closing device {}", this)
        super.close()

        handler.stop {
            try {
                faros?.close()
            } catch (e2: IOException) {
                logger.error("Faros socket close failed")
            } catch (npe: NullPointerException) {
                logger.info("Can't close an unopened socket")
            }

            apiManager.close()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FarosDeviceManager::class.java)
    }
}
