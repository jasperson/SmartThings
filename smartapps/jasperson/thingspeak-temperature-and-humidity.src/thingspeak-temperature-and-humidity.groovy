/**
 *  ThingSpeak Temperature and Humidity
 *
 *  Copyright 2017 J.R. Jasperson
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "ThingSpeak Temperature and Humidity",
    namespace: "jasperson",
    author: "J.R. Jasperson",
    description: "ThingSpeak integration to track and visualize temperature and humidity",
    iconUrl: "http://cdn.device-icons.smartthings.com/Weather/weather2-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Weather/weather2-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Weather/weather2-icn@3x.png")


// Presented to user on app installation/update for configuration
preferences {
    section("Devices") {
        input "temperatureDevs", "capability.temperatureMeasurement", title: "Temperature", required:false, multiple: true
        input "humdityDevs", "capability.relativeHumidityMeasurement", title: "Humidity", required:false, multiple: true
    }

    section ("ThingSpeak Channel ID") {
        input "channelId", "number", title: "Channel id"
    }

    section ("ThingSpeak Write Key") {
        input "channelKey", "text", title: "Channel key"
    }
}

// Invoked on app install
def installed() {
    initialize()
}

// Invoked on app update/save
def updated() {
    unsubscribe()
    initialize()
}

// Invoked by installed() and updated()
def initialize() {
    subscribe(temperatureDevs, "temperature", handleTemperatureEvent)
    subscribe(humidityDevs, "humidity", handleHumidityEvent)

    updateChannelInfo()
    log.debug("${app.label}: State: ${state}") 
}

def handleTemperatureEvent(evt) {
    logField(evt) { it.toString() }
}

def handleHumidityEvent(evt) {
    logField(evt) { it.toString() }
}

// Invoked by updateChannelInfo()
private getFieldMap(channelInfo) {
    def fieldMap = [:]
    channelInfo?.findAll { it.key?.startsWith("field") }.each { fieldMap[it.value?.trim()] = it.key }
    return fieldMap
}

// Invoked by initialize()
private updateChannelInfo() {
    log.debug("${app.label}: Retrieving channel info for ${channelId}")

    def url = "https://api.thingspeak.com/channels/${channelId}/feeds.json?key=${channelKey}&results=0"
    log.debug("${app.label}: updateChannelInfo URL: ${url}")
    httpGet(url) {
        response ->
        if (response.status != 200 ) {
            log.debug("${app.label}: ThingSpeak data retrieval failed, status = ${response.status}")
        } else {
            state.channelInfo = response.data?.channel
            log.debug("${app.label}: Channel Info: ${state.channelInfo}")
        }
    }
    state.fieldMap = getFieldMap(state.channelInfo)
    log.debug("${app.label}: FieldMap: ${state.fieldMap}")
}

// Invoked by handler(s)
private logField(evt, Closure c) {
    def deviceName = evt.displayName.trim()
    def fieldNum = state.fieldMap[deviceName]
    if (!fieldNum) {
        log.debug("${app.label}: Device '${deviceName}' has no field")
        return
    }

    def value = c(evt.value)
    log.debug("${app.label}: Logging to channel ${channelId}, ${fieldNum}, ${value}")
 
    def url = "https://api.thingspeak.com/update?api_key=${channelKey}&${fieldNum}=${value}"
    httpGet(url) { 
        response -> 
        if (response.status != 200 ) {
            log.debug("${app.label}: ThingSpeak logging failed, status = ${response.status}")
        }
    }
}