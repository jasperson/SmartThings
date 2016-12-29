/*
 *  Mode Setter
 *
 *  Copyright 2016 J.R. Jasperson
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
 * Based on: https://github.com/imbrianj/nobody_home/blob/master/nobody_home.groovy
 */

definition(
    name: "Mode Setter",
    namespace: "jasperson",
    author: "J.R. Jasperson",
    description: "Set the SmartThings location mode based on presence & sunrise/sunset",
    category: "Mode Magic",
    iconUrl: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn@3x.png"
)

// Presented to user on app installation/update for configuration
preferences{
    section("Presence Sensors") {
        input "people", "capability.presenceSensor", multiple: true
    }
    section("Mode Settings") {
		input "newAwayDayMode",		"mode", title: "Everyone is away during the day"
        input "newAwayNightMode",	"mode", title: "Everyone is away at night"
        input "newHomeDayMode", 	"mode", title: "Someone is home during the day"
        input "newHomeNightMode",	"mode", title: "Someone is home at night"
        input "ignoreMode",			"mode", title: "Ignore state changes if in this mode"
    }
    section("Mode Change Delay (minutes)") {
        input "awayThreshold", "decimal", title: "Away delay [5m]", required: false
        input "arrivalThreshold", "decimal", title: "Arrival delay [0m]", required: false
    }
    section("Notifications") {
        input "sendPushMessage", "bool", title: "Push notification", required:false
    }
}

// Invoked on app install
def installed(){
    send("installed() @${location.name}: ${settings}", "debug")
    initialize(true)
}

// Invoked on app update
def updated(){
    send("updated() @${location.name}: ${settings}", "debug")
    unsubscribe()
    initialize(false)
}

// Invoked by installed() and updated()
def initialize(isInstall){
    // Subscriptions, attribute/state, callback function
    subscribe(people,   "presence", presenceHandler)
    subscribe(location, "sunrise",  sunriseHandler)
    subscribe(location, "sunset",   sunsetHandler)

	// Default any unspecified optional parameters and set state 
    if (settings.awayThreshold == null) {
        settings.awayThreshold = 5
    }
    state.awayDelay = (int) settings.awayThreshold * 60
    send("awayThreshold set to ${state.awayDelay} second(s)", "debug")

    if (settings.arrivalThreshold == null) {
        settings.arrivalThreshold = 0
    }
    state.arrivalDelay = (int) settings.arrivalThreshold * 60
    send("arrivalThreshold set to ${state.arrivalDelay} second(s)", "debug")

    state.isPush = settings.sendPushMessage ? true : false
    send("sendPushMessage set to ${state.isPush}", "debug")

    //JR TODO: Set SMS state
    
    // JR Test
    	def sunInfo = getSunriseAndSunset()
    	Date now = new Date()
        /*
        log.debug("${app.label}: Sunrise is ${sunInfo.sunrise}")
        log.debug("${app.label}: Sunset is ${sunInfo.sunset}")
        log.debug("${app.label}: Now is ${now}")
        */
    	if (now >= sunInfo.sunset && now < sunInfo.sunrise){	//JR FIXME: Does this work after sunrise as expected?  Format is: Wed Dec 28 14:20:00 UTC 2016
        	state.modeIfHome = settings.newHomeDayMode
        	state.modeIfAway = settings.newAwayDayMode
    	}
    	else{
        	state.modeIfHome = settings.newHomeNightMode
        	state.modeIfAway = settings.newAwayNightMode
    	}
    // Test Continues in commented out section below   
	// Executes during installed() invocations
    if (isInstall) {
    	// JR TODO: Cut/Paste JR Test section from above

        state.eventDevice = ""		// Device that generated the last event
        state.timerDevice = null	// Device that triggered timer (not necessarily eventDevice)

        // Set pending operation in state to avoid incorrectly extending timers
        state.pendingOp = "init"

        // Schedule setInitialMode to install faster and reference custom app name in notificaiton
        runIn(7, "setInitialMode")
    }
}

// Scheduled invocation by initialize() on install only
def setInitialMode(){
    changeMode()
    state.pendingOp = null
}

// Invoked at sunrise via subscription
def sunriseHandler(evt){
    state.modeIfHome = settings.newHomeDayMode
    state.modeIfAway = settings.newAwayDayMode
    changeMode()
}

// Invoked at sunset via subscription
def sunsetHandler(evt){
    state.modeIfHome = settings.newHomeNightMode
    state.modeIfAway = settings.newAwayNightMode
    changeMode()
}

// Invoked by setInitialMode and {sunrise, sunset}Handler
def changeMode(){
    if (isEveryoneAway()) {
    	setMode(state.modeIfAway, " because no one is home")
    }
    else{
    	setMode(state.modeIfHome, " because someone is home")
    }
}

// Invoked when a selected presence sensor changes state
def presenceHandler(evt){
    // Set state.eventDevice to the device name that changed state
    state.eventDevice= evt.device?.displayName

    // Ignore if setInitialMode() has not yet completed
    if (state.pendingOp == "init") {
        send("Pending ${state.pendingOp} op still in progress, ignoring presence event", "info")
        return
    }

    if (evt.value == "not present") {
        handleDeparture()
    } else {
        handleArrival()
    }
}

// Invoked by presenceHandler when a selected presence sensor changes state to "not present"
def handleDeparture(){
    send("${state.eventDevice} left ${location.name}", "info")

    if (!isEveryoneAway()) {
        send("Someone is still home, no actions needed", "info")
        return
    }

    // Now we set away mode. We perform the following actions even if
    // home is already in away mode because an arrival timer may be
    // pending, and scheduling delaySetMode() has the nice effect of
    // canceling any previous pending timer, which is what we want to
    // do. So we do this even if delay is 0.
    send("Scheduling ${state.modeIfAway} mode in ${state.awayDelay} second(s)", "info")
    state.pendingOp = "away"
    state.timerDevice = state.eventDevice
    // we always use runIn(). This has the benefit of automatically
    // replacing any pending arrival/away timer. if any arrival timer
    // is active, it will be clobbered with this away timer. If any
    // away timer is active, it will be extended with this new timeout
    // (though normally it should not happen)
    runIn(state.awayDelay, "delaySetMode")
}

// Invoked by presenceHandler when a selected presence sensor changes state to "present"
def handleArrival(){
    send("${state.eventDevice} arrived at ${location.name}", "info")

    def numHome = isAnyoneHome()
    def whoIsHome = whoIsHome()
    if (!numHome) {
        // No one home, do nothing for now (should NOT happen)
        send("${deviceName} arrived, but isAnyoneHome() returned false!", "warn")
        return
    }

    if (numHome > 1){
		// Not the first one home, do nothing, as any action that
        // should happen would've happened when the first sensor
        // arrived. this is the opposite of isEveryoneAway() where we
        // don't do anything if someone's still home.
        send("${whoisHome} is already home, no actions needed", "info")
        return
    }

    // Check if any pending arrival timer is already active. we want
    // the timer to trigger when the first person arrives, but not
    // extended if a secondperson arrives later. This should not
    // happen because of the >1 check above, but just in case.
    if (state.pendingOp == "arrive") {
        send("Pending ${state.pendingOp} op already in progress, do nothing", "info")
        return
    }

    // Schedule arrival timer
    send("Scheduling ${state.modeIfHome} mode in ${state.arrivalDelay} second(s)", "info")
    state.pendingOp = "arrive"
    state.timerDevice = state.eventDevice
    // if any away timer is active, it will be clobbered with this arrival timer
    runIn(state.arrivalDelay, "delaySetMode")
}

// ********** helper functions **********

// Evaluate and change the system to the new mode if necessary
def setMode(newMode, reason=""){
    if (location.mode == settings.ignoreMode) {
        send("${location.name} is in ignore mode: ${location.mode}", "info")
        return
    }
    else if (location.mode != newMode) {
        setLocationMode(newMode)
        send("${location.name} changed mode from ${location.mode} to ${newMode} ${reason}", "info")
    } else {
        send("${location.name} is already in ${newMode} mode, no actions needed", "info")
    }
}

// Generate a verbose departure/arrival reason string
def reasonStr(isAway, delaySec, delayMin){
    def reason

    // If invoked by timer, use the stored timer trigger device, otherwise use the last event device
    if (state.timerDevice) {
        reason = " because ${state.timerDevice} "
    } else {
        reason = " because ${state.eventDevice} "
    }

    if (isAway) {
        reason += "left"
    } else {
        reason += "arrived"
    }

    if (delaySec) {
        if (delaySec > 60) {
            if (delayMin == null) {
                delayMin = (int) delaySec / 60
            }
            reason += " ${delayMin} minute(s) ago"
        } else {
            reason += " ${delaySec} second(s) ago"
        }
    }
    return reason
}

// http://docs.smartthings.com/en/latest/smartapp-developers-guide/scheduling.html#schedule-from-now
//
// By default, if a method is scheduled to run in the future, and then
// another call to runIn with the same method is made, the last one
// overwrites the previously scheduled method.
//
// We use the above property to schedule our arrval/departure delay
// using the same function so we don't have to worry about
// arrival/departure timer firing independently and complicating code.
def delaySetMode(){
    def newMode = null
    def reason = ""

    // Timer has elapsed, check presence status to determine action
    if (isEveryoneAway()) {
        reason = reasonStr(true, state.awayDelay, awayThreshold)
        newMode = state.modeIfAway
        if (state.pendingOp) {
            send("${state.pendingOp} timer elapsed: everyone is away", "info")
        }
    } else {
        reason = reasonStr(false, state.arrivalDelay, arrivalThreshold)
        newMode = state.modeIfHome
        if (state.pendingOp) {
            send("${state.pendingOp} timer elapsed: someone is home", "info")
        }
    }

    // Now change the mode
    setMode(newMode, reason);
    state.pendingOp = null
    state.timerDevice = null
}

// Returns boolean
private isEveryoneAway(){
    def result = true

    if (people.findAll { it?.currentPresence == "present" }) {
        result = false
    }
    return result
}

// Returns number of people that are home
private isAnyoneHome(){
    def result = 0
    for (person in people) {
        if (person.currentPresence == "present") {
            result++
        }
    }
    return result
}

// Returns list of name(s) of people that are home
private whoIsHome(){
	def whoIsHomeStr = ""
    def whoIsHomeList = []
    for (person in people) { // JR TODO: findall()? - http://docs.groovy-lang.org/latest/html/groovy-jdk/java/util/List.html#findAll(groovy.lang.Closure)
        if (person.currentPresence == "present") {
            whoIsHomeList.add(person.displayName)
        }
    }
    whoIsHomeStr = whoIsHomeList.join(",")
    return whoIsHomeStr
}

private send(msg, logLevel){
	// log levels: [trace, debug, info, warn, error, fatal]
    if (logLevel != "debug"){
    	if (state.isPush) {
        	sendPush("${app.label}: ${msg}")	// sendPush() sends the specified message as a push notification to users mobile devices and displays it in Hello, Home
    	} // JR TODO: Add SMS else if
    	else{
    		sendNotificationEvent(msg)			// sendNotificationEvent() displays a message in Hello, Home, but does not send a push notification or SMS message.
		}
	}
    log."$logLevel"("${app.label}: ${msg}")
}