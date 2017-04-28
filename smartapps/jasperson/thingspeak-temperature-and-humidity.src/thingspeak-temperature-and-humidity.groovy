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
        input "tempDev", "capability.temperatureMeasurement", title: "Temperature", required:false, multiple: false
        input "RHDev", "capability.relativeHumidityMeasurement", title: "Humidity", required:false, multiple: false
    }

    section ("ThingSpeak Channel ID") {
        input "channelID", "number", title: "Channel ID"
    }

    section ("ThingSpeak Write Key") {
        input "channelKey", "text", title: "Channel Key"
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
	schedule("0 0/5 * 1/1 * ? *", handleSchedule)
	subscribe(tempDev, "temperature", handleTemperatureEvent)
    subscribe(RHDev, "humidity", handleHumidityEvent)

    updateChannelInfo()
    send("State: ${state}") 
}

def handleSchedule(){
	send("handleSchedule...")
    def currentTemp = tempDev.currentState("temperature")
    def currentRH = RHDev.currentState("humidity")
    send("handleSchedule: t: ${tempDev}")
    send("handleSchedule: rh: ${RHDev}")
    send("handleSchedule: currentTemp: ${currentTemp}")
    send("handleSchedule: currentRH: ${currentRH}")
    
    def url = "https://api.thingspeak.com/update?api_key=${channelKey}&${state.fieldMap['temperature']}=${currentTemp}"
    send("HST URL: ${url}")
    send("HSRH URL: https://api.thingspeak.com/update?api_key=${channelKey}&${state.fieldMap['humidity']}=${currentRH}")
    send("HSC URL: https://api.thingspeak.com/update?api_key=${channelKey}&${state.fieldMap['temperature']}=${currentTemp}&${state.fieldMap['humidity']}=${currentRH}")
    /*
    httpGet(url) { 
        response -> 
        if (response.status != 200 ) {
            send("ThingSpeak logging failed, status = ${response.status}")
        }
    }
    */
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
    send("Retrieving channel info for ${channelID}")

    def url = "https://api.thingspeak.com/channels/${channelID}/feeds.json?key=${channelKey}&results=0"
    httpGet(url) {
        response ->
        if (response.status != 200 ) {
            send("ThingSpeak data retrieval failed, status = ${response.status}")
        } else {
            state.channelInfo = response.data?.channel
            send("Channel Info: ${state.channelInfo}")
        }
    }
    state.fieldMap = getFieldMap(state.channelInfo)
}

// Invoked by handler(s)
private logField(evt, Closure c) {
	send("Event: ${evt}")
    def deviceName = evt.displayName.trim()
    def fieldNum = state.fieldMap[deviceName]
    if (!fieldNum) {
        send("Device '${deviceName}' has no field")
        return
    }

    def value = c(evt.value)
    send("Logging to channel ${channelID}, ${fieldNum}, ${value}")
 
    def url = "https://api.thingspeak.com/update?api_key=${channelKey}&${fieldNum}=${value}"
    send("logField URL: ${url}")
    httpGet(url) { 
        response -> 
        if (response.status != 200 ) {
            send("ThingSpeak logging failed, status = ${response.status}")
        }
    }
}
private send(msg){
	// log levels: [trace, debug, info, warn, error, fatal]
	sendNotificationEvent(msg)			// sendNotificationEvent() displays a message in Hello, Home, but does not send a push notification or SMS message.
    log.debug("${app.label}: ${msg}")
}