/**
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
 * Shamelessly script-kittied from: https://github.com/imbrianj/nobody_home/blob/master/nobody_home.groovy
 */
definition(
    name: "Mode Setter",
    namespace: "jasperson",
    author: "J.R. Jasperson",
    description: "Set the SmartThings mode based on presence & sunrise/sunset",
    category: "Mode Magic",
    iconUrl: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Weather/weather14-icn@3x.png"
)

preferences{
    section("Presence Sensors") {
        input "people", "capability.presenceSensor", multiple: true
    }
    section("Mode Settings") {
		input "newAwayDayMode",		"mode", title: "Everyone is away during the day"	// JR Edit: new mode
        input "newAwayNightMode",	"mode", title: "Everyone is away at night"			// JR Edit: was 'newAwayMode'
        input "newHomeDayMode", 	"mode", title: "Someone is home during the day"		// JR Edit: was newSunriseMode
        input "newHomeNightMode",	"mode", title: "Someone is home at night"			// JR Edit: was newSunsetMode
    }
    section("Mode Change Delay (minutes)") {
        input "awayThreshold", "decimal", title: "Away delay [5m]", required: false
        input "arrivalThreshold", "decimal", title: "Arrival delay [2m]", required: false
    }
    section("Notifications") {
        input "sendPushMessage", "bool", title: "Push notification", required:false
    }
}

// Invoked on install
def installed(){
    log.debug("Mode Setter: installed() @${location.name}: ${settings}")
    initialize(true)
}

// Invoked on update
def updated(){
    log.debug("Mode Setter: updated() @${location.name}: ${settings}")
    unsubscribe()
    initialize(false)
}

def initialize(isInstall){
    // subscribe to all the events we care about
    log.debug("Mode Setter: Subscribing to events ...")

    // Subscriptions, attribute/state, callback function
    subscribe(people,   "presence", presenceHandler)
    subscribe(location, "sunrise",  sunriseHandler)
    subscribe(location, "sunset",   sunsetHandler)

    // set the optional parameter values. these are not available
    // directly until the app has initialized (that is,
    // installed/updated has returned). so here we access them through
    // the settings object, as otherwise will get an exception.

    // store information we need in state object so we can get access
    // to it later in our event handlers.

    // calculate the away threshold in seconds. can't use the simpler
    // default falsy value, as value of 0 (no delay) is evaluated to
    // false (not specified), but we want 0 to represent no delay. so
    // we compare against null explicitly to see if the user has set a
    // value or not.
    if (settings.awayThreshold == null) {
        settings.awayThreshold = 5  // default away 5 minute
    }
    state.awayDelay = (int) settings.awayThreshold * 60
    log.debug("Mode Setter: awayThreshold set to " + state.awayDelay + " second(s)")

    if (settings.arrivalThreshold == null) {
        settings.arrivalThreshold = 2  // default arrival 2 minute
    }
    state.arrivalDelay = (int) settings.arrivalThreshold * 60
    log.debug("Mode Setter: arrivalThreshold set to " + state.arrivalDelay + " second(s)")

    // get push notification setting
    state.isPush = settings.sendPushMessage ? true : false
    log.debug("Mode Setter: sendPushMessage set to " + state.isPush)

    // on install (not update), figure out what mode we should be in
    // IF someone's home. This value is needed so that when a presence
    // sensor is triggered, we know what mode to set the system to, as
    // the sunrise/sunset event handler may not be triggered yet after
    // a fresh install.
    if (isInstall) {
        // TODO: for now, we simply assume daytime. a better approach
        //       would be to figure out whether current time is day or
        //       night, and set it appropriately. However there
        //       doesn't seem to be a way to query this directly
        //       without a zip code. This will become the correct
        //       value at the next sunrise/sunset event.
        log.debug("Mode Setter: No sun info yet, assuming daytime")
        state.modeIfHome = newHomeDayMode
        state.modeIfAway = newAwayDayMode

        state.eventDevice = ""  // last event device

        // device that triggered timer. This is not necessarily the
        // eventDevice. For example, if A arrives, kick off timer,
        // then b arrives before timer elapsed, we want the
        // notification message to reference A, not B.
        state.timerDevice = null

        // anything in flight? We use this to avoid scheduling
        // duplicate timers (so we don't extend the timer).
        state.pendingOp = "init"

        // now set the correct mode for the location. This way, we
        // don't need to wait for the next sun/presence event.

        // we schedule this action to run after the app has fully
        // initialized. This way, the app install is faster and the
        // user customized app name is used in the notification.
        runIn(7, "setInitialMode")
    }
    // On update, we don't change state.modeIfHome. This is so that we
    // preserve the current sun rise/set state we obtained in earlier
    // sunset/sunrise handler. This way the app remains in the correct
    // sun state when the user reconfigures it.
}

def setInitialMode()
{
    changeMode()
    state.pendingOp = null
}

// ********** sunrise/sunset handling **********

// event handler when the sunrise time is reached
def sunriseHandler(evt)
{
    state.modeIfHome = newHomeDayMode
    state.modeIfAway = newAwayDayMode
    setMode()
}

// event handler when the sunset time is reached
def sunsetHandler(evt)
{
    state.modeIfHome = newHomeNightMode
    state.modeIfAway = newAwayNightMode
    setMode()
}

def changeMode(){
    if (isEveryoneAway()) {
    	setMode(state.modeIfAway, " because no one is present")
    }
    else{
    	setMode(state.modeIfHome, " because someone is present")
    }
}

// event handler when presence sensor changes state
def presenceHandler(evt)
{
    // get the device name that resulted in the change
    state.eventDevice= evt.device?.displayName

    // is setInitialMode() still pending?
    if (state.pendingOp == "init") {
        log.debug("Mode Setter: Pending ${state.pendingOp} op still in progress, ignoring presence event")
        return
    }

    if (evt.value == "not present") {
        handleDeparture()
    } else {
        handleArrival()
    }
}

def handleDeparture()
{
    log.info("Mode Setter: ${state.eventDevice} left ${location.name}")

    // do nothing if someone's still home
    if (!isEveryoneAway()) {
        log.info("Mode Setter: Someone is still present, no actions needed")
        return
    }

    // Now we set away mode. We perform the following actions even if
    // home is already in away mode because an arrival timer may be
    // pending, and scheduling delaySetMode() has the nice effect of
    // canceling any previous pending timer, which is what we want to
    // do. So we do this even if delay is 0.
    log.info("Mode Setter: Scheduling ${state.modeIfAway} mode in " + state.awayDelay + "s") // FIXME: Is this state set?
    state.pendingOp = "away"
    state.timerDevice = state.eventDevice
    // we always use runIn(). This has the benefit of automatically
    // replacing any pending arrival/away timer. if any arrival timer
    // is active, it will be clobbered with this away timer. If any
    // away timer is active, it will be extended with this new timeout
    // (though normally it should not happen)
    runIn(state.awayDelay, "delaySetMode")
}

def handleArrival()
{
    // someone returned home, set home/night mode after delay
    log.info("Mode Setter: ${state.eventDevice} arrived at ${location.name}")

    def numHome = isAnyoneHome()
    if (!numHome) {
        // no one home, do nothing for now (should NOT happen)
        log.warn("Mode Setter: ${deviceName} arrived, but isAnyoneHome() returned false!")
        return
    }

    if (numHome > 1) {
        // not the first one home, do nothing, as any action that
        // don't do anything if someone's still home.
        log.debug("Mode Setter: Someone is already present, no actions needed")
        return
    }

    // check if any pending arrival timer is already active. we want
    // the timer to trigger when the 1st person arrives, but not
    // extended when the 2nd person arrives later. this should not
    // happen because of the >1 check above, but just in case.
    if (state.pendingOp == "arrive") {
        log.debug("Mode Setter: Pending ${state.pendingOp} op already in progress, do nothing")
        return
    }

    // now we set home/night mode
    log.info("Mode Setter: Scheduling ${state.modeIfHome} mode in " + state.arrivalDelay + "s")
    state.pendingOp = "arrive"
    state.timerDevice = state.eventDevice
    // if any away timer is active, it will be clobbered with
    // this arrival timer
    runIn(state.arrivalDelay, "delaySetMode")
}


// ********** helper functions **********

// change the system to the new mode, unless its already in that mode.
def setMode(newMode, reason="")
{
    if (location.mode != newMode) {
        // notification message
        def message = "Mode Setter: ${location.name} changed mode from '${location.mode}' to '${newMode}'" + reason
        setLocationMode(newMode)
        send(message)  // send message after changing mode
    } else {
        log.debug("Mode Setter: ${location.name} is already in ${newMode} mode, no actions needed")
    }
}

// create a useful departure/arrival reason string
def reasonStr(isAway, delaySec, delayMin)
{
    def reason

    // if we are invoked by timer, use the stored timer trigger
    // device, otherwise use the last event device
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
            reason += " ${delayMin} minutes ago"
        } else {
            reason += " ${delaySec}s ago"
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
def delaySetMode()
{
    def newMode = null
    def reason = ""

    // timer has elapsed, check presence status to figure out what we
    // need to do
    if (isEveryoneAway()) {
        reason = reasonStr(true, state.awayDelay, awayThreshold)
        newMode = state.modeIfAway
        if (state.pendingOp) {
            log.debug("Mode Setter: ${state.pendingOp} timer elapsed: everyone is away")
        }
    } else {
        reason = reasonStr(false, state.arrivalDelay, arrivalThreshold)
        newMode = state.modeIfHome
        if (state.pendingOp) {
            log.debug("Mode Setter: ${state.pendingOp} timer elapsed: someone is home")
        }
    }

    // now change the mode
    setMode(newMode, reason);

    state.pendingOp = null
    state.timerDevice = null
}

private isEveryoneAway()
{
    def result = true

    if (people.findAll { it?.currentPresence == "present" }) {
        result = false
    }

    return result
}

// return the number of people that are home
private isAnyoneHome()
{
    def result = 0
    // iterate over our people variable that we defined
    // in the preferences method
    for (person in people) {
        if (person.currentPresence == "present") {
            result++
        }
    }
    return result
}

private send(msg)
{
    if (state.isPush) {
        log.debug("Mode Setter: Sending push notification")
        sendPush(msg)
    } else {
        log.debug("Mode Setter: Sending notification")
        sendNotificationEvent(msg)
    }
    log.info("Mode Setter: ${msg}")
}